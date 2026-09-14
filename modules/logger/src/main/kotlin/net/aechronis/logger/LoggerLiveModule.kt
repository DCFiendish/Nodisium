package net.aechronis.logger

import net.nodisium.server.modules.HotSwappableModule
import net.nodisium.server.modules.ModuleContext

/** [HotSwappableModule] adapter around [Logger]. */
class LoggerLiveModule : HotSwappableModule {
    override fun initialize(context: ModuleContext) =
        Logger.init(
            LoggerConfig(
                databasePath = "nodisium-data/logger/logger.db",
                originalWorldPath = "nodisium-data/logger/original",
                limit = 999999999,
            ),
        )

    override fun prepareForShutdown() = Logger.close()

    override fun shutdown() = Logger.close()
}
