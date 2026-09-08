package net.nodisium.combat.tasks

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import net.nodisium.combat.Combat
import net.nodisium.combat.objects.Gun
import net.nodisium.combat.objects.Item

private const val UPDATE_PERIOD_TICKS = 4
private const val BAR_SEGMENTS = 10

/**
 * Drives the "above the hotbar" gun HUD via the vanilla action bar (Player.sendActionBar) -- a
 * fill-bar while reloading (Combat.reloadProgress, kept live by ReloadListener), a fill-bar while
 * the post-shot cooldown hasn't elapsed yet (Combat.playerLastFireTimes/Gun.cooldownMs -- how long
 * until the gun can fire again), otherwise the gun's name + current/max ammo (Gun.ammoText). All
 * three share one packet/location so the HUD doesn't jump between different screen regions.
 */
object ActionBarManager {
    // Last text sent per player -- skips the resend (and the packet Player.sendActionBar always
    // fires, unlike e.g. AbstractInventory.setItemStack which no-ops on an unchanged value) on the
    // overwhelmingly common tick where the bar hasn't visibly changed.
    private val lastSent = mutableMapOf<Player, Component>()
    private var task: Task? = null

    fun start() {
        task = MinecraftServer
            .getSchedulerManager()
            .buildTask {
                for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                    update(player)
                }
            }.repeat(TaskSchedule.tick(UPDATE_PERIOD_TICKS))
            .schedule()
    }

    fun stop() {
        task?.cancel()
        task = null
        lastSent.clear()
    }

    fun clearPlayer(player: Player) {
        lastSent.remove(player)
    }

    private fun update(player: Player) {
        val gun = Item.getFromItemStack(player.itemInMainHand) as? Gun ?: return
        val reloadProgress = Combat.reloadProgress[player]
        val cooldownProgress = if (reloadProgress == null) cooldownProgress(player, gun) else null
        val text =
            when {
                reloadProgress != null ->
                    Component.text("Reload ", NamedTextColor.GRAY).append(progressBar(reloadProgress, NamedTextColor.YELLOW))
                cooldownProgress != null ->
                    Component.text("Ready ", NamedTextColor.GRAY).append(progressBar(cooldownProgress, NamedTextColor.RED))
                else -> gun.ammoText(player.itemInMainHand)
            }
        if (lastSent[player] == text) return
        lastSent[player] = text
        player.sendActionBar(text)
    }

    /**
     * 0.0-1.0 fraction of the way from "just fired" to "can fire again", or null once the cooldown's
     * elapsed -- also null if the last recorded shot was from a *different* gun than the one
     * currently held (e.g. right after a weapon swap), matching the same per-gun cooldown scoping
     * Gun.fire itself enforces.
     */
    private fun cooldownProgress(
        player: Player,
        gun: Gun,
    ): Double? {
        val lastFire = Combat.playerLastFireTimes[player] ?: return null
        if (lastFire.first !== gun) return null
        val elapsed = System.currentTimeMillis() - lastFire.second
        if (elapsed >= gun.cooldownMs) return null
        return elapsed.toDouble() / gun.cooldownMs
    }

    private fun progressBar(
        progress: Double,
        filledColor: NamedTextColor,
    ): Component {
        val filled = (progress.coerceIn(0.0, 1.0) * BAR_SEGMENTS).toInt()
        return Component
            .text("|".repeat(filled), filledColor)
            .append(Component.text("|".repeat(BAR_SEGMENTS - filled), NamedTextColor.DARK_GRAY))
    }
}
