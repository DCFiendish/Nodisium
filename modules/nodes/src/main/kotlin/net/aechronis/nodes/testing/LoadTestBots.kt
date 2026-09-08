package net.aechronis.nodes.testing

import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.Town
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.item.ItemStack
import net.minestom.server.item.Material

/**
 * War-load-test scaffolding, not for live play: auto-enlists any player named "Bot_<n>" (the
 * naming scheme rust-mc-bot uses) into one of the two test towns by suffix parity, and hands
 * them a fence to place as a war flag. Lets the bot swarm exercise FlagWar's beginAttack/beacon/
 * minimap-broadcast path without the bots needing to speak Minestom's chat-command or creative-
 * inventory protocol themselves.
 *
 * TownA/TownB and their home territories were previously assumed to already exist, but nothing
 * ever actually created them or claimed a territory for them -- every bot's enrollment silently
 * no-op'd (Town.fromName returned null) and every flag placement failed FlagWar.beginAttack's
 * very first check (target territory has no owning town), even though the bot connected fine and
 * sent a structurally valid place-block packet. There was no visible error anywhere in that
 * chain. ensureTestTownsExist() now creates both towns (idempotent, safe to call every boot) and
 * claims two real, adjacent, currently-unclaimed production territories as their homes.
 *
 * Territories 440/275 were picked because they're geometrically adjacent (share 7 real
 * chunk-border pairs -- confirmed against the live production world.json, not assumed) and
 * unclaimed pre-launch. rust-mc-bot's main.rs targets those same 7 border-chunk coordinates on
 * each side, one per attacking bot -- both sides must stay in sync. If either territory gets
 * claimed by a real town before launch, this and main.rs's coordinate tables both need to move to
 * a different unclaimed adjacent pair.
 *
 * Town ownership alone isn't enough for an attack to be legal: FlagWar.chunkIsEnemy requires both
 * towns to belong to nations with an explicit enemy relationship -- a bare town (nation == null,
 * the default from Town.create) can never be a valid attack target, regardless of territory
 * ownership. This was the last of three silent failure points found running this test for real:
 * missing town, missing territory ownership, and missing nation/enemy relationship all produced
 * the exact same symptom (bot connects fine, sends a structurally valid place-block packet, no
 * error anywhere) -- confirmed by watching towns.json's "captured" list stay empty across a real
 * local bot-swarm run against this code before this fix was added, then non-empty after.
 *
 * Remove all of this once real players replace load-test bots. Moved here from
 * server/src/main/kotlin/net/nodisium/server/LoadTestBots.kt when nodes became an independently
 * hot-swappable module (see ModuleManager) -- server no longer has a compile dependency on it.
 */
object LoadTestBots {
    private val botNameRegex = Regex("""^Bot_(\d+)$""")

    private const val TOWN_A_HOME_TERRITORY = 440
    private const val TOWN_B_HOME_TERRITORY = 275

    fun init() {
        ensureTestTownsExist()
        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent::class.java) { event ->
            val player = event.player
            val suffix = botNameRegex.matchEntire(player.username)?.groupValues?.get(1)?.toIntOrNull()
                ?: return@addListener

            // Resident.create() is idempotent -- calling it here guarantees a Resident exists
            // regardless of whether nodes' own PlayerLoadedEvent listener (which normally creates
            // it) has run yet. That listener only fires once the client sends Player Loaded, a
            // network round trip after PlayerSpawnEvent, so relying on it here raced and silently
            // skipped enrollment for every bot before this fix.
            Resident.create(player)
            val resident = Resident.fromPlayer(player)!!
            val homeTown = Town.fromName(if (suffix % 2 == 0) "TownA" else "TownB")
            if (homeTown != null && resident.town == null) {
                Town.addResident(homeTown, resident)
            }

            player.inventory.setItemStack(0, ItemStack.of(Material.OAK_FENCE))
        }
    }

    private fun ensureTestTownsExist() {
        val townA = createTownIfMissing("TownA", TOWN_A_HOME_TERRITORY) ?: return
        val townB = createTownIfMissing("TownB", TOWN_B_HOME_TERRITORY) ?: return
        ensureAtWar(townA, townB)
    }

    private fun createTownIfMissing(name: String, territoryId: Int): Town? {
        Town.fromName(name)?.let { return it }
        val territory = Territory.fromId(TerritoryId(territoryId))
        if (territory == null) {
            System.err.println("[LoadTestBots] Territory $territoryId for $name not found -- war-flag load test disabled")
            return null
        }
        if (territory.town != null) {
            System.err.println(
                "[LoadTestBots] Territory $territoryId is already owned by ${territory.town!!.name} -- " +
                    "pick a different unclaimed territory for $name",
            )
            return null
        }
        return Town.create(name, territory, null).getOrElse {
            System.err.println("[LoadTestBots] Failed to create $name: $it")
            null
        }
    }

    private fun ensureAtWar(townA: Town, townB: Town) {
        val nationA = townA.nation ?: Nation.create("NationA", townA).getOrElse {
            System.err.println("[LoadTestBots] Failed to create NationA: $it")
            return
        }
        val nationB = townB.nation ?: Nation.create("NationB", townB).getOrElse {
            System.err.println("[LoadTestBots] Failed to create NationB: $it")
            return
        }
        if (!nationA.enemies.contains(nationB)) {
            val result = Nation.addEnemy(nationA, nationB)
            if (result.isFailure) {
                System.err.println("[LoadTestBots] Failed to set NationA/NationB as enemies: ${result.exceptionOrNull()}")
            }
        }
    }
}
