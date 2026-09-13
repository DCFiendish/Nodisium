package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Combat
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.TranslatableComponent
import net.minestom.server.entity.Entity
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.entity.EntityDamageEvent
import net.minestom.server.event.player.PlayerDeathEvent
import net.minestom.server.event.player.PlayerDisconnectEvent

object CombatListener {
    fun onDamage(event: EntityDamageEvent) {
        // nodes' friendly-fire check (NodesPlayerDamageListener, registered on the higher-priority
        // Nodes.highPriorityEventNode) cancels ally/nation damage before this listener's subtree
        // runs. Without this check, allowed friendly-fire sparring still combat-tagged both players
        // even though the "attack" was cancelled/non-hostile -- and combat-tag has real teeth
        // (disconnecting while tagged is an instant-kill).
        if (event.isCancelled) return
        val victim = event.entity as? Player ?: return
        val attacker = event.damage.attacker as? Player ?: return
        if (attacker.uuid == victim.uuid) return

        Combat.tag(attacker, victim)
    }

    // Damage.buildDeathMessage() only ever fills the victim's %1$s, so death keys with a
    // killer placeholder (e.g. "death.attack.player": "%1$s was slain by %2$s") reach the
    // client with %2$s unresolved -- it renders as literal percent/dollar-sign text instead
    // of the killer's name. Append the killer as a second translation argument here.
    fun onDeath(event: PlayerDeathEvent) {
        val attacker = event.player.lastDamageSource?.attacker ?: return
        if (attacker.uuid == event.player.uuid) return
        val message = event.chatMessage as? TranslatableComponent ?: return
        event.chatMessage = message.arguments(message.arguments() + attackerName(attacker))
    }

    private fun attackerName(attacker: Entity): Component =
        (attacker as? Player)?.let { Component.text(it.username) }
            ?: attacker.customName
            ?: Component.translatable(attacker.entityType)

    fun onDisconnect(event: PlayerDisconnectEvent) {
        val player = event.player
        val wasInCombat = Combat.isInCombat(player)

        Combat.clear(player)
        if (wasInCombat) {
            // Death listeners run synchronously; retain their penalties without persisting zero health.
            player.kill()
            player.heal()
        }
    }

    fun init() {
        val combatEventNode = EventNode.all("vanilla-combat").setPriority(1000)
        Vanilla.eventNode.addChild(combatEventNode)
        combatEventNode.addListener(EntityDamageEvent::class.java, CombatListener::onDamage)
        combatEventNode.addListener(PlayerDeathEvent::class.java, CombatListener::onDeath)

        Vanilla.eventNode.addListener(PlayerDisconnectEvent::class.java, CombatListener::onDisconnect)
    }
}
