package net.aechronis.nodes.listeners

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.war.Warzone
import net.minestom.server.entity.Player
import net.minestom.server.event.entity.EntityDamageEvent

object NodesPlayerDamageListener {
    private fun onDamage(event: EntityDamageEvent) {
        val victim = event.entity
        val attacker = event.damage.attacker

        if (victim !is Player || attacker !is Player) return

        // an active siege/warzone lets combatants fight regardless of nation/ally ties --
        // otherwise two players sharing a nation/alliance could be unable to damage each
        // other mid-attack, defeating the point of the siege
        val pos = victim.position
        val territory = Territory.fromBlock(pos.blockX(), pos.blockZ())
        val territoryChunk = TerritoryChunk.fromBlock(pos.blockX(), pos.blockZ())
        if (territoryChunk?.attacker !== null || (territory !== null && Warzone.isActive(territory))) return

        // if relationship is ally, town or nation, and config specifies it, cancel event and notify attacker
        val relationship = Town.relationshipOfPlayerToPlayer(victim, attacker)
        val (cancel, message) = when (relationship) {
            DiplomaticRelationship.TOWN, DiplomaticRelationship.NATION -> {
                val cancelled = !Nodes.config.allowNationFriendlyFire
                cancelled to if (cancelled) "You cannot attack members of your nation" else ""
            }

            DiplomaticRelationship.ALLY -> {
                val cancelled = !Nodes.config.allowAllyFriendlyFire
                cancelled to if (cancelled) "You cannot attack your allies" else ""
            }

            else -> false to ""
        }

        if (cancel) {
            event.isCancelled = true
            Message.error(attacker, message)
        }
    }

    fun init() {
        // Must run before vanilla's CombatListener (which tags on any non-self EntityDamageEvent
        // and checks event.isCancelled). Nodes.eventNode and Vanilla.eventNode are same-priority
        // siblings under the global handler, and Minestom compiles+dispatches each sibling's whole
        // subtree as one unit in priority order -- ties fall back to insertion order, and Vanilla.init()
        // currently runs before Nodes.initialize(), so a same-priority registration here would lose
        // the race and cancel the event only after combat-tag already fired. highPriorityEventNode
        // (-999) is the existing convention for exactly this "must run and possibly cancel before
        // other systems react" case (see NodesWorldListener/NodesPlotSelectionListener).
        Nodes.highPriorityEventNode.addListener(EntityDamageEvent::class.java, this::onDamage)
    }
}
