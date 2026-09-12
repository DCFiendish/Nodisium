package net.aechronis.vanilla.listeners

import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.managers.Items
import net.minestom.server.component.DataComponents
import net.minestom.server.entity.GameMode
import net.minestom.server.event.player.PlayerBlockBreakEvent
import net.minestom.server.instance.block.Block
import net.minestom.server.item.ItemStack
import net.minestom.server.item.component.EnchantmentList
import net.minestom.server.item.enchant.Enchantment
import kotlin.random.Random

object PlayerBreakListener {
    fun onBlockBreak(event: PlayerBlockBreakEvent) {
        if (event.isCancelled) return
        val player = event.player
        // Reach-hack guard: reject breaks farther than vanilla's ~6-block interaction range
        // instead of trusting the client-reported block position.
        val blockCenter = event.blockPosition.asVec().add(0.5, 0.5, 0.5)
        if (player.position.distanceSquared(blockCenter) > 36.0) {
            event.isCancelled = true
            return
        }
        // MusicListener owns jukebox drops (block + record) when the music feature is enabled;
        // without this, both listeners spawn a duplicate jukebox item depending on listener order.
        if (Vanilla.config.musicEnabled && event.block.compare(Block.JUKEBOX)) return
        if (player.gameMode == GameMode.CREATIVE) return
        val instance = player.instance ?: return
        val material = event.block.registry()?.material() ?: return

        val config = Vanilla.config
        val heldItem = player.itemInMainHand
        val hasSilkTouch = heldItem.get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY).has(Enchantment.SILK_TOUCH)
        val silkTouchApplies = hasSilkTouch && material in config.blocksConfig.blocksSilkTouchable

        if (material in config.blocksConfig.blocksRequiringTool) {
            val heldMaterial = heldItem.material()
            val canMine = config.blocksConfig.toolMinableBlocks[heldMaterial]?.contains(material) == true
            if (!canMine) {
                event.isCancelled = true
                return
            }
        }

        val drops =
            if (silkTouchApplies) {
                listOf(ItemStack.of(material))
            } else {
                config.blocksConfig.blockDrops[material] ?: listOf(ItemStack.of(material))
            }
        val dropPos = event.blockPosition.add(0.5, 0.5, 0.5).asPos()
        for (stack in drops) {
            if (!stack.isAir && stack.amount() > 0) Items.spawn(instance, dropPos, stack)
        }

        val damagedTool = heldItem.damageWithUnbreaking(1)
        if (damagedTool !== heldItem) player.itemInMainHand = damagedTool
    }

    // Minestom's ItemStack.damage() is a mechanical no-op unless the item actually carries a
    // DAMAGE component, and it already breaks the item into ItemStack.AIR at max damage on its
    // own -- but it knows nothing about the Unbreakable flag or the Unbreaking enchantment's
    // damage-skip chance, both of which are real vanilla behavior a tool-durability feature needs.
    private fun ItemStack.damageWithUnbreaking(amount: Int): ItemStack {
        if (get(DataComponents.UNBREAKABLE) != null) return this
        val unbreakingLevel = get(DataComponents.ENCHANTMENTS, EnchantmentList.EMPTY).level(Enchantment.UNBREAKING)
        // Vanilla's chance to actually apply damage is 1/(level+1) -- level 0 (no enchant) always damages.
        if (unbreakingLevel > 0 && Random.nextInt(unbreakingLevel + 1) != 0) return this
        return damage(amount)
    }

    fun init() {
        Vanilla.eventNode.addListener(PlayerBlockBreakEvent::class.java, PlayerBreakListener::onBlockBreak)
    }
}
