package net.nodisium.server

import me.lucko.spark.minestom.SparkMinestom
import net.aechronis.utils.createTestServer
import net.aechronis.utils.hasPermission
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.command.ConsoleSender
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.minestom.server.instance.anvil.AnvilLoader
import net.nodisium.server.modules.ModuleContext
import net.nodisium.server.modules.ModuleManager
import net.nodisium.server.modules.ModulesCommand
import java.nio.file.Path

fun main() {
    // Pvp playtest boot: the Nodisium Playtest Map (a purpose-built arena, not the Agadir Crisis
    // terrain -- see AgadirWorld.kt for that one, still available, just not attached below).
    // StoneFlatTerrain.generator still covers any chunk outside the imported box so the world
    // never has unrendered holes. Spawn point per the map author.
    val spawnPoint = Pos(150.0, 105.0, 150.0)
    val instance = createTestServer(
        generator = StoneFlatTerrain.generator,
        spawnPoint = spawnPoint,
        auth = Auth.Offline(),
        port = 25567,
    )
    // The map was exported with the datapack-style dimension layout (dimensions/minecraft/overworld/region/...)
    // rather than the standard single-player format (region/ at the root), so AnvilLoader needs the
    // overworld subfolder directly -- pointing it at the map root finds no region/ and silently falls
    // back to StoneFlatTerrain.generator for every chunk.
    instance.setChunkLoader(AnvilLoader(Path.of("nodisium-data/nodisium-playtest-map/dimensions/minecraft/overworld")))
    instance.setChunkSupplier(::FullbrightChunk)
    // Real permission gating -- see Permissions.kt kdoc. Enabled before any command registers so
    // every hasPermission check from here on resolves against real group data, not a missing provider.
    Permissions.init()

    // nodes/vanilla/combat/worldedit are loaded here as independently hot-swappable modules
    // instead of being called directly -- see ModuleManager's kdoc. Each module's own boot config
    // (warp points, prep zones, NodesConfig, etc.) now lives in that module's own *LiveModule
    // wrapper (e.g. NodesLiveModule, VanillaLiveModule), not here, since this file no longer has a
    // compile dependency on any of their concrete types.
    ModuleManager.load(ModuleContext(spawnPoint = spawnPoint, instance = instance))
    MinecraftServer.getSchedulerManager().buildShutdownTask { ModuleManager.shutdownAll() }
    MinecraftServer.getCommandManager().register(ModulesCommand())

    ResourcePack.init()
    TickMonitor.init()
    // LoadTestBots.init() -- disabled for the pvp playtest: real players don't need the
    // TownA/TownB/NationA/NationB bot fixture, and it recreates those towns on every boot
    // (createTownIfMissing) even after they're wiped from the save data. Now lives at
    // net.aechronis.nodes.testing.LoadTestBots (see NodesLiveModule).
    // Perf profiler -- /spark ..., self-registers its own commands (`.commands(true)`). Same
    // nodisium.<node> permission convention as every other admin command here (see TestGunGive's
    // "nodisium.testgun", backed by the same net.aechronis.utils.hasPermission); console is always
    // allowed, matching every other CommandSender that isn't a Player.
    SparkMinestom.builder(Path.of("nodisium-data/spark"))
        .commands(true)
        .permissionHandler { sender, permission -> sender is ConsoleSender || (sender is Player && sender.hasPermission(permission)) }
        .enable()
    // TestGunGive/PvpKit/KitCommand moved into net.aechronis.nodes.testing (see NodesLiveModule) --
    // they hand out real Gun/Ammo instances, which server no longer has a compile dependency on.
    MinecraftServer.getCommandManager().register(SourceCommand())
    TestMeleeTarget.spawn(instance, spawnPoint)

    println("Nodisium test server ready — port 25567, offline mode")
}
