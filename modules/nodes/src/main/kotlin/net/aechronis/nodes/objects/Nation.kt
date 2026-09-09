/**
 * Nation
 * -----------------------------
 *
 */

package net.aechronis.nodes.objects

import com.google.gson.JsonPrimitive
import net.aechronis.nodes.Message
import net.aechronis.nodes.Nodes
import net.aechronis.nodes.constants.ErrorNationExists
import net.aechronis.nodes.constants.ErrorPlayerHasNation
import net.aechronis.nodes.constants.ErrorPlayerNotInTown
import net.aechronis.nodes.constants.ErrorTerritoryOwned
import net.aechronis.nodes.constants.ErrorTownHasNation
import net.aechronis.nodes.serdes.SaveState
import net.aechronis.nodes.utils.ChatColor
import net.aechronis.nodes.utils.Color
import net.minestom.server.command.CommandSender
import net.minestom.server.entity.Player
import java.util.Random
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

// random number generator
private val random = Random()

class Nation(
    val uuid: UUID,
    var name: String,
    // Main town in nation, used for nation leadership. Null only while the nation has no towns
    // yet (e.g. created ahead of a Discord-approved team picking their territory) -- invariant
    // maintained by addTown/removeTown: capital is non-null whenever towns is non-empty.
    var capital: Town?,
) {

    companion object {
        fun count(): Int = Nodes.nations.size

        fun fromName(name: String): Nation? = Nodes.nations[name]

        // Nodes.nations is keyed by name, not uuid -- nations are few enough
        // (dozens at most) that a linear scan here is fine.
        fun fromUuid(uuid: UUID): Nation? = Nodes.nations.values.find { it.uuid == uuid }

        private fun indexTownMembers(nation: Nation, town: Town) {
            val indexedPlayers = town.playersOnline.associateBy { it.uuid }
            town.residents.forEach { resident ->
                if (resident.town !== town) return@forEach
                resident.nation = nation
                nation.residents.add(resident)
                val player = resident.player() ?: indexedPlayers[resident.uuid]
                if (player != null) {
                    nation.playersOnline.removeAll { it.uuid == resident.uuid }
                    nation.playersOnline.add(player)
                }
                resident.needsUpdate()
            }
        }

        private fun unindexTownMembers(nation: Nation, town: Town) {
            val residents = town.residents.filter { it.town === town }
            val residentIds = residents.mapTo(hashSetOf()) { it.uuid }
            residents.forEach { resident ->
                if (resident.nation === nation) resident.nation = null
                nation.residents.remove(resident)
                resident.needsUpdate()
            }
            nation.playersOnline.removeAll { it.uuid in residentIds }
        }

        /**
         * Create a nation. `town` is optional -- a nation can exist before its first town
         * (e.g. approved via Discord ahead of the team picking territory); `addTown` promotes
         * the first town added to capital automatically.
         */
        fun create(name: String, town: Town? = null, leader: Resident? = null): Result<Nation> {
            if (town != null && town.nation != null) return Result.failure(ErrorTownHasNation)
            if (leader?.nation != null) return Result.failure(ErrorPlayerHasNation)
            if (town != null && leader != null && !town.residents.contains(leader)) return Result.failure(ErrorPlayerNotInTown)
            if (fromName(name) != null) return Result.failure(ErrorNationExists)

            val nation = Nation(UUID.randomUUID(), name, town)
            Nodes.nations[name] = nation
            if (town != null) {
                Town.initializeCapitalLives(town)
                nation.towns.add(town)
                town.nation = nation
                indexTownMembers(nation, town)
                town.needsUpdate()
            }
            nation.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(nation)
        }

        fun load(
            uuid: UUID,
            name: String,
            capitalName: String?,
            color: Color?,
            towns: ArrayList<String>,
            reservedTerritoryIds: ArrayList<Int> = arrayListOf(),
        ): Nation {
            val capital = if (capitalName != null) {
                Town.fromName(capitalName) ?: throw net.aechronis.nodes.constants.ErrorTownDoesNotExist
            } else {
                null
            }
            val nation = Nation(uuid, name, capital)
            if (capital != null) Town.initializeCapitalLives(capital)
            if (color != null) nation.color = color
            for (townName in towns) {
                val town = Town.fromName(townName) ?: continue
                nation.towns.add(town)
                town.nation = nation
                town.needsUpdate()
                indexTownMembers(nation, town)
            }
            reservedTerritoryIds.forEach { id ->
                val territory = Territory.fromId(TerritoryId(id)) ?: return@forEach
                // Defensive: a real town owner always wins and should already imply the
                // reservation was cleared at claim time (see Town.create/addTerritory) -- this
                // only guards stale/inconsistent save data.
                if (territory.town != null) return@forEach
                nation.reservedTerritories.add(territory.id)
                territory.reservedNation = nation
            }
            nation.needsUpdate()
            Nodes.nations[name] = nation
            return nation
        }

        fun destroy(nation: Nation) {
            nation.allies.forEach {
                it.allies.remove(nation)
                it.needsUpdate()
            }
            nation.enemies.forEach {
                it.enemies.remove(nation)
                it.needsUpdate()
            }
            nation.towns.forEach { town ->
                unindexTownMembers(nation, town)
                town.nation = null
                town.needsUpdate()
            }
            nation.reservedTerritories.forEach { id -> Territory.fromId(id)?.reservedNation = null }
            nation.reservedTerritories.clear()
            nation.towns.clear()
            nation.residents.clear()
            nation.playersOnline.clear()
            Nodes.nations.remove(nation.name)
            Nodes.needsSave = true
            Resident.renderMinimaps()
        }

        /**
         * Earmark a still-unclaimed territory for a nation ahead of any town existing on it.
         * Fails if a real town already owns the territory; re-reserving to a different nation
         * (or the same one) simply reassigns it.
         */
        fun reserveTerritory(nation: Nation, territory: Territory): Result<Territory> {
            if (territory.town != null) return Result.failure(ErrorTerritoryOwned)
            val previousNation = territory.reservedNation
            previousNation?.reservedTerritories?.remove(territory.id)
            previousNation?.needsUpdate()
            territory.reservedNation = nation
            nation.reservedTerritories.add(territory.id)
            nation.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(territory)
        }

        fun unreserveTerritory(territory: Territory): Result<Territory> {
            territory.reservedNation?.let {
                it.reservedTerritories.remove(territory.id)
                it.needsUpdate()
            }
            territory.reservedNation = null
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(territory)
        }

        fun addTown(nation: Nation, town: Town): Result<Town> {
            if (town.nation != null) return Result.failure(ErrorTownHasNation)
            nation.towns.add(town)
            town.nation = nation
            town.needsUpdate()
            indexTownMembers(nation, town)
            // First town of a nation created without one (see create()) becomes capital --
            // keeps the invariant that capital is non-null whenever towns is non-empty.
            if (nation.capital == null) {
                nation.capital = town
                Town.initializeCapitalLives(town)
            }
            nation.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(town)
        }

        fun removeTown(nation: Nation, town: Town): Result<Town> {
            if (town.nation !== nation) return Result.failure(net.aechronis.nodes.constants.ErrorNationDoesNotHaveTown)
            nation.towns.remove(town)
            unindexTownMembers(nation, town)
            town.nation = null
            if (nation.towns.isEmpty()) {
                destroy(nation)
            } else if (town === nation.capital) {
                val newCapital = nation.towns.first()
                nation.capital = newCapital
                Town.initializeCapitalLives(newCapital)
                newCapital.residents.forEach { it.player()?.let { player -> Message.print(player, "Your town is now the capital of ${nation.name}") } }
            }
            town.needsUpdate()
            nation.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(town)
        }

        fun setColor(nation: Nation, r: Int, g: Int, b: Int) {
            nation.color = Color(r, g, b)
            nation.needsUpdate()
            Nodes.needsSave = true
        }

        fun rename(nation: Nation, name: String): Boolean {
            if (Nodes.nations.containsKey(name)) return false
            Nodes.nations.remove(nation.name)
            nation.name = name
            Nodes.nations[name] = nation
            nation.needsUpdate()
            nation.towns.forEach { town ->
                town.needsUpdate()
                town.residents.forEach { it.needsUpdate() }
            }
            nation.enemies.forEach { it.needsUpdate() }
            nation.allies.forEach { it.needsUpdate() }
            Nodes.needsSave = true
            return true
        }

        fun setCapital(nation: Nation, town: Town) {
            if (town.nation !== nation || nation.capital === town) return
            nation.capital = town
            Town.initializeCapitalLives(town)
            nation.needsUpdate()
            Nodes.needsSave = true
        }

        fun addAlly(nation: Nation, other: Nation): Result<Boolean> {
            if ((nation.allies.contains(other) && other.allies.contains(nation)) || nation === other) return Result.failure(net.aechronis.nodes.constants.ErrorAlreadyAllies)
            if (nation.enemies.contains(other) || other.enemies.contains(nation)) return Result.failure(net.aechronis.nodes.constants.ErrorAlreadyEnemies)
            nation.allies.add(other)
            other.allies.add(nation)
            nation.towns.forEach { town ->
                town.residents.forEach { it.player()?.let { player -> Message.print(player, "Your nation is now allied with ${other.name}") } }
                town.needsUpdate()
            }
            other.towns.forEach { town ->
                town.residents.forEach { it.player()?.let { player -> Message.print(player, "Your nation is now allied with ${nation.name}") } }
                town.needsUpdate()
            }
            nation.needsUpdate()
            other.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(true)
        }

        fun removeAlly(nation: Nation, other: Nation): Result<Boolean> {
            if (!nation.allies.contains(other) || !other.allies.contains(nation)) return Result.failure(net.aechronis.nodes.constants.ErrorNotAllies)
            nation.allies.remove(other)
            other.allies.remove(nation)
            nation.towns.forEach { it.needsUpdate() }
            other.towns.forEach { it.needsUpdate() }
            nation.needsUpdate()
            other.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(true)
        }

        fun addEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
            if (nation === enemy) return Result.failure(net.aechronis.nodes.constants.ErrorWarSameNation)
            if (nation.allies.contains(enemy)) return Result.failure(net.aechronis.nodes.constants.ErrorWarAlly)
            if (nation.enemies.contains(enemy) && enemy.enemies.contains(nation)) return Result.failure(net.aechronis.nodes.constants.ErrorAlreadyEnemies)
            nation.enemies.add(enemy)
            enemy.enemies.add(nation)
            nation.towns.forEach { it.needsUpdate() }
            enemy.towns.forEach { it.needsUpdate() }
            nation.needsUpdate()
            enemy.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(true)
        }

        fun removeEnemy(nation: Nation, enemy: Nation): Result<Boolean> {
            nation.enemies.remove(enemy)
            enemy.enemies.remove(nation)
            nation.towns.forEach { it.needsUpdate() }
            enemy.towns.forEach { it.needsUpdate() }
            nation.needsUpdate()
            enemy.needsUpdate()
            Nodes.needsSave = true
            Resident.renderMinimaps()
            return Result.success(true)
        }

        fun loadDiplomacy(
            towns: ArrayList<Town>,
            townAllies: ArrayList<ArrayList<String>>,
            townEnemies: ArrayList<ArrayList<String>>,
            nations: ArrayList<Nation>,
            nationAllies: ArrayList<ArrayList<String>>,
            nationEnemies: ArrayList<ArrayList<String>>,
        ) {
            val allies = hashSetOf<NationPair>()
            val enemies = hashSetOf<NationPair>()
            towns.forEachIndexed { i, town ->
                val nation = town.nation ?: return@forEachIndexed
                if (town !== nation.capital) return@forEachIndexed
                townAllies[i].forEach { name -> Town.fromName(name)?.let { other -> if (other === other.nation?.capital) allies.add(NationPair(nation, other.nation!!)) } }
                townEnemies[i].forEach { name -> Town.fromName(name)?.let { other -> if (other === other.nation?.capital) enemies.add(NationPair(nation, other.nation!!)) } }
            }
            allies.forEach { pair ->
                pair.nation1.allies.add(pair.nation2)
                pair.nation2.allies.add(pair.nation1)
            }
            enemies.forEach { pair ->
                pair.nation1.enemies.add(pair.nation2)
                pair.nation2.enemies.add(pair.nation1)
            }
            nations.forEachIndexed { i, nation ->
                nationAllies[i].forEach { name -> fromName(name)?.let { nation.allies.add(it) } }
                nationEnemies[i].forEach { name -> fromName(name)?.let { nation.enemies.add(it) } }
            }
        }
    }

    // Same cross-thread-mutation shape as Town.kt's fields (see its comment) -- nation-level
    // commands (join/leave/ally/enemy) run per-acting-player, not per-nation, same risk.
    val playersOnline: MutableSet<Player> = ConcurrentHashMap.newKeySet()
    val towns: MutableSet<Town> = ConcurrentHashMap.newKeySet()
    val residents: MutableSet<Resident> = ConcurrentHashMap.newKeySet()
    val allies: MutableSet<Nation> = ConcurrentHashMap.newKeySet()
    val enemies: MutableSet<Nation> = ConcurrentHashMap.newKeySet()
    // Still-unclaimed territory earmarked for this nation ahead of any town existing on it.
    // Source of truth for Territory.reservedNation -- see reserveTerritory/unreserveTerritory.
    val reservedTerritories: MutableSet<TerritoryId> = ConcurrentHashMap.newKeySet()

    // color for displaying on map
    // assign random color by default
    var color: Color = Color(
        random.nextInt(256),
        random.nextInt(256),
        random.nextInt(256),
    )

    // json string and memoization flag
    private var saveState = NationSaveState(this)

    private var needsUpdate = false

    // prints out nation object info
    fun printInfo(sender: CommandSender) {
        val leader = this.capital?.leader?.name ?: "${ChatColor.GRAY}None"

        // read info out of towns:
        // - get town names
        // - get total residents count
        var residents = 0
        val towns = if (this.towns.isNotEmpty()) {
            val townNames: ArrayList<String> = arrayListOf()
            for (t in this.towns) {
                townNames.add(t.name)
                residents += t.residents.size
            }
            townNames.joinToString(", ")
        } else {
            "${ChatColor.GRAY}None"
        }

        val allies = if (this.allies.isNotEmpty()) {
            this.allies.joinToString(", ") { v -> v.name }
        } else {
            "${ChatColor.GRAY}None"
        }

        val enemies = if (this.enemies.isNotEmpty()) {
            this.enemies.joinToString(", ") { v -> v.name }
        } else {
            "${ChatColor.GRAY}None"
        }

        Message.print(sender, "${ChatColor.BOLD}Nation ${this.name}:")
        Message.print(sender, "- Capital${ChatColor.WHITE}: ${this.capital?.name ?: "${ChatColor.GRAY}None"}")
        Message.print(sender, "- Leader${ChatColor.WHITE}: $leader")
        Message.print(sender, "- Towns[${this.towns.size}]${ChatColor.WHITE}: $towns")
        Message.print(sender, "- Residents${ChatColor.WHITE}: $residents")
        Message.print(sender, "- Allies${ChatColor.WHITE}: $allies")
        Message.print(sender, "- Enemies${ChatColor.WHITE}: $enemies")
        if (this.reservedTerritories.isNotEmpty()) {
            val reserved = this.reservedTerritories.joinToString(", ") { it.toString() }
            Message.print(sender, "- Reserved territories${ChatColor.WHITE}: $reserved")
        }
    }

    /**
     * Immutable save snapshot, must be composed of immutable primitives.
     * Used to generate json string serialization.
     */
    class NationSaveState(n: Nation) : SaveState {
        val uuid = n.uuid
        val name = n.name
        val capital: String? = n.capital?.name
        val color = n.color
        val towns = n.towns.map { x -> x.name }
        val allies = n.allies.map { x -> x.name }
        val enemies = n.enemies.map { x -> x.name }
        val reservedTerritories = n.reservedTerritories.map { it.toInt() }

        override var jsonString: String? = null

        override fun createJsonString(): String {
            val towns = this.towns.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }
            val allies = this.allies.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }
            val enemies = this.enemies.joinToString(",", "[", "]") { JsonPrimitive(it).toString() }
            val reservedTerritories = this.reservedTerritories.joinToString(",", "[", "]")

            // Omit the key entirely rather than writing a JSON null -- Deserializer.kt reads
            // this with JsonObject.get("capital")?.asString, which relies on a missing key
            // returning a real Kotlin null; a JsonNull element would throw on .asString instead.
            val capitalJson = if (this.capital != null) "\"capital\":${JsonPrimitive(this.capital)}," else ""
            val jsonString = (
                "{" +
                    "\"uuid\":${JsonPrimitive(this.uuid.toString())}," +
                    capitalJson +
                    "\"color\":[${this.color.r},${this.color.g},${this.color.b}]," +
                    "\"towns\":$towns," +
                    "\"allies\":$allies," +
                    "\"enemies\":$enemies," +
                    "\"reservedTerritories\":$reservedTerritories" +
                    "}"
                )

            return jsonString
        }
    }

    // function to let client flag this object as dirty
    fun needsUpdate() {
        this.needsUpdate = true
    }

    // wrapper to return self as savestate
    // - returns memoized copy if needsUpdate false
    // - otherwise, parses self
    fun getSaveState(): NationSaveState {
        if (this.needsUpdate) {
            this.saveState = NationSaveState(this)
            this.needsUpdate = false
        }
        return this.saveState
    }
}
