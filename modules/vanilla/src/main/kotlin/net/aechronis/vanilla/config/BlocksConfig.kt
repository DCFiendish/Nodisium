package net.aechronis.vanilla.config

import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

private val PickaxeBlocks =
    listOf(
        Material.STONE,
        Material.COBBLESTONE,
        Material.DEEPSLATE,
        Material.COBBLED_DEEPSLATE,
        Material.GRANITE,
        Material.DIORITE,
        Material.ANDESITE,
        Material.POLISHED_GRANITE,
        Material.POLISHED_DIORITE,
        Material.POLISHED_ANDESITE,
        Material.SANDSTONE,
        Material.RED_SANDSTONE,
        Material.COAL_ORE,
        Material.DEEPSLATE_COAL_ORE,
        Material.NETHER_QUARTZ_ORE,
        Material.NETHERRACK,
        Material.BASALT,
        Material.BLACKSTONE,
        Material.NETHER_BRICKS,
        Material.STONE_BRICKS,
        Material.END_STONE,
        Material.PRISMARINE,
        Material.COAL_BLOCK,
        Material.QUARTZ_BLOCK,
        Material.IRON_ORE,
        Material.DEEPSLATE_IRON_ORE,
        Material.COPPER_ORE,
        Material.DEEPSLATE_COPPER_ORE,
        Material.LAPIS_ORE,
        Material.DEEPSLATE_LAPIS_ORE,
        Material.LAPIS_BLOCK,
        Material.RAW_IRON_BLOCK,
        Material.RAW_COPPER_BLOCK,
        Material.DIAMOND_ORE,
        Material.DEEPSLATE_DIAMOND_ORE,
        Material.EMERALD_ORE,
        Material.DEEPSLATE_EMERALD_ORE,
        Material.GOLD_ORE,
        Material.DEEPSLATE_GOLD_ORE,
        Material.NETHER_GOLD_ORE,
        Material.REDSTONE_ORE,
        Material.DEEPSLATE_REDSTONE_ORE,
        Material.RAW_GOLD_BLOCK,
        Material.GOLD_BLOCK,
        Material.REDSTONE_BLOCK,
        Material.OBSIDIAN,
        Material.CRYING_OBSIDIAN,
        Material.ANCIENT_DEBRIS,
        Material.RESPAWN_ANCHOR,
        Material.NETHERITE_BLOCK,
        Material.DIAMOND_BLOCK,
        Material.EMERALD_BLOCK,
        Material.IRON_BLOCK,
    )

private val StonePickaxeBlocks =
    PickaxeBlocks.filterNot {
        it in
            setOf(
                Material.DIAMOND_ORE,
                Material.DEEPSLATE_DIAMOND_ORE,
                Material.GOLD_ORE,
                Material.DEEPSLATE_GOLD_ORE,
                Material.REDSTONE_ORE,
                Material.DEEPSLATE_REDSTONE_ORE,
                Material.EMERALD_ORE,
                Material.DEEPSLATE_EMERALD_ORE,
                Material.OBSIDIAN,
                Material.CRYING_OBSIDIAN,
                Material.ANCIENT_DEBRIS,
                Material.RESPAWN_ANCHOR,
                Material.NETHERITE_BLOCK,
                Material.DIAMOND_BLOCK,
                Material.EMERALD_BLOCK,
                Material.GOLD_BLOCK,
            )
    }

private val IronPickaxeBlocks =
    PickaxeBlocks.filterNot {
        it in
            setOf(
                Material.OBSIDIAN,
                Material.CRYING_OBSIDIAN,
                Material.ANCIENT_DEBRIS,
                Material.RESPAWN_ANCHOR,
                Material.NETHERITE_BLOCK,
            )
    }

data class BlocksConfig(
    val blockDrops: Map<Material, List<ItemStack>> =
        mapOf(
            Material.STONE to listOf(ItemStack.of(Material.COBBLESTONE)),
            Material.DEEPSLATE to listOf(ItemStack.of(Material.COBBLED_DEEPSLATE)),
            Material.GRASS_BLOCK to listOf(ItemStack.of(Material.DIRT)),
            Material.GLOWSTONE to listOf(ItemStack.of(Material.GLOWSTONE_DUST, 3)),
            Material.SEA_LANTERN to listOf(ItemStack.of(Material.PRISMARINE_CRYSTALS, 3)),
            Material.AMETHYST_CLUSTER to listOf(ItemStack.of(Material.AMETHYST_SHARD, 4)),
            Material.CLAY to listOf(ItemStack.of(Material.CLAY_BALL, 4)),
            Material.MELON to listOf(ItemStack.of(Material.MELON_SLICE, 5)),
            Material.SNOW to listOf(ItemStack.of(Material.SNOWBALL, 1)),
            Material.GLASS to listOf(),
            Material.GLASS_PANE to listOf(),
            Material.ICE to listOf(),
            // Ores: without these, breaking an ore block (not silk-touched, handled separately
            // above) fell through to the "drop the block itself" default -- so mining dropped a
            // placeable ore block instead of the actual resource.
            Material.COAL_ORE to listOf(ItemStack.of(Material.COAL)),
            Material.DEEPSLATE_COAL_ORE to listOf(ItemStack.of(Material.COAL)),
            Material.IRON_ORE to listOf(ItemStack.of(Material.RAW_IRON)),
            Material.DEEPSLATE_IRON_ORE to listOf(ItemStack.of(Material.RAW_IRON)),
            Material.COPPER_ORE to listOf(ItemStack.of(Material.RAW_COPPER)),
            Material.DEEPSLATE_COPPER_ORE to listOf(ItemStack.of(Material.RAW_COPPER)),
            Material.GOLD_ORE to listOf(ItemStack.of(Material.RAW_GOLD)),
            Material.DEEPSLATE_GOLD_ORE to listOf(ItemStack.of(Material.RAW_GOLD)),
            Material.NETHER_GOLD_ORE to listOf(ItemStack.of(Material.GOLD_NUGGET, 3)),
            Material.LAPIS_ORE to listOf(ItemStack.of(Material.LAPIS_LAZULI, 6)),
            Material.DEEPSLATE_LAPIS_ORE to listOf(ItemStack.of(Material.LAPIS_LAZULI, 6)),
            Material.REDSTONE_ORE to listOf(ItemStack.of(Material.REDSTONE, 4)),
            Material.DEEPSLATE_REDSTONE_ORE to listOf(ItemStack.of(Material.REDSTONE, 4)),
            Material.DIAMOND_ORE to listOf(ItemStack.of(Material.DIAMOND)),
            Material.DEEPSLATE_DIAMOND_ORE to listOf(ItemStack.of(Material.DIAMOND)),
            Material.EMERALD_ORE to listOf(ItemStack.of(Material.EMERALD)),
            Material.DEEPSLATE_EMERALD_ORE to listOf(ItemStack.of(Material.EMERALD)),
            Material.NETHER_QUARTZ_ORE to listOf(ItemStack.of(Material.QUARTZ)),
        ),
    val blocksRequiringTool: Set<Material> = PickaxeBlocks.toSet(),
    val blocksSilkTouchable: Set<Material> = PickaxeBlocks.toSet(),
    val toolMinableBlocks: Map<Material, List<Material>> =
        mapOf(
            Material.WOODEN_PICKAXE to StonePickaxeBlocks,
            Material.GOLDEN_PICKAXE to StonePickaxeBlocks,
            Material.STONE_PICKAXE to StonePickaxeBlocks,
            Material.IRON_PICKAXE to IronPickaxeBlocks,
            Material.DIAMOND_PICKAXE to PickaxeBlocks,
            Material.NETHERITE_PICKAXE to PickaxeBlocks,
        ),
)
