/**
 * Handlers for town chest protection actions:
 *
 * NodesChestProtectListener:
 * - handler for clicking chest for protecting it, created
 *   dynamically per player
 *
 * NodesProtectedChestDestructionListener:
 * - handle detecting chest destruction to remove protected blocks
 */

package net.aechronis.nodes.listeners

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.PROTECTED_BLOCKS
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.Warzone
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent

/**
 * Listener for any special chest protection
 */
object NodesChestProtectionListener {
    private fun onBlockInteract(event: PlayerBlockInteractEvent) {
        val player: Player = event.player
        // Not `!!` -- this can be null for a moment during /nodesadmin load, which clears and
        // repopulates Nodes.residents on a different thread than the one this event fires on.
        val resident: Resident = Resident.fromPlayer(player) ?: return
        if (!resident.isProtectingChests) {
            return
        }

        if (PROTECTED_BLOCKS.any { event.block.compare(it) }) {
            val town: Town = resident.town!!
            val territory: Territory? =
                Territory.fromBlock(event.blockPosition.blockX, event.blockPosition.blockZ)
            val territoryTown: Town? = territory?.let {
                controllingTown(it, event.blockPosition.blockX, event.blockPosition.blockZ)
            }

            if (town !== territoryTown) {
                Message.error(player, "This is not your town (stopping, use /t protect to start protecting again)")
                println(town)
                println(territoryTown)
                Resident.stopProtectingChests(resident)
                return
            }

            // unprotect
            if (town.protectedBlocks.contains(event.blockPosition)) {
                Town.protectChest(town, event.blockPosition, false)

                Message.print(player, "${ChatColor.DARK_AQUA}Removed chest protection")
            }
            // protect
            else {
                Town.protectChest(town, event.blockPosition, true)

                Message.print(player, "You have protected this chest")
            }

            event.isCancelled = true
            return
        }

        Message.error(player, "Not a chest (stopping, use /t protect to start protecting again)")
        Resident.stopProtectingChests(resident)
    }

    fun init() {
        Nodes.highPriorityEventNode.addListener(PlayerBlockInteractEvent::class.java, this::onBlockInteract)
    }
}

// A completed warzone occupation transfers chest-protection control to the
// capturing town's chunk/territory occupier, not the original owner.
private fun controllingTown(territory: Territory, blockX: Int, blockZ: Int): Town? {
    if (!Warzone.isActive(territory)) return territory.town
    return TerritoryChunk.fromBlock(blockX, blockZ)?.occupier ?: territory.occupier ?: territory.town
}

/**
 * Listen to block destruction, unprotect chests if occurs
 */
object NodesChestProtectionDestroyListener {
    private fun onBlockBreak(event: PlayerBlockBreakEvent) {
        val town: Town? = Territory.fromBlock(event.blockPosition.blockX, event.blockPosition.blockZ)
            ?.let { territory -> controllingTown(territory, event.blockPosition.blockX, event.blockPosition.blockZ) }

        if (event.isCancelled || town == null || !town.protectedBlocks.contains(event.blockPosition)) {
            return
        }

        val resident = Resident.fromPlayer(event.player)
        if (resident != null && (resident.hasTownPermissionBypass() || resident.hasTownProtectedChestPermissions(town))) {
            Town.protectChest(town, event.blockPosition, false)
            return
        }

        Message.error(event.player, "This chest is for trusted residents only")
        event.isCancelled = true
    }

    fun init() {
        // Run after general Nodes permissions but before Vanilla storage handles
        // barrel destruction and converts the event into a manual block break.
        Nodes.postPermissionEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreak)
    }
}
