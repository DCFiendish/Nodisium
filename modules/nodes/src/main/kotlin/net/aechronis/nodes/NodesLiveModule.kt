package net.aechronis.nodes

import net.aechronis.nodes.testing.KitCommand
import net.aechronis.nodes.testing.PvpKit
import net.aechronis.nodes.testing.TestGunGive
import net.aechronis.nodes.testing.TestWeapons
import net.minestom.server.MinecraftServer
import net.nodisium.server.modules.HotSwappableModule
import net.nodisium.server.modules.ModuleContext
import java.nio.file.Files
import java.nio.file.Paths

/**
 * [HotSwappableModule] adapter around [Nodes] -- the boot config here is the same block that used
 * to live directly in `Main.kt`, moved verbatim so it travels with the module through a reload
 * instead of living on the core classloader (which can no longer reference [NodesConfig] at all
 * once `nodes` is off `server`'s compile classpath). Also owns the local-playtest tooling
 * (`testing/` package) that used to live directly in `Main.kt`/`server` for the same reason --
 * those files reach into both `nodes` and `combat` concrete types, which server can no longer do.
 */
class NodesLiveModule : HotSwappableModule {
    private val testCommands = listOf(TestGunGive(), KitCommand())

    override fun initialize(context: ModuleContext) {
        Nodes.initialize(
            NodesConfig(
                path = "nodisium-data/nodes",
                chunkAttackTime = 7500,
                defaultRespawnPoint = context.spawnPoint,
                canInteractInEmpty = false,
                canInteractInUnclaimed = false,
                adminUsernames = setOf("DCFiendish"),
                discordWarWebhookUrl = readPlainConfigFile("discord_war_webhook.txt"),
                discordChatWebhookUrl = readPlainConfigFile("discord_chat_webhook.txt"),
                dailyStatsEnabled = true,
                // Null (git publish skipped, local file still written) until the repo/deploy
                // key are set up on the VM -- see docs/STATS.md. Same plain-file pattern as
                // the webhook URLs above: not committed to source, not an env var (this
                // deploy's Pterodactyl egg has no such startup variable).
                dailyStatsGitRepoPath = readPlainConfigFile("daily_stats_git_repo_path.txt")?.let(java.nio.file.Paths::get),
            ),
        )
        TestWeapons.register()
        PvpKit.init()
        testCommands.forEach(MinecraftServer.getCommandManager()::register)
    }

    // Plain file, not an env var -- see the field doc on NodesConfig.discordWarWebhookUrl.
    private fun readPlainConfigFile(fileName: String): String? =
        runCatching { Files.readString(Paths.get("nodisium-data/$fileName")).trim() }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }

    override fun shutdown() {
        testCommands.forEach(MinecraftServer.getCommandManager()::unregister)
        PvpKit.stop()
        Nodes.cleanup()
    }
}
