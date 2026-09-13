/**
 * Instance for attacking a chunk
 * - holds state data of attack
 * - functions as runnable thread for attack tick
 */

package net.aechronis.nodes.war

import net.aechronis.nodes.Nodes
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.townNametagViewedByPlayer
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Entity
import net.minestom.server.entity.EntityType
import net.minestom.server.entity.Player
import net.minestom.server.entity.metadata.display.AbstractDisplayMeta
import net.minestom.server.entity.metadata.display.TextDisplayMeta
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class Attack(
    val attacker: UUID, // attacker's UUID
    val town: Town, // attacker's town
    val coord: Coord, // chunk coord under attack
    val targetTerritory: Territory, // territory targeted when attack started
    val flagBase: BlockVec, // fence base of flag
    val flagBlock: BlockVec, // wool block for flag
    val flagTorch: BlockVec, // torch block of flag
    val skyBeaconColorBlocks: List<BlockVec>,
    val skyBeaconWireframeBlocks: List<BlockVec>,
    val progressBar: BossBar, // progress bar
    val attackTime: Long, //
    var progress: Long, // initial progress, current tick count
    val mode: AttackMode = AttackMode.WAR,
) : Runnable {
    // no build region
    val noBuildXMin: Int
    val noBuildXMax: Int
    val noBuildZMin: Int
    val noBuildZMax: Int
    val noBuildYMin: Int
    val noBuildYMax: Int = 255 // temporarily set to height

    var thread: Task = MinecraftServer.getSchedulerManager()
        .buildTask { this.run() }
        .delay(TaskSchedule.tick(FlagWar.ATTACK_TICK))
        .repeat(TaskSchedule.tick(FlagWar.ATTACK_TICK))
        .schedule()

    // text display used to show town name and progress on flag
    val textDisplay = AttackTextDisplay(this, flagBase.add(0.5, 3.0, 0.5).asPos())

    // Pre-generated, immutable-after-init base of the json serialization -- safe to read
    // concurrently since nothing ever mutates it again after the constructor. toJson() used to
    // also reuse a second shared `jsonString` StringBuilder field here, mutating it in place
    // (setLength(0) + append) and returning it *by reference* on every call. WarSerializer.save()
    // collects those live references and, when saving asynchronously, hands them to a background
    // thread that reads them via CompletableFuture.runAsync -- if the save loop fired again before
    // that read finished (easily possible during an active war, saves run on every needsSave tick),
    // the main thread would call toJson() again and mutate the very StringBuilder the background
    // thread was still iterating. toJson() now allocates a fresh StringBuilder per call instead --
    // removes the shared mutable state instead of trying to synchronize around it.
    val jsonStringBase: StringBuilder

    init {
        val flagX = flagBase.blockX
        val flagY = flagBase.blockY
        val flagZ = flagBase.blockZ

        // set no build ranges
        this.noBuildXMin = flagX - Nodes.config.flagNoBuildDistance
        this.noBuildXMax = flagX + Nodes.config.flagNoBuildDistance
        this.noBuildZMin = flagZ - Nodes.config.flagNoBuildDistance
        this.noBuildZMax = flagZ + Nodes.config.flagNoBuildDistance
        this.noBuildYMin = flagY + Nodes.config.flagNoBuildYOffset

        // set boss bar progress
        val progressNormalized: Float = this.progress.toFloat() / this.attackTime.toFloat()
        this.progressBar.progress(progressNormalized)

        // pre-generate main part of the JSON serialization string
        this.jsonStringBase = generateFixedJsonBase(
            this.attacker,
            this.coord,
            this.flagBase,
        )
    }

    override fun run() {
        FlagWar.attackTick(this)
    }

    fun cancel(brokenBy: Player? = null) {
        this.thread.cancel()
        FlagWar.cancelAttack(this, brokenBy)
    }

    // returns json format string as a fresh StringBuilder every call -- see the comment on
    // jsonStringBase above for why this must not reuse/mutate a shared field.
    // only used with WarSerializer objects
    fun toJson(): StringBuilder {
        val now = System.currentTimeMillis() / 1000
        val remainingSeconds = (this.attackTime - this.progress) / FlagWar.ATTACK_TICK
        val completionTime = now + remainingSeconds

        val result = StringBuilder(this.jsonStringBase.length + 40)
        result.append(this.jsonStringBase)
        if (mode != AttackMode.WAR) result.append("\"mode\":\"$mode\",")
        result.append("\"t\":$completionTime")
        result.append("}")

        return result
    }
}

// pre-generate main part of the JSON serialization string
// for the attack which does not change
// (only part that changes is progress)
// parts required for serialization:
// - attacker: player uuid
// - coord: chunk coord
// - block: flag base block (fence)
// - skyBeaconColorBlocks: track blocks in sky beacon
// - skyBeaconWireframeBlocks: track blocks in sky beacon
private fun generateFixedJsonBase(
    attacker: UUID,
    coord: Coord,
    block: BlockVec,
): StringBuilder {
    val s = StringBuilder()

    s.append("{")

    // attacker uuid
    s.append("\"id\":\"$attacker\",")

    // chunk coord [c.x, c.z]
    s.append("\"c\":[${coord.x},${coord.z}],")

    // flag base block [b.x, b.y, b.z]
    s.append("\"b\":[${block.blockX},${block.blockY},${block.blockZ}],")

    return s
}

class AttackTextDisplay(
    val attack: Attack,
    val loc: Pos,
) {
    // per-player displays -- touched both from FlagWar.attackTick() (this Attack's own scheduled
    // task thread) and NodesPlayerJoinQuitListener (the joining/quitting player's own thread), so
    // genuinely concurrent.
    val playerTextDisplays: MutableMap<UUID, Entity> = ConcurrentHashMap()

    // Entities are created lazily, per-player, the first time FlagWar.attackTick() finds them
    // in range -- not eagerly for every online player here. See TEXT_DISPLAY_RANGE_SQUARED in
    // FlagWar.kt for why: this used to unconditionally spawn+update one entity per online
    // player per attack regardless of distance.

    /**
     * Remove a player's TextDisplay.
     */
    fun removePlayerTextDisplay(player: Player) {
        // remove the display from the map
        val display = playerTextDisplays.remove(player.uuid)

        // remove the actual display
        display?.remove()
    }

    /**
     * Update the progress text display with current timer.
     */
    fun update(player: Player) {
        // computeIfAbsent, not get-then-put -- attackTick() and a join/quit event for this same
        // player can call update() concurrently (see playerTextDisplays' kdoc); a plain
        // get-then-put here could spawn two entities for one player, orphaning one.
        val textDisplay = playerTextDisplays.computeIfAbsent(player.uuid) { createTextDisplay(loc) }

        // set viewable rule so only this player can see it
        textDisplay.updateViewableRule { viewer -> viewer == player }

        val remainingTicks = attack.attackTime - attack.progress
        val remainingSeconds = remainingTicks / 20
        val minutes = remainingSeconds / 60
        val seconds = remainingSeconds % 60

        val timeText = String.format("%02d:%02d", minutes, seconds)
        val townNameText = townNametagViewedByPlayer(attack.town, player, false)
        val text = "$townNameText\n$timeText"

        setTextDisplayText(textDisplay, text)
    }

    /**
     * Remove all entities, for cleanup.
     */
    fun remove() {
        // Remove all per-player town name displays
        for (display in playerTextDisplays.values) {
            display.remove()
        }
        playerTextDisplays.clear()
    }
}

/**
 * Create a new textDisplay with associated metadata.
 */
private fun createTextDisplay(
    loc: Pos,
): Entity {
    val textDisplay = Entity(EntityType.TEXT_DISPLAY)
    textDisplay.setInstance(MinecraftServer.getInstanceManager().instances.first(), loc)
    textDisplay.setNoGravity(true)

    // Set billboard mode to CENTER so the text always faces the player
    val meta = textDisplay.entityMeta
    if (meta is TextDisplayMeta) {
        meta.billboardRenderConstraints = AbstractDisplayMeta.BillboardConstraints.CENTER // face player
        meta.backgroundColor = 0 // invisible bg
    }

    return textDisplay
}

/**
 * Helper function to set text on a TEXT_DISPLAY entity.
 */
private fun setTextDisplayText(entity: Entity, text: String) {
    val meta = entity.entityMeta
    if (meta is TextDisplayMeta) {
        // text embeds a legacy "§a[Town]"-style prefix (see townNametagViewedByPlayer) --
        // Component.text() would render that "§a" literally instead of as color (same bug as
        // Nametag.kt's team prefix).
        meta.text = LegacyComponentSerializer.legacySection().deserialize(text)
    }
}
