package net.nodisium.combat

import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.timer.Task
import net.nodisium.combat.listeners.AimingListener
import net.nodisium.combat.listeners.FireListener
import net.nodisium.combat.listeners.MeleeListener
import net.nodisium.combat.listeners.MovementListener
import net.nodisium.combat.listeners.PlayerDisconnectListener
import net.nodisium.combat.listeners.ReloadListener
import net.nodisium.combat.listeners.WeaponSwapListener
import net.nodisium.combat.objects.Gun
import net.nodisium.combat.objects.Melee
import net.nodisium.combat.tasks.ActionBarManager
import net.nodisium.combat.tasks.ModelRefreshTask
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Central per-player combat state. Every map here is a concurrent collection from the start --
 * Minestom's per-chunk-parallel tick dispatch means plain HashMap/HashSet is a real race under
 * this project's threading model (confirmed for this exact concern in
 * docs/research-todo/01-concurrency-model.md), not a theoretical one.
 */
object Combat {
    val eventNode: EventNode<net.minestom.server.event.Event> = EventNode.all("combat")

    /**
     * Keyed by player, valued by (which Gun, when) -- not just a bare timestamp. A cooldown is only
     * ever enforced against the *same* gun that set it (see Gun.fire/ActionBarManager); without the
     * gun half of this pair, switching from a slow-cooldown gun to a fast one right after firing
     * would carry the slow gun's recent timestamp over and block the fast gun from firing at all,
     * for a weapon that was never even used yet.
     */
    internal val playerLastFireTimes = ConcurrentHashMap<Player, Pair<Gun, Long>>()
    internal val lastFireInputTimes = ConcurrentHashMap<Player, Long>()
    internal val autoFireTasks = ConcurrentHashMap<Player, Task>()
    internal val reloadTasks = ConcurrentHashMap<Player, Task>()
    /** 0.0-1.0 fraction of the way through the in-progress reload, if any -- see ReloadListener/ActionBarManager. */
    internal val reloadProgress = ConcurrentHashMap<Player, Double>()
    internal val aimingPlayers: MutableSet<Player> = ConcurrentHashMap.newKeySet()
    /** Players currently holding a WASD movement key -- see MovementListener, read by Gun.fire for spread. */
    internal val movingPlayers: MutableSet<Player> = ConcurrentHashMap.newKeySet()
    /** Players currently sprinting -- see MovementListener, read by Gun.fire for the running-spread penalty. */
    internal val sprintingPlayers: MutableSet<Player> = ConcurrentHashMap.newKeySet()
    /** Same (which weapon, when) shape as [playerLastFireTimes], same reason -- see MeleeListener. */
    internal val meleeLastAttackTimes = ConcurrentHashMap<Player, Pair<Melee, Long>>()

    private val initialized = AtomicBoolean(false)

    fun initialize() {
        check(initialized.compareAndSet(false, true)) { "Combat is already initialized -- call shutdown() first" }
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

        FireListener.init()
        ReloadListener.init()
        AimingListener.init()
        MovementListener.init()
        MeleeListener.init()
        WeaponSwapListener.init()
        PlayerDisconnectListener.init()
        ActionBarManager.start()
        ModelRefreshTask.start()
    }

    /** Symmetric inverse of [initialize] -- detaches [eventNode], stops both scheduled tasks, and
     * cancels every outstanding per-player task before clearing all per-player state. */
    fun shutdown() {
        if (!initialized.compareAndSet(true, false)) return
        MinecraftServer.getGlobalEventHandler().removeChild(eventNode)
        ActionBarManager.stop()
        ModelRefreshTask.stop()
        autoFireTasks.values.forEach(Task::cancel)
        reloadTasks.values.forEach(Task::cancel)
        playerLastFireTimes.clear()
        lastFireInputTimes.clear()
        autoFireTasks.clear()
        reloadTasks.clear()
        reloadProgress.clear()
        aimingPlayers.clear()
        movingPlayers.clear()
        sprintingPlayers.clear()
        meleeLastAttackTimes.clear()
    }

    /**
     * Atomically checks [weapon]'s per-player cooldown in [map] and, if it has elapsed (or there's
     * no prior entry, or the prior entry was for a *different* weapon), records [now] as the new
     * last-fire/last-attack time and returns true. Shared by Gun.fire's cooldown and MeleeListener's
     * attack-speed gate -- both need the exact same "is this weapon off cooldown for this player"
     * check against a Pair<Weapon, Long> map.
     *
     * Uses ConcurrentHashMap.compute, not a separate read-then-write (get + put), specifically
     * because a plain check-then-set here is a real TOCTOU race under this project's threading
     * model (see this object's kdoc): an automatic gun's scheduled auto-fire task and a fresh
     * PlayerHandAnimationEvent for the same player can land close enough together that both would
     * read the map before either wrote to it, letting the same gun fire twice inside one
     * cooldownMs window. compute()'s remapping function runs atomically per key, so the read and
     * the write happen as one indivisible step.
     */
    private fun <T : Any> tryStartCooldown(
        map: ConcurrentHashMap<Player, Pair<T, Long>>,
        player: Player,
        weapon: T,
        now: Long,
        cooldownMs: Long,
    ): Boolean {
        var started = false
        map.compute(player) { _, existing ->
            if (existing != null && existing.first === weapon && now - existing.second < cooldownMs) {
                existing
            } else {
                started = true
                weapon to now
            }
        }
        return started
    }

    /** See [tryStartCooldown] -- returns false without recording anything if [gun]'s cooldown for [player] hasn't elapsed. */
    internal fun tryStartFireCooldown(
        player: Player,
        gun: Gun,
        now: Long,
    ): Boolean = tryStartCooldown(playerLastFireTimes, player, gun, now, gun.cooldownMs)

    /** See [tryStartCooldown] -- returns false without recording anything if [melee]'s attack-speed cooldown for [player] hasn't elapsed. */
    internal fun tryStartMeleeCooldown(
        player: Player,
        melee: Melee,
        now: Long,
        cooldownMs: Long,
    ): Boolean = tryStartCooldown(meleeLastAttackTimes, player, melee, now, cooldownMs)

    /** Drops every per-player entry -- called on disconnect so state maps don't grow unbounded. */
    internal fun clearPlayer(player: Player) {
        playerLastFireTimes.remove(player)
        lastFireInputTimes.remove(player)
        autoFireTasks.remove(player)?.cancel()
        reloadTasks.remove(player)?.cancel()
        reloadProgress.remove(player)
        aimingPlayers.remove(player)
        movingPlayers.remove(player)
        sprintingPlayers.remove(player)
        meleeLastAttackTimes.remove(player)
        ActionBarManager.clearPlayer(player)
    }
}
