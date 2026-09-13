package net.aechronis.nodes.testing

import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.vanilla.managers.PvpPrep
import net.kyori.adventure.text.Component
import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance
import net.minestom.server.item.Material
import net.nodisium.combat.objects.Ammo
import net.nodisium.combat.objects.AmmoType
import net.nodisium.combat.objects.DamageFalloff
import net.nodisium.combat.objects.Gun
import net.nodisium.combat.objects.Item
import net.nodisium.combat.objects.Melee

/**
 * combat itself has no dependency on nodes (see modules/combat/build.gradle.kts) -- this predicate
 * lives here, in nodes (which already depends on both vanilla and combat -- see
 * modules/nodes/build.gradle.kts), and gets injected into Gun.usableZones. Rule: field guns are
 * only usable in wilderness (unclaimed land) or in a chunk currently under active siege -- not
 * inside a town's peacetime territory.
 *
 * Moved here from server/src/main/kotlin/net/nodisium/server/TestWeapons.kt when nodes/vanilla/
 * combat/worldedit became independently hot-swappable modules (see ModuleManager) -- server no
 * longer has a compile dependency on any of them, so glue code needing concrete types from more
 * than one module has to live inside whichever module is willing to depend on the others (nodes
 * already aggregates cross-module concerns this way, e.g. NodesVanillaStorageBridge).
 */
private val wildernessOrWarzoneOnly: (Instance, Pos) -> Boolean = { _, pos ->
    val territory = Territory.fromBlock(pos.blockX(), pos.blockZ())
    val chunk = TerritoryChunk.fromBlock(pos.blockX(), pos.blockZ())
    territory?.town == null || chunk?.attacker != null
}

/**
 * Pvp-playtest guard: a gun carrying this in usableZones simply won't fire inside a configured
 * PvpPrep box (see VanillaConfig.pvpPrepConfig) -- the actual no-damage rule is enforced
 * separately by PvpPrepListener's EntityDamageEvent cancel, this only stops wasted shots/ammo.
 */
private val outsidePvpPrepZone: (Instance, Pos) -> Boolean = { instance, pos -> !PvpPrep.isInside(instance, pos) }

// Placeholder musket-era test weapons for combat testing, backed by the from-scratch
// modules/combat (replacing the old net.aechronis.combat-based stub -- see docs/HANDOFF.md). No
// real resource-pack models exist yet (item models fall back to the base Material), and stats are
// unbalanced guesses -- just enough to exercise fire/reload/melee/ADS end-to-end locally. See
// docs/research-todo/10-asset-sourcing-and-licensing.md for the asset-sourcing plan.
object TestWeapons {
    val bayonet =
        Melee(
            name = "bayonet",
            itemName = Component.text("Bayonet"),
            damage = 6.0,
            attackSpeed = 2.0,
        )

    val shotgunShell =
        Ammo(
            name = "shotgun_shell",
            ammoType = AmmoType.SHOTGUN,
            itemName = Component.text("Shotgun Shell"),
        )

    // Demonstrates pelletCount (docs/HANDOFF.md's guns plan) -- a double-barrel-style shotgun,
    // wide spread and short falloff range, one shell per barrel.
    val shotgun =
        Gun(
            name = "shotgun",
            itemName = Component.text("Shotgun"),
            ammo = shotgunShell,
            magazineSize = 2,
            pelletCount = 8,
            damageFalloff =
                DamageFalloff(
                    maxDamage = 4f,
                    falloffStartRange = 5.0,
                    falloffEndRange = 15.0,
                    minDamage = 1f,
                ),
            automatic = false,
            cooldownMs = 1200,
            reloadMs = 2500,
            recoilMin = 3f,
            recoilMax = 5f,
            spreadMin = 3f,
            spreadMax = 8f,
        )

    val artilleryShell =
        Ammo(
            name = "artillery_shell",
            ammoType = AmmoType.ARTILLERY,
            itemName = Component.text("Artillery Shell"),
        )

    // Demonstrates zone-restricted weapons (docs/HANDOFF.md's guns/vehicles plan §6) -- a horse-drawn
    // field gun, only usable in wilderness or an actively-sieged chunk, wired against real nodes
    // territory data via wildernessOrWarzoneOnly above.
    val fieldGun =
        Gun(
            name = "field_gun",
            itemName = Component.text("Field Gun"),
            ammo = artilleryShell,
            magazineSize = 1,
            damageFalloff =
                DamageFalloff(
                    maxDamage = 40f,
                    falloffStartRange = 10.0,
                    falloffEndRange = 60.0,
                    minDamage = 15f,
                ),
            automatic = false,
            cooldownMs = 6000,
            reloadMs = 8000,
            recoilMin = 6f,
            recoilMax = 10f,
            spreadMin = 1f,
            spreadMax = 3f,
            usableZones = listOf(wildernessOrWarzoneOnly),
        )

    val mp18Magazine =
        Ammo(
            name = "mp18_magazine",
            ammoType = AmmoType.MACHINE_GUN,
            itemName = Component.text("MP18 Magazine"),
        )

    val mp18 =
        Gun(
            name = "mp18",
            itemName = Component.text("MP18"),
            ammo = mp18Magazine,
            magazineSize = 32,
            damageFalloff = DamageFalloff(maxDamage = 1.5f, falloffStartRange = 10.0, falloffEndRange = 30.0, minDamage = 1f),
            automatic = true,
            cooldownMs = 150,
            reloadMs = 2500,
            recoilMin = 0.5f,
            recoilMax = 1.5f,
            spreadMin = 1f,
            spreadMax = 4f,
        )

    // Lebel M1886 mesh reused for a different historical rifle (see resourcepack/CREDITS.md) --
    // exported through obj3, verified in Blockbench only, not yet checked against the real client.
    val gewehr98Round =
        Ammo(
            name = "gewehr_98_round",
            ammoType = AmmoType.RIFLE,
            itemName = Component.text("7.92x57mm Mauser Round"),
        )

    val gewehr98 =
        Gun(
            name = "gewehr_98",
            itemName = Component.text("Gewehr 98"),
            material = Material.IRON_INGOT,
            customModelData = "lebel_m1886_import",
            ammo = gewehr98Round,
            magazineSize = 6,
            maxRange = 512.0,
            damageFalloff = DamageFalloff(maxDamage = 12f, falloffStartRange = 512.0, falloffEndRange = 512.0, minDamage = 12f),
            automatic = false,
            cooldownMs = 1250,
            reloadMs = 5000,
            recoilMin = 13.5f,
            recoilMax = 21f,
            spreadMin = 4f,
            spreadMax = 9f,
            sprintSpreadMultiplier = 2.5f,
            usableZones = listOf(outsidePvpPrepZone),
        )

    // No model sourced/converted yet -- renders as the base Material until one is (see
    // docs/HANDOFF.md's asset-sourcing plan). Recoil/spread borrowed from kar98k's bolt-action
    // tuning since no gun-specific numbers were given.
    val leeEnfieldRound =
        Ammo(
            name = "lee_enfield_round",
            ammoType = AmmoType.RIFLE,
            itemName = Component.text(".303 British Round"),
        )

    val leeEnfield =
        Gun(
            name = "lee_enfield",
            itemName = Component.text("Lee-Enfield"),
            ammo = leeEnfieldRound,
            magazineSize = 7,
            maxRange = 512.0,
            damageFalloff = DamageFalloff(maxDamage = 14f, falloffStartRange = 512.0, falloffEndRange = 512.0, minDamage = 14f),
            automatic = false,
            cooldownMs = 1250,
            reloadMs = 5000,
            recoilMin = 13.5f,
            recoilMax = 21f,
            spreadMin = 4f,
            spreadMax = 9f,
            sprintSpreadMultiplier = 2.5f,
            usableZones = listOf(outsidePvpPrepZone),
        )

    val mosinNagantRound =
        Ammo(
            name = "mosin_nagant_round",
            ammoType = AmmoType.RIFLE,
            itemName = Component.text("7.62x54mmR Round"),
        )

    val mosinNagant =
        Gun(
            name = "mosin_nagant",
            itemName = Component.text("Mosin-Nagant"),
            ammo = mosinNagantRound,
            magazineSize = 5,
            maxRange = 512.0,
            damageFalloff = DamageFalloff(maxDamage = 10f, falloffStartRange = 512.0, falloffEndRange = 512.0, minDamage = 10f),
            automatic = false,
            cooldownMs = 1250,
            reloadMs = 4500,
            recoilMin = 13.5f,
            recoilMax = 21f,
            spreadMin = 4f,
            spreadMax = 9f,
            sprintSpreadMultiplier = 2.5f,
            usableZones = listOf(outsidePvpPrepZone),
        )

    // Beretta Model 57 mesh -- visually an M12-style SMG, not the historical pistol the name
    // suggests (see resourcepack/CREDITS.md) -- reused for the board's "Beretta M1918". Magazine
    // size picked from the 20-25 range given; cooldownMs is a placeholder, user flagged this needs
    // real playtesting to land the right automatic fire rate.
    val berettaM1918Magazine =
        Ammo(
            name = "beretta_m1918_magazine",
            ammoType = AmmoType.MACHINE_GUN,
            itemName = Component.text("Beretta M1918 Magazine"),
        )

    val berettaM1918 =
        Gun(
            name = "beretta_m1918",
            itemName = Component.text("Beretta M1918"),
            material = Material.IRON_INGOT,
            customModelData = "beretta_57",
            ammo = berettaM1918Magazine,
            magazineSize = 20,
            damageFalloff = DamageFalloff(maxDamage = 1f, falloffStartRange = 10.0, falloffEndRange = 30.0, minDamage = 0.5f),
            automatic = true,
            cooldownMs = 130,
            reloadMs = 2800,
            recoilMin = 0.5f,
            recoilMax = 1.5f,
            spreadMin = 1f,
            spreadMax = 4f,
        )

    // Placeholder bayonet models stand in for these -- see resourcepack/CREDITS.md, the pack has no
    // standalone trench-knife assets.
    val usTrenchKnife =
        Melee(
            name = "us_trench_knife",
            itemName = Component.text("US M1918 Mk1 Trench Knife"),
            itemModel = "nodisium:us_trench_knife",
            damage = 6.0,
            attackSpeed = 2.0,
        )

    val nahkampfmesser =
        Melee(
            name = "nahkampfmesser",
            itemName = Component.text("Nahkampfmesser"),
            itemModel = "nodisium:nahkampfmesser",
            damage = 6.0,
            attackSpeed = 2.0,
        )

    val couteauPoignard =
        Melee(
            name = "couteau_poignard",
            itemName = Component.text("Couteau Poignard Modele 1916"),
            itemModel = "nodisium:couteau_poignard",
            damage = 6.0,
            attackSpeed = 2.0,
        )

    val kar98kRound =
        Ammo(
            name = "kar98k_round",
            ammoType = AmmoType.RIFLE,
            itemName = Component.text("7.92x57mm Mauser Round"),
        )

    // First real-model gun: obj3-imported mesh (TastyTony Kar98K, CC-BY 4.0 -- see
    // resourcepack/CREDITS.md), carried on iron_ingot + custom_model_data since obj3 selects its
    // baked model that way, not via item_model like the older item-model-based guns above. No
    // itemModelEmpty/Reloading/Aiming variant exists yet for this pipeline (see docs/HANDOFF.md --
    // GUI icon and per-state pose swap are still open), so it renders as one fixed model in every
    // state. No damage falloff -- a flat 8f (4 hearts) at any range out to maxRange 512.0, the
    // render-distance ceiling this project might push to. DamageFalloff still requires start/end
    // fields, so maxDamage==minDamage with falloffStartRange==falloffEndRange==maxRange just means
    // "constant across the whole range" -- no separate flat-damage type needed. Real bolt-action
    // stats otherwise -- 5-round magazine, slow single-shot cooldown/reload, heavy recoil, tight
    // spread. cooldownMs/reloadMs are the midpoint of the 1-1.5s / 3.5-4s ranges given (no range
    // support on these fields).
    val kar98k =
        Gun(
            name = "kar98k",
            itemName = Component.text("Kar98k"),
            material = Material.IRON_INGOT,
            customModelData = "kar98k_lowpoly",
            customModelDataAiming = "kar98k_lowpoly_aiming",
            ammo = kar98kRound,
            magazineSize = 5,
            maxRange = 512.0,
            damageFalloff = DamageFalloff(maxDamage = 8f, falloffStartRange = 512.0, falloffEndRange = 512.0, minDamage = 8f),
            automatic = false,
            cooldownMs = 1250,
            reloadMs = 3750,
            recoilMin = 13.5f,
            recoilMax = 21f,
            spreadMin = 4f,
            spreadMax = 9f,
            // Bolt-action: pinpoint standing/crouched (spreadSuppressed in Gun.fire), basically
            // unusable walking, and unusable even point-blank sprinting -- stop to actually land a hit.
            sprintSpreadMultiplier = 2.5f,
            usableZones = listOf(outsidePvpPrepZone),
        )

    fun register() {
        Item.registerItems(
            bayonet, artilleryShell, fieldGun, shotgunShell, shotgun,
            mp18Magazine, mp18,
            usTrenchKnife, nahkampfmesser, couteauPoignard,
            kar98kRound, kar98k,
            gewehr98Round, gewehr98,
            leeEnfieldRound, leeEnfield,
            mosinNagantRound, mosinNagant,
            berettaM1918Magazine, berettaM1918,
        )
    }
}
