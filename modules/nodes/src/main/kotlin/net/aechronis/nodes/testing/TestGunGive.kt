package net.aechronis.nodes.testing

import net.aechronis.utils.Command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.command.builder.arguments.ArgumentType
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.Player
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material
import net.minestom.server.item.component.CustomModelData

/**
 * Local playtesting only: `/testgun <name>` hands the sender a raw obj³ export visual-check item
 * (an iron_ingot carrying the custom_model_data string each model's resourcepack selector matches
 * on) for any of the imported obj³ weapons, so they can be inspected in-hand without waiting for
 * DevLoadout's on-spawn slots. Same item shape as the TEMP stacks in DevLoadout.kt -- keep the
 * name list there and here in sync if a model is added/removed/renamed.
 *
 * "kar98k" is special-cased below to hand the real, fully-loaded [TestWeapons.kar98k] Gun instead
 * of the bare placeholder stack -- the only one of these eight wired into real combat stats so far.
 *
 * Moved here from server/src/main/kotlin/net/nodisium/server/TestGunGive.kt alongside
 * [TestWeapons] when nodes became an independently hot-swappable module (see ModuleManager) --
 * server no longer has a compile dependency on nodes or combat.
 */
class TestGunGive : Command("testgun", "nodisium.testgun") {
    companion object {
        private val WEAPONS =
            linkedMapOf(
                "kar98k" to ("kar98k_lowpoly" to "KAR98K"),
                "lebel_m1886" to ("lebel_m1886_import" to "LEBEL M1886"),
                "federov_avtomat" to ("federov_avtomat" to "FEDOROV AVTOMAT"),
                "mossberg_patriot" to ("mossberg_patriot" to "MOSSBERG PATRIOT"),
                "sks" to ("sks" to "SKS"),
                "springfield_1873" to ("springfield_1873" to "SPRINGFIELD 1873"),
                "vpo102" to ("vpo102" to "VPO-102"),
                "beretta_57" to ("beretta_57" to "BERETTA 57"),
            )
    }

    init {
        val nameArg = ArgumentType.Word("name").from(*WEAPONS.keys.toTypedArray())

        setDefaultExecutor { player: Player, _ ->
            player.sendMessage(Component.text("Usage: /testgun <name>", NamedTextColor.LIGHT_PURPLE))
            player.sendMessage(
                Component.text("Available: ${WEAPONS.keys.joinToString(", ")}", NamedTextColor.LIGHT_PURPLE),
            )
        }

        addSyntax({ player: Player, context ->
            val name = context[nameArg]
            if (name == "kar98k") {
                val gun = TestWeapons.kar98k
                val stack = gun.setAmmo(gun.toItemStack(), gun.magazineSize)
                val given =
                    player.inventory.addItemStack(stack) &&
                        player.inventory.addItemStack(TestWeapons.kar98kRound.toItemStack().withAmount(20))
                if (!given) {
                    player.sendMessage(Component.text("Your inventory is full", NamedTextColor.RED))
                } else {
                    player.sendMessage(Component.text("Gave Kar98k + ammo", NamedTextColor.LIGHT_PURPLE))
                }
                return@addSyntax
            }

            val (customModelData, label) = WEAPONS.getValue(name)
            val stack =
                ItemStack.of(Material.IRON_INGOT)
                    .with(DataComponents.CUSTOM_MODEL_DATA, CustomModelData(listOf(), listOf(), listOf(customModelData), listOf()))
                    .with(DataComponents.ITEM_NAME, Component.text("$label TEST", NamedTextColor.RED))
            if (!player.inventory.addItemStack(stack)) {
                player.sendMessage(Component.text("Your inventory is full", NamedTextColor.RED))
            } else {
                player.sendMessage(Component.text("Gave $label test item", NamedTextColor.LIGHT_PURPLE))
            }
        }, nameArg)
    }
}
