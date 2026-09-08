package net.nodisium.combat.tasks

import net.minestom.server.MinecraftServer
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import net.nodisium.combat.objects.Gun
import net.nodisium.combat.objects.Item

/**
 * Unconditionally re-applies the correct held-item model variant to every online player's Gun,
 * every tick -- see [Gun.refreshModel]'s kdoc for why this has to run continuously rather than
 * just once on each aim/reload/ammo state transition (a one-shot set is the root cause of the
 * "aiming pose never visually changes" bug tracked in docs/HANDOFF.md). Matches
 * `Aechronis/aechronis`'s own `ModelManager.start()` cadence (`TaskSchedule.tick(1)`).
 */
object ModelRefreshTask {
    private var task: Task? = null

    fun start() {
        task = MinecraftServer
            .getSchedulerManager()
            .buildTask {
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: continue
                    gun.refreshModel(player)
                }
            }.repeat(TaskSchedule.tick(1))
            .schedule()
    }

    fun stop() {
        task?.cancel()
        task = null
    }
}
