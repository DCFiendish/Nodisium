package net.nodisium.server

import me.lucko.luckperms.minestom.CommandRegistry
import me.lucko.luckperms.minestom.LuckPermsMinestom
import net.luckperms.api.LuckPermsProvider
import net.luckperms.api.node.Node
import net.luckperms.api.node.types.InheritanceNode
import net.minestom.server.MinecraftServer
import net.minestom.server.event.player.PlayerSpawnEvent
import java.nio.file.Path

/**
 * Real LuckPerms (ConceptMC's Minestom port, same lib+convention as Aechronis/aechronis's own
 * Server.kt), replacing the no-provider-registered state every `net.aechronis.utils.hasPermission`
 * check has been silently failing closed against (see server/build.gradle.kts's prior comment on
 * why `com.conceptmc:luckperms-minestom` was already a dependency) -- every permission-gated
 * command (`/warp`, `/ec`, `/nda`, `/testgun`, `//` worldedit, ...) currently denies everyone,
 * DCFiendish included, since nothing was ever enabled to answer those checks.
 *
 * Seeds three groups on first boot only (skipped once `staff` already exists, so retuning via
 * `/lp` in-game afterward sticks across restarts): `default` (base gameplay -- every player lands
 * here automatically, LuckPerms' own reserved name), `mod` (inherits default, adds moderation
 * tools), `staff` (inherits mod, gets `*` -- everything, including nodes.admin/worldedit/spark).
 *
 * [ownerUsernames] get `staff` assigned live on their own join instead of at a UUID guessed here
 * at boot -- offline-mode UUIDs come from whatever the connecting client sends in its login packet
 * (confirmed via decompiling Minestom's LoginListener: it trusts ClientLoginStartPacket.profileId()
 * as-is, no server-side "OfflinePlayer:<name>" hashing), so a client-specific UUID scheme (e.g. the
 * nodisium-testclient dev client) won't match that guess. Using `player.uuid` at actual spawn time
 * is the real key LuckPerms checks permissions against, whatever it turns out to be.
 */
object Permissions {
    private val ownerUsernames = setOf("DCFiendish")

    private val defaultNodes = setOf(
        "vanilla.warp", "vanilla.ec", "vanilla.craft", "vanilla.koth", "vanilla.kit",
        "vanilla.list", "vanilla.ignore", "vanilla.music", "vanilla.back",
    )
    private val modNodes = setOf(
        "vanilla.invsee", "vanilla.kill", "vanilla.teleport", "vanilla.gamemode",
        "vanilla.clear", "vanilla.fly", "vanilla.broadcast", "vanilla.whitelist", "vanilla.give",
        "vanilla.vanish.1",
    )

    fun init() {
        LuckPermsMinestom
            .builder(Path.of("nodisium-data/luckperms"))
            .permissionSuggestions(defaultNodes + modNodes + setOf("nodes.admin", "nodisium.testgun", "vanilla.setwarp", "*"))
            .commandRegistry(CommandRegistry.minestom())
            .enable()

        val luckPerms = LuckPermsProvider.get()
        val groupManager = luckPerms.groupManager
        if (groupManager.getGroup("staff") == null) {
            val default = groupManager.createAndLoadGroup("default").join()
            val mod = groupManager.createAndLoadGroup("mod").join()
            val staff = groupManager.createAndLoadGroup("staff").join()

            defaultNodes.forEach { default.data().add(Node.builder(it).build()) }
            mod.data().add(InheritanceNode.builder("default").build())
            modNodes.forEach { mod.data().add(Node.builder(it).build()) }
            staff.data().add(InheritanceNode.builder("mod").build())
            staff.data().add(Node.builder("*").build())

            groupManager.saveGroup(default).join()
            groupManager.saveGroup(mod).join()
            groupManager.saveGroup(staff).join()
        }

        MinecraftServer.getGlobalEventHandler().addListener(PlayerSpawnEvent::class.java) { event ->
            if (!event.isFirstSpawn || event.player.username !in ownerUsernames) return@addListener
            // Node.add is idempotent (returns DataMutateResult.ALREADY_HAS, no duplicate) -- no
            // membership check needed before adding on every join.
            val user = luckPerms.userManager.loadUser(event.player.uuid, event.player.username).join()
            user.data().add(InheritanceNode.builder("staff").build())
            luckPerms.userManager.saveUser(user).join()
        }
    }
}
