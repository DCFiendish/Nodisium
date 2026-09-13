/**
 * Daily player/nation stats snapshot -- see docs/STATS.md.
 *
 * Runs once a day at midnight America/New_York (deliberately the server's lowest-population
 * window, chosen to keep this off the critical path during 200-player Nodes-war peaks) and
 * writes one JSON file the website reads, which needs a full walk of every resident/nation and
 * is intentionally not something the consumer polls live.
 */

package net.aechronis.nodes.tasks

import com.google.gson.Gson
import net.aechronis.nodes.Nodes
import net.minestom.server.MinecraftServer
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.concurrent.Executors

object DailyStatsSnapshot {
    private val ZONE: ZoneId = ZoneId.of("America/New_York")
    private val gson = Gson()

    // Same reasoning as PlayerData.kt's autosaveExecutor in the vanilla module: git/disk I/O
    // here must never run on Minestom's shared scheduler pool, so a slow push doesn't stall
    // any other manager's tick.
    private val ioExecutor =
        Executors.newSingleThreadExecutor { r -> Thread(r, "nodes-daily-stats").apply { isDaemon = true } }

    private var task: Task? = null

    fun start(outputPath: Path, gitRepoPath: Path?) {
        if (task != null) return
        scheduleNext(outputPath, gitRepoPath)
    }

    fun stop() {
        task?.cancel()
        task = null
    }

    private fun scheduleNext(outputPath: Path, gitRepoPath: Path?) {
        task = MinecraftServer.getSchedulerManager()
            .buildTask {
                ioExecutor.execute { runSnapshot(outputPath, gitRepoPath) }
                scheduleNext(outputPath, gitRepoPath)
            }
            .delay(TaskSchedule.millis(millisUntilNextMidnight()))
            .schedule()
    }

    private fun millisUntilNextMidnight(): Long = millisUntilNextMidnight(ZonedDateTime.now(ZONE))

    // Pulled out of the zero-arg version so it's testable without waiting for a real clock --
    // same pattern as Nodes.rateToAmount/FlagWar.calculateAttackTimeTicks. `now` must already
    // be in the target zone (ZONE) for the DST-transition-day math to come out right.
    internal fun millisUntilNextMidnight(now: ZonedDateTime): Long {
        val nextMidnight = now.toLocalDate().plusDays(1).atStartOfDay(now.zone)
        return Duration.between(now, nextMidnight).toMillis()
    }

    // Runs on ioExecutor, off the main thread -- reads Resident/Nation/Town state, which is
    // otherwise only ever mutated from the main thread, so this is a snapshot-read race in
    // theory (same tradeoff already accepted by SaveManager's async save path). Acceptable
    // here: worst case a stat is one event behind, never corrupted, and this runs once a day
    // when the server is near-empty anyway.
    internal fun runSnapshot(outputPath: Path, gitRepoPath: Path?) {
        try {
            val payload = buildPayload()
            val json = gson.toJson(payload)

            Files.createDirectories(outputPath.parent)
            Files.writeString(outputPath, json)

            if (gitRepoPath != null) publishToGit(gitRepoPath, json)
        } catch (e: Exception) {
            System.err.println("[DailyStatsSnapshot] Failed to build/write snapshot: ${e.message}")
            e.printStackTrace()
        }
    }

    internal fun buildPayload(): DailyStatsPayload {
        val players = Nodes.residents.values.map { resident ->
            PlayerStats(
                uuid = resident.uuid.toString(),
                name = resident.name,
                town = resident.town?.name,
                nation = resident.nation?.name,
                kills = resident.kills,
                deaths = resident.deaths,
                kd = kd(resident.kills, resident.deaths),
                capsPlaced = resident.capsPlaced,
                attacksDefended = resident.attacksDefended,
                playtimeMs = resident.totalPlaytimeMillis,
            )
        }

        val nations = Nodes.nations.values.map { nation ->
            NationStats(
                name = nation.name,
                kills = nation.kills,
                deaths = nation.deaths,
                kd = kd(nation.kills, nation.deaths),
                nodesCaptured = nation.nodesCaptured,
                nodesLost = nation.nodesLost,
                // Live sum of current members' playtime, not its own persisted counter --
                // see docs/STATS.md ("combined playtime of every player in that nation").
                playtimeMs = nation.residents.sumOf { it.totalPlaytimeMillis },
            )
        }

        return DailyStatsPayload(System.currentTimeMillis(), players, nations)
    }

    private fun kd(kills: Int, deaths: Int): Double = if (deaths == 0) kills.toDouble() else kills.toDouble() / deaths

    // Off the main thread already (see runSnapshot) -- a slow/hanging git remote never affects
    // the tick thread. Failure here is logged, never thrown back into the daily task loop: a
    // GitHub outage shouldn't stop next month's local-disk snapshot from running.
    private fun publishToGit(gitRepoPath: Path, json: String) {
        try {
            Files.writeString(gitRepoPath.resolve("daily-stats.json"), json)
            runGit(gitRepoPath, "add", "daily-stats.json")
            // Nothing changed since yesterday's push (e.g. a quiet server) -- `commit` would
            // exit non-zero, which is expected here, not a failure to surface.
            val commit = runGit(gitRepoPath, "commit", "-m", "Daily stats update")
            if (commit != 0) return
            runGit(gitRepoPath, "push")
        } catch (e: Exception) {
            System.err.println("[DailyStatsSnapshot] Failed to publish stats to git: ${e.message}")
        }
    }

    private fun runGit(repoPath: Path, vararg args: String): Int {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repoPath.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        if (exit != 0) println("[DailyStatsSnapshot] git ${args.joinToString(" ")} exited $exit: $output")
        return exit
    }
}

data class PlayerStats(
    val uuid: String,
    val name: String,
    val town: String?,
    val nation: String?,
    val kills: Int,
    val deaths: Int,
    val kd: Double,
    val capsPlaced: Int,
    val attacksDefended: Int,
    val playtimeMs: Long,
)

data class NationStats(
    val name: String,
    val kills: Int,
    val deaths: Int,
    val kd: Double,
    val nodesCaptured: Int,
    val nodesLost: Int,
    val playtimeMs: Long,
)

data class DailyStatsPayload(
    val generatedAt: Long,
    val players: List<PlayerStats>,
    val nations: List<NationStats>,
)
