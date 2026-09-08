package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.entity.damage.DamageType
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.potion.PotionEffect
import net.minestom.server.registry.RegistryKey
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object EnvironmentalDamage {
    private val fireContactTicks = ConcurrentHashMap<UUID, Int>()
    private val IN_FIRE: RegistryKey<DamageType> = RegistryKey.unsafeOf("minecraft:in_fire")
    private val ON_FIRE: RegistryKey<DamageType> = RegistryKey.unsafeOf("minecraft:on_fire")
    private val DROWN: RegistryKey<DamageType> = RegistryKey.unsafeOf("minecraft:drown")
    private var task: Task? = null

    fun init() {
        val timeStart = System.currentTimeMillis()
        task = MinecraftServer
            .getSchedulerManager()
            .buildTask(::tick)
            .repeat(TaskSchedule.tick(1))
            .schedule()
        Vanilla.eventNode.addListener(PlayerDeathEvent::class.java, ::removePlayer)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, ::removePlayer)
        println("├─ Environmental damage enabled in ${System.currentTimeMillis() - timeStart}ms")
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun tick() {
        // This ran every single tick directly on the global scheduler thread, touching each
        // player's own fireTicks/health/airTicks and reading blocks around them -- none of that
        // is covered by Minestom's per-entity tick-thread synchronization guarantee unless it
        // goes through that entity's own scheduler. Dispatching per-player also spreads the work
        // across Minestom's per-chunk worker threads instead of serializing all players through
        // one scheduler thread every tick.
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            player.scheduler().scheduleNextTick { tickPlayer(player) }
        }
    }

    internal fun tickPlayer(player: Player) {
        if (player.gameMode == GameMode.CREATIVE || player.gameMode == GameMode.SPECTATOR) {
            reset(player)
            return
        }
        if (Vanilla.config.fireDamageEnabled) tickFire(player) else fireContactTicks.remove(player.uuid)
        if (Vanilla.config.drowningEnabled) tickDrowning(player)
    }

    private fun tickFire(player: Player) {
        // Runs for every online player every tick, so avoid computing the occupied-block set
        // twice (isInWaterOrBubbleColumn + isInFire each used to walk the bounding box and call
        // instance.getBlock() independently) -- compute it once and reuse it for both checks.
        val occupied = blocksOccupiedBy(player).toList()

        if (occupied.any { it === Block.WATER || it === Block.BUBBLE_COLUMN }) {
            player.fireTicks = 0
            fireContactTicks.remove(player.uuid)
            return
        }

        if (occupied.any { it === Block.FIRE || it === Block.SOUL_FIRE }) {
            player.fireTicks = maxOf(player.fireTicks, Vanilla.config.fireTicks)
            if (player.hasEffect(PotionEffect.FIRE_RESISTANCE)) {
                fireContactTicks.remove(player.uuid)
                return
            }
            val ticks = (fireContactTicks[player.uuid] ?: (Vanilla.config.fireContactTicks - 1)) + 1
            if (ticks >= Vanilla.config.fireContactTicks) {
                player.damage(IN_FIRE, Vanilla.config.fireDmg)
                fireContactTicks[player.uuid] = 0
            } else {
                fireContactTicks[player.uuid] = ticks
            }
            return
        }

        fireContactTicks.remove(player.uuid)
        if (player.fireTicks > 0 && player.fireTicks % 20 == 0 && !player.hasEffect(PotionEffect.FIRE_RESISTANCE)) {
            player.damage(ON_FIRE, Vanilla.config.fireDmg)
        }
    }

    private fun tickDrowning(player: Player) {
        val metadata = player.entityMeta
        if (!isEyeInWater(player) || canBreatheUnderwater(player)) {
            metadata.airTicks = Vanilla.config.maxAirTicks
            return
        }

        val airTicks = metadata.airTicks - 1
        if (airTicks == -20) {
            metadata.airTicks = 0
            player.damage(DROWN, Vanilla.config.drowningDmg)
        } else {
            metadata.airTicks = airTicks
        }
    }

    private fun isEyeInWater(player: Player): Boolean {
        val instance = player.instance ?: return false
        val eye = player.position.add(0.0, player.eyeHeight, 0.0)
        return instance.getBlock(eye, Block.Getter.Condition.TYPE) === Block.WATER
    }

    private fun canBreatheUnderwater(player: Player): Boolean =
        player.hasEffect(PotionEffect.WATER_BREATHING) || player.hasEffect(PotionEffect.CONDUIT_POWER)

    private fun blocksOccupiedBy(player: Player): Sequence<Block> =
        sequence {
            val instance = player.instance ?: return@sequence
            val blocks = player.boundingBox.getBlocks(player.position)
            while (blocks.hasNext()) {
                val block = blocks.next()
                yield(instance.getBlock(block.blockX(), block.blockY(), block.blockZ(), Block.Getter.Condition.TYPE) ?: Block.AIR)
            }
        }

    private fun removePlayer(event: PlayerDeathEvent) = reset(event.player)

    private fun removePlayer(event: PlayerDisconnectEvent) = reset(event.player)

    private fun reset(player: Player) {
        fireContactTicks.remove(player.uuid)
        player.fireTicks = 0
        player.entityMeta.airTicks = Vanilla.config.maxAirTicks
    }
}
