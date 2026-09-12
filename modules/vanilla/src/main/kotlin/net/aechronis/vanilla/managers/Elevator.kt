package net.aechronis.vanilla.managers

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.FallDamageListener
import net.minestom.server.coordinate.Pos
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ConcurrentSkipListSet
import kotlin.math.abs

// based on ccnet elevators
object Elevator {
    private val IRON = Block.IRON_BLOCK
    private val TYPE = Block.Getter.Condition.TYPE

    // Was plain HashMap<UUID, HashMap<Long, TreeSet<Int>>>, unlike every other stateful manager
    // in this codebase (Crops, Saplings, Combat, Food, EnvironmentalDamage, PlayerData), which all
    // use ConcurrentHashMap because Minestom can tick different instances/chunks on different
    // threads. getOrScanColumn's getOrPut (a compound read-modify-write) ran from onInput (fired
    // per player), while PlayerBlockPlaceEvent/PlayerBlockBreakEvent mutated the inner TreeSet
    // directly from their own listener threads -- no lock anywhere. Two players racing on the same
    // column could corrupt the plain HashMap's bucket chain (a classic concurrent-structural-
    // modification hang) or hit a torn TreeSet read mid-add/remove, handing back a stale targetY
    // and teleporting someone into a solid block. ConcurrentSkipListSet is a drop-in replacement
    // for TreeSet<Int> here -- same higher()/lower()/add()/remove() surface this file already uses
    // -- but safe under concurrent access.
    private val columns = ConcurrentHashMap<UUID, ConcurrentHashMap<Long, ConcurrentSkipListSet<Int>>>()

    private fun columnKey(
        x: Int,
        z: Int,
    ): Long = x.toLong() shl 32 or (z.toLong() and 0xFFFFFFFFL)

    private fun getOrScanColumn(
        instance: Instance,
        x: Int,
        z: Int,
    ): ConcurrentSkipListSet<Int> {
        val byInstance = columns.computeIfAbsent(instance.uuid) { ConcurrentHashMap() }
        return byInstance.computeIfAbsent(columnKey(x, z)) {
            val set = ConcurrentSkipListSet<Int>()
            for (y in -64..320) {
                if (instance.getBlock(x, y, z, TYPE) === IRON) set.add(y)
            }
            set
        }
    }

    fun onInput(event: PlayerInputEvent) {
        val step =
            when {
                event.hasPressedJumpKey() -> 1
                event.hasPressedShiftKey() -> -1
                else -> return
            }
        val player = event.player
        val instance = player.instance ?: return
        val pos = player.position
        val bx = pos.blockX()
        val bz = pos.blockZ()
        val floorY = pos.blockY() - 1

        if (instance.getBlock(bx, floorY, bz, TYPE) !== IRON) return

        val col = getOrScanColumn(instance, bx, bz)
        val targetY = if (step > 0) col.higher(floorY) else col.lower(floorY)
        if (targetY == null || abs(targetY - floorY) > Vanilla.config.elevatorMaxSearch) return

        // The cache only updates on player-driven place/break -- an explosion or other external
        // edit can silently remove the cached floor's iron block. Re-verify it's still there before
        // teleporting; if not, drop the stale entry (self-healing, same pattern as Saplings'
        // growthTick re-checking the block is still a sapling) and bail instead of dropping the
        // player onto a now-missing floor.
        if (instance.getBlock(bx, targetY, bz, TYPE) !== IRON) {
            col.remove(targetY)
            return
        }

        if (instance.getBlock(bx, targetY + 1, bz, TYPE)?.isAir == true &&
            instance.getBlock(bx, targetY + 2, bz, TYPE)?.isAir == true
        ) {
            FallDamageListener.reset(player)
            player.teleport(Pos(pos.x(), (targetY + 1).toDouble(), pos.z(), pos.yaw(), pos.pitch()))
        }
    }

    fun init() {
        val timeStart = System.currentTimeMillis()
        Vanilla.eventNode.addListener(PlayerInputEvent::class.java, Elevator::onInput)
        Vanilla.eventNode.addListener(PlayerBlockPlaceEvent::class.java) { event ->
            if (event.block === IRON) {
                val iid = event.player.instance?.uuid ?: return@addListener
                val key = columnKey(event.blockPosition.blockX(), event.blockPosition.blockZ())
                columns[iid]?.get(key)?.add(event.blockPosition.blockY())
            }
        }
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java) { event ->
            if (event.block === IRON) {
                val iid = event.player.instance?.uuid ?: return@addListener
                val key = columnKey(event.blockPosition.blockX(), event.blockPosition.blockZ())
                columns[iid]?.get(key)?.remove(event.blockPosition.blockY())
            }
        }
        println("├─ Elevators enabled in ${System.currentTimeMillis() - timeStart}ms")
    }
}
