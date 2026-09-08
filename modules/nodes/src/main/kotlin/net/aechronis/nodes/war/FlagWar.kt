/**
 * Flag war implementation conceptually based on
 * Towny flag war:
 * https://github.com/TownyAdvanced/Towny/tree/master/src/com/palmergames/bukkit/towny/war/flagwar
 *
 * War handled by placing a "flag" block onto a chunk to start
 * a "conquer" timer. When timer ends, the chunk is claimed
 * by the attacker's town.
 *
 * When a territory's core is taken, the territory is converted
 * to "occupied" status by the attacking town.
 *
 * Flag block object:
 *     i       <- torch for light (so players can see it)
 *    [ ]      <- wool beacon block (destroy to cancel)
 *     |       <- initial item placed to start claim
 *
 * ----
 * i dont even fully understand save architecture anymore after 6 months
 * hope this doesnt break during war time :^)
 * t. xeth
 */

package net.aechronis.nodes.war

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.ErrorAlreadyCaptured
import net.aechronis.nodes.constants.ErrorAlreadyUnderAttack
import net.aechronis.nodes.constants.ErrorAnnexDisabled
import net.aechronis.nodes.constants.ErrorChunkNotEdge
import net.aechronis.nodes.constants.ErrorFlagTooHigh
import net.aechronis.nodes.constants.ErrorNotBorderTerritory
import net.aechronis.nodes.constants.ErrorNotEnemy
import net.aechronis.nodes.constants.ErrorSkirmishNationRequired
import net.aechronis.nodes.constants.ErrorSkirmishTargetLocked
import net.aechronis.nodes.constants.ErrorSkirmishTargetSelectionRole
import net.aechronis.nodes.constants.ErrorSkyBlocked
import net.aechronis.nodes.constants.ErrorTooManyAttacks
import net.aechronis.nodes.constants.ErrorTownBlacklisted
import net.aechronis.nodes.constants.ErrorTownNotWhitelisted
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.serdes.WarDeserializer
import net.aechronis.nodes.war.serdes.WarSerializer
import net.kyori.adventure.bossbar.BossBar
import net.kyori.adventure.text.Component
import net.minestom.server.MinecraftServer
import net.minestom.server.adventure.audience.Audiences
import net.minestom.server.command.CommandSender
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.entity.Player
import net.minestom.server.instance.block.Block
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

private val SKY_BEACON_FRAME_BLOCK = Block.SEA_LANTERN
private val SKY_BEACON_BLOCK = Block.BLACK_WOOL

// contain all flag materials for sky beacon
private val SKY_BEACON_BLOCKS: Set<Block> = setOf(
    SKY_BEACON_FRAME_BLOCK,
    SKY_BEACON_BLOCK,
)

internal data class SkirmishTargetSelection(
    val nationId: UUID,
    val territoryId: TerritoryId,
)

internal enum class TownDefeatOutcome {
    ALREADY_DEFEATED_THIS_WAR,
    LOST_LIFE,
    ANNEXED,
    FINAL_LIFE_PROTECTED,
}

object FlagWar {

    // ============================================
    // war settings
    // ============================================
    // flag that war is turned on
    internal var enabled: Boolean = false

    // Public read-only view of `enabled` -- combat/zone-restriction code outside this module (e.g.
    // "this weapon is only usable while war is active") needs to read the global war flag, but
    // `enabled` itself stays internal since only FlagWar's own logic should ever set it.
    val isEnabled: Boolean get() = enabled

    // allow annexing territories during war (disable for border skirmishes)
    internal var canAnnexTerritories: Boolean = false

    // only allow attacking border territories, cannot go deeper in
    internal var canOnlyAttackBorders: Boolean = false

    // TODO: war permissions, can create/destroy during war
    internal var destructionEnabled: Boolean = false

    // ticks for the save task
    var saveTaskPeriod: Int = 20
    // ============================================

    // flag items that can be used to claim during war
    internal val flagBlocks: MutableSet<Block> = mutableSetOf()

    // flag sky beacon size, must be in [2, 16]
    internal var skyBeaconSize: Int = 6

    // map attacker UUID -> List of Attack instances
    // Both maps below were plain HashMap/ArrayList while chunkToAttacker (same lifecycle, same
    // call sites) was already ConcurrentHashMap -- flag place/break events dispatch per-chunk on
    // Minestom's chunk-owner threads, not a single global thread, so two attacks starting or
    // ending in different chunks at the same moment could mutate these concurrently.
    internal val attackers: ConcurrentHashMap<UUID, CopyOnWriteArrayList<Attack>> = ConcurrentHashMap()

    // Per-attacker lock objects, used only to serialize beginAttack's maxPlayerChunkAttacks
    // check against its own later attackers-list add (see beginAttack) -- not a general-purpose
    // lock, and never held across different attackers, so it adds no cross-player contention.
    private val attackerLocks: ConcurrentHashMap<UUID, Any> = ConcurrentHashMap()

    // map chunk -> Attack instance
    internal val chunkToAttacker: ConcurrentHashMap<Coord, Attack> = ConcurrentHashMap()

    // map flag block -> Attack instance (for cancelling attacks)
    internal val blockToAttacker: ConcurrentHashMap<BlockVec, Attack> = ConcurrentHashMap()

    // set of all occupied chunks
    internal val occupiedChunks: MutableSet<Coord> = ConcurrentHashMap.newKeySet() // create concurrent set from ConcurrentHashMap

    // One target territory per nation for the current border skirmish -- see
    // prepareSkirmishTargetSelection().
    internal val skirmishTargetsByNation: MutableMap<UUID, TerritoryId> = hashMapOf()

    // A town can consume at most one life during one enabled war period.
    internal val townsDefeatedThisWar: MutableSet<UUID> = hashSetOf()

    // attack/flag update tick interval
    internal const val ATTACK_TICK: Int = 20

    // max distance (blocks) a player needs to be from a flag to receive its progress text
    // display. Without this, attackTick() below updated (and kept a live entity for) every
    // online player regardless of distance, so cost scaled as O(concurrent attacks x total
    // online players) instead of O(concurrent attacks x nearby players) -- a real lag source
    // on populated servers during multi-front wars.
    private const val TEXT_DISPLAY_RANGE_SQUARED: Double = 64.0 * 64.0

    // flag that save required
    internal var needsSave: Boolean = false

    // periodic task to check for save
    internal var saveTask: Task? = null

    fun initialize(flagBlocks: Set<Block>) {
        this.flagBlocks.clear()
        FlagWar.flagBlocks.addAll(flagBlocks)
        skyBeaconSize = Nodes.config.flagBeaconSize.coerceIn(2, 16)
    }

    /**
     * Print info to sender about current war state
     */
    fun printInfo(sender: CommandSender, detailed: Boolean = false) {
        val status = if (enabled) "enabled" else "${ChatColor.GRAY}disabled"
        Message.print(sender, "${ChatColor.BOLD}Nodes war status: $status")
        if (enabled) {
            Message.print(sender, "- Can Annex Territories${ChatColor.WHITE}: $canAnnexTerritories")
            Message.print(sender, "- Can Only Attack Borders${ChatColor.WHITE}: $canOnlyAttackBorders")
            Message.print(sender, "- Destruction Enabled${ChatColor.WHITE}: $destructionEnabled")
            if (canOnlyAttackBorders) {
                Message.print(sender, "- Nations With Selected Targets${ChatColor.WHITE}: ${skirmishTargetsByNation.size}")
            }
            if (detailed) {
                Message.print(sender, "- Using Towns Whitelist${ChatColor.WHITE}: ${Nodes.config.warUseWhitelist}")
                Message.print(sender, "- Can leave town${ChatColor.WHITE}: ${Nodes.config.canLeaveTownDuringWar}")
            }
        }
    }

    /**
     * Async save loop task
     */
    internal object SaveLoop : Runnable {
        override fun run() {
            if (needsSave) {
                needsSave = false
                WarSerializer.save(true)
            }
        }
    }

    /**
     * Load war state from .json file
     */
    internal fun load() {
        // clear all maps
        attackers.clear()
        chunkToAttacker.clear()
        blockToAttacker.clear()
        occupiedChunks.clear()
        skirmishTargetsByNation.clear()
        townsDefeatedThisWar.clear()

        if (Files.exists(Nodes.config.pathWar)) {
            WarDeserializer.fromJson(Nodes.config.pathWar)
        }

        if (enabled) {
            if (canOnlyAttackBorders) {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes border skirmishing enabled")
            } else {
                Message.broadcast("${ChatColor.DARK_RED}${ChatColor.BOLD}Nodes war enabled")
            }
        }
    }

    /**
     * Load an occupied chunk from json
     */
    internal fun loadOccupiedChunk(townName: String, coord: Coord) {
        // get town
        val town = Town.fromName(townName)
        if (town == null) {
            return
        }

        // get territory chunk
        val terrChunk = TerritoryChunk.fromCoord(coord)
        if (terrChunk == null) {
            return
        }

        // mark chunk occupied
        terrChunk.occupier = town
        occupiedChunks.add(terrChunk.coord)
    }

    // cleanup when server is shutdown
    internal fun cleanup() {
        // stop save task
        saveTask?.cancel()
        saveTask = null

        // remove all progress bars from players
        for (attack in chunkToAttacker.values) {
            attack.progressBar.removeViewer(Audiences.all())
        }

        // save current war state
        if (enabled) {
            WarSerializer.save(false)
        }

        // disable war
        enabled = false

        // iterate chunks and stop current attacks
        for ((coord, attack) in chunkToAttacker) {
            val chunk = TerritoryChunk.fromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
            attack.thread.cancel()
            cancelAttack(attack)
        }

        // clear occupied chunks
        for (coord in occupiedChunks) {
            val chunk = TerritoryChunk.fromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
        }

        // clear all maps
        attackers.clear()
        chunkToAttacker.clear()
        blockToAttacker.clear()
        occupiedChunks.clear()
        skirmishTargetsByNation.clear()
        townsDefeatedThisWar.clear()
    }

    /**
     * Enable war, set war state flags
     */
    internal fun enable(canAnnexTerritories: Boolean, canOnlyAttackBorders: Boolean, destructionEnabled: Boolean) {
        skirmishTargetsByNation.clear()
        townsDefeatedThisWar.clear()
        enabled = true
        FlagWar.canAnnexTerritories = canAnnexTerritories
        FlagWar.canOnlyAttackBorders = canOnlyAttackBorders
        FlagWar.destructionEnabled = destructionEnabled

        // create task
        saveTask?.cancel()
        saveTask = MinecraftServer.getSchedulerManager()
            .buildTask(SaveLoop)
            .delay(TaskSchedule.tick(saveTaskPeriod))
            .repeat(TaskSchedule.tick(saveTaskPeriod))
            .schedule()
    }

    /**
     * Disable war, cleanup war state
     */
    internal fun disable() {
        enabled = false
        canAnnexTerritories = false

        // kill save task
        saveTask?.cancel()
        saveTask = null

        // iterate chunks and stop current attacks
        for ((coord, attack) in chunkToAttacker) {
            val chunk = TerritoryChunk.fromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
            attack.thread.cancel()
            cancelAttack(attack)
        }

        // clear occupied chunks
        for (coord in occupiedChunks) {
            val chunk = TerritoryChunk.fromCoord(coord)
            if (chunk !== null) {
                chunk.attacker = null
                chunk.occupier = null
            }
        }

        // clear all maps
        attackers.clear()
        chunkToAttacker.clear()
        blockToAttacker.clear()
        occupiedChunks.clear()
        skirmishTargetsByNation.clear()
        townsDefeatedThisWar.clear()
        Resident.renderMinimaps()

        // save war.json (empty)
        WarSerializer.save(true)
    }

    // A single-chunk territory's core chunk is also its only border chunk. With
    // canOnlyAttackBorders on, that chunk is the sole capturable target for that territory --
    // gating it behind canAnnexTerritories too makes the whole territory uncapturable.
    internal fun canCaptureTerritoryCore(): Boolean = canAnnexTerritories || canOnlyAttackBorders

    // initiate attack on a territory chunk:
    // 1. check chunk is valid target, flag placement valid,
    //    and player can attack
    // 2. create and run attack timer thread
    internal fun beginAttack(attacker: UUID, attackingTown: Town, chunk: TerritoryChunk, flagBase: BlockVec, mode: AttackMode = AttackMode.WAR): Result<Attack> {
        val flagBaseX = flagBase.blockX
        val flagBaseY = flagBase.blockY
        val flagBaseZ = flagBase.blockZ
        val territory = chunk.territory
        val territoryTown = territory.town

        // run checks that chunk attack is valid

        // check chunk has a town
        if (territoryTown === null) {
            return Result.failure(ErrorNotEnemy)
        }

        if (mode == AttackMode.WARZONE) {
            // Warzones are scheduled events, not declared wars -- any town in a
            // nation may contest them, so the blacklist/whitelist checks below
            // (which gate normal war) do not apply.
            if (!Warzone.isActive(territory) || attackingTown.nation == null) return Result.failure(ErrorNotEnemy)
        } else {
            // check if town blacklisted
            if (Nodes.config.warUseBlacklist && Nodes.config.warBlacklist.contains(territoryTown.uuid)) {
                return Result.failure(ErrorTownBlacklisted)
            }

            // check if town not whitelisted
            if (Nodes.config.warUseWhitelist) {
                if (!Nodes.config.warWhitelist.contains(territoryTown.uuid) || (Nodes.config.onlyWhitelistCanClaim && !Nodes.config.warWhitelist.contains(attackingTown.uuid))) {
                    return Result.failure(ErrorTownNotWhitelisted)
                }
            }
        }

        val pendingSkirmishTarget = if (mode == AttackMode.WAR) {
            prepareSkirmishTargetSelection(attacker, attackingTown, territory)
                .getOrElse { return Result.failure(it) }
        } else {
            null
        }

        // check chunk not currently under attack
        if (chunk.attacker !== null) {
            return Result.failure(ErrorAlreadyUnderAttack)
        }

        // check chunk not already captured by town or allies
        if (chunkAlreadyCaptured(chunk, territory, attackingTown)) {
            return Result.failure(ErrorAlreadyCaptured)
        }

        // check chunk either:
        // 1. belongs to enemy
        // 2. town chunk occupied by enemy
        // 3. allied chunk occupied by enemy
        // A warzone chunk skips the enemy check entirely -- any nation may
        // contest it -- but still goes through the same chunk-by-chunk
        // capture mechanics as normal war below.
        if (mode == AttackMode.WARZONE || chunkIsEnemy(chunk, territory, attackingTown)) {
            if (mode == AttackMode.WAR) {
                if (!canCaptureTerritoryCore() && chunk.coord == territory.core) {
                    return Result.failure(ErrorAnnexDisabled)
                }

                // check for only attacking border territories
                if (canOnlyAttackBorders && !isBorderTerritory(territory)) {
                    return Result.failure(ErrorNotBorderTerritory)
                }

                // check that chunk valid, either:
                // 1. next to wilderness
                // 2. next to occupied chunk (by town or allies)
                if (!chunkIsAtEdge(chunk, attackingTown)) {
                    return Result.failure(ErrorChunkNotEdge)
                }
            }

            // check that there is room to create flag
            if (flagBaseY >= 253) { // need room for wool + torch
                return Result.failure(ErrorFlagTooHigh)
            }

            // check flag has vision to sky
            for (y in flagBaseY + 1..255) {
                if (!MinecraftServer.getInstanceManager().instances.first().getBlock(flagBaseX, y, flagBaseZ).isAir) {
                    return Result.failure(ErrorSkyBlocked)
                }
            }

            // Check-and-reserve the attacker's concurrent-attack slot atomically, synchronized
            // per attacker. This size check and the real attackers-list add (inside createAttack,
            // after placing real blocks/beacons -- genuinely slow work) used to be two separate
            // steps with no lock between them: two concurrent beginAttack calls for the same
            // attacker (e.g. flag placements in two different chunks landing on two different
            // Minestom chunk-owner threads in the same tick) could both read the pre-add size and
            // both pass the check, silently bypassing maxPlayerChunkAttacks. Locking per attacker
            // (not globally) means different players' attacks never contend with each other.
            val attackerLock = attackerLocks.computeIfAbsent(attacker) { Any() }
            synchronized(attackerLock) {
                val currentAttacks = attackers.get(attacker)
                if (currentAttacks != null && currentAttacks.size >= Nodes.config.maxPlayerChunkAttacks) {
                    return Result.failure(ErrorTooManyAttacks)
                }

                val attack = createAttack(
                    attacker,
                    attackingTown,
                    chunk,
                    flagBase,
                    mode = mode,
                )

                pendingSkirmishTarget?.takeIf(::commitSkirmishTargetSelection)?.let { selection ->
                    attackingTown.nation?.let { nation ->
                        Message.broadcast(
                            "${ChatColor.DARK_RED}[War] ${nation.name} selected ${territory.name} " +
                                "(id=${territory.id}) for this border skirmish!",
                        )
                    }
                }

                // mark that save required
                needsSave = true

                return Result.success(attack)
            }
        } else {
            return Result.failure(ErrorNotEnemy)
        }
    }

    internal fun beginWarzoneAttack(attacker: UUID, attackingTown: Town, chunk: TerritoryChunk, flagBase: BlockVec): Result<Attack> = beginAttack(attacker, attackingTown, chunk, flagBase, AttackMode.WARZONE)

    internal fun canAnnexDefeatedTown(mode: AttackMode): Boolean = mode != AttackMode.WAR || canAnnexTerritories

    /**
     * Resolves a town's core-chunk defeat: annex outright outside normal war (warzone capture of
     * an unregistered/stopped territory), otherwise consume one of the defeated town's lives, or
     * annex it once its lives are exhausted. A town can lose at most one life per enabled war
     * period, tracked in [townsDefeatedThisWar].
     */
    internal fun resolveTownDefeat(
        attackerTown: Town,
        defeatedTown: Town,
        mode: AttackMode,
    ): TownDefeatOutcome {
        if (mode != AttackMode.WAR) {
            Town.annex(attackerTown, defeatedTown)
            WarSerializer.save(false)
            return TownDefeatOutcome.ANNEXED
        }

        if (!townsDefeatedThisWar.add(defeatedTown.uuid)) {
            return TownDefeatOutcome.ALREADY_DEFEATED_THIS_WAR
        }

        val outcome = when {
            defeatedTown.lives > 1 -> {
                Town.loseLife(defeatedTown)
                TownDefeatOutcome.LOST_LIFE
            }
            canAnnexDefeatedTown(mode) -> {
                Town.annex(attackerTown, defeatedTown)
                TownDefeatOutcome.ANNEXED
            }
            else -> TownDefeatOutcome.FINAL_LIFE_PROTECTED
        }
        needsSave = true
        // Persist the life and per-war defeat marker together. towns.json will catch up
        // through the normal world save queue; war.json is the journal used to recover
        // either value after an abrupt stop.
        WarSerializer.save(false)
        return outcome
    }

    // Pure attack-time formula, pulled out of createAttack() so it's testable without a live
    // Minestom instance/Territory/Town graph. chunkAttackTimeMs is converted to ticks (20/1000)
    // then multiplied by whichever modifiers apply, in the same order the inline version used.
    internal fun calculateAttackTimeTicks(
        chunkAttackTimeMs: Long,
        bordersWilderness: Boolean,
        wastelandMultiplier: Double,
        hasOwningTown: Boolean,
        isHomeTerritory: Boolean,
        homeMultiplier: Double,
        isDefendingSide: Boolean,
        defenderTimeMultiplier: Double,
        attackerTimeMultiplier: Double,
    ): Long {
        var attackTime = chunkAttackTimeMs.toDouble() * 20 / 1000
        if (bordersWilderness) {
            attackTime *= wastelandMultiplier
        }
        if (hasOwningTown) {
            if (isHomeTerritory) {
                attackTime *= homeMultiplier
            }
            attackTime *= if (isDefendingSide) defenderTimeMultiplier else attackerTimeMultiplier
        }
        return attackTime.toLong()
    }

    // actually creates attack instance
    internal fun createAttack(
        attacker: UUID,
        attackingTown: Town,
        chunk: TerritoryChunk,
        flagBase: BlockVec,
        skyBeaconColorBlocksInput: MutableList<BlockVec>? = null,
        skyBeaconWireframeBlocksInput: MutableList<BlockVec>? = null,
        mode: AttackMode = AttackMode.WAR,
    ): Attack {
        val flagBaseX = flagBase.blockX
        val flagBaseY = flagBase.blockY
        val flagBaseZ = flagBase.blockZ
        val territory = chunk.territory

        val flagBlock = flagBase.add(0, 1, 0)
        val flagTorch = flagBase.add(0, 2, 0)
        val action = if (mode == AttackMode.WARZONE) "Capturing warzone" else "Attacking"
        val progressBar = BossBar.bossBar(Component.text("$action ${territory.town!!.name} at ($flagBaseX, $flagBaseY, $flagBaseZ)"), 1f, BossBar.Color.YELLOW, BossBar.Overlay.PROGRESS)

        // calculate max attack time based on chunk and other modifiers
        val terrTown = territory.town
        val attackTimeTicks = calculateAttackTimeTicks(
            chunkAttackTimeMs = Nodes.config.chunkAttackTime,
            bordersWilderness = territory.bordersWilderness,
            wastelandMultiplier = Nodes.config.chunkAttackFromWastelandMultiplier,
            hasOwningTown = terrTown !== null,
            isHomeTerritory = terrTown !== null && territory.id == terrTown.home,
            homeMultiplier = Nodes.config.chunkAttackHomeMultiplier,
            isDefendingSide = terrTown !== null && (terrTown.uuid == attackingTown.uuid || Town.areAllied(terrTown, attackingTown)),
            defenderTimeMultiplier = territory.defenderTimeMultiplier,
            attackerTimeMultiplier = territory.attackerTimeMultiplier,
        )

        val progress = 0L

        // get sky beacon blocks
        val skyBeaconColorBlocks: MutableList<BlockVec> = if (skyBeaconColorBlocksInput === null) {
            mutableListOf()
        } else {
            skyBeaconColorBlocksInput
        }
        val skyBeaconWireframeBlocks: MutableList<BlockVec> = if (skyBeaconWireframeBlocksInput === null) {
            mutableListOf()
        } else {
            skyBeaconWireframeBlocksInput
        }

        if (skyBeaconColorBlocksInput === null || skyBeaconWireframeBlocksInput === null) {
            createAttackBeacon(
                skyBeaconColorBlocks,
                skyBeaconWireframeBlocks,
                chunk.coord,
                flagBaseY,
            )
        }

        // no flag base block, set to default
        if (!Nodes.config.flagBlocks.contains(MinecraftServer.getInstanceManager().instances.first().getBlock(flagBase))) {
            MinecraftServer.getInstanceManager().instances.first().setBlock(flagBase, Nodes.config.flagBlockDefault)
        }

        // initialize flag blocks
        MinecraftServer.getInstanceManager().instances.first().setBlock(flagBlock, Block.DEEPSLATE)
        MinecraftServer.getInstanceManager().instances.first().setBlock(flagTorch, Block.TORCH)

        // create new attack instance
        val attack = Attack(
            attacker,
            attackingTown,
            chunk.coord,
            territory,
            flagBase,
            flagBlock,
            flagTorch,
            skyBeaconColorBlocks.toList(),
            skyBeaconWireframeBlocks.toList(),
            progressBar,
            attackTimeTicks,
            progress,
            mode,
        )

        // mark territory chunk under attack
        chunk.attacker = attackingTown

        // enable boss bar for player
        val player = MinecraftServer.getConnectionManager().getOnlinePlayerByUuid(attacker)
        if (player != null) {
            attack.progressBar.addViewer(player)
        }

        // add attack to list of attacks by attacker
        var currentAttacks = attackers.get(attacker)
        if (currentAttacks == null) {
            currentAttacks = CopyOnWriteArrayList()
            attackers.put(attacker, currentAttacks)
        }
        currentAttacks.add(attack)

        // map chunk to the attack
        chunkToAttacker.put(chunk.coord, attack)
        Resident.renderMinimaps()

        // map flag block to attack (for breaking)
        blockToAttacker.put(flagBlock, attack)

        return attack
    }

    internal fun loadAttack(attacker: UUID, coord: Coord, flagBase: BlockVec, completionTime: Long, mode: AttackMode = AttackMode.WAR) {
        val resident = Resident.fromUuid(attacker) ?: return
        val attackingTown = resident.town ?: return
        val chunk = TerritoryChunk.fromCoord(coord) ?: return
        if (chunk.attacker !== null || chunk.territory.town === null) return
        // A warzone's core-chunk capture is never gated by the global
        // annex/border-skirmish settings -- those only apply to AttackMode.WAR.
        if (mode == AttackMode.WAR && !canCaptureTerritoryCore() && chunk.coord == chunk.territory.core) return

        val attack = createAttack(attacker, attackingTown, chunk, flagBase, mode = mode)
        val now = System.currentTimeMillis() / 1000
        val remainingTicks = ((completionTime - now) * ATTACK_TICK).coerceAtLeast(0)
        attack.progress = (attack.attackTime - remainingTicks).coerceIn(0, attack.attackTime)
        attack.progressBar.progress(attack.progress.toFloat() / attack.attackTime.toFloat())

        if (attack.progress >= attack.attackTime) {
            attack.thread.cancel()
            finishAttack(attack)
        }
    }

    // check if territory is a border territory of a town, requirements:
    // any adjacent territory is not of the same town
    internal fun isBorderTerritory(territory: Territory): Boolean {
        // do not allow attacking home territory
        val territoryTown = territory.town
        if (territoryTown !== null && territoryTown.home == territory.id) {
            return false
        }

        // territory borders wilderness (no territories)
        if (territory.bordersWilderness) {
            return true
        }

        // otherwise, check if any neighbor territory is not owned by the town
        for (neighborTerritoryId in territory.neighbors) {
            val neighborTerritory = Nodes.territories[neighborTerritoryId]
            if (neighborTerritory !== null && neighborTerritory.town !== territoryTown) {
                return true
            }
        }

        return false
    }

    /**
     * Normal wars require declared enemies; a border skirmish is between
     * nations rather than declared enemies, so any non-allied town counts
     * as an opponent while one is active.
     */
    internal fun townIsWarOpponent(attackingTown: Town, otherTown: Town?): Boolean {
        if (otherTown == null) return false
        return if (canOnlyAttackBorders) {
            !Town.areAllied(attackingTown, otherTown)
        } else {
            Town.areEnemies(attackingTown, otherTown)
        }
    }

    /**
     * Validate the nation-wide target lock for a border skirmish. A null
     * selection means either this is not a skirmish or the nation already
     * selected this territory. A non-null selection is committed only after
     * the flag passes every other attack validation.
     */
    internal fun prepareSkirmishTargetSelection(
        attacker: UUID,
        attackingTown: Town,
        territory: Territory,
    ): Result<SkirmishTargetSelection?> {
        if (!canOnlyAttackBorders) return Result.success(null)

        val nation = attackingTown.nation ?: return Result.failure(ErrorSkirmishNationRequired)
        val selectedTerritory = skirmishTargetsByNation[nation.uuid]
        if (selectedTerritory != null) {
            return if (selectedTerritory == territory.id) {
                Result.success(null)
            } else {
                Result.failure(ErrorSkirmishTargetLocked)
            }
        }

        val resident = Resident.fromUuid(attacker)
        val canSelect = resident?.town === attackingTown &&
            (resident === attackingTown.leader || attackingTown.officers.contains(resident))
        if (!canSelect) return Result.failure(ErrorSkirmishTargetSelectionRole)

        return Result.success(SkirmishTargetSelection(nation.uuid, territory.id))
    }

    internal fun commitSkirmishTargetSelection(selection: SkirmishTargetSelection): Boolean {
        val existing = skirmishTargetsByNation[selection.nationId]
        check(existing == null || existing == selection.territoryId) {
            "Nation ${selection.nationId} already selected skirmish territory $existing"
        }
        if (existing != null) return false
        skirmishTargetsByNation[selection.nationId] = selection.territoryId
        return true
    }

    internal fun skirmishTarget(attackingTown: Town): Territory? = attackingTown.nation
        ?.let { nation -> skirmishTargetsByNation[nation.uuid] }
        ?.let(Territory::fromId)

    internal fun loadDefeatedTown(townId: UUID) {
        if (enabled && Town.fromUuid(townId) != null) townsDefeatedThisWar.add(townId)
    }

    internal fun loadSkirmishTarget(nationId: UUID, territoryId: TerritoryId) {
        if (!enabled || !canOnlyAttackBorders) return
        if (Nation.fromUuid(nationId) == null) {
            System.err.println("[Nodes] Ignoring skirmish target for unknown nation $nationId")
            return
        }
        if (Territory.fromId(territoryId) == null) {
            System.err.println("[Nodes] Ignoring unknown skirmish target territory $territoryId")
            return
        }
        skirmishTargetsByNation[nationId] = territoryId
    }

    /** A successful territory capture defeats its town by taking its home. */
    internal fun shouldAnnexTown(
        defeatedTown: Town,
        capturedTerritory: Territory,
    ): Boolean {
        if (capturedTerritory.town !== defeatedTown) return false
        // A town containing any registered warzone must remain a town: annexing it through
        // another territory would otherwise permanently consume its warzone land.
        if (Warzone.ownsRegisteredZone(defeatedTown)) return false
        return capturedTerritory.id == defeatedTown.home
    }

    // check if chunk was already captured
    // 1. territory occupied by town or allies and chunk not occupied
    // 2. chunk occupied by town or allies
    internal fun chunkAlreadyCaptured(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        val territoryOccupier = territory.occupier
        val chunkOccupier = chunk.occupier

        if (territoryOccupier === attackingTown || Town.areAllied(attackingTown, territoryOccupier)) {
            if (!townIsWarOpponent(attackingTown, chunkOccupier)) {
                return true
            }
        }

        if (chunkOccupier !== null) {
            if (chunkOccupier === attackingTown || Town.areAllied(attackingTown, chunkOccupier)) {
                return true
            }
        }

        return false
    }

    // check chunk belongs to an enemy and can be attacked:
    // 1. belongs to enemy town
    // 2. town chunk occupied by enemy
    // 3. allied chunk occupied by enemy
    // 4. town's occupied territory, chunk occupied by enemy
    // 5. ally's occupied territory, chunk occupied by enemy
    internal fun chunkIsEnemy(chunk: TerritoryChunk, territory: Territory, attackingTown: Town): Boolean {
        if (townIsWarOpponent(attackingTown, territory.town)) {
            return true
        }

        val attackingNation = attackingTown.nation
        val territoryNation = territory.town?.nation

        // your town, nation, or ally town chunk occupied by enemy
        if ((territory.town === attackingTown) ||
            (attackingNation !== null && attackingNation === territoryNation) ||
            (Town.areAllied(attackingTown, territory.town))
        ) {
            if (townIsWarOpponent(attackingTown, territory.occupier)) {
                return true
            }
            if (townIsWarOpponent(attackingTown, chunk.occupier)) {
                return true
            }
        }

        // your occupied territory or ally's occupied territory
        // chunk occupied by enemy
        val occupier = territory.occupier
        val occupierNation = occupier?.nation
        if (occupier === attackingTown ||
            (attackingNation !== null && attackingNation === occupierNation) ||
            Town.areAllied(attackingTown, occupier)
        ) {
            if (townIsWarOpponent(attackingTown, chunk.occupier)) {
                return true
            }
        }

        return false
    }

    // check that chunk valid, either:
    // 1. next to wilderness
    // 2. next to occupied chunk (by town or allies)
    internal fun chunkIsAtEdge(chunk: TerritoryChunk, attackingTown: Town): Boolean {
        val coord = chunk.coord

        val chunkNorth = TerritoryChunk.fromCoord(Coord(coord.x, coord.z - 1))
        val chunkSouth = TerritoryChunk.fromCoord(Coord(coord.x, coord.z + 1))
        val chunkWest = TerritoryChunk.fromCoord(Coord(coord.x - 1, coord.z))
        val chunkEast = TerritoryChunk.fromCoord(Coord(coord.x + 1, coord.z))

        return canAttackFromNeighborChunk(chunkNorth, attackingTown) ||
            canAttackFromNeighborChunk(chunkSouth, attackingTown) ||
            canAttackFromNeighborChunk(chunkWest, attackingTown) ||
            canAttackFromNeighborChunk(chunkEast, attackingTown)
    }

    /**
     * conditions for attacking a chunk relative to a neighbor chunk
     */
    internal fun canAttackFromNeighborChunk(neighborChunk: TerritoryChunk?, attacker: Town): Boolean {
        // no territory here
        if (neighborChunk === null) {
            return true
        }

        val attackerNation = attacker.nation

        val neighborTerritory = neighborChunk.territory
        val neighborTown = neighborTerritory.town
        val neighborTerritoryOccupier = neighborTerritory.occupier
        val neighborChunkOccupier = neighborChunk.occupier

        // territory is unoccupied
        if (neighborTown === null) {
            return true
        }

        // neighbor is your town and occupier is friendly
        if (neighborTown === attacker) {
            if (neighborTerritoryOccupier === null) {
                return true
            } else if (Town.areAllied(attacker, neighborTerritoryOccupier)) {
                return true
            }
        }

        // you are neighbor territory occupier or an ally is the occupier
        if (neighborTerritoryOccupier === attacker || Town.areAllied(attacker, neighborTerritoryOccupier)) {
            return true
        }

        // you or an ally is occupying the neighboring chunk
        if (neighborChunkOccupier === attacker || Town.areAllied(attacker, neighborChunkOccupier)) {
            return true
        }

        if (attackerNation !== null) {
            val neighborNation = neighborTown.nation
            val neighborTerritoryOccupierNation = neighborTerritoryOccupier?.nation
            val neighborChunkOccupierNation = neighborChunk.occupier?.nation

            // additional neighbor town check, when occupier is in same nation (somehow)
            if (neighborTown === attacker && neighborNation === neighborTerritoryOccupierNation) {
                return true
            }

            // neighboring chunk belongs to nation and occupied by friendly
            if (attackerNation === neighborNation) {
                if (neighborTerritoryOccupier === null) {
                    return true
                } else if (Town.areAllied(attacker, neighborTerritoryOccupier)) {
                    return true
                }
            }

            if (attackerNation === neighborTerritoryOccupierNation) {
                return true
            }

            if (attackerNation === neighborChunkOccupierNation) {
                return true
            }
        }

        return false
    }

    /**
     * Create/update a flag attack beacon
     * Uses an fast editing session with single packet send and
     * no lighting updates
     * - with block.setType(): takes ~2 ms to update
     * - with edit session: takes ~200-300 us to update
     */
    internal fun createAttackBeacon(
        skyBeaconColorBlocks: MutableList<BlockVec>,
        skyBeaconWireframeBlocks: MutableList<BlockVec>,
        coord: Coord,
        flagBaseY: Int,
    ) {
        // get starting corner
        val size = skyBeaconSize
        val startPositionInChunk: Int = (16 - size) / 2
        val x0: Int = coord.x * 16 + startPositionInChunk
        val z0: Int = coord.z * 16 + startPositionInChunk
        // Was unclamped -- only yEnd was truncated at 255, so a flag placed high enough that
        // y0 alone already exceeded 255 (roughly Y 206-252 depending on flagBeaconSkyLevel)
        // produced yEnd == 255 < y0, an empty "y0..yEnd" range, and the beacon silently rendered
        // nothing at all. Clamping y0 too guarantees a non-empty range even at the world ceiling.
        val y0: Int = maxOf(flagBaseY + Nodes.config.flagBeaconSkyLevel, Nodes.config.flagBeaconMinSkyLevel).coerceAtMost(255)
        val xEnd: Int = x0 + size - 1
        val zEnd: Int = z0 + size - 1
        val yEnd: Int = minOf(255, y0 + size) // truncate at map limit

        for (y in y0..yEnd) {
            for (x in x0..xEnd) {
                for (z in z0..zEnd) {
                    val blockPos = BlockVec(x, y, z)
                    val block = MinecraftServer.getInstanceManager().instances.first().getBlock(x, y, z)
                    if (block == Block.AIR || SKY_BEACON_BLOCKS.contains(block)) {
                        if (((y == y0 || y == yEnd) && (x == x0 || x == xEnd || z == z0 || z == zEnd)) ||
                            // end caps, edges glowstone
                            ((x == x0 || x == xEnd) && (z == z0 || z == zEnd))
                        ) { // middle section corners glowstone
                            // block.setType(BEACON_EDGE_BLOCK) // slow
                            skyBeaconWireframeBlocks.add(blockPos)
                            MinecraftServer.getInstanceManager().instances.first().setBlock(blockPos, SKY_BEACON_FRAME_BLOCK)
                        } else { // color block
                            // setFlagAttackColorBlock(block, progress) // slow
                            skyBeaconColorBlocks.add(blockPos)
                            MinecraftServer.getInstanceManager().instances.first().setBlock(blockPos, SKY_BEACON_BLOCK)
                        }
                    }
                }
            }
        }
    }

    // cleanup attack instance, then dispatch signal
    // that attack cancelled (was defended)
    // (runs on main thread)
    // TODO: signal event that chunk defended (broadcast message)
    internal fun cancelAttack(attack: Attack) {
        // remove status from territory chunk
        val chunk = TerritoryChunk.fromCoord(attack.coord)
        chunk?.attacker = null

        // remove progress bar from player
        attack.progressBar.removeViewer(Audiences.all())

        // remove claim flag
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagTorch, Block.AIR)
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagBlock, Block.AIR)
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagBase, Block.AIR)

        // remove sky beacon
        for (block in attack.skyBeaconWireframeBlocks) {
            MinecraftServer.getInstanceManager().instances.first().setBlock(block, Block.AIR)
        }
        for (block in attack.skyBeaconColorBlocks) {
            MinecraftServer.getInstanceManager().instances.first().setBlock(block, Block.AIR)
        }

        // remove text display
        attack.textDisplay.remove()

        // remove attack instance references
        attackers.get(attack.attacker)?.remove(attack)
        chunkToAttacker.remove(attack.coord)
        blockToAttacker.remove(attack.flagBlock)
        Resident.renderMinimaps()

        // mark save needed
        needsSave = true
    }

    internal fun cancelWarzoneAttacks(territory: Territory) {
        chunkToAttacker.values
            .filter { it.mode == AttackMode.WARZONE && it.targetTerritory === territory }
            .toList()
            .forEach(::cancelAttack)
    }

    /**
     * finish attack instance and capture chunk
     * - set chunk occupation status
     * - dispatch signal that attack finished
     * (runs on main thread)
     * different results:
     *   1. attacking enemy chunk -> capture chunk
     *   2. attacking enemy home chunk -> capture territory
     *   3. attacking town/ally occupied chunk -> capture chunk
     *   4. attacking town/ally home chunk -> recapture territory
     */
    internal fun finishAttack(attack: Attack) {
        val messageContext = if (attack.mode == AttackMode.WARZONE) "[Warzone]" else "[War]"

        // remove progress bar from player
        attack.progressBar.removeViewer(Audiences.all())

        // remove claim flag
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagTorch, Block.AIR)
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagBlock, Block.AIR)
        MinecraftServer.getInstanceManager().instances.first().setBlock(attack.flagBase, Block.AIR)

        // remove sky beacon
        for (block in attack.skyBeaconWireframeBlocks) {
            MinecraftServer.getInstanceManager().instances.first().setBlock(block, Block.AIR)
        }
        for (block in attack.skyBeaconColorBlocks) {
            MinecraftServer.getInstanceManager().instances.first().setBlock(block, Block.AIR)
        }

        // remove town and cap progress textdisplay
        attack.textDisplay.remove()

        // remove attack instance references
        attackers.get(attack.attacker)?.remove(attack)
        chunkToAttacker.remove(attack.coord)
        blockToAttacker.remove(attack.flagBlock)

        // mark that save required
        needsSave = true

        // chunk should not be null unless territory swapped
        // out during attack and chunks were modified in new territory
        val chunk = TerritoryChunk.fromCoord(attack.coord)
        if (chunk == null || chunk.territory !== attack.targetTerritory) {
            println("finishAttack(): TerritoryChunk at ${attack.coord} is null")
            Resident.renderMinimaps()
            return
        }

        if (chunk.coord == chunk.territory.core && !canCaptureTerritoryCore()) {
            chunk.attacker = null
            Resident.renderMinimaps()
            return
        }

        // handle occupation state of chunk
        // if chunk is core chunk of territory, attacking town occupies territory
        if (chunk.coord == chunk.territory.core) {
            val territory = chunk.territory
            val territoryTown = territory.town
            val attacker = Resident.fromUuid(attack.attacker)
            val attackerTown = attack.town
            val attackerNation = attackerTown.nation

            // cleanup territory chunks
            for (coord in territory.chunks) {
                val territoryChunk = TerritoryChunk.fromCoord(coord)
                if (territoryChunk != null) {
                    // cancel any concurrent attacks in this territory
                    chunkToAttacker.get(territoryChunk.coord)?.cancel()

                    // clear occupy/attack status from chunks
                    territoryChunk.attacker = null
                    territoryChunk.occupier = null

                    // remove from internal list of occupied chunks
                    occupiedChunks.remove(territoryChunk.coord)
                }
            }

            // handle re-capturing your own territory, nation territory, or ally territory from enemy
            if (territoryTown === attackerTown ||
                (attackerNation !== null && attackerNation === territoryTown?.nation) ||
                Town.areAllied(attackerTown, territoryTown)
            ) {
                val occupier = territory.occupier
                Town.release(territory)
                Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} liberated territory (id=${territory.id}) from ${occupier?.name}!")
            }
            // captured enemy territory
            else {
                Town.capture(attackerTown, territory)
                // Warzone scoring starts only when normal war mechanics complete
                // a core-chunk capture of this territory.
                if (attack.mode == AttackMode.WARZONE) Warzone.onTerritoryOccupied(territory, attackerTown)
                Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} captured territory (id=${territory.id}) from ${territory.town?.name}!")
                // Warzones are always occupied, never permanently annexed. This includes a
                // warzone that is a town's home territory and a stopped warzone later
                // captured during normal war.
                if (territoryTown != null && !Warzone.isRegistered(territory) && shouldAnnexTown(territoryTown, territory)) {
                    val defeatedTownName = territoryTown.name
                    when (resolveTownDefeat(attackerTown, territoryTown, attack.mode)) {
                        TownDefeatOutcome.ALREADY_DEFEATED_THIS_WAR -> Unit
                        TownDefeatOutcome.LOST_LIFE -> Message.broadcast(
                            "${ChatColor.DARK_RED}[Conquest] $defeatedTownName lost a life and has " +
                                "${territoryTown.lives} remaining; it cannot lose another life this war!",
                        )
                        TownDefeatOutcome.ANNEXED -> Message.broadcast(
                            "${ChatColor.DARK_RED}[Conquest] ${attackerTown.name} annexed $defeatedTownName; " +
                                "${attacker?.name ?: attackerTown.name} made the decisive capture!",
                        )
                        TownDefeatOutcome.FINAL_LIFE_PROTECTED -> Message.broadcast(
                            "${ChatColor.DARK_RED}[Conquest] $defeatedTownName was defeated but cannot be annexed " +
                                "during this war mode; it remains on its final life.",
                        )
                    }
                }
            }
        }
        // else, attacking normal chunk cases:
        // 1. your town, chunk captured by enemy -> liberating, remove flag
        // 2. your town (occupied) -> liberating, put flag
        // 3. territory occupied by your town, captured -> liberating, remove flag
        // 4. enemy town, empty chunk -> attacking, put flag
        else {
            val town = chunk.territory.town
            val occupier = chunk.territory.occupier
            val attacker = Resident.fromUuid(attack.attacker)

            chunk.attacker = null

            if (town === attack.town) {
                // re-capturing territory from occupier
                if (occupier !== null) {
                    chunk.occupier = town
                    occupiedChunks.add(chunk.coord)

                    Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} liberated chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${occupier.name}!")
                }
                // must be defending captured chunk
                else {
                    val chunkOccupier = chunk.occupier

                    chunk.occupier = null
                    occupiedChunks.remove(chunk.coord)

                    if (chunkOccupier !== null) {
                        Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${chunkOccupier.name}!")
                    }
                }
            } else if (occupier === attack.town && chunk.occupier !== null) {
                val chunkOccupier = chunk.occupier
                chunk.occupier = null
                occupiedChunks.remove(chunk.coord)

                Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} defended chunk (${chunk.coord.x}, ${chunk.coord.z}) against ${chunkOccupier?.name}!")
            } else {
                chunk.occupier = attack.town
                occupiedChunks.add(chunk.coord)

                Message.broadcast("${ChatColor.DARK_RED}$messageContext ${attacker?.name} captured chunk (${chunk.coord.x}, ${chunk.coord.z}) from ${chunk.territory.town?.name}!")
            }

            // update minimaps
            Resident.renderMinimaps()
        }
    }

    // update tick for attack instance
    internal fun attackTick(attack: Attack) {
        val progress = attack.progress + ATTACK_TICK

        if (progress >= attack.attackTime) {
            // cancel thread, then finalize attack
            attack.thread.cancel()
            finishAttack(attack)
        } else { // update
            attack.progress = progress

            // update boss bar progress
            val progressNormalized: Float = progress.toFloat() / attack.attackTime.toFloat()
            attack.progressBar.progress(progressNormalized)

            // update progress text -- only for players actually near the flag. Also drop the
            // display for anyone who was tracked but has since wandered out of range, so their
            // entity doesn't linger showing a frozen countdown.
            val flagPos = attack.textDisplay.loc
            for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                val inRange = player.position.distanceSquared(flagPos) <= TEXT_DISPLAY_RANGE_SQUARED
                if (inRange) {
                    attack.textDisplay.update(player)
                } else if (attack.textDisplay.playerTextDisplays.containsKey(player.uuid)) {
                    attack.textDisplay.removePlayerTextDisplay(player)
                }
            }
        }
    }

    // intended to run on PlayerJoin event
    // if war enabled and player has attacks,
    // send progress bars to player
    fun sendWarProgressBarToPlayer(player: Player) {
        val uuid = player.uuid

        // add attack to list of attacks by attacker
        val currentAttacks = attackers.get(uuid)
        if (currentAttacks != null) {
            for (attack in currentAttacks) {
                attack.progressBar.addViewer(player)
            }
        }
    }
}
