package net.aechronis.watchdog

import net.nodisium.server.modules.HotSwappableModule
import net.nodisium.server.modules.ModuleContext

/** [HotSwappableModule] adapter around [Watchdog]. */
class WatchdogLiveModule : HotSwappableModule {
    override fun initialize(context: ModuleContext) = Watchdog.initialize()

    override fun shutdown() = Watchdog.shutdown()
}
