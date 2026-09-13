package net.aechronis.nodes

import com.google.gson.Gson
import com.sun.net.httpserver.HttpServer
import net.aechronis.nodes.war.FlagWar
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.net.InetAddress
import java.net.InetSocketAddress
import java.nio.charset.StandardCharsets
import java.util.concurrent.Executors

/**
 * Read-only stats snapshot served over loopback HTTP, so the Discord bot (and later the website,
 * once it has a public route to reach it -- see docs/DISCORD_BOT.md) has one shared data source
 * instead of each reimplementing town/nation lookups. Loopback-bound only -- do not point this at
 * a public port without revisiting that doc's open questions first.
 *
 * The snapshot is rebuilt on Minestom's own scheduler (main thread), same as every other Nodes
 * manager, so reading Town/Nation state here is never a cross-thread race. The HTTP server itself
 * runs on its own daemon thread and only ever serves whatever was last rebuilt -- request handling
 * never touches the tick thread.
 */
object StatsApi {
    private val gson = Gson()
    private var server: HttpServer? = null
    private var snapshotTask: Task? = null

    @Volatile
    private var cachedResponse: ByteArray = "{}".toByteArray(StandardCharsets.UTF_8)

    fun start(port: Int, snapshotPeriod: Long) {
        refreshSnapshot()
        snapshotTask = MinecraftServer.getSchedulerManager()
            .buildTask(::refreshSnapshot)
            .repeat(TaskSchedule.millis(snapshotPeriod))
            .schedule()

        val httpServer = HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), port), 0)
        httpServer.executor = Executors.newSingleThreadExecutor { r -> Thread(r, "nodes-stats-api").apply { isDaemon = true } }
        httpServer.createContext("/api/stats") { exchange ->
            try {
                val body = cachedResponse
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(200, body.size.toLong())
                exchange.responseBody.use { it.write(body) }
            } finally {
                exchange.close()
            }
        }
        httpServer.start()
        server = httpServer
        println("├─ Stats API listening on 127.0.0.1:$port")
    }

    fun stop() {
        server?.stop(0)
        server = null
        snapshotTask?.cancel()
        snapshotTask = null
    }

    // Runs on the scheduler (main) thread -- safe to read live Town/Nation state directly.
    internal fun refreshSnapshot() {
        val towns = Nodes.towns.values.map { town ->
            TownStats(
                name = town.name,
                nation = town.nation?.name,
                population = town.residents.size,
                territories = town.territories.size,
                lives = town.lives,
            )
        }
        val nations = Nodes.nations.values.map { nation ->
            NationStats(
                name = nation.name,
                capital = nation.capital?.name,
                towns = nation.towns.size,
                residents = nation.residents.size,
                allies = nation.allies.map { it.name },
                enemies = nation.enemies.map { it.name },
            )
        }
        val war = WarStats(
            enabled = FlagWar.enabled,
            canAnnexTerritories = FlagWar.canAnnexTerritories,
            canOnlyAttackBorders = FlagWar.canOnlyAttackBorders,
            destructionEnabled = FlagWar.destructionEnabled,
        )
        val snapshot = StatsSnapshot(System.currentTimeMillis(), war, towns, nations)
        cachedResponse = gson.toJson(snapshot).toByteArray(StandardCharsets.UTF_8)
    }

    internal data class TownStats(
        val name: String,
        val nation: String?,
        val population: Int,
        val territories: Int,
        val lives: Int,
    )

    internal data class NationStats(
        val name: String,
        val capital: String?,
        val towns: Int,
        val residents: Int,
        val allies: List<String>,
        val enemies: List<String>,
    )

    internal data class WarStats(
        val enabled: Boolean,
        val canAnnexTerritories: Boolean,
        val canOnlyAttackBorders: Boolean,
        val destructionEnabled: Boolean,
    )

    internal data class StatsSnapshot(
        val generatedAt: Long,
        val war: WarStats,
        val towns: List<TownStats>,
        val nations: List<NationStats>,
    )
}
