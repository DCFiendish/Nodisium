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

    // The team's Mural weapon-planning board (see docs/HANDOFF.md) named these; stats below are
    // rough placeholders (the M1911's magazine size/heart-per-shot are the board's own numbers,
    // everything else is a guess) -- a real balance pass will replace all of it, so no effort went
    // into precise falloff/recoil/spread tuning here. "arasaka" (an SMG on the board) isn't
    // implemented -- not a real WWI-era weapon name, needs the team to clarify what it actually is.
    val m1911Round =
        Ammo(
            name = "m1911_round",
            ammoType = AmmoType.PISTOL,
            itemName = Component.text("M1911 Round"),
        )

    val m1911 =
        Gun(
            name = "m1911",
            itemName = Component.text("Colt M1911"),
            ammo = m1911Round,
            magazineSize = 7,
            damageFalloff = DamageFalloff(maxDamage = 2f, falloffStartRange = 20.0, falloffEndRange = 40.0, minDamage = 2f),
            automatic = false,
            cooldownMs = 400,
            reloadMs = 1500,
            recoilMin = 1f,
            recoilMax = 2f,
            spreadMin = 0.5f,
            spreadMax = 1.5f,
        )

    val mauserC96Round =
        Ammo(
            name = "mauser_c96_round",
            ammoType = AmmoType.PISTOL,
            itemName = Component.text("Mauser C96 Round"),
        )

    val mauserC96 =
        Gun(
            name = "mauser_c96",
            itemName = Component.text("Mauser C96"),
            ammo = mauserC96Round,
            magazineSize = 10,
            damageFalloff = DamageFalloff(maxDamage = 2f, falloffStartRange = 20.0, falloffEndRange = 40.0, minDamage = 2f),
            automatic = false,
            cooldownMs = 400,
            reloadMs = 1800,
            recoilMin = 1f,
            recoilMax = 2f,
            spreadMin = 0.5f,
            spreadMax = 1.5f,
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

    val tommyGunMagazine =
        Ammo(
            name = "tommy_gun_magazine",
            ammoType = AmmoType.MACHINE_GUN,
            itemName = Component.text("Tommy Gun Magazine"),
        )

    val tommyGun =
        Gun(
            name = "tommy_gun",
            itemName = Component.text("Tommy Gun"),
            ammo = tommyGunMagazine,
            magazineSize = 30,
            damageFalloff = DamageFalloff(maxDamage = 1.5f, falloffStartRange = 10.0, falloffEndRange = 30.0, minDamage = 1f),
            automatic = true,
            cooldownMs = 120,
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
    // state. No damage falloff -- a flat 12.7f (6 hearts through full leather armor: 7 armor, 0
    // toughness, vanilla's armor formula) at any range out to maxRange 512.0, the render-distance
    // ceiling this project might push to. DamageFalloff still requires start/end fields, so
    // maxDamage==minDamage with falloffStartRange==falloffEndRange==maxRange just means "constant
    // across the whole range" -- no separate flat-damage type needed. Real bolt-action stats
    // otherwise -- 5-round magazine, slow single-shot cooldown/reload, heavy recoil, tight spread.
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
            damageFalloff = DamageFalloff(maxDamage = 12.7f, falloffStartRange = 512.0, falloffEndRange = 512.0, minDamage = 12.7f),
            automatic = false,
            cooldownMs = 1050,
            reloadMs = 1820,
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
            m1911Round, m1911, mauserC96Round, mauserC96,
            mp18Magazine, mp18, tommyGunMagazine, tommyGun,
            usTrenchKnife, nahkampfmesser, couteauPoignard,
            kar98kRound, kar98k,
        )
    }
}
