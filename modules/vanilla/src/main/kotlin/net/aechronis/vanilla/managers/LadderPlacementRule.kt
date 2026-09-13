package net.aechronis.vanilla.managers

import net.minestom.server.MinecraftServer
import net.minestom.server.coordinate.Point
import net.minestom.server.instance.block.Block
import net.minestom.server.instance.block.BlockFace
import net.minestom.server.instance.block.rule.BlockPlacementRule
import net.minestom.server.utils.Direction
import kotlin.math.cos
import kotlin.math.sin

object LadderFix {
    fun init() {
        val blockManager = MinecraftServer.getBlockManager()
        val existing = blockManager.getBlockPlacementRule(Block.LADDER)
        if (existing is LadderPlacementRule) return
        blockManager.registerBlockPlacementRule(LadderPlacementRule(Block.LADDER, existing))
    }
}

/**
 * Minestom ships no default placement rule for LADDER (see BlockManager -- the registry starts
 * empty), so without this the engine just stamps the clicked face straight onto the "facing"
 * property with no support check at all: clicking the top/bottom of a block (no vertical wall in
 * that direction) still "places" a ladder facing whatever the default state happens to be,
 * attached to a wall that isn't there.
 *
 * Mirrors vanilla LadderBlock.getStateForPlacement: face the clicked side if it has a solid
 * neighbor behind it, otherwise fall back to whichever horizontal side does, preferring the one
 * closest to the direction the player is already facing. No valid side at all -> cancel the
 * placement entirely (return null), same as vanilla.
 */
internal class LadderPlacementRule(
    block: Block,
    private val fallback: BlockPlacementRule?,
) : BlockPlacementRule(block) {
    override fun blockPlace(placementState: PlacementState): Block? {
        val facing = resolveFacing(placementState) ?: return null
        return placementState.block.withProperty("facing", facing.name.lowercase())
    }

    override fun blockUpdate(updateState: UpdateState): Block {
        val facing = Direction.valueOf(updateState.currentBlock.getProperty("facing")!!.uppercase())
        if (hasSupport(updateState.instance, updateState.blockPosition, facing)) return updateState.currentBlock
        return fallback?.blockUpdate(updateState) ?: Block.AIR
    }

    override fun isSelfReplaceable(replacement: Replacement): Boolean = fallback?.isSelfReplaceable(replacement) ?: false

    override fun maxUpdateDistance(): Int = fallback?.maxUpdateDistance() ?: super.maxUpdateDistance()

    private fun resolveFacing(state: PlacementState): Direction? {
        val clicked = state.blockFace?.toDirection()
        if (clicked != null && clicked.horizontal() && hasSupport(state.instance, state.placePosition, clicked)) return clicked

        val yaw = state.playerPosition?.yaw ?: 0f
        return Direction.HORIZONTAL
            .filter { it != clicked }
            .sortedByDescending { lookAlignment(it, yaw) }
            .firstOrNull { hasSupport(state.instance, state.placePosition, it) }
    }

    private fun hasSupport(
        instance: Block.Getter,
        placePosition: Point,
        facing: Direction,
    ): Boolean {
        val wall = placePosition.relative(BlockFace.fromDirection(facing.opposite()))
        return instance.getBlock(wall).isSolid()
    }

    /** How closely [direction] matches the player's own horizontal look direction -- higher is closer. */
    private fun lookAlignment(
        direction: Direction,
        yaw: Float,
    ): Double {
        val yawRad = Math.toRadians(-yaw.toDouble())
        val lookX = sin(yawRad)
        val lookZ = cos(yawRad)
        return direction.normalX() * lookX + direction.normalZ() * lookZ
    }
}
