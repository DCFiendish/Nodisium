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
        task = MinecraftServer
            .getSchedulerManager()
            .buildTask(::saveAll)
            .repeat(TaskSchedule.seconds(300))
            .schedule()

        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("├─ Storage enabled in ${timeLoad}ms")
    }

    fun stop() {
        task?.cancel()
        task = null
        saveAll()
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

    fun saveAll() {
        // saveAll() runs on the global scheduler thread (like Crops'/Saplings' periodic tasks),
        // which per Minestom's threading model has no synchronization guarantee for touching
        // chunk/block state directly. writeToBlock() -> setBlock() used to run straight from here
        // with no deferral -- unlike every other periodic block-touching task in this codebase --
        // so this 300s autosave sweep could race a player's own in-progress interaction with the
        // same barrel on the instance's real tick thread, corrupting the write or dropping it
        // (items silently vanish from the barrel). Defer each write onto its owning instance's own
        // tick thread instead, same as Crops.growthTick, and wait for all of them (via
        // self-completed futures, since scheduleNextTick doesn't hand back one) before moving on to
        // the chunk-save step below, which depends on the writes already having happened.
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
        try {
            CompletableFuture.allOf(*writeFutures.toTypedArray()).join()
        } catch (e: Exception) {
            System.err.println("Failed to wait for one or more storage writes: ${e.message}")
        }

        val saves = chunks.map { chunk -> chunk.instance.saveChunkToStorage(chunk) }
        try {
            CompletableFuture.allOf(*saves.toTypedArray()).join()
        } catch (e: Exception) {
            System.err.println("Failed to save one or more storage chunks: ${e.message}")
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

    private fun persistAndArchiveMigration(
        key: BlockKey,
        file: Path,
    ) {
        val chunk = key.instance.getChunkAt(key.pos) ?: return
        key.instance.saveChunkToStorage(chunk).join()
        Files.move(
            file,
            file.resolveSibling("${file.fileName}.migrated"),
            StandardCopyOption.REPLACE_EXISTING,
        )
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
