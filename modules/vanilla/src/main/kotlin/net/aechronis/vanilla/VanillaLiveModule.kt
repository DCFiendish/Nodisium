package net.aechronis.vanilla

import net.aechronis.vanilla.config.PvpPrepConfig
import net.aechronis.vanilla.objects.PrepZoneConfig
import net.minestom.server.coordinate.BlockVec
import net.nodisium.server.modules.HotSwappableModule
import net.nodisium.server.modules.ModuleContext

/**
 * [HotSwappableModule] adapter around [Vanilla] -- the pvp-prep-zone corners here are the same
 * block that used to live directly in `Main.kt`, moved verbatim (see that file's history for why
 * each box is where it is: exact WorldEdit corners picked in-game at each warp's landing platform).
 */
class VanillaLiveModule : HotSwappableModule {
    override fun initialize(context: ModuleContext) {
        fun prepZone(name: String, cornerOne: BlockVec, cornerTwo: BlockVec) = PrepZoneConfig(
            name = name,
            instance = context.instance,
            cornerOne = cornerOne,
            cornerTwo = cornerTwo,
        )
        val prepZones = listOf(
            prepZone("koth1", BlockVec(193, -25, 251), BlockVec(199, -22, 254)),
            prepZone("koth2", BlockVec(292, -24, 72), BlockVec(295, -21, 66)),
            prepZone("koth3", BlockVec(126, -25, 5), BlockVec(130, -22, 2)),
            prepZone("koth4", BlockVec(9, -23, 207), BlockVec(6, -20, 213)),
            prepZone("spawn", BlockVec(159, 107, 144), BlockVec(144, 104, 159)),
        )
        Vanilla.init(VanillaConfig(path = "nodisium-data/vanilla", pvpPrepConfig = PvpPrepConfig(zones = prepZones)))
    }

    override fun shutdown() = Vanilla.shutdown()
}
