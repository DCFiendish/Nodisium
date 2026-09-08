/*
 * Nodes Engine/API
 */

package net.aechronis.nodes

import com.google.gson.JsonObject
import net.aechronis.nodes.commands.AllyChatCommand
import net.aechronis.nodes.commands.AllyCommand
import net.aechronis.nodes.commands.GlobalChatCommand
import net.aechronis.nodes.commands.NationChatCommand
import net.aechronis.nodes.commands.NationCommand
import net.aechronis.nodes.commands.NodesAdminCommand
import net.aechronis.nodes.commands.PlayerCommand
import net.aechronis.nodes.commands.PortCommand
import net.aechronis.nodes.commands.TerritoryCommand
import net.aechronis.nodes.commands.TownChatCommand
import net.aechronis.nodes.commands.TownCommand
import net.aechronis.nodes.commands.UnallyCommand
import net.aechronis.nodes.commands.WarzoneCommand
import net.aechronis.nodes.commands.WaypointCommand
import net.aechronis.nodes.listeners.NodesChatListener
import net.aechronis.nodes.listeners.NodesChestProtectionDestroyListener
import net.aechronis.nodes.listeners.NodesChestProtectionListener
import net.aechronis.nodes.listeners.NodesIncomeInventoryListener
import net.aechronis.nodes.listeners.NodesPlayerDamageListener
import net.aechronis.nodes.listeners.NodesPlayerJoinQuitListener
import net.aechronis.nodes.listeners.NodesPlayerMoveListener
import net.aechronis.nodes.listeners.NodesPlotSelectionListener
import net.aechronis.nodes.listeners.NodesVanillaStorageBridge
import net.aechronis.nodes.listeners.NodesWorldListener
import net.aechronis.nodes.objects.Building
import net.aechronis.nodes.objects.Coord
import net.aechronis.nodes.objects.MinimapPassengerTracker
import net.aechronis.nodes.objects.MiningBoostManager
import net.aechronis.nodes.objects.Nametag
import net.aechronis.nodes.objects.Nation
import net.aechronis.nodes.objects.OreBlockCache
import net.aechronis.nodes.objects.OreSampler
import net.aechronis.nodes.objects.RelationshipHitbox
import net.aechronis.nodes.objects.Resident
import net.aechronis.nodes.objects.ResourceNode
import net.aechronis.nodes.objects.Territory
import net.aechronis.nodes.objects.TerritoryChunk
import net.aechronis.nodes.objects.TerritoryId
import net.aechronis.nodes.objects.TerritoryPreprocessing
import net.aechronis.nodes.objects.TerritoryResources
import net.aechronis.nodes.objects.Town
import net.aechronis.nodes.objects.WaypointMenu
import net.aechronis.nodes.serdes.Deserializer
import net.aechronis.nodes.tasks.IncomeManager
import net.aechronis.nodes.tasks.SaveManager
import net.aechronis.nodes.tasks.SerialSaveQueue
import net.aechronis.nodes.tasks.TaskSaveBackup
import net.aechronis.nodes.tasks.TaskSaveBuildings
import net.aechronis.nodes.tasks.TaskSaveWorld
import net.aechronis.nodes.utils.loadLongFromFile
import net.aechronis.nodes.war.FlagWar
import net.aechronis.nodes.war.Warzone
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.entity.Player
import net.minestom.server.event.EventNode
import net.minestom.server.item.Material
import net.minestom.server.timer.Task
import java.nio.file.Files
import java.util.UUID
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ThreadLocalRandom
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.system.measureNanoTime

/** Global lifecycle, persistence, registries, and cross-domain engine coordination. */
object Nodes {
    val lowPriorityEventNode = EventNode.all("nodes-low-priority").setPriority(999)
    val eventNode = EventNode.all("nodes")
    val highPriorityEventNode = EventNode.all("nodes-high-priority").setPriority(-999)

    // Runs right after highPriorityEventNode's own permission checks but before general/low
    // priority listeners -- e.g. NodesChestProtectionDestroyListener needs to see a protected-chest
    // break before Vanilla's barrel storage handler (on eventNode) converts it into a manual drop.
    val postPermissionEventNode = EventNode.all("nodes-post-permission").setPriority(-998)

    // resourceNodes/territoryChunks/territories/towns/nations/residents/buildings are ALL
    // cleared and rebuilt together by loadWorld() (see its kdoc), reachable live via
    // /nodesadmin load (nodes.admin permission -- not console/startup-only) while players stay
    // connected throughout (loadWorld()'s own `finally` block re-creates every online player's
    // Resident/minimap afterward). territoryChunks/territories/minimapBuildingsByChunk were
    // already fixed to ConcurrentHashMap in an earlier pass for exactly this reason -- the other
    // five siblings, cleared by that same function on the same reachable path, were missed then.
    // towns/nations/residents were LinkedHashMap; checked every non-sorted call site
    // (ArgumentTown/Nation/Resident, NationCommand's list, Town.fromIncomeInventory, etc.) and
    // none rely on insertion order -- display ordering is explicitly re-sorted where it matters
    // (e.g. NationCommand's `sortByDescending`), so switching to ConcurrentHashMap costs nothing
    // real. buildings was a plain MutableList (Building.register/destroy add/remove it
    // per-placement, read on every /port command) -- CopyOnWriteArrayList, not a locked
    // ArrayList, since writes are rare (player building placement) and reads are frequent
    // (iterated every port lookup); its snapshot-iterator semantics also mean a concurrent
    // add/remove during iteration can't throw here either.
    internal val resourceNodes: ConcurrentHashMap<String, ResourceNode> = ConcurrentHashMap()
    internal val territoryChunks: ConcurrentHashMap<Coord, TerritoryChunk> = ConcurrentHashMap()
    internal val territories: ConcurrentHashMap<TerritoryId, Territory> = ConcurrentHashMap()
    internal val towns: ConcurrentHashMap<String, Town> = ConcurrentHashMap()
    internal val nations: ConcurrentHashMap<String, Nation> = ConcurrentHashMap()
    internal val residents: ConcurrentHashMap<UUID, Resident> = ConcurrentHashMap()
    internal val buildings: CopyOnWriteArrayList<Building> = CopyOnWriteArrayList()
    internal val minimapBuildingsByChunk: ConcurrentHashMap<Coord, Building> = ConcurrentHashMap()

    // Guards Town.annex/merge/release-style territory-ownership transfers so an admin merge/move
    // can't interleave with a concurrent war capture touching the same territory.
    internal val occupationPersistenceLock = Any()

    // Both were plain HashMap -- playerWarpTasks is read/written from PortWarpCommand's per-player
    // command executor (a different thread per player, same territories/territoryChunks concern
    // above) *and* removed from PortWarpTask's own scheduled-task callback (the scheduler thread,
    // not any specific player's tick thread), so two players warping at once (or a warp completing
    // while a different player starts one) mutate this plain HashMap from genuinely different
    // threads. chunkToBuilding has the identical shape: written/removed by Building.kt on
    // place/destroy (per-chunk-thread, player-triggered) and read by Nodes.kt's income tick (the
    // scheduler thread) plus Building.getAt (any chunk-owning thread) concurrently.
    val playerWarpTasks: ConcurrentHashMap<Player, Task> = ConcurrentHashMap()
    val chunkToBuilding: ConcurrentHashMap<List<Int>, Building> = ConcurrentHashMap()
    internal var lastBackupTime: Long = 0
    val war = FlagWar

    // Guards needsSave/saveRevision/lastQueuedRevision/backupPending -- previously saveWorld() read
    // and reset needsSave with no lock at all, a real check-then-act race between the periodic
    // SaveManager tick and any other caller (shutdown, /nodesadmin save) running concurrently: both
    // could see needsSave=true, both build a save task, and whichever finished last could stamp
    // lastBackupTime/reset needsSave over the other's result. saveRevision/lastQueuedRevision let a
    // save that's already in flight represent state newer than what's on disk without forcing every
    // caller to block on it.
    private val saveStateLock = Any()
    private val saveQueue = SerialSaveQueue()
    private var saveRevision = 0L
    private var lastQueuedRevision = -1L
    private var backupPending = false
    internal var needsSave: Boolean = false
        set(value) {
            synchronized(saveStateLock) {
                field = value
                if (value) saveRevision++
            }
        }
    internal val hiddenOreInvalidBlocks: OreBlockCache = OreBlockCache()
    lateinit var config: NodesConfig

    // Guards against double-initialize/double-cleanup in the same JVM (e.g. a module hot-reload
    // calling initialize() again) -- without this, a second initialize() would register a second
    // copy of every listener/command on top of the first instead of replacing it.
    private val initialized = AtomicBoolean(false)
    private var commands: List<Command> = emptyList()
    private val completedCleanupStages = HashSet<CleanupStage>()

    fun initialize(config: NodesConfig = NodesConfig()) {
        check(initialized.compareAndSet(false, true)) { "Nodes is already initialized -- call cleanup() first" }
        completedCleanupStages.clear()
        val timeStart = System.currentTimeMillis()
        this.config = config
        FlagWar.initialize(config.flagBlocks)
        println("Loading world from: $config.path")
        try {
            if (loadWorld()) {
                println("- Resource Nodes: ${ResourceNode.count()}")
                println("- Territories: ${Territory.count()}")
                println("- Residents: ${Resident.count()}")
                println("- Towns: ${Town.count()}")
                println("- Nations: ${Nation.count()}")
            } else {
                println("Error loading world: Invalid world file at ${config.path}/${config.pathWorld}")
            }
        } catch (err: Exception) {
            err.printStackTrace()
            println("Error loading world: $err")
        }
        MinecraftServer.getGlobalEventHandler().addChild(lowPriorityEventNode)
        MinecraftServer.getGlobalEventHandler().addChild(eventNode)
        MinecraftServer.getGlobalEventHandler().addChild(highPriorityEventNode)
        MinecraftServer.getGlobalEventHandler().addChild(postPermissionEventNode)
        MinimapPassengerTracker.init()
        RelationshipHitbox.init()
        NodesChatListener.init()
        NodesChestProtectionListener.init()
        NodesChestProtectionDestroyListener.init()
        NodesIncomeInventoryListener.init()
        NodesPlayerDamageListener.init()
        NodesPlayerJoinQuitListener.init()
        NodesPlayerMoveListener.init()
        NodesPlotSelectionListener.init()
        NodesWorldListener.init()
        NodesVanillaStorageBridge.init()
        WaypointMenu.init()
        commands = listOf(
            TownCommand(),
            NationCommand(),
            NodesAdminCommand(),
            AllyCommand(),
            UnallyCommand(),
            GlobalChatCommand(),
            TownChatCommand(),
            NationChatCommand(),
            AllyChatCommand(),
            PlayerCommand(),
            TerritoryCommand(),
            PortCommand(),
            WaypointCommand(),
            WarzoneCommand(),
        )
        commands.forEach(MinecraftServer.getCommandManager()::register)
        lastBackupTime = loadLongFromFile(config.pathLastBackupTime) ?: System.currentTimeMillis()
        reloadManagers()
        MiningBoostManager.start()
        initializeOnlinePlayers()
        println("Enabled in ${System.currentTimeMillis() - timeStart}ms")
        println("now this is epic")
    }

    internal fun reloadManagers() {
        SaveManager.stop()
        IncomeManager.stop()
        Nametag.stop()
        SaveManager.start(config.savePeriod)
        IncomeManager.start()
        Nametag.start(config.nametagUpdatePeriod)
    }

    internal fun initializeOnlinePlayers() {
        for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
            Resident.create(player)
            val resident = Resident.fromPlayer(player)!!
            Resident.setOnline(resident, player)
            Warzone.onPlayerTerritoryChanged(player, Territory.fromPlayer(player))
            MiningBoostManager.onPlayerJoin(player)
            if (resident.minimap == null) resident.createMinimap(player)
        }
    }

    /**
     * Public entry point to enable war/FlagWar without going through the /nodesadmin command --
     * that command tree is wired exclusively for Player senders (via the NodesCommand/Command
     * wrapper), so it can't be invoked from console or programmatically at startup. Mirrors what
     * NodesAdminWarEnableCommand does.
     */
    fun enableWar(canAnnexTerritories: Boolean = true, canOnlyAttackBorders: Boolean = false, destructionEnabled: Boolean = true) {
        FlagWar.enable(canAnnexTerritories, canOnlyAttackBorders, destructionEnabled)
    }

    /**
     * Symmetric inverse of [initialize] -- unregisters every command/event node this module
     * registered, stops every scheduled manager, and does a final synchronous save. Staged and
     * idempotent (each [CleanupStage] runs at most once) so a hot-reload that calls this can
     * safely retry, and so double-cleanup in the same JVM is a no-op rather than a double-save or
     * a crash from unregistering something already unregistered.
     */
    internal fun cleanup() {
        if (!initialized.get()) return

        val globalEventHandler = MinecraftServer.getGlobalEventHandler()
        cleanupStage(CleanupStage.EVENTS) {
            listOf(lowPriorityEventNode, eventNode, highPriorityEventNode, postPermissionEventNode).forEach { node ->
                globalEventHandler.removeChild(node)
            }
        }
        cleanupStage(CleanupStage.COMMANDS) {
            commands.forEach(MinecraftServer.getCommandManager()::unregister)
            commands = emptyList()
        }
        cleanupStage(CleanupStage.MANAGERS) {
            SaveManager.stop()
            IncomeManager.stop()
            Nametag.stop()
            MiningBoostManager.stop()
        }
        cleanupStage(CleanupStage.RESIDENTS) {
            residents.values.forEach { it.destroyMinimap() }
        }
        cleanupStage(CleanupStage.TOWNS) {
            towns.values.forEach { town -> if (town.income.pushToStorage(true)) town.needsUpdate() }
        }
        cleanupStage(CleanupStage.FLAG_WAR) {
            if (FlagWar.enabled) FlagWar.cleanup()
        }
        cleanupStage(CleanupStage.WARZONE, Warzone::cleanup)
        cleanupStage(CleanupStage.FINAL_SAVE) {
            saveWorld(checkIfNeedsSave = false, async = false)
        }
        initialized.set(false)
    }

    private fun cleanupStage(stage: CleanupStage, action: () -> Unit) {
        if (stage in completedCleanupStages) return
        action()
        completedCleanupStages += stage
    }

    private enum class CleanupStage {
        EVENTS,
        COMMANDS,
        MANAGERS,
        RESIDENTS,
        TOWNS,
        FLAG_WAR,
        WARZONE,
        FINAL_SAVE,
    }

    internal fun loadResources(json: JsonObject) {
        resourceNodes.putAll(ResourceNode.loadFromJson(json))
    }

    internal fun loadTerritories(json: JsonObject, ids: List<TerritoryId>? = null) {
        val preprocessing = TerritoryPreprocessing.loadFromJson(json, ids)
        val graph = HashMap<TerritoryId, TerritoryResources>()
        if (ids != null) {
            val neighbors = hashSetOf<TerritoryId>()
            ids.forEach { id ->
                territories[id]?.let { territory ->
                    for (neighborId in territory.neighbors) {
                        territories[neighborId]?.let { neighbor ->
                            neighbors.add(neighborId)
                            for (neighborNeighborId in neighbor.neighbors) neighbors.add(neighborNeighborId)
                        }
                    }
                }
            }
            neighbors.forEach { id ->
                territories[id]?.let { territory ->
                    val resources = territory.resourceNodes.map { resourceNodes[it] ?: error("Resource node '$it' does not exist (for territory id=${territory.id})") }.sortedBy { it.priority }
                    graph[id] = resources.fold(config.globalResources.copy()) { current, resource -> resource.apply(current) }
                }
            }
        }
        preprocessing.forEach { territory ->
            val resources = territory.resourceNodes.map { resourceNodes[it] ?: error("Resource node '$it' does not exist (for territory id=${territory.id})") }.sortedBy { it.priority }
            graph[territory.id] = resources.fold(config.globalResources.copy()) { current, resource -> resource.apply(current) }
        }
        val toBuild = if (ids == null) {
            preprocessing
        } else {
            val neighborIds = hashSetOf<TerritoryId>()
            preprocessing.filter { graph[it.id]!!.hasNeighborModifier }.forEach { territory ->
                for (neighborId in territory.neighbors) neighborIds.add(neighborId)
            }
            preprocessing.forEach { neighborIds.remove(it.id) }
            preprocessing + neighborIds.mapNotNull { territories[it]?.toPreprocessing() }
        }
        toBuild.forEach { territory ->
            var resources = graph[territory.id] ?: return@forEach
            for (neighborId in territory.neighbors) {
                graph[neighborId]?.takeIf { it.hasNeighborModifier }?.let { resources = resources.accumulateNeighborModifiers(it) }
            }
            graph[territory.id] = resources
        }
        toBuild.forEach { data ->
            if (!data.chunks.contains(data.core)) {
                println("[Nodes] Territory ${data.id} chunk does not contain core")
                // Was a bare `return`, which in Kotlin is a non-local return from the whole
                // loadTerritories() function, not just this forEach iteration -- one malformed
                // territory silently aborted loading of every territory after it. return@forEach
                // skips only this one and lets the rest still load.
                return@forEach
            }
            val resources = graph[data.id]!!.applyNeighborModifiers()
            val names = data.resourceNodes.sortedBy { resourceNodes[it]!!.priority }
            val territory = Territory(data.id, data.name, data.color, data.core, data.chunks, data.bordersWilderness, data.neighbors, names, resources.income, OreSampler(ArrayList(resources.ores.values)), resources.attackerTimeMultiplier, resources.defenderTimeMultiplier)
            territories[data.id]?.let { old ->
                old.chunks.forEach(territoryChunks::remove)
                territory.town = old.town
                territory.occupier = old.occupier
            }
            territories[data.id] = territory
            data.chunks.forEach { coord ->
                // Nothing previously stopped two territories from listing the same chunk in
                // world.json -- the later one in load order silently won with no trace, and the
                // earlier territory's chunk map entry just vanished. Log so a bad map edit is
                // visible instead of manifesting later as a territory missing part of its claim.
                val existing = territoryChunks[coord]
                if (existing != null && existing.territory.id != territory.id) {
                    System.err.println("[Nodes] Territory ${territory.id} claims chunk $coord which is already claimed by territory ${existing.territory.id} -- overwriting, check world.json for overlapping territories")
                }
                territoryChunks[coord] = TerritoryChunk(coord, territory)
            }
        }
    }

    internal fun loadWorld(): Boolean {
        residents.values.forEach { it.destroyMinimap() }
        MiningBoostManager.reset()

        // Previously no catch at all: a parse failure partway through (e.g. malformed
        // towns.json) threw straight past the clears above with towns/nations/residents left
        // half-wiped, half-reloaded -- and nothing here stopped the next scheduled autosave from
        // persisting that partial state over the last good save. The `/nodesadmin load` command
        // also called this with no try/catch of its own, so a bad reload there had no clean
        // failure path either. Catching here can't undo the clears that already ran (a fully
        // atomic reload would need the deserializers to build into fresh structures and swap
        // them in only on success -- a bigger change than this fix), but it does stop a broken
        // in-memory state from silently overwriting disk on the next autosave, and gives a clear
        // error instead of an uncaught exception.
        try {
            resourceNodes.clear()
            territoryChunks.clear()
            territories.clear()
            towns.clear()
            nations.clear()
            residents.clear()
            buildings.clear()
            minimapBuildingsByChunk.clear()
            chunkToBuilding.clear()
            if (!Files.exists(config.pathWorld)) {
                System.err.println("Failed to load world: ${config.pathWorld}")
                return false
            }
            val (resources, territoriesJson) = Deserializer.worldFromJson(config.pathWorld)
            if (resources != null) loadResources(resources)
            if (territoriesJson != null) loadTerritories(territoriesJson)
            hiddenOreInvalidBlocks.load(config.pathOreCache)
            if (!Files.exists(config.pathTowns)) {
                System.err.println("No towns found: ${config.pathTowns}")
                return true
            }
            Deserializer.townsFromJson(config.pathTowns)
            residents.values.forEach { it.getSaveState() }
            towns.values.forEach { it.getSaveState() }
            nations.values.forEach { it.getSaveState() }
            FlagWar.load()
            Warzone.load()
            if (!Files.exists(config.pathBuildings)) {
                System.err.println("No buildings found: ${config.pathBuildings}")
                return true
            }
            Deserializer.buildingsFromJson(config.pathBuildings)
            buildings.forEach { it.getSaveState() }
            return true
        } catch (err: Exception) {
            err.printStackTrace()
            System.err.println("Error loading world: $err")
            // don't let a broken in-memory state get autosaved over the last good files
            needsSave = false
            return false
        } finally {
            for (player in MinecraftServer.getConnectionManager().onlinePlayers) {
                Resident.create(player)
                val resident = Resident.fromPlayer(player)!!
                Resident.setOnline(resident, player)
                Warzone.onPlayerTerritoryChanged(player, Territory.fromPlayer(player))
                MiningBoostManager.onPlayerJoin(player)
                resident.createMinimap(player)
            }
        }
    }

    // Previously: needsSave was read and reset with no lock, and each save ran on its own
    // fire-and-forget CompletableFuture with no ordering guarantee -- two overlapping async saves
    // (e.g. a periodic SaveManager tick landing during a manual /nodesadmin save) could interleave
    // or race on which one's TaskSaveWorld actually wins, and a failed save silently dropped
    // needsSave back to false as if it had succeeded. Now: state changes happen under
    // saveStateLock, actual disk I/O is serialized through saveQueue (one save runs at a time, in
    // order), and saveRevision/lastQueuedRevision let a save already in flight represent state
    // newer than what's on disk without needing to block every caller on it. A failed save restores
    // needsSave so the next tick retries instead of silently losing the pending change.
    fun saveWorld(
        checkIfNeedsSave: Boolean = true,
        async: Boolean = false,
    ): CompletableFuture<Void> {
        if (!config.save) return CompletableFuture.completedFuture(null)
        val current = System.currentTimeMillis()
        val request =
            try {
                synchronized(saveStateLock) {
                    val dirtyRevision = saveRevision
                    val saveState = !checkIfNeedsSave || (needsSave && dirtyRevision > lastQueuedRevision)
                    val backup = !backupPending && current > lastBackupTime + config.backupPeriod
                    if (!saveState && !backup) {
                        val pending = saveQueue.current()
                        if (!async) pending.join()
                        return pending
                    }

                    val backupTimestamp = current.takeIf { backup }

                    if (saveState) {
                        saveWorldPreprocess()
                        lastQueuedRevision = dirtyRevision
                        SaveRequest(
                            worldTask = TaskSaveWorld(
                                residents.values.map { it.getSaveState() },
                                towns.values.map { it.getSaveState() },
                                nations.values.map { it.getSaveState() },
                                backupTimestamp,
                            ),
                            buildingTask = TaskSaveBuildings(buildings.map { it.getSaveState() }, config.pathBuildings),
                            revision = dirtyRevision,
                            backupTimestamp = backupTimestamp,
                        ).also { if (backupTimestamp != null) backupPending = true }
                    } else {
                        SaveRequest(
                            backupTask = TaskSaveBackup(backupTimestamp!!),
                            backupTimestamp = backupTimestamp,
                        ).also { backupPending = true }
                    }
                }
            } catch (error: Throwable) {
                synchronized(saveStateLock) {
                    needsSave = true
                    backupPending = false
                }
                System.err.println("[Nodes] Failed to prepare world save: ${error.message}")
                error.printStackTrace()
                val failure = CompletableFuture.failedFuture<Void>(error)
                if (!async) failure.join()
                return failure
            }

        val future = enqueueSave(request)
        if (!async) future.join()
        return future
    }

    private fun enqueueSave(request: SaveRequest): CompletableFuture<Void> {
        val future =
            saveQueue.submit {
                val elapsed =
                    measureNanoTime {
                        request.worldTask?.run()
                        request.buildingTask?.run()
                        request.backupTask?.run()
                    }
                println("[Nodes] Saved world in ${elapsed}ns")
            }

        future.whenComplete { _, error ->
            synchronized(saveStateLock) {
                if (error == null) {
                    request.revision?.let { revision -> if (saveRevision == revision) needsSave = false }
                    request.backupTimestamp?.let { timestamp -> lastBackupTime = timestamp }
                } else if (request.revision != null) {
                    needsSave = true
                }
                if (request.backupTimestamp != null) backupPending = false
            }
            if (error != null) {
                System.err.println("[Nodes] Failed to save world: ${error.message}")
                error.printStackTrace()
            }
        }
        return future
    }

    private data class SaveRequest(
        val worldTask: TaskSaveWorld? = null,
        val buildingTask: TaskSaveBuildings? = null,
        val backupTask: TaskSaveBackup? = null,
        val revision: Long? = null,
        val backupTimestamp: Long? = null,
    )

    internal fun saveWorldPreprocess() {
        towns.values.forEach { town -> if (town.income.pushToStorage(false)) town.needsUpdate() }
    }

    // Pulled out of runIncome() so it's directly testable without a live town/territory graph.
    // Always grants the whole-number part of the rate; the leftover fraction is a probabilistic
    // extra unit (e.g. a 2.3-per-cycle rate always grants 2, plus a 30% chance of a 3rd) rather
    // than being silently dropped or always rounded down.
    internal fun rateToAmount(rate: Double): Int {
        if (rate <= 0.0) return 0
        val integer = kotlin.math.floor(rate)
        val fractional = kotlin.math.max(0.0, rate - integer)
        return integer.toInt() + if (fractional > 0.0 && ThreadLocalRandom.current().nextDouble() < fractional) 1 else 0
    }

    /** Cross-domain income engine. */
    fun runIncome() {
        val taxRate = config.taxIncomeRate.coerceIn(0.0, 1.0)
        val keptRate = 1.0 - taxRate
        towns.values.forEach { town ->
            try {
                val own = mutableMapOf<Material, Double>()
                val incomes = HashMap<Town, MutableMap<Material, Double>>()
                incomes[town] = own
                town.territories.forEach { id ->
                    val territory = Territory.fromId(id) ?: return@forEach
                    val territoryIncome = mutableMapOf<Material, Double>()
                    territory.income.forEach { (material, amount) -> territoryIncome[material] = (territoryIncome[material] ?: 0.0) + amount }
                    territory.chunks.forEach { coord ->
                        chunkToBuilding[listOf(coord.x, coord.z)]?.income()?.forEach { (material, amount) -> territoryIncome[material] = (territoryIncome[material] ?: 0.0) + amount }
                    }
                    val warzoneMultiplier = Warzone.multiplierFor(territory)
                    if (warzoneMultiplier != 1.0) {
                        territoryIncome.replaceAll { _, amount -> amount * warzoneMultiplier }
                    }
                    territory.occupier?.let { occupier ->
                        val occupierIncome = incomes.getOrPut(occupier) { mutableMapOf() }
                        territoryIncome.forEach { (material, amount) ->
                            occupierIncome[material] = (occupierIncome[material] ?: 0.0) + amount * taxRate
                            own[material] = (own[material] ?: 0.0) + amount * keptRate
                        }
                    } ?: territoryIncome.forEach { (material, amount) -> own[material] = (own[material] ?: 0.0) + amount }
                }
                incomes.forEach { (_, income) -> income.forEach { (material, amount) -> rateToAmount(amount).takeIf { it > 0 }?.let { Town.addToIncome(town, material, it) } } }
            } catch (err: Exception) {
                println("Error running income for town ${town.name}")
                err.printStackTrace()
            }
        }
        Message.broadcast("Towns have collected income (use \"/t income\" to get)")
    }
}
