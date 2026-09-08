package io.github.openminigameserver.worldedit

import io.github.openminigameserver.worldedit.platform.config.WorldEditConfig
import net.nodisium.server.modules.HotSwappableModule
import net.nodisium.server.modules.ModuleContext
import java.io.File

/**
 * [HotSwappableModule] adapter around [MinestomWorldEdit]. Unlike the other three modules,
 * [MinestomWorldEdit] is a class (one instance per generation), not a singleton object, so this
 * wrapper holds the live instance itself rather than delegating to a shared object.
 */
class WorldEditLiveModule : HotSwappableModule {
    private lateinit var worldEdit: MinestomWorldEdit

    override fun initialize(context: ModuleContext) {
        worldEdit = MinestomWorldEdit()
        worldEdit.init(WorldEditConfig(dataFolder = File("nodisium-data/worldedit")))
    }

    override fun prepareForShutdown() = worldEdit.prepareForShutdown()

    override fun shutdown() = worldEdit.shutdown()
}
