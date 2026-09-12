package net.aechronis.vanilla.managers

import net.aechronis.utils.hasPermission
import net.aechronis.vanilla.Vanilla
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.GameMode
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerGameModeChangeEvent
import net.minestom.server.event.player.PlayerInputEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

object Vanish {
    private const val DOUBLE_SHIFT_WINDOW_MILLIS = 500L

    // Was a plain HashMap mutated from concurrent per-player input/spawn/disconnect events --
    // same bug class already fixed elsewhere in this codebase (Elevator/Storage/Recipes/etc).
    private val vanished = ConcurrentHashMap<UUID, State>()

    private data class State(
        val level: Int,
        var previousGameMode: GameMode? = null,
        var lastShiftPress: Long = 0,
    )

    fun level(player: Player): Int = (5 downTo 1).firstOrNull { player.hasPermission("vanilla.vanish.$it") } ?: 0

    fun isVanished(player: Player): Boolean = vanished.containsKey(player.uuid)

    fun toggle(player: Player) {
        if (isVanished(player)) {
            unvanish(player)
            return
        }

        val level = level(player)
        if (level == 0) {
            player.sendMessage(Component.text("You don't have permission to vanish.", NamedTextColor.RED))
            return
        }
        vanish(player, level)
    }

    private fun vanish(
        player: Player,
        level: Int,
    ) {
        require(level > 0) { "A vanish level is required" }
        vanished[player.uuid] = State(level)
        player.isGlowing = true
        updateVisibility(player)
        player.sendMessage(Component.text("You are now vanished (level $level).", NamedTextColor.LIGHT_PURPLE))
        refreshVisibility()
    }

    private fun unvanish(player: Player) {
        val state = vanished.remove(player.uuid) ?: return
        state.previousGameMode?.let { player.gameMode = it }
        player.isGlowing = false
        updateVisibility(player)
        player.sendMessage(Component.text("You are now visible.", NamedTextColor.LIGHT_PURPLE))
        refreshVisibility()
    }

    private fun updateVisibility(
        player: Player,
        gameMode: GameMode = player.gameMode,
    ) {
        val state = vanished[player.uuid]
        when {
            gameMode == GameMode.SPECTATOR -> player.updateViewableRule { false }
            state != null -> player.updateViewableRule { viewer -> level(viewer) > state.level }
            else -> player.updateViewableRule { true }
        }
    }

    private fun refreshVisibility() {
        MinecraftServer
            .getConnectionManager()
            .onlinePlayers
            .filter { isVanished(it) || it.gameMode == GameMode.SPECTATOR }
            .forEach(::updateVisibility)
    }

    private fun onInput(event: PlayerInputEvent) {
        if (!event.hasPressedShiftKey()) return
        val player = event.player
        val state = vanished[player.uuid] ?: return
        val now = System.currentTimeMillis()
        if (now - state.lastShiftPress > DOUBLE_SHIFT_WINDOW_MILLIS) {
            state.lastShiftPress = now
            return
        }

        state.lastShiftPress = 0
        val previousGameMode = state.previousGameMode
        if (previousGameMode == null) {
            if (player.gameMode == GameMode.SPECTATOR) return
            state.previousGameMode = player.gameMode
            player.gameMode = GameMode.SPECTATOR
            player.sendMessage(Component.text("Spectator mode enabled.", NamedTextColor.LIGHT_PURPLE))
        } else {
            player.gameMode = previousGameMode
            state.previousGameMode = null
            player.sendMessage(Component.text("Restored $previousGameMode mode.", NamedTextColor.LIGHT_PURPLE))
        }
    }

    private fun onGameModeChange(event: PlayerGameModeChangeEvent) {
        // This event fires before Minestom applies the new mode. Apply the requested state now,
        // then reconcile next tick in case another listener cancelled or rewrote the event.
        updateVisibility(event.player, event.newGameMode)
        event.player.scheduleNextTick {
            if (event.player.isOnline) updateVisibility(event.player)
        }
    }

    private fun onSpawn(event: PlayerSpawnEvent) {
        updateVisibility(event.player)
        // Joining clients initially receive every profile from Minestom. Reapply managed rules to
        // remove profiles they cannot view and add authorized profiles before their entity spawns.
        refreshVisibility()
    }

    private fun onDisconnect(event: PlayerDisconnectEvent) {
        vanished.remove(event.player.uuid)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerInputEvent::class.java, ::onInput)
        Vanilla.eventNode.addListener(PlayerGameModeChangeEvent::class.java, ::onGameModeChange)
        Vanilla.eventNode.addListener(PlayerSpawnEvent::class.java, ::onSpawn)
        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, ::onDisconnect)
        MinecraftServer.getConnectionManager().onlinePlayers.forEach(::updateVisibility)
    }
}
