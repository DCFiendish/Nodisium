package net.aechronis.vanilla.managers

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import net.aechronis.vanilla.Vanilla
import net.aechronis.vanilla.listeners.WarpListener
import net.aechronis.vanilla.objects.WarpPoint
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.format.NamedTextColor
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/**
 * Player-triggered `/warp <name>` with a short move-cancellable cast timer -- see WarpCommand.
 * Warp points can come from two places: [net.aechronis.vanilla.VanillaConfig.warpsConfig] (seeded
 * at boot, same static-config convention Koth uses) and in-game `/setwarp`/`/warp remove` (see
 * SetWarpCommand), persisted to [warpsFile] so they survive a restart without editing code. A
 * persisted entry overwrites a config-seeded one of the same name -- in-game edits are the
 * authoritative source once the server's running.
 */
object Warp {
    private data class PendingWarp(
        val destination: WarpPoint,
        val initialPosition: Pos,
        val task: Task,
    )

    /** Flat, Gson-friendly shape for [warpsFile] -- Instance itself isn't serializable, so a
     * persisted warp is resolved back to this server's single boot instance on load. */
    private data class PersistedWarp(
        val name: String,
        val x: Double,
        val y: Double,
        val z: Double,
        val yaw: Float,
        val pitch: Float,
    )

    private val definitions = linkedMapOf<String, WarpPoint>()
    private val pending = ConcurrentHashMap<UUID, PendingWarp>()
    private val gson = Gson()
    private val saveLock = Any()

    private lateinit var warpsFile: Path

    fun init(path: Path) {
        warpsFile = path
        Files.createDirectories(path.parent)

        loadConfiguration()
        loadPersisted()
        WarpListener.init()
    }

    fun stop() {
        pending.values.forEach { it.task.cancel() }
        pending.clear()
        definitions.clear()
    }

    private fun loadConfiguration() {
        val warps = Vanilla.config.warpsConfig.warps
        require(warps.map { it.name }.toSet().size == warps.size) { "Warp names must be unique" }
        require(Vanilla.config.warpsConfig.warpTimeSeconds > 0) { "warpTimeSeconds must be positive" }
        definitions.putAll(warps.associateBy { it.name })
    }

    private fun loadPersisted() {
        if (!Files.exists(warpsFile)) return
        runCatching {
            Files.newBufferedReader(warpsFile).use { reader ->
                val type = object : TypeToken<List<PersistedWarp>>() {}.type
                val loaded: List<PersistedWarp>? = gson.fromJson(reader, type)
                val instance = MinecraftServer.getInstanceManager().instances.firstOrNull() ?: return@use
                loaded?.forEach { p ->
                    definitions[p.name] = WarpPoint(p.name, instance, Pos(p.x, p.y, p.z, p.yaw, p.pitch))
                }
            }
        }.onFailure { error ->
            System.err.println("Failed to load warps: ${error.message}")
        }
    }

    private fun persist() {
        synchronized(saveLock) {
            val tmpPath = warpsFile.resolveSibling("${warpsFile.fileName}.tmp")
            Files.newBufferedWriter(tmpPath).use { writer ->
                val toSave =
                    definitions.values.map {
                        PersistedWarp(it.name, it.position.x(), it.position.y(), it.position.z(), it.position.yaw(), it.position.pitch())
                    }
                gson.toJson(toSave, writer)
            }
            Files.move(tmpPath, warpsFile, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    fun names(): Set<String> = definitions.keys

    fun isPending(player: Player): Boolean = pending.containsKey(player.uuid)

    /** Creates or overwrites a warp named [name] at [player]'s current instance/position. */
    fun setWarp(
        player: Player,
        name: String,
    ) {
        val instance = player.instance ?: return
        definitions[name] = WarpPoint(name, instance, player.position)
        persist()
    }

    /** Removes warp [name]. Returns false if it didn't exist. */
    fun deleteWarp(name: String): Boolean {
        val removed = definitions.remove(name) != null
        if (removed) persist()
        return removed
    }

    /** Starts a cast for [name]. Returns false if [name] isn't a configured warp. */
    fun start(
        player: Player,
        name: String,
    ): Boolean {
        val destination = definitions[name] ?: return false
        cancel(player, notify = false)

        val warpTimeSeconds = Vanilla.config.warpsConfig.warpTimeSeconds
        val initialPosition = player.position
        val task =
            MinecraftServer
                .getSchedulerManager()
                .buildTask { complete(player) }
                .delay(TaskSchedule.seconds(warpTimeSeconds))
                .schedule()
        pending[player.uuid] = PendingWarp(destination, initialPosition, task)
        player.sendMessage(
            Component.text("Warping to ${destination.name} in ${warpTimeSeconds}s -- don't move...", NamedTextColor.YELLOW),
        )
        return true
    }

    /** Cancels [player]'s pending warp, if any. Safe to call when nothing is pending. */
    fun cancel(
        player: Player,
        notify: Boolean,
    ) {
        val warp = pending.remove(player.uuid) ?: return
        warp.task.cancel()
        if (notify) player.sendMessage(Component.text("Warp cancelled -- you moved.", NamedTextColor.RED))
    }

    /** Cancels [player]'s pending warp if [newPosition] has left the block they cast from. */
    fun cancelIfMoved(
        player: Player,
        newPosition: Pos,
    ) {
        val warp = pending[player.uuid] ?: return
        if (newPosition.blockX() != warp.initialPosition.blockX() ||
            newPosition.blockY() != warp.initialPosition.blockY() ||
            newPosition.blockZ() != warp.initialPosition.blockZ()
        ) {
            cancel(player, notify = true)
        }
    }

    private fun complete(player: Player) {
        val warp = pending.remove(player.uuid) ?: return
        if (player.instance === warp.destination.instance) {
            player.teleport(warp.destination.position)
        } else {
            player.setInstance(warp.destination.instance, warp.destination.position)
        }
        player.sendMessage(Component.text("Warped to ${warp.destination.name}", NamedTextColor.GREEN))
    }
}
