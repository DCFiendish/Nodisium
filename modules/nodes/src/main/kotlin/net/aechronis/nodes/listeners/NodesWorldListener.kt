/**
 * Main listener for Nodes world:
 * - town permissions and protections
 * - flag war events
 * - hidden ore
 * - ore taxation
 */

package net.aechronis.nodes.listeners

import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.DiplomaticRelationship
import net.aechronis.nodes.constants.ErrorAlreadyCaptured
import net.aechronis.nodes.constants.ErrorAlreadyUnderAttack
import net.aechronis.nodes.constants.ErrorAnnexDisabled
import net.aechronis.nodes.constants.ErrorChunkNotEdge
import net.aechronis.nodes.constants.ErrorFlagTooHigh
import net.aechronis.nodes.constants.ErrorNoTerritory
import net.aechronis.nodes.constants.ErrorNotBorderTerritory
import net.aechronis.nodes.constants.ErrorNotEnemy
import net.aechronis.nodes.constants.ErrorSkirmishNationRequired
import net.aechronis.nodes.constants.ErrorSkirmishTargetLocked
import net.aechronis.nodes.constants.ErrorSkirmishTargetSelectionRole
import net.aechronis.nodes.constants.ErrorSkyBlocked
import net.aechronis.nodes.constants.ErrorTooManyAttacks
import net.aechronis.nodes.constants.ErrorTownBlacklisted
import net.aechronis.nodes.constants.ErrorTownNotWhitelisted
import net.aechronis.nodes.constants.INTERACTIVE_BLOCKS
import net.aechronis.nodes.constants.PROTECTED_BLOCKS
import net.aechronis.nodes.constants.PermissionsGroup
import net.aechronis.nodes.constants.TownPermissions
import net.aechronis.nodes.objects.MiningBoostManager
import net.aechronis.nodes.objects.Plot
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.war.Attack
import net.aechronis.nodes.war.FlagWar
import net.aechronis.nodes.war.Warzone
import net.aechronis.vanilla.managers.StorageAccess
import net.minestom.server.MinecraftServer
import net.minestom.server.component.DataComponents
import net.minestom.server.coordinate.BlockVec
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.ItemEntity
import net.minestom.server.entity.Player
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.event.player.PlayerBlockInteractEvent
import net.minestom.server.event.player.PlayerBlockPlaceEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.enchant.Enchantment
import java.util.concurrent.ThreadLocalRandom

object NodesWorldListener {
    private fun onBlockBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return

        val player: Player = event.player
        if (Nodes.config.adminUsernames.contains(player.username)) return
        val blockPos = event.blockPosition
        val territoryChunk = TerritoryChunk.fromBlock(blockPos.blockX, blockPos.blockZ)

        // if chunk is being attacked (normal war or a warzone), do flag checks
        if (territoryChunk?.attacker !== null) {
            val attack = FlagWar.chunkToAttacker.get(territoryChunk.coord)!!

            if (blockInWarFlagNoBuildRegion(blockPos, attack)) {
                // handle war flag breaking
                if (attack.flagBlock == blockPos) {
                    event.isCancelled = true

                    // handle breaking allies flags
                    if (!Nodes.config.allowBreakingAlliesFlags) {
                        // allow player to break their own flag
                        if (player.uuid != attack.attacker) {
                            val relationship = Town.relationshipOfPlayerToTown(player, attack.town)
                            if (relationship in setOf(
                                    DiplomaticRelationship.NATION,
                                    DiplomaticRelationship.ALLY,
                                    DiplomaticRelationship.TOWN,
                                )
                            ) {
                                Message.error(player, "[War] Cannot break ally war flags")
                                return
                            }
                        }
                    }
                    attack.cancel()
                    Message.broadcast("${ChatColor.GOLD}[War] Attack at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ}) defeated by ${player.username}")
                    return
                }
                event.isCancelled = true
                Message.error(
                    player,
                    "[War] Cannot break blocks within ${Nodes.config.flagNoBuildDistance} blocks of war flags",
                )
                return
            }
        }

        val territory: Territory? = Territory.fromBlock(blockPos.blockX, blockPos.blockZ)
        val town: Town? = territory?.town
        val resident = Resident.fromPlayer(player)

        // interacting in areas with no territory or no town
        if (town === null) {
            if (hasWildernessPermissions(territory)) {
                return
            }

            event.isCancelled = true
            Message.error(player, "You cannot destroy here!")
            return
        }

        // interacting in a town
        if (resident !== null) {
            val plot = Plot.at(town, blockPos.blockX, blockPos.blockY, blockPos.blockZ)
            val plotPermission = plot?.let { getPlotPermission(TownPermissions.DESTROY, it, resident, town) }
            if (plotPermission != null) {
                if (plotPermission) return
                event.isCancelled = true
                Message.error(player, "You cannot destroy here!")
                return
            }

            if (hasTownPermissions(TownPermissions.DESTROY, town, resident)) {
                return
            }

            // A completed warzone occupation is controlled by the capturing
            // town, not by the original owner or its plots.
            val warzoneOccupierTown = territoryChunk?.let { warzoneOccupier(territory, it) }
            if (warzoneOccupierTown != null) {
                if (hasTownPermissions(TownPermissions.DESTROY, warzoneOccupierTown, resident)) return
                event.isCancelled = true
                Message.error(player, "You cannot destroy here!")
                return
            }

            // territory occupier permissions
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.DESTROY, town, occupier, resident)) {
                return
            }

            // war permissions
            if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                return
            }
        }

        event.isCancelled = true
        Message.error(player, "You cannot destroy here!")
    }

    private fun onBlockBreakSuccess(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) {
            return
        }

        val player = event.player
        val block = event.block
        val blockPos = event.blockPosition

        // handle hidden ore mining
        if (Nodes.config.oreBlocks.contains(block)) {
            if (!Nodes.hiddenOreInvalidBlocks.contains(blockPos)) {
                handleHiddenOre(player, blockPos)

                // temporarily invalide block location
                Nodes.hiddenOreInvalidBlocks.add(blockPos)
                // Ledger must survive a restart or place-then-rebreak farming reopens -- see
                // TaskSaveOreCache, which only runs when a save is actually dirty.
                Nodes.needsSave = true
            }
        }
    }

    private fun onBlockPlace(event: PlayerBlockPlaceEvent) {
        if (event.isCancelled) return

        val block = event.block
        val blockPos = event.blockPosition
        val player: Player = event.player
        if (Nodes.config.adminUsernames.contains(player.username)) return

        // war/warzone specific tasks
        run {
            val territoryChunk = TerritoryChunk.fromBlock(blockPos.blockX, blockPos.blockZ)
            val isWarzone = territoryChunk?.territory?.let(Warzone::isActive) == true
            if ((FlagWar.enabled || isWarzone) && territoryChunk !== null) {
                // disable block placement in flag no build distance
                if (territoryChunk.attacker !== null) {
                    val attack = FlagWar.chunkToAttacker.get(territoryChunk.coord)
                    if (attack !== null) {
                        if (blockInWarFlagNoBuildRegion(blockPos, attack)) {
                            event.isCancelled = true
                            Message.error(
                                player,
                                "[War] Cannot build within ${Nodes.config.flagNoBuildDistance} blocks of war flags",
                            )
                            return
                        }
                    }
                }
                // check if this is flag placement
                else if (FlagWar.flagBlocks.contains(block)) {
                    // get player and town
                    val resident = Resident.fromPlayer(player)
                    if (resident !== null) {
                        val town = resident.town
                        if (town !== null) {
                            val context = if (isWarzone) "[Warzone]" else "[War]"
                            val result = if (isWarzone) {
                                FlagWar.beginWarzoneAttack(player.uuid, town, territoryChunk, blockPos)
                            } else {
                                FlagWar.beginAttack(player.uuid, town, territoryChunk, blockPos)
                            }
                            if (result.isSuccess) {
                                // get town being attacked
                                val townAttacked = territoryChunk.territory.town!!

                                // reclaiming your town
                                if (townAttacked === town) {
                                    Message.broadcast("${ChatColor.DARK_RED}$context ${event.player.username} is liberating ${townAttacked.name} at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ})")
                                } else if (isWarzone) {
                                    Message.broadcast("${ChatColor.DARK_RED}$context ${event.player.username} is capturing warzone ${townAttacked.name} at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ})")
                                } else { // attacking enemy
                                    Message.broadcast("${ChatColor.DARK_RED}$context ${event.player.username} is attacking ${townAttacked.name} at (${blockPos.blockX}, ${blockPos.blockY}, ${blockPos.blockZ})")
                                }
                            } else {
                                when (result.exceptionOrNull()) {
                                    ErrorNoTerritory -> Message.error(player, "$context There is no territory here")

                                    ErrorAlreadyUnderAttack -> Message.error(player, "$context Chunk already under attack")

                                    ErrorAlreadyCaptured -> Message.error(
                                        player,
                                        "$context Chunk already captured by town or allies",
                                    )

                                    ErrorTownBlacklisted -> Message.error(
                                        player,
                                        "$context Cannot attack this town (blacklisted)",
                                    )

                                    ErrorTownNotWhitelisted -> Message.error(
                                        player,
                                        "$context Cannot attack this town (not whitelisted)",
                                    )

                                    ErrorNotEnemy -> Message.error(
                                        player,
                                        if (isWarzone) "$context You must be in a nation to capture this warzone" else "$context Chunk does not belong to an enemy",
                                    )

                                    ErrorAnnexDisabled -> Message.error(player, "$context Territory annexing is disabled")

                                    ErrorNotBorderTerritory -> Message.error(
                                        player,
                                        "$context You can only attack border territories",
                                    )

                                    ErrorSkirmishNationRequired -> Message.error(
                                        player,
                                        "$context You must be in a nation to attack during a border skirmish",
                                    )

                                    ErrorSkirmishTargetSelectionRole -> Message.error(
                                        player,
                                        "$context A town leader or officer must place the first flag to select your nation's target",
                                    )

                                    ErrorSkirmishTargetLocked -> {
                                        val selected = FlagWar.skirmishTarget(town)
                                        Message.error(
                                            player,
                                            "$context Your nation can only attack ${selected?.name ?: "its selected territory"} during this skirmish",
                                        )
                                    }

                                    ErrorChunkNotEdge -> Message.error(
                                        player,
                                        "$context Must attack from territory edge or from captured chunk",
                                    )

                                    ErrorFlagTooHigh -> Message.error(
                                        player,
                                        "$context Flag placement too high, cannot create flag",
                                    )

                                    ErrorSkyBlocked -> Message.error(player, "$context Flag must see the sky")

                                    ErrorTooManyAttacks -> Message.error(
                                        player,
                                        "$context You cannot attack any more chunks at the same time",
                                    )
                                }

                                // cancel event
                                event.isCancelled = true
                            }
                        } else {
                            Message.error(player, "[War] Cannot claim unless you are part of a town")
                            event.isCancelled = true
                        }
                    } else {
                        event.isCancelled = true
                    }
                }
            }
        }

        val territory: Territory? = Territory.fromBlock(blockPos.blockX, blockPos.blockZ)
        val territoryChunk = TerritoryChunk.fromBlock(blockPos.blockX, blockPos.blockZ)
        val resident = Resident.fromPlayer(player)
        val town: Town? = territory?.town

        // interacting in areas with no territory or no town
        if (town === null) {
            if (hasWildernessPermissions(territory)) {
                return
            }

            NodesBlockPlacementCooldownListener.apply(player, blockPos.blockX, blockPos.blockZ)
            event.isCancelled = true
            Message.error(player, "You cannot build here!")
            return
        }

        // interacting in a town
        if (resident !== null) {
            val plot = Plot.at(town, blockPos.blockX, blockPos.blockY, blockPos.blockZ)
            val plotPermission = plot?.let { getPlotPermission(TownPermissions.BUILD, it, resident, town) }
            if (plotPermission != null) {
                if (plotPermission) return
                NodesBlockPlacementCooldownListener.apply(player, blockPos.blockX, blockPos.blockZ)
                event.isCancelled = true
                Message.error(player, "You cannot build here!")
                return
            }

            if (hasTownPermissions(TownPermissions.BUILD, town, resident)) {
                return
            }

            // Warzone occupations use the capturing town's ordinary
            // permissions, rather than the owner town's permissions.
            val warzoneOccupierTown = territoryChunk?.let { warzoneOccupier(territory, it) }
            if (warzoneOccupierTown != null) {
                if (hasTownPermissions(TownPermissions.BUILD, warzoneOccupierTown, resident)) return
                NodesBlockPlacementCooldownListener.apply(player, blockPos.blockX, blockPos.blockZ)
                event.isCancelled = true
                Message.error(player, "You cannot build here!")
                return
            }

            // territory occupier permissions
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.BUILD, town, occupier, resident)) {
                return
            }

            // war permissions
            if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                return
            }

            // ignore if war/warzone enabled and item in hand is a flag material
            if ((FlagWar.enabled || Warzone.isActive(territory)) && Nodes.config.flagBlocks.contains(block)) {
                return
            }
        }

        NodesBlockPlacementCooldownListener.apply(player, blockPos.blockX, blockPos.blockZ)
        event.isCancelled = true
        Message.error(player, "You cannot build here!")
    }

    private fun onBlockPlaceSuccess(event: PlayerBlockPlaceEvent) {
        if (event.isCancelled) {
            return
        }

        val block = event.block
        val blockPos = event.blockPosition

        // invalide hidden ore blocks
        if (Nodes.config.oreBlocks.contains(block)) {
            Nodes.hiddenOreInvalidBlocks.add(blockPos)
            Nodes.needsSave = true
        }
    }

    private fun onBlockInteract(event: PlayerBlockInteractEvent) {
        if (event.isCancelled) return

        val territory: Territory? = Territory.fromBlock(event.blockPosition.blockX, event.blockPosition.blockZ)
        val territoryChunk = TerritoryChunk.fromBlock(event.blockPosition.blockX, event.blockPosition.blockZ)
        val resident = Resident.fromPlayer(event.player)
        val town: Town? = territory?.town

        // interacting in areas with no territory or no town
        // DO NOT USE WILDERNESS PERMISSIONS
        if (territory === null) {
            return
        }
        if (town === null) {
            return
        }

        if (resident !== null) {
            if (INTERACTIVE_BLOCKS.none { event.block.compare(it) }) {
                return
            }

            val plot = Plot.at(town, event.blockPosition.blockX, event.blockPosition.blockY, event.blockPosition.blockZ)

            // Warzone occupations use the capturing town's ordinary
            // permissions, rather than the owner town's permissions.
            val warzoneOccupierTown = warzoneOccupier(territory, territoryChunk!!)
            if (warzoneOccupierTown != null) {
                val permission = if (PROTECTED_BLOCKS.any { event.block.compare(it) }) TownPermissions.CHESTS else TownPermissions.INTERACT
                if (!hasTownPermissions(permission, warzoneOccupierTown, resident)) {
                    event.isCancelled = true
                    Message.error(event.player, if (permission == TownPermissions.CHESTS) "You cannot use chests here!" else "You cannot interact here!")
                    return
                }
                if (permission == TownPermissions.CHESTS &&
                    warzoneOccupierTown.protectedBlocks.contains(event.blockPosition) &&
                    !resident.hasTownProtectedChestPermissions(warzoneOccupierTown)
                ) {
                    event.isCancelled = true
                    Message.error(event.player, "This chest is for trusted residents only")
                }
                return
            }

            // special permissions for using chests, furnaces, etc...
            if (PROTECTED_BLOCKS.any { event.block.compare(it) }) {
                // war permissions override
                if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                    return
                }

                val plotPermission = plot?.let { getPlotPermission(TownPermissions.CHESTS, it, resident, town) }

                // normal town permissions
                if (plotPermission == true || (plotPermission == null && hasTownPermissions(TownPermissions.CHESTS, town, resident))) {
                    // check if chest protected
                    if (town.protectedBlocks.contains(event.blockPosition) && !resident.hasTownProtectedChestPermissions(town)) {
                        event.isCancelled = true
                        Message.error(event.player, "This chest is for trusted residents only")
                    }

                    return
                }

                event.isCancelled = true
                Message.error(event.player, "You cannot use chests here!")
                return
            }

            // general interact permissions
            val plotPermission = plot?.let { getPlotPermission(TownPermissions.INTERACT, it, resident, town) }
            if (plotPermission == true || (plotPermission == null && hasTownPermissions(TownPermissions.INTERACT, town, resident))) {
                return
            }
            if (plotPermission == false) {
                event.isCancelled = true
                Message.error(event.player, "You cannot interact here!")
                return
            }

            // territory occupier permissions
            val occupier: Town? = territory.occupier
            if (occupier !== null && hasOccupierPermissions(TownPermissions.INTERACT, town, occupier, resident)) {
                return
            }

            // war permissions
            if (hasWarPermissions(resident, territory, territoryChunk!!)) {
                return
            }
        }

        event.isCancelled = true
        Message.error(event.player, "You cannot interact here!")
    }

    // Bridges Vanilla's barrel storage into Nodes' town permission system -- see
    // NodesVanillaStorageBridge. Vanilla has no notion of claims on its own, so without this,
    // any player could break or loot any barrel regardless of who owns the territory.
    fun hasStorageAccess(player: Player, position: Point, access: StorageAccess): Boolean {
        val blockPosition = BlockVec(position.blockX(), position.blockY(), position.blockZ())
        val territory = Territory.fromBlock(blockPosition.blockX, blockPosition.blockZ)
        val town = territory?.town

        if (access == StorageAccess.INTERACT && territory == null) return true
        if (town == null) return access == StorageAccess.INTERACT || hasWildernessPermissions(territory)

        val resident = Resident.fromPlayer(player) ?: return false
        if (resident.hasTownPermissionBypass()) return true

        val territoryChunk = TerritoryChunk.fromBlock(blockPosition.blockX, blockPosition.blockZ)
        if (territoryChunk != null) {
            val warzoneOccupierTown = warzoneOccupier(territory, territoryChunk)
            if (warzoneOccupierTown != null) {
                val permission = if (access == StorageAccess.INTERACT) TownPermissions.CHESTS else TownPermissions.DESTROY
                return hasTownPermissions(permission, warzoneOccupierTown, resident) &&
                    (!warzoneOccupierTown.protectedBlocks.contains(blockPosition) || resident.hasTownProtectedChestPermissions(warzoneOccupierTown))
            }
            if (hasWarPermissions(resident, territory, territoryChunk)) return true
        }

        val permission = if (access == StorageAccess.INTERACT) TownPermissions.CHESTS else TownPermissions.DESTROY
        val plotPermission = Plot.at(town, blockPosition.blockX, blockPosition.blockY, blockPosition.blockZ)
            ?.let { getPlotPermission(permission, it, resident, town) }
        val allowed = when (plotPermission) {
            true -> true
            false -> false
            null -> hasTownPermissions(permission, town, resident) ||
                (territory.occupier?.let { occupier -> hasOccupierPermissions(permission, town, occupier, resident) } == true)
        }
        if (!allowed) return false

        return !town.protectedBlocks.contains(blockPosition) || resident.hasTownProtectedChestPermissions(town)
    }

    fun init() {
        Nodes.highPriorityEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreak)
        Nodes.lowPriorityEventNode.addListener(PlayerBlockBreakEvent::class.java, this::onBlockBreakSuccess)
        Nodes.highPriorityEventNode.addListener(PlayerBlockPlaceEvent::class.java, this::onBlockPlace)
        Nodes.lowPriorityEventNode.addListener(PlayerBlockPlaceEvent::class.java, this::onBlockPlaceSuccess)
        Nodes.highPriorityEventNode.addListener(PlayerBlockInteractEvent::class.java, this::onBlockInteract)
    }
}

/**
 * Permissions for unclaimed territories or empty areas (no territories)
 */
private fun hasWildernessPermissions(territory: Territory?): Boolean {
    if (territory !== null && Nodes.config.canInteractInUnclaimed) {
        return true
    } else if (Nodes.config.canInteractInEmpty) {
        return true
    }

    return false
}

/**
 * Default permissions check for town:
 * perms: town permissions type
 * town: town
 * player: player interacting in town
 */
internal fun hasTownPermissions(perms: TownPermissions, town: Town, player: Resident): Boolean {
    if (town.permissions[perms].contains(PermissionsGroup.TOWN) && player.town === town) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.TRUSTED) && player.town === town && player.trusted) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.NATION) && town.nation !== null && player.nation === town.nation) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.ALLY) && town.nation !== null && player.town?.nation !== null && town.nation!!.allies.contains(player.town!!.nation)) {
        return true
    } else if (town.permissions[perms].contains(PermissionsGroup.OUTSIDER)) {
        return true
    }

    return false
}

/**
 * Returns a plot override, or null when the plot should inherit town permissions.
 */
private fun getPlotPermission(
    permission: TownPermissions,
    plot: net.aechronis.nodes.objects.Plot,
    resident: Resident,
    town: Town,
): Boolean? {
    plot.playerPermission(resident.uuid, permission)?.let { return it }

    val groupMatches = listOf(
        PermissionsGroup.TOWN to (resident.town === town),
        PermissionsGroup.TRUSTED to (resident.town === town && resident.trusted),
        PermissionsGroup.NATION to (town.nation !== null && resident.nation === town.nation),
        PermissionsGroup.ALLY to (
            town.nation !== null &&
                resident.town?.nation !== null &&
                town.nation!!.allies.contains(resident.town!!.nation)
            ),
        PermissionsGroup.OUTSIDER to true,
    )

    for ((group, matches) in groupMatches) {
        if (matches) {
            plot.groupPermission(group, permission)?.let { return it }
        }
    }

    return null
}

/**
 * Permissions check for a town's territory occupied by another town:
 * perms: town permissions type
 * town: town that owns the territory
 * occupier: town that is occupier of the territory
 * player: player interacting in the territory
 */
private fun hasOccupierPermissions(perms: TownPermissions, town: Town, occupier: Town, player: Resident): Boolean = if (Nodes.config.allowControlInOccupiedTownList.contains(town.uuid)) {
    hasTownPermissions(perms, occupier, player)
} else {
    false
}

/**
 * Warzones run outside global FlagWar, so their settled occupations must not
 * depend on FlagWar.enabled or the optional occupied-town control list.
 * Chunk occupation takes precedence until a core capture occupies the whole
 * territory.
 */
internal fun warzoneOccupier(territory: Territory, territoryChunk: TerritoryChunk): Town? = if (Warzone.isActive(territory)) territoryChunk.occupier ?: territory.occupier else null

// bypass permissions and allow all interaction in
// captured chunks/territories during wartime
private fun hasWarPermissions(resident: Resident, territory: Territory, territoryChunk: TerritoryChunk): Boolean {
    if (FlagWar.enabled) {
        val residentTown = resident.town
        val territoryTown = territory.town

        if (residentTown !== null) {
            // extended permissions for allies
            if (Nodes.config.warPermissions) {
                val residentNation = residentTown.nation

                val territoryOccupierNation = territory.occupier?.nation
                val territoryTownNation = territoryTown?.nation
                val chunkOccupierNation = territoryChunk.occupier?.nation
                val chunkAttackerNation = territoryChunk.attacker?.nation

                if (territory.occupier === residentTown ||
                    (residentNation !== null && territoryOccupierNation !== null && residentNation.allies.contains(territoryOccupierNation)) ||
                    territoryChunk.occupier === residentTown ||
                    territoryChunk.attacker === residentTown ||
                    (residentNation !== null && territoryTownNation !== null && residentNation.allies.contains(territoryTownNation)) ||
                    (residentNation !== null && chunkOccupierNation !== null && residentNation.allies.contains(chunkOccupierNation)) ||
                    (residentNation !== null && chunkAttackerNation !== null && residentNation.allies.contains(chunkAttackerNation))
                ) {
                    return true
                }

                if (residentNation !== null) {
                    if (residentNation === territoryChunk.occupier?.nation ||
                        residentNation === territory.occupier?.nation ||
                        residentNation === territoryChunk.attacker?.nation
                    ) {
                        return true
                    }
                }
            }
            // only let town/nation by default
            else {
                if (territory.occupier === residentTown || territoryChunk.occupier === residentTown || territoryChunk.attacker === residentTown) {
                    return true
                }

                val residentNation = residentTown.nation
                if (residentNation !== null) {
                    if (residentNation === territoryChunk.occupier?.nation ||
                        residentNation === territory.occupier?.nation ||
                        residentNation === territoryChunk.attacker?.nation
                    ) {
                        return true
                    }
                }
            }
        }
    }

    return false
}

// handle hidden ore generation during mining
private fun handleHiddenOre(player: Player, block: BlockVec) {
    // ignore hidden ore for silk touch tools.
    // Was `inMainHand?.get(...)?.level(...) != 0` -- for an empty hand, or any tool with no
    // ENCHANTMENTS component at all (i.e. any ordinary unenchanted tool), the whole safe-call
    // chain evaluates to null, and `null != 0` is true. That silently disabled hidden ore for
    // every unenchanted pickaxe, not just actual silk touch ones. Default the missing case to
    // level 0 explicitly so only a real Silk Touch enchantment suppresses the ore roll.
    val inMainHand: ItemStack? = player.itemInMainHand
    val silkTouchLevel = inMainHand?.get(DataComponents.ENCHANTMENTS)?.level(Enchantment.SILK_TOUCH) ?: 0
    if (silkTouchLevel != 0) {
        return
    }

    val blockX = block.blockX
    val blockZ = block.blockZ
    val blockY = block.blockY

    val territory = Territory.fromBlock(blockX, blockZ)

    if (territory !== null) {
        val random = ThreadLocalRandom.current()

        val territoryTown = territory.town
        val territoryNation = territoryTown?.nation

        val playerTown = Town.fromPlayer(player)
        val playerNation = playerTown?.nation
        val warzoneOccupierTown = TerritoryChunk.fromBlock(blockX, blockZ)?.let { warzoneOccupier(territory, it) }

        // conditions allowed for mining ore
        if ((Nodes.config.allowOreInWilderness && territoryTown === null) ||
            (territoryTown !== null && territoryTown === playerTown) ||
            (Nodes.config.allowOreInNationTowns && territoryNation !== null && territoryNation === playerNation) ||
            (Nodes.config.allowOreInCaptured && (territory.occupier === playerTown || warzoneOccupierTown === playerTown))
        ) {
            val rateMultiplier = Warzone.multiplierFor(territory) * MiningBoostManager.miningMultiplier()
            val itemDrops = territory.ores.sample(blockY).map { itemStack ->
                if (rateMultiplier == 1.0) {
                    itemStack
                } else {
                    itemStack.withAmount((itemStack.amount() * rateMultiplier).toInt().coerceAtLeast(1))
                }
            }

            // do tax event check
            val territoryOccupier = territory.occupier
            if (territoryOccupier !== null && random.nextDouble() <= Nodes.config.taxMineRate) {
                for (itemStack in itemDrops) {
                    Town.addToIncome(territoryOccupier, itemStack.material(), itemStack.amount())
                }
            }
            // else, drop items normally
            else {
                for (itemStack in itemDrops) {
                    val itemEntity = ItemEntity(itemStack)
                    itemEntity.setInstance(MinecraftServer.getInstanceManager().instances.first(), block)
                }
            }
        }
    }
}

/**
 * Return if a block is within a war attack flag's no build region
 */
private fun blockInWarFlagNoBuildRegion(block: BlockVec, attack: Attack): Boolean {
    val x = block.blockX
    val y = block.blockY
    val z = block.blockZ

    if (x < attack.noBuildXMin || x > attack.noBuildXMax) {
        return false
    }
    if (y < attack.noBuildYMin || y > attack.noBuildYMax) {
        return false
    }
    if (z < attack.noBuildZMin || z > attack.noBuildZMax) {
        return false
    }

    return true
}
