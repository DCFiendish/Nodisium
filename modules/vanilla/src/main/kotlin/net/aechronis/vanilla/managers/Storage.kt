package net.aechronis.vanilla.managers

import net.aechronis.vanilla.listeners.StorageListener
import net.aechronis.vanilla.objects.BlockKey
import net.aechronis.vanilla.objects.StorageContents
import net.aechronis.vanilla.serdes.StorageDeserializer
import net.aechronis.vanilla.serdes.StorageSerializer
import net.kyori.adventure.key.Key
import net.kyori.adventure.nbt.BinaryTagIO
import net.kyori.adventure.nbt.BinaryTagTypes
import net.kyori.adventure.nbt.ListBinaryTag
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.entity.Player
import net.minestom.server.instance.Chunk
import net.minestom.server.instance.Instance
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockHandler
import net.minestom.server.inventory.Inventory
import net.minestom.server.timer.Task
import net.minestom.server.timer.TaskSchedule
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap

enum class StorageAccess {
    INTERACT,
    BREAK,
}

object Storage {
    val barrels = ConcurrentHashMap<BlockKey, StorageContents>()
    val inventoryToKey = ConcurrentHashMap<Inventory, BlockKey>()

    private val barrelKey = Key.key("minecraft:barrel")
    private val defaultBarrelHandler =
        object : BlockHandler {
            override fun getKey(): Key = barrelKey
        }
    private var legacyRoot: Path? = null
    private var task: Task? = null

    // Vanilla has no notion of claims/permissions on its own -- Nodes wires this up via
    // NodesVanillaStorageBridge so barrels respect town ownership. Without a checker installed,
    // storage is unrestricted (matches vanilla behavior when Nodes isn't loaded).
    @Volatile
    private var accessChecker: ((Player, Point, StorageAccess) -> Boolean)? = null

    fun setAccessChecker(checker: ((Player, Point, StorageAccess) -> Boolean)?) {
        accessChecker = checker
    }

    fun hasAccess(
        player: Player,
        position: Point,
        access: StorageAccess,
    ): Boolean = accessChecker?.invoke(player, position, access) ?: true

    fun init(legacyRoot: Path) {
        val timeStart = System.currentTimeMillis()
        this.legacyRoot = legacyRoot
        val blockManager = MinecraftServer.getBlockManager()
        if (blockManager.getHandler(barrelKey.asString()) == null) {
            blockManager.registerHandler(barrelKey) { defaultBarrelHandler }
        }
        StorageListener.init()

        // Barrels already persist on inventory close, but that only covers barrels a player
        // actually opened and closed cleanly -- this is a periodic safety net for the rest.
        // saveAll() returns its completion future rather than blocking; the periodic run below
        // fires-and-forgets it instead of joining, so this doesn't tie up the shared scheduler
        // pool thread for as long as the barrel writes + chunk saves take (see saveAll() below).
        task = MinecraftServer
            .getSchedulerManager()
            .buildTask { saveAll() }
            .repeat(TaskSchedule.seconds(300))
            .schedule()

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Storage enabled in ${timeLoad}ms")
    }

    fun stop() {
        task?.cancel()
        task = null
        // Block here (unlike the periodic autosave) -- shutdown must not proceed until the
        // final save has actually landed on disk.
        saveAll().join()
    }

    fun keyFor(
        instance: Instance,
        pos: Point,
    ): BlockKey = BlockKey(instance, pos.asVec())

    fun loadOrCreate(key: BlockKey): StorageContents {
        val block = key.instance.getBlock(key.pos)
        val blockNbt = block.nbtOrEmpty()
        val file = legacyFileFor(key)
        var created = false
        var migratedFile: Path? = null
        val contents =
            barrels.computeIfAbsent(key) {
                created = true
                if (blockNbt.contains(StorageSerializer.ITEMS_KEY, BinaryTagTypes.LIST)) {
                    StorageDeserializer.deserialize(blockNbt)
                } else if (file != null && Files.exists(file)) {
                    runCatching {
                        Files.newInputStream(file).use { input ->
                            val named = BinaryTagIO.reader().readNamed(input, BinaryTagIO.Compression.GZIP)
                            StorageDeserializer.deserialize(named.value)
                        }
                    }.onSuccess { migratedFile = file }.getOrElse { StorageContents() }
                } else {
                    StorageContents()
                }
            }
        inventoryToKey.putIfAbsent(contents.inventory, key)
        if (created && block.compare(Block.BARREL) && !blockNbt.contains(StorageSerializer.ITEMS_KEY, BinaryTagTypes.LIST)) {
            writeToBlock(key)
            migratedFile?.let { persistAndArchiveMigration(key, it) }
        }
        return contents
    }

    fun register(
        key: BlockKey,
        contents: StorageContents,
    ) {
        barrels.put(key, contents)?.let { previous ->
            inventoryToKey.remove(previous.inventory)
        }
        inventoryToKey[contents.inventory] = key
    }

    fun withContents(
        block: Block,
        contents: StorageContents,
    ): Block = withItems(block, StorageSerializer.serializeItems(contents.inventory))

    fun save(key: BlockKey) {
        writeToBlock(key)
    }

    private fun writeToBlock(key: BlockKey): Boolean {
        val contents = barrels[key] ?: return false
        val block = key.instance.getBlock(key.pos)
        if (!block.compare(Block.BARREL)) return false
        key.instance.setBlock(key.pos, withContents(block, contents), false)
        return true
    }

    /**
     * Writes every tracked barrel back to its block and flushes the touched chunks to disk,
     * returning a future that completes once that's done. Deliberately non-blocking: this used
     * to `.join()` on both stages inline, which -- since this runs on Minestom's shared global
     * scheduler thread pool (the same pool Crops/Saplings/Food/EnvironmentalDamage/Combat/Koth's
     * own periodic ticks share) -- tied up a pool thread for as long as every barrel write plus
     * every chunk save took, once every 300s. Callers that must wait (e.g. `stop()`, which needs
     * the save to have actually landed before shutdown proceeds) can `.join()` the returned
     * future themselves; the periodic autosave task does not.
     */
    fun saveAll(): CompletableFuture<Void> {
        // writeToBlock() -> setBlock() touches chunk/block state, which per Minestom's threading
        // model isn't safe from the global scheduler thread -- defer each write onto its owning
        // instance's own tick thread instead, same as Crops.growthTick, using self-completed
        // futures since scheduleNextTick doesn't hand one back.
        val chunks = ConcurrentHashMap.newKeySet<Chunk>()
        val writeFutures = barrels.keys.map { key ->
            val future = CompletableFuture<Void>()
            key.instance.scheduleNextTick {
                try {
                    if (writeToBlock(key)) key.instance.getChunkAt(key.pos)?.let(chunks::add)
                } catch (e: Exception) {
                    System.err.println("Failed to save storage at $key: ${e.message}")
                } finally {
                    future.complete(null)
                }
            }
            future
        }
        return CompletableFuture
            .allOf(*writeFutures.toTypedArray())
            .exceptionally { e ->
                System.err.println("Failed to wait for one or more storage writes: ${e.message}")
                null
            }.thenCompose {
                val saves = chunks.map { chunk -> chunk.instance.saveChunkToStorage(chunk) }
                CompletableFuture.allOf(*saves.toTypedArray())
            }.exceptionally { e ->
                System.err.println("Failed to save one or more storage chunks: ${e.message}")
                null
            }
    }

    fun remove(key: BlockKey) {
        val contents = barrels.remove(key)
        if (contents != null) {
            inventoryToKey.remove(contents.inventory)
        }
    }

    private fun withItems(
        block: Block,
        items: ListBinaryTag,
    ): Block {
        val handler =
            block.handler()
                ?: MinecraftServer.getBlockManager().getHandler(barrelKey.asString())
                ?: defaultBarrelHandler
        return block
            .withNbt(block.nbtOrEmpty().put(StorageSerializer.ITEMS_KEY, items))
            .withHandler(handler)
    }

    // Was key.instance.saveChunkToStorage(chunk).join() -- called synchronously from
    // loadOrCreate(), which runs on the interacting player's own event-dispatch thread (via
    // StorageListener.onInteract/onBreak). The first player to touch any legacy-format barrel
    // stalled their own interaction on a real blocking chunk write. Migration correctness doesn't
    // require the caller to wait: the block's own NBT (written by writeToBlock() just before this
    // is called) already carries the migrated contents, so it's safe to let the chunk save and the
    // legacy-file archive/rename finish asynchronously.
    private fun persistAndArchiveMigration(
        key: BlockKey,
        file: Path,
    ) {
        val chunk = key.instance.getChunkAt(key.pos) ?: return
        key.instance.saveChunkToStorage(chunk).whenComplete { _, error ->
            if (error != null) {
                System.err.println("Failed to save chunk during legacy barrel migration at $key: ${error.message}")
                return@whenComplete
            }
            runCatching {
                Files.move(
                    file,
                    file.resolveSibling("${file.fileName}.migrated"),
                    StandardCopyOption.REPLACE_EXISTING,
                )
            }.onFailure { error ->
                System.err.println("Failed to archive migrated legacy barrel file $file: ${error.message}")
            }
        }
    }

    private fun legacyFileFor(key: BlockKey): Path? {
        val root = legacyRoot ?: return null
        val x = key.pos.x().toInt()
        val y = key.pos.y().toInt()
        val z = key.pos.z().toInt()
        return root
            .resolve(key.instance.uuid.toString())
            .resolve("${x}_${y}_$z.dat")
    }
}
