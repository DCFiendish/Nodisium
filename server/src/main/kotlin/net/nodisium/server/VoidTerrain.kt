package net.nodisium.server

import net.minestom.server.instance.generator.Generator

/**
 * No-op generator -- every chunk stays air. Paired with [AgadirWorld.WORLD_BORDER] so players are
 * pushed back by the border before they'd ever see the void, unlike [StoneFlatTerrain] (solid
 * fallback, used for the separate pvp-playtest arena where there's no crop to hide).
 */
object VoidTerrain {
    val generator = Generator { }
}
