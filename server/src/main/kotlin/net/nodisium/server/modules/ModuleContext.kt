package net.nodisium.server.modules

import net.minestom.server.coordinate.Pos
import net.minestom.server.instance.Instance

/** Shared boot state a module's `initialize()` may need. Grow only when something real needs it. */
data class ModuleContext(
    val spawnPoint: Pos,
    val instance: Instance,
)
