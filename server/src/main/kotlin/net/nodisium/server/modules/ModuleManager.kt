package net.nodisium.server.modules

import java.nio.file.Path
import kotlin.io.path.exists

/**
 * Boots and hot-reloads the four game modules (`vanilla`, `combat`, `worldedit`, `nodes`) from
 * their own jars instead of compiling them into the core server jar -- see
 * docs on `ModuleClassLoader` for why that split is required for a reload to actually pick up new
 * code. Hand-written for this project's exact (tiny) dependency graph rather than a port of
 * upstream's general multi-module resolver: there is exactly one edge (`nodes` needs `vanilla`'s
 * classes), so a static map is simpler and clearer than a general graph solver.
 *
 * Every public entry point that touches [generations] runs under [lock] -- `load`/`reload`/
 * `shutdownAll` fully serialize against each other, so two admins reloading at once (or a reload
 * racing the boot-time `load()` or the JVM shutdown hook's `shutdownAll()`) can't race on the same
 * map and orphan a classloader that never gets closed.
 */
object ModuleManager {
    private data class Generation(
        val classLoader: ModuleClassLoader,
        val instance: HotSwappableModule,
    )

    private data class ModuleDescriptor(
        val id: String,
        val implementationClass: String,
        val dependsOn: Set<String> = emptySet(),
    )

    private val descriptors = listOf(
        ModuleDescriptor("vanilla", "net.aechronis.vanilla.VanillaLiveModule"),
        ModuleDescriptor("combat", "net.nodisium.combat.CombatLiveModule"),
        ModuleDescriptor("worldedit", "io.github.openminigameserver.worldedit.WorldEditLiveModule"),
        // nodes also depends on combat: modules/nodes/src/.../testing/TestWeapons.kt is
        // cross-module test-harness glue (guns whose usableZones need real Territory data) that
        // moved in from server's own Main.kt once server stopped compiling against any module --
        // see that file's kdoc.
        ModuleDescriptor("nodes", "net.aechronis.nodes.NodesLiveModule", dependsOn = setOf("vanilla", "combat")),
    ).associateBy { it.id }

    // Dependency-safe boot order, computed once from the static graph above rather than hardcoded
    // twice -- topological sort over a 4-node graph, plain enough not to need a library for it.
    private val bootOrder: List<String> = run {
        val ordered = LinkedHashSet<String>()
        fun visit(id: String) {
            if (id in ordered) return
            descriptors.getValue(id).dependsOn.forEach(::visit)
            ordered += id
        }
        descriptors.keys.forEach(::visit)
        ordered.toList()
    }

    private val lock = Any()
    private val generations = HashMap<String, Generation>()
    private var loaded = false
    private lateinit var context: ModuleContext

    private val modulesDirectory: Path
        get() = Path.of(System.getProperty("nodisium.modulesDirectory", "nodisium-data/modules"))

    fun load(context: ModuleContext) = synchronized(lock) {
        check(!loaded) { "ModuleManager.load() already ran -- use reload(id) to swap a module afterward" }
        loaded = true
        this.context = context
        bootOrder.forEach(::loadOneModule)
    }

    fun ids(): List<String> = bootOrder

    /**
     * Reloads [id] and every module that (transitively) depends on it, in dependency order.
     * On failure, everything already shut down for this attempt stays unloaded (not resurrected --
     * an already-torn-down generation can't safely be un-shut-down), but modules earlier in the
     * cascade that were *already reloaded successfully in this same call* are left running on
     * their new generation rather than torn back down, so a `nodes` failure cascaded from a
     * `vanilla` reload doesn't also take a perfectly good new `vanilla` generation offline. The
     * returned failure names the actual module that failed, not just [id], since those can differ
     * once a cascade is involved.
     */
    fun reload(id: String): Result<Unit> = synchronized(lock) {
        require(id in descriptors) { "Unknown module '$id' -- known modules: ${descriptors.keys}" }
        val affected = affectedBy(id)
        val shutdownOrder = bootOrder.filter { it in affected }.asReversed()
        val reloadOrder = bootOrder.filter { it in affected }

        shutdownOrder.forEach(::shutdownOneModule)
        for (moduleId in reloadOrder) {
            val outcome = runCatching { loadOneModule(moduleId) }
            if (outcome.isFailure) {
                val cause = outcome.exceptionOrNull()
                val message = if (moduleId == id) {
                    "Failed to reload '$moduleId': ${cause?.message}"
                } else {
                    "Failed to reload '$moduleId' (cascaded from '$id'): ${cause?.message}"
                }
                return@synchronized Result.failure(IllegalStateException(message, cause))
            }
        }
        Result.success(Unit)
    }

    /** Every online player currently sees a working generation of every module -- true only once
     * every descriptor has an active generation (a failed reload leaves the failing one and
     * anything after it in the cascade absent, per [reload]'s contract). */
    fun isFullyLoaded(): Boolean = synchronized(lock) { generations.keys.containsAll(descriptors.keys) }

    /** Currently-loaded module ids, for tests and diagnostics. */
    fun loadedIds(): Set<String> = synchronized(lock) { generations.keys.toSet() }

    fun shutdownAll() = synchronized(lock) {
        bootOrder.asReversed().forEach(::shutdownOneModule)
        loaded = false
    }

    private fun affectedBy(id: String): Set<String> {
        val affected = hashSetOf(id)
        var changed = true
        while (changed) {
            changed = false
            descriptors.values.forEach { descriptor ->
                if (descriptor.id !in affected && descriptor.dependsOn.any { it in affected }) {
                    affected += descriptor.id
                    changed = true
                }
            }
        }
        return affected
    }

    // Callers must hold `lock`.
    private fun loadOneModule(id: String) {
        val descriptor = descriptors.getValue(id)
        val jarPath = modulesDirectory.resolve("$id.jar")
        check(jarPath.exists()) { "Module jar not found: $jarPath -- build :modules:$id:jar first" }

        val dependencyLoaders = descriptor.dependsOn.map { dependencyId ->
            generations[dependencyId]?.classLoader
                ?: error("Cannot load '$id': its dependency '$dependencyId' is not currently loaded")
        }
        val classLoader = ModuleClassLoader(jarPath.toUri().toURL(), dependencyLoaders)
        try {
            val instance = Class.forName(descriptor.implementationClass, true, classLoader)
                .getDeclaredConstructor()
                .newInstance() as HotSwappableModule
            instance.initialize(context)
            generations[id] = Generation(classLoader, instance)
        } catch (e: Throwable) {
            classLoader.close()
            throw e
        }
    }

    // Callers must hold `lock`.
    private fun shutdownOneModule(id: String) {
        val generation = generations.remove(id) ?: return
        runCatching {
            generation.instance.prepareForShutdown()
            generation.instance.shutdown()
        }.onFailure { error ->
            System.err.println("[Modules] Error shutting down '$id': ${error.message}")
            error.printStackTrace()
        }
        generation.classLoader.close()
    }
}
