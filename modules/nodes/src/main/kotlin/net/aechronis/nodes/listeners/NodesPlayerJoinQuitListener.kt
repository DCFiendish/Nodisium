/**
 * Handle when player join or quit server
 * join: create resident (if does not exist) and mark player online
 * quit: mark player offline
 */

package net.aechronis.nodes.listeners

import net.aechronis.nodes.DiscordWebhook
import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.chat.Chat
import net.aechronis.nodes.objects.MiningBoostManager
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.WaypointMenu
import net.aechronis.nodes.war.FlagWar
import net.aechronis.nodes.war.Warzone
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerLoadedEvent
import net.minestom.server.event.player.PlayerRespawnEvent
import net.minestom.server.event.player.PlayerSpawnEvent

object NodesPlayerJoinQuitListener {
    fun onPlayerJoin(event: PlayerLoadedEvent) {
        // create resident wrapper for player
        // createResident checks if resident already exists
        val player: Player = event.player
        Resident.create(player)

        val resident: Resident = Resident.fromPlayer(player)!!
        Resident.setOnline(resident, player)
        Resident.beginSession(resident)
        resident.createMinimap(player)
        Warzone.onPlayerTerritoryChanged(player, Territory.fromPlayer(player))
        MiningBoostManager.onPlayerJoin(player)

        // if war or a warzone is active, send active chunk attack progress bars
        if (FlagWar.enabled || Warzone.hasActiveZones()) {
            FlagWar.sendWarProgressBarToPlayer(player)
        }

        // if war or a warzone is active, add per-player text displays for active attacks
        if (FlagWar.enabled || Warzone.hasActiveZones()) {
            for (attack in FlagWar.chunkToAttacker.values) {
                attack.textDisplay.update(player)
            }
        }
    }

    fun onPlayerSpawn(event: PlayerSpawnEvent) {
        Resident.fromPlayer(event.player)?.minimap?.respawn()
    }

    fun onPlayerRespawn(event: PlayerRespawnEvent) {
        val player = event.player
        val respawnPoint = Resident.fromPlayer(player)?.town?.spawnpoint ?: Nodes.config.defaultRespawnPoint
        event.respawnPosition = respawnPoint
        player.respawnPoint = respawnPoint
        MinecraftServer.getSchedulerManager().scheduleNextTick {
            if (player.isOnline) Resident.fromPlayer(player)?.minimap?.respawn()
        }
    }

    fun onPlayerDeath(event: PlayerDeathEvent) {
        val player = event.player
        val resident = Resident.fromPlayer(player) ?: return
        val position = player.position
        resident.recordDeathWaypoint(
            position.blockX(),
            position.blockY(),
            position.blockZ(),
        )
        Message.print(player, "Death waypoint set at ${position.blockX()}, ${position.blockY()}, ${position.blockZ()}")

        // Built independently of event.chatMessage -- that TranslatableComponent only resolves to
        // real text client-side (via the vanilla client's own lang file), so there's nothing to
        // serialize to plain text for Discord here.
        val attacker = player.lastDamageSource?.attacker
        val deathLine = if (attacker != null && attacker.uuid != player.uuid) {
            val killerName = (attacker as? Player)?.username ?: attacker.entityType.key().asString().substringAfter(':')
            "☠️ **${player.username}** was slain by **$killerName**"
        } else {
            "☠️ **${player.username}** died"
        }
        DiscordWebhook.send(Nodes.config.discordChatWebhookUrl, deathLine)

        // combat stats -- see docs/STATS.md. Only PvP kills count against a killer; mob/
        // environment/self deaths only increment the victim's death counter.
        Resident.addDeath(resident)
        resident.town?.nation?.let { Nation.addDeath(it) }
        if (attacker is Player && attacker.uuid != player.uuid) {
            Resident.fromPlayer(attacker)?.let { killer ->
                Resident.addKill(killer)
                killer.town?.nation?.let { Nation.addKill(it) }
            }
        }
    }

    fun onPlayerQuit(event: PlayerDisconnectEvent) {
        val player: Player = event.player
        MiningBoostManager.onPlayerQuit(player)
        val resident = Resident.fromPlayer(player)
        if (resident != null) {
            resident.destroyMinimap()
            resident.clearDeathWaypoint()
            Resident.stopPlotSelection(resident)
            Resident.setOffline(resident, player)
            Resident.endSession(resident)
        }
        WaypointMenu.close(player)

        // remove player from muting global chat
        Chat.enableGlobalChat(player)
        Warzone.onPlayerQuit(player)

        // if war or a warzone is active, remove per-player town name displays for active attacks
        if (FlagWar.enabled || Warzone.hasActiveZones()) {
            for (attack in FlagWar.chunkToAttacker.values) {
                attack.textDisplay.removePlayerTextDisplay(player)
            }
        }

        // if playing attacking a chunk, stop it
        if (FlagWar.enabled || Warzone.hasActiveZones()) {
            val attacks = FlagWar.attackers[player.uuid]
            if (attacks !== null) {
                // a.cancel() -> FlagWar.cancelAttack() removes the attack from this same list,
                // so iterating the live list directly threw ConcurrentModificationException.
                for (a in attacks.toList()) {
                    a.cancel()
                }
            }
        }
    }

    fun init() {
        Nodes.eventNode.addListener(PlayerLoadedEvent::class.java, this::onPlayerJoin)
        Nodes.eventNode.addListener(PlayerSpawnEvent::class.java, this::onPlayerSpawn)
        Nodes.eventNode.addListener(PlayerRespawnEvent::class.java, this::onPlayerRespawn)
        Nodes.eventNode.addListener(PlayerDeathEvent::class.java, this::onPlayerDeath)
        Nodes.eventNode.addListener(PlayerDisconnectEvent::class.java, this::onPlayerQuit)
    }
}
