package net.nodisium.combat

import net.nodisium.server.modules.HotSwappableModule
import net.nodisium.server.modules.ModuleContext

/** [HotSwappableModule] adapter around [Combat]. */
class CombatLiveModule : HotSwappableModule {
    override fun initialize(context: ModuleContext) = Combat.initialize()

    override fun shutdown() = Combat.shutdown()
}
