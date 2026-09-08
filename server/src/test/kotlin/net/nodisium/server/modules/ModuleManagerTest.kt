package net.nodisium.server.modules

import net.aechronis.utils.createTestServer
import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Pos
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * Exercises ModuleManager against the real jars `syncModuleJars` builds (see server/build.gradle.kts
 * -- `tasks.test` depends on it), not mocks: real classloading, real initialize()/shutdown() calls
 * on vanilla/combat/worldedit/nodes. One test method, not several -- ModuleManager is a singleton
 * with real global state (loaded generations), so splitting this into independent @Test methods
 * would make them order-dependent on the same JVM's shared state instead of actually independent.
 */
class ModuleManagerTest {
    @Test
    fun `boots all four modules and survives repeated reloads, including both dependency cascades`() {
        val instance = createTestServer()
        val context = ModuleContext(spawnPoint = Pos(0.0, 64.0, 0.0), instance = instance)

        // Same registration Main.kt uses for the real boot path -- a graceful MinecraftServer stop
        // (not a force-kill) must actually reach ModuleManager.shutdownAll() through this hook, not
        // just when shutdownAll() is called directly (which is all the end of this test used to
        // check, and wouldn't have caught the hook itself being wired wrong or never firing).
        MinecraftServer.getSchedulerManager().buildShutdownTask { ModuleManager.shutdownAll() }

        ModuleManager.load(context)
        assertTrue(ModuleManager.isFullyLoaded(), "all four modules should be loaded after boot")

        // load() must refuse a second call rather than silently leaking the first boot's
        // classloaders/listeners (nothing would ever shut them down otherwise).
        assertFailsWith<IllegalStateException> { ModuleManager.load(context) }

        // Leaf modules (nothing depends on them) -- reloading either must not disturb the rest.
        assertTrue(ModuleManager.reload("worldedit").isSuccess, "worldedit should reload cleanly")
        assertTrue(ModuleManager.isFullyLoaded())

        // nodes depends on both vanilla and combat (see ModuleManager's nodes descriptor) --
        // reloading either dependency must cascade-reload nodes automatically, not leave it
        // running against an orphaned classloader from the dependency's old generation.
        assertTrue(ModuleManager.reload("vanilla").isSuccess, "vanilla should reload cleanly")
        assertTrue(ModuleManager.isFullyLoaded(), "reloading vanilla should cascade-reload nodes")

        assertTrue(ModuleManager.reload("combat").isSuccess, "combat should reload cleanly")
        assertTrue(ModuleManager.isFullyLoaded(), "reloading combat should cascade-reload nodes")

        // Repeat a leaf (nodes has no dependents, so this is a plain single-module reload) a few
        // times in a row -- a single successful reload can hide something that only shows up on a
        // second or third pass (duplicate command registration, leaked listeners, an
        // accumulating shutdown task).
        repeat(3) {
            assertTrue(ModuleManager.reload("nodes").isSuccess, "nodes should reload cleanly on repeat $it")
        }
        assertTrue(ModuleManager.isFullyLoaded())

        // Real graceful stop, not a direct shutdownAll() call -- proves the shutdown task
        // registered above actually runs and actually tears every module down, not just that
        // shutdownAll() works when called directly.
        MinecraftServer.stopCleanly()
        assertTrue(ModuleManager.loadedIds().isEmpty(), "a graceful server stop should shut down every module")
    }
}
