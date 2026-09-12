package net.nodisium.server

import me.lucko.spark.minestom.SparkMinestom
import net.aechronis.utils.createTestServer
import net.aechronis.utils.hasPermission
import net.minestom.server.Auth
import net.minestom.server.MinecraftServer
import net.minestom.server.command.ConsoleSender
import net.minestom.server.coordinate.Pos
import net.minestom.server.entity.Player
import net.nodisium.server.modules.ModuleContext
import net.nodisium.server.modules.ModuleManager
import net.nodisium.server.modules.ModulesCommand
import java.nio.file.Path

fun main() {
    // Agadir Crisis map boot: real Underilla-sourced Europe terrain, cropped to the 1911
    // participants' box (see AgadirWorld.kt). VoidTerrain.generator covers any chunk outside the
    // crop, paired with AgadirWorld.WORLD_BORDER so players are pushed back before they'd reach
    // it. Pvp-playtest-map boot (StoneFlatTerrain + FullbrightChunk) is still available, just not
    // attached below -- see git history for that block if it's needed again.
    // Spawn point is the crop box's center (see AgadirWorld.WORLD_BORDER); Y=150 is comfortably
    // above tree canopy at that column (confirmed by scanning the chunk directly -- ground itself
    // is under trees there, not a clean spot to spawn on).
    val spawnPoint = Pos(-750.0, 150.0, 1250.0)
    val instance = createTestServer(
        generator = VoidTerrain.generator,
        spawnPoint = spawnPoint,
        auth = Auth.Offline(),
        port = 25567,
    )
    AgadirWorld.attach(instance)
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
