package net.aechronis.vanilla

import net.aechronis.vanilla.config.BlocksConfig
import net.aechronis.vanilla.config.FoodConfig
import net.aechronis.vanilla.config.KothsConfig
import net.aechronis.vanilla.config.MusicConfig
import net.aechronis.vanilla.config.PvpPrepConfig
import net.aechronis.vanilla.config.RecipesConfig
import net.aechronis.vanilla.config.WarpsConfig

data class VanillaConfig(
    // Feature toggles
    val commandsEnabled: Boolean = true,
    val playerDataEnabled: Boolean = true,
    val storageEnabled: Boolean = true,
    val whitelistEnabled: Boolean = true,
    val recipesEnabled: Boolean = true,
    val cropsEnabled: Boolean = true,
    val saplingsEnabled: Boolean = true,
    val elevatorEnabled: Boolean = true,
    val mannequinEnabled: Boolean = true,
    val treeFellerEnabled: Boolean = true,
    val foodEnabled: Boolean = true,
    val itemsEnabled: Boolean = true,
    val bundlesEnabled: Boolean = true,
    val blockDropsEnabled: Boolean = true,
    val fallDamageEnabled: Boolean = true,
    val fireDamageEnabled: Boolean = true,
    val drowningEnabled: Boolean = true,
    val serverLinksEnabled: Boolean = true,
    val combatEnabled: Boolean = true,
    val musicEnabled: Boolean = true,
    val spawnEnabled: Boolean = true,
    val kothEnabled: Boolean = true,
    val warpEnabled: Boolean = true,
    val pvpPrepEnabled: Boolean = true,
    val movementAntiCheatEnabled: Boolean = true,
    val blockPlacementCooldownEnabled: Boolean = true,
    val filterEnabled: Boolean = true,
    val signsEnabled: Boolean = true,
    val shelvesEnabled: Boolean = true,
    val itemFramesEnabled: Boolean = true,
    val vanishEnabled: Boolean = true,
    val voteEnabled: Boolean = true,
    // Paths
    val path: String = "vanilla",
    val playerDataPath: String = "playerdata",
    val storagePath: String = "storage",
    val whitelistPath: String = "whitelist.json",
    val spawnPath: String = "spawn",
    val warpsPath: String = "warps.json",
    val votePath: String = "vote.txt",
    // Blocks
    val blocksConfig: BlocksConfig = BlocksConfig(),
    // Food
    val foodConfig: FoodConfig = FoodConfig(),
    // Music
    val musicConfig: MusicConfig = MusicConfig(),
    // Recipes
    val recipesConfig: RecipesConfig = RecipesConfig(),
    // koths
    val kothsConfig: KothsConfig = KothsConfig(),
    // warps
    val warpsConfig: WarpsConfig = WarpsConfig(),
    // pvp prep zones (no-damage/no-break boxes around warp landing spots)
    val pvpPrepConfig: PvpPrepConfig = PvpPrepConfig(),
    // Crops
    val cropGrowthCheckSeconds: Long = 20L,
    val wheatMsPerStage: Long = 72_000L,
    val carrotMsPerStage: Long = 72_000L,
    val potatoMsPerStage: Long = 72_000L,
    // Saplings
    val saplingGrowthMs: Long = 600_000L,
    val saplingGrowthCheckSeconds: Long = 20L,
    val saplingBoneMealAmount: Int = 3,
    // Mannequins
    val mannequinDespawnTime: Int = 60,
    // Items (drop & pickup)
    val itemPickupDelayMs: Long = 500L,
    val dropPickupDelayMs: Long = 2_000L,
    val dropDespawnSeconds: Long = 300L,
    val dropThrowVelocity: Double = 6.0,
    val dropThrowUpwardVelocity: Double = 2.0,
    val dropSpawnHeight: Double = 1.3,
    val dropMagnetRadius: Double = 4.0,
    val dropMagnetSpeed: Double = 3.0,
    // Bundles
    val bundleMaxItemStacks: Int = 16,
    // Elevator
    val elevatorMaxSearch: Int = 120,
    // TreeFeller
    val treeFellerMaxSize: Int = 120,
    val treeFellerMaxHeight: Int = 26,
    val treeFellerBreakLeaves: Boolean = true,
    val treeFellerLeafMaxDistance: Int = 6,
    val treeFellerMaxLeaves: Int = 600,
    val treeFellerBlocksPerTick: Int = 8,
    val treeFellerTickInterval: Int = 1,
    val treeFellerSaplingChance: Double = 0.05,
    val treeFellerStickChance: Double = 0.02,
    // Server Links
    val serverLinks: List<Pair<String, String>> =
        listOf(
            "Map" to "https://map.aechronis.net",
            "Website" to "https://aechronis.net",
            "Discord" to "https://discord.aechronis.net",
            "Store" to "https://shop.aechronis.net",
        ),
    // Combat
    val combatDurationSeconds: Long = 10L,
    val combatTickSeconds: Long = 1L,
    // EnviromentalDmg
    val maxAirTicks: Int = 300,
    val fireTicks: Int = 160,
    val fireContactTicks: Int = 10,
    val fireDmg: Float = 1f,
    val drowningDmg: Float = 2f,
    // Movement anti-cheat (baseline speed/fly-hack detection -- see
    // docs/research-todo/03-anti-cheat-and-security.md). Deliberately generous per-move-event
    // distance caps, not tick-precise physics, to keep false-positive risk low.
    val maxHorizontalDistancePerMove: Double = 1.5,
    val maxHorizontalDistancePerMoveSprintOrSpeed: Double = 2.2,
    val maxUnsupportedAscentBlocks: Double = 4.0,
    val maxUnsupportedAscentBlocksJumpBoost: Double = 6.0,
) {
    // Was a bare data class with no sanity checks at all -- a negative heal amount or a
    // zero/negative growth timer used to silently do whatever the downstream arithmetic implied
    // (division by zero, instant/never growth, etc.) instead of failing fast at boot where the
    // typo is actually easy to spot. Cheap insurance, not exhaustive -- only guards the fields
    // where a bad value would misbehave at runtime rather than just look wrong.
    init {
        require(cropGrowthCheckSeconds > 0) { "cropGrowthCheckSeconds must be positive" }
        require(wheatMsPerStage > 0) { "wheatMsPerStage must be positive" }
        require(carrotMsPerStage > 0) { "carrotMsPerStage must be positive" }
        require(potatoMsPerStage > 0) { "potatoMsPerStage must be positive" }

        require(saplingGrowthMs > 0) { "saplingGrowthMs must be positive" }
        require(saplingGrowthCheckSeconds > 0) { "saplingGrowthCheckSeconds must be positive" }
        require(saplingBoneMealAmount > 0) { "saplingBoneMealAmount must be positive" }

        require(mannequinDespawnTime > 0) { "mannequinDespawnTime must be positive" }

        require(dropPickupDelayMs >= 0) { "dropPickupDelayMs must not be negative" }
        require(dropDespawnSeconds > 0) { "dropDespawnSeconds must be positive" }
        require(dropThrowVelocity >= 0) { "dropThrowVelocity must not be negative" }
        require(dropThrowUpwardVelocity >= 0) { "dropThrowUpwardVelocity must not be negative" }
        require(dropSpawnHeight >= 0) { "dropSpawnHeight must not be negative" }
        require(dropMagnetRadius >= 0) { "dropMagnetRadius must not be negative" }
        require(dropMagnetSpeed >= 0) { "dropMagnetSpeed must not be negative" }

        require(bundleMaxItemStacks > 0) { "bundleMaxItemStacks must be positive" }

        require(elevatorMaxSearch > 0) { "elevatorMaxSearch must be positive" }

        require(treeFellerMaxSize > 0) { "treeFellerMaxSize must be positive" }
        require(treeFellerMaxHeight > 0) { "treeFellerMaxHeight must be positive" }
        require(treeFellerLeafMaxDistance >= 0) { "treeFellerLeafMaxDistance must not be negative" }
        require(treeFellerMaxLeaves > 0) { "treeFellerMaxLeaves must be positive" }
        require(treeFellerBlocksPerTick > 0) { "treeFellerBlocksPerTick must be positive" }
        require(treeFellerTickInterval > 0) { "treeFellerTickInterval must be positive" }
        require(treeFellerSaplingChance in 0.0..1.0) { "treeFellerSaplingChance must be between 0 and 1" }
        require(treeFellerStickChance in 0.0..1.0) { "treeFellerStickChance must be between 0 and 1" }

        require(combatDurationSeconds > 0) { "combatDurationSeconds must be positive" }
        require(combatTickSeconds > 0) { "combatTickSeconds must be positive" }

        require(maxAirTicks > 0) { "maxAirTicks must be positive" }
        require(fireTicks > 0) { "fireTicks must be positive" }
        require(fireContactTicks > 0) { "fireContactTicks must be positive" }
        require(fireDmg >= 0f) { "fireDmg must not be negative" }
        require(drowningDmg >= 0f) { "drowningDmg must not be negative" }

        require(maxHorizontalDistancePerMove > 0.0) { "maxHorizontalDistancePerMove must be positive" }
        require(maxHorizontalDistancePerMoveSprintOrSpeed >= maxHorizontalDistancePerMove) {
            "maxHorizontalDistancePerMoveSprintOrSpeed must be at least maxHorizontalDistancePerMove"
        }
        require(maxUnsupportedAscentBlocks > 0.0) { "maxUnsupportedAscentBlocks must be positive" }
        require(maxUnsupportedAscentBlocksJumpBoost >= maxUnsupportedAscentBlocks) {
            "maxUnsupportedAscentBlocksJumpBoost must be at least maxUnsupportedAscentBlocks"
        }
    }
}
