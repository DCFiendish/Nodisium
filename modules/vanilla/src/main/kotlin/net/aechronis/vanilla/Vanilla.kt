package net.aechronis.vanilla

import net.aechronis.vanilla.commands.Back
import net.aechronis.vanilla.commands.Broadcast
import net.aechronis.vanilla.commands.Clear
import net.aechronis.vanilla.commands.Convert
import net.aechronis.vanilla.commands.Craft
import net.aechronis.vanilla.commands.EnderChest
import net.aechronis.vanilla.commands.Fly
import net.aechronis.vanilla.commands.GameMode
import net.aechronis.vanilla.commands.Give
import net.aechronis.vanilla.commands.Gm
import net.aechronis.vanilla.commands.Ignore
import net.aechronis.vanilla.commands.InventorySee
import net.aechronis.vanilla.commands.Kill
import net.aechronis.vanilla.commands.KothCommand
import net.aechronis.vanilla.commands.List
import net.aechronis.vanilla.commands.Message
import net.aechronis.vanilla.commands.Music
import net.aechronis.vanilla.commands.Reply
import net.aechronis.vanilla.commands.SetWarpCommand
import net.aechronis.vanilla.commands.Teleport
import net.aechronis.vanilla.commands.Vanish
import net.aechronis.vanilla.commands.Vote
import net.aechronis.vanilla.commands.WarpCommand
import net.aechronis.vanilla.commands.Whitelist
import net.aechronis.vanilla.listeners.BlockPlacementCooldownListener
import net.aechronis.vanilla.listeners.CombatInventoryListener
import net.aechronis.vanilla.listeners.CommandsListener
import net.aechronis.vanilla.listeners.FallDamageListener
import net.aechronis.vanilla.listeners.MovementAntiCheatListener
import net.aechronis.vanilla.listeners.PlayerBreakListener
import net.aechronis.vanilla.listeners.ServerLinksListener
import net.aechronis.vanilla.managers.Blocks
import net.aechronis.vanilla.managers.Bundles
import net.aechronis.vanilla.managers.Combat
import net.aechronis.vanilla.managers.Crops
import net.aechronis.vanilla.managers.Elevator
import net.aechronis.vanilla.managers.EnvironmentalDamage
import net.aechronis.vanilla.managers.Filter
import net.aechronis.vanilla.managers.Food
import net.aechronis.vanilla.managers.ItemFrames
import net.aechronis.vanilla.managers.Items
import net.aechronis.vanilla.managers.Koth
import net.aechronis.vanilla.managers.Mannequin
import net.aechronis.vanilla.managers.PlayerData
import net.aechronis.vanilla.managers.PvpPrep
import net.aechronis.vanilla.managers.Recipes
import net.aechronis.vanilla.managers.Saplings
import net.aechronis.vanilla.managers.Shelves
import net.aechronis.vanilla.managers.Signs
import net.aechronis.vanilla.managers.Storage
import net.aechronis.vanilla.managers.TreeFeller
import net.aechronis.vanilla.managers.VoteLinks
import net.aechronis.vanilla.managers.Warp
import net.minestom.server.MinecraftServer
import net.minestom.server.command.builder.Command
import net.minestom.server.event.EventNode
import java.nio.file.Path
import java.util.concurrent.atomic.AtomicBoolean
import net.aechronis.vanilla.managers.Music as MusicManager
import net.aechronis.vanilla.managers.Vanish as VanishManager
import net.aechronis.vanilla.managers.Whitelist as WhitelistManager

object Vanilla {
    val eventNode = EventNode.all("vanilla")
    lateinit var config: VanillaConfig
        private set

    private val initialized = AtomicBoolean(false)
    private var registeredCommands: kotlin.collections.List<Command> = emptyList()

    fun init(c: VanillaConfig = VanillaConfig()) {
        check(initialized.compareAndSet(false, true)) { "Vanilla is already initialized -- call shutdown() first" }
        config = c
        // measure load time
        val timeStart = System.currentTimeMillis()

        MinecraftServer.getGlobalEventHandler().addChild(eventNode)

        if (config.commandsEnabled) {
            val commands =
                mutableListOf(
                    Back(),
                    Message(),
                    Reply(),
                    GameMode(),
                    Give(),
                    Teleport(),
                    Fly(),
                    Kill(),
                    Broadcast(),
                    Clear(),
                    EnderChest(),
                    InventorySee(),
                    Ignore(),
                    Gm(),
                    List(),
                )
            if (config.musicEnabled) commands += Music()
            if (config.blocksEnabled) commands += Convert()
            if (config.recipesEnabled) commands += Craft()
            if (config.whitelistEnabled) commands += Whitelist()
            if (config.kothEnabled) commands += KothCommand()
            if (config.warpEnabled) commands += listOf(WarpCommand(), SetWarpCommand())
            if (config.vanishEnabled) commands += Vanish()
            if (config.voteEnabled) commands += Vote()
            registeredCommands = commands
            MinecraftServer.getCommandManager().register(*commands.toTypedArray())
        }
        println("Loading Vanilla")
        if (config.filterEnabled) Filter.init()
        if (config.playerDataEnabled) PlayerData.init(Path.of(config.path, config.playerDataPath))
        if (config.storageEnabled) Storage.init(Path.of(config.path, config.storagePath))
        if (config.signsEnabled) Signs.init()
        if (config.shelvesEnabled) Shelves.init()
        if (config.itemFramesEnabled) ItemFrames.init()
        if (config.whitelistEnabled) WhitelistManager.init(Path.of(config.path, config.whitelistPath))
        if (config.recipesEnabled) Recipes.init()
        if (config.cropsEnabled) Crops.init()
        if (config.saplingsEnabled) Saplings.init()
        if (config.elevatorEnabled) Elevator.init()
        if (config.mannequinEnabled) Mannequin.init()
        if (config.blocksEnabled) Blocks.init()
        if (config.treeFellerEnabled) TreeFeller.init()
        if (config.foodEnabled) Food.init()
        if (config.itemsEnabled) Items.init()
        if (config.bundlesEnabled) Bundles.init()
        if (config.commandsEnabled) CommandsListener.init()
        if (config.blockDropsEnabled) PlayerBreakListener.init()
        if (config.fallDamageEnabled) FallDamageListener.init()
        if (config.movementAntiCheatEnabled) MovementAntiCheatListener.init()
        if (config.blockPlacementCooldownEnabled) BlockPlacementCooldownListener.init()
        if (config.fireDamageEnabled || config.drowningEnabled) EnvironmentalDamage.init()
        if (config.serverLinksEnabled) ServerLinksListener.init()
        if (config.combatEnabled) {
            Combat.init()
            CombatInventoryListener.init()
        }
        if (config.musicEnabled) MusicManager.init()
        if (config.kothEnabled) Koth.init()
        if (config.warpEnabled) Warp.init(Path.of(config.path, config.warpsPath))
        if (config.pvpPrepEnabled) PvpPrep.init()
        if (config.vanishEnabled) VanishManager.init()
        if (config.voteEnabled) VoteLinks.init(Path.of(config.path, config.votePath))

        // print load time
        val timeEnd = System.currentTimeMillis()
        val timeLoad = timeEnd - timeStart
        println("└─ Vanilla Loaded in ${timeLoad}ms")
    }

    /**
     * Symmetric inverse of [init] -- unregisters commands, detaches [eventNode] (which covers
     * every manager that only ever added listeners to it), stops every manager that runs its own
     * background [net.minestom.server.timer.Task] (those aren't cancelled by detaching eventNode),
     * and does each manager's final save. Safe to call even if a given subsystem was never enabled
     * (each stop() is idempotent on an unstarted manager -- cancelling a null task is a no-op).
     */
    fun shutdown() {
        if (!initialized.compareAndSet(true, false)) return
        println("Vanilla: shutting down...")

        registeredCommands.forEach(MinecraftServer.getCommandManager()::unregister)
        registeredCommands = emptyList()
        MinecraftServer.getGlobalEventHandler().removeChild(eventNode)

        // Every manager below runs its own Task outside of eventNode's listener tree, so
        // detaching eventNode alone would leave these running against torn-down state.
        Koth.stop()
        Combat.stop()
        Saplings.stop()
        Crops.stop()
        Food.stop()
        EnvironmentalDamage.stop()
        Storage.stop()
        PlayerData.stop()
        Warp.stop()

        println("Vanilla: data saved.")
    }
}
