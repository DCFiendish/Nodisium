package net.aechronis.nodes.testing

import net.aechronis.utils.Command
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.EquipmentSlot
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

/**
 * PvP-playtest base loadout: Kar98k + reserve ammo, bread, and a full leather armor set. Applied
 * on every join/respawn (PlayerSpawnEvent covers both) and on demand via /kit, so a dead player is
 * never stuck without a weapon. Clears the inventory first so a stray pickup from the last life
 * doesn't linger into the next one. Bot_<n> players are excluded -- they're LoadTestBots' war-flag
 * load-test bots, not pvp playtesters. Replaces the old DevLoadout full-arsenal dump.
 *
 * Registered on a low-priority (999, same convention as Nodes.lowPriorityEventNode) node so it
 * always runs after PlayerData's default-priority first-spawn restore -- otherwise whichever of
 * the two fires last on a given boot wins, and a player with an old saved .dat (e.g. from a
 * pre-PvpKit test session that saved a full weapon-arsenal inventory) would have that stale
 * inventory silently clobber the clean kit on join. Confirmed as a real bug, not theoretical --
 * an existing playtester's leftover DevLoadout-era .dat reappeared on join before this fix.
 *
 * Moved here from server/src/main/kotlin/net/nodisium/server/PvpKit.kt alongside [TestWeapons]
 * when nodes became an independently hot-swappable module (see ModuleManager) -- registers
 * directly on the global event handler (not Nodes.eventNode), so [stop] detaches it explicitly
 * rather than relying on Nodes.cleanup()'s eventNode teardown to catch it.
 */
object PvpKit {
    private val eventNode = EventNode.all("pvp-kit").setPriority(999)
    private val botNameRegex = Regex("""^Bot_\d+$""")

    private const val RESERVE_ROUNDS = 20
    private const val BREAD_COUNT = 16

    fun giveKit(player: Player) {
        val inventory = player.inventory
        inventory.clear()
        inventory.setItemStack(
            0,
            TestWeapons.kar98k.setAmmo(TestWeapons.kar98k.toItemStack(), TestWeapons.kar98k.magazineSize),
        )
        inventory.setItemStack(1, TestWeapons.kar98kRound.toItemStack().withAmount(RESERVE_ROUNDS))
        inventory.setItemStack(2, ItemStack.of(Material.BREAD).withAmount(BREAD_COUNT))

        player.setEquipment(EquipmentSlot.HELMET, ItemStack.of(Material.LEATHER_HELMET))
        player.setEquipment(EquipmentSlot.CHESTPLATE, ItemStack.of(Material.LEATHER_CHESTPLATE))
        player.setEquipment(EquipmentSlot.LEGGINGS, ItemStack.of(Material.LEATHER_LEGGINGS))
        player.setEquipment(EquipmentSlot.BOOTS, ItemStack.of(Material.LEATHER_BOOTS))
    }

    fun init() {
        eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
            val player = event.player
            if (botNameRegex.matches(player.username)) return@addListener
            giveKit(player)
        }
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
    }

    fun stop() {
        MinecraftServer.getGlobalEventHandler().removeChild(eventNode)
    }
}

class KitCommand : Command("kit", "vanilla.kit") {
    init {
        setDefaultExecutor { sender: Player, _ -> PvpKit.giveKit(sender) }
    }
}
