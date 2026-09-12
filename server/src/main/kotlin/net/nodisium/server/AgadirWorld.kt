package net.nodisium.server

import net.minestom.server.instance.InstanceContainer
import net.minestom.server.instance.WorldBorder
import net.minestom.server.instance.anvil.AnvilLoader
import java.nio.file.Path

/**
 * Loads the Agadir Crisis map -- a real downloaded Underilla-sourced Europe world (see
 * `nodisium-data/world`'s PATH comment below), replacing the abandoned WorldPainter/SRTM15+
 * pipeline (see docs/HANDOFF.md for that history; the pipeline itself and `tools/agadir-mapgen`
 * are gone, superseded by this download). Chunks the loader has no data for fall through to
 * whatever Generator the instance was constructed with -- pass [VoidTerrain.generator] for that,
 * paired with [WORLD_BORDER] so players are pushed back before they'd ever see the void (call
 * [attach] to wire both). Node/territory data for this map is built by
 * `tools/nodes-real-borders/` (real country border polygons + this map's own biome data), not
 * hand-typed -- see that directory's README.
 */
object AgadirWorld {
    // Crop box picked in WorldPainter: Britain/France/Germany/Spain/Morocco, the actual 1911
    // Agadir Crisis participants -- corners (-4000,-2000) and (2500,4500) in the map's own block
    // space. Square (6500x6500), so a single diameter covers both axes exactly.
    val WORLD_BORDER = WorldBorder(6500.0, -750.0, 1250.0, 5, 15)
    // nodisium-data/world is a full Minecraft world save folder (level.dat, region/, entities/,
    // session.lock) -- now sourced from the Underilla Europe download (cropped to the box above),
    // copied in as-is, standard single-player layout. Re-disassembled AnvilLoader(Path) for this
    // (2026.07.12-26.2) since the prior comment here claimed it resolves
    // dimensions/<namespace>/<value>/region -- false for this constructor, it only ever does
    // path.resolve("level.dat") and path.resolve("region"), nothing else. So PATH must be a folder
    // with region/ directly inside it, exactly the shape this download already has (no dimensions/
    // subfolder needed, unlike the old wpscript export or the pvp-playtest map -- see Main.kt).
    //
    // Regenerate/recopy if this directory is ever missing (nodisium-data/ is gitignored -- this
    // world is not committed).
    //
    // NODISIUM_WORLD_PATH overrides this for fast iteration against a small test map (see
    // tools/agadir-mapgen/agadir-import-test.js) without touching the real world or needing a
    // separate Main-like entry point -- e.g.
    // NODISIUM_WORLD_PATH=nodisium-data/test-world ./gradlew.bat :server:run
    val PATH: String = System.getenv("NODISIUM_WORLD_PATH") ?: "nodisium-data/world"

    fun attach(instance: InstanceContainer) {
        instance.setChunkLoader(AnvilLoader(Path.of(PATH)))
        instance.setWorldBorder(WORLD_BORDER)
    }
}
