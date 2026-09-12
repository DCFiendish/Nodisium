package net.aechronis.vanilla.managers

import net.aechronis.vanilla.serdes.PlayerDataDeserializer
import net.aechronis.vanilla.serdes.PlayerDataSerializer
import net.kyori.adventure.nbt.BinaryTagIO
import net.minestom.server.MinecraftServer
import net.minestom.server.entity.Player
import net.minestom.server.event.Event
import net.minestom.server.event.EventNode
import net.minestom.server.event.player.PlayerDisconnectEvent
import net.minestom.server.event.player.PlayerSpawnEvent
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.AbstractMap.SimpleImmutableEntry
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors

// loosely based on https://github.com/Quiet-Terminal-Interactive/Cattlelog
object PlayerData {
    private val tracked: MutableSet<Player> = ConcurrentHashMap.newKeySet<Player>()
    private lateinit var dataPath: Path
    // Registered directly on the global handler, not as a child of Vanilla.eventNode -- so
    // Vanilla.shutdown() removing Vanilla.eventNode alone would miss this one; stop() below
    // detaches it explicitly.
    private var node: EventNode<Event>? = null
    private var task: Task? = null

    // saveAll() does real blocking disk I/O (gzip + write + atomic rename) for every online
    // player, one after another. The periodic autosave task used to run that directly on
    // Minestom's shared global scheduler thread pool -- the same pool Crops/Saplings/Food/
    // EnvironmentalDamage/Combat/Koth's own periodic ticks share -- so a 200-player save sweep
    // could tie up a pool thread for the whole sweep every 300s. Dispatch it to its own executor
    // instead so the scheduler pool is never blocked by this.
    private val autosaveExecutor =
        Executors.newSingleThreadExecutor { runnable -> Thread(runnable, "vanilla-playerdata-autosave").apply { isDaemon = true } }

    fun init(path: Path) {
        val timeStart = System.currentTimeMillis()
        Files.createDirectories(path)
        dataPath = path

        val eventNode = EventNode.all("vanilla-playerdata")
        node = eventNode

        eventNode.addListener(PlayerSpawnEvent::class.java) { event ->
            Commands.allowEnderChest(event.player)
            tracked.add(event.player)
            if (!event.isFirstSpawn) return@addListener
            loadPlayer(event.player, path)
        }

        eventNode.addListener(PlayerDisconnectEvent::class.java) { event ->
            tracked.remove(event.player)
            Commands.closeViewsOf(event.player)
            try {
                savePlayer(event.player, path)
            } finally {
                Commands.removeEnderChest(event.player)
            }
        }

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

        // Previously only saved on disconnect/shutdown -- a crash or lost connection (not a clean
        // disconnect) meant every change since the last clean save was gone.
        task = MinecraftServer
            .getSchedulerManager()
            .buildTask { autosaveExecutor.execute(::saveAll) }
            .repeat(TaskSchedule.seconds(300))
            .schedule()

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Playerdata enabled in ${timeLoad}ms")
    }

    fun stop() {
        task?.cancel()
        task = null
        node?.let(MinecraftServer.getGlobalEventHandler()::removeChild)
        node = null
        saveAll()
        tracked.clear()
        autosaveExecutor.shutdown()
    }

    fun saveAll() {
        if (!::dataPath.isInitialized) return
        for (player in tracked) {
            try {
                savePlayer(player, dataPath)
            } catch (e: Exception) {
                System.err.println("Failed to save player data for ${player.uuid}: ${e.message}")
            }
        }
    }

    fun hasSavedData(player: Player): Boolean = ::dataPath.isInitialized && Files.exists(dataPath.resolve("${player.uuid}.dat"))

    fun loadPlayer(
        player: Player,
        path: Path,
    ) {
        val path: Path = path.resolve("${player.uuid}.dat")
        if (!Files.exists(path)) {
            return
        }

        runCatching {
            Files.newInputStream(path).use { input ->
                val named = BinaryTagIO.reader().readNamed(input, BinaryTagIO.Compression.GZIP)
                PlayerDataDeserializer.deserialize(player, named.value)
            }
        }.onFailure { error ->
            System.err.println("Failed to load player data for ${player.uuid}: ${error.message}")
        }
    }

    private fun savePlayer(
        player: Player,
        path: Path,
    ) {
        val data = PlayerDataSerializer.serialize(player)

        val target: Path = path.resolve("${player.uuid}.dat")
        // Write to a per-writer temp file and atomically move it into place, rather than
        // truncate-writing the real file directly -- autosave and the disconnect-save can both
        // fire for the same player close together, and a direct write left a window where a
        // reader (or a second concurrent writer) could see a half-written, corrupt .dat file.
        val tmp: Path = path.resolve("${player.uuid}.dat.${Thread.currentThread().threadId()}.tmp")

        Files.newOutputStream(tmp).use { out ->
            BinaryTagIO.writer().writeNamed(
                SimpleImmutableEntry("", data),
                out,
                BinaryTagIO.Compression.GZIP,
            )
        }
        Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
    }
}
