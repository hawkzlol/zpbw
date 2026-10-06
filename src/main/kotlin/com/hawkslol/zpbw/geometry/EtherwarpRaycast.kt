package com.hawkslol.zpbw.geometry

import net.minecraft.core.BlockPos
import net.minecraft.core.Direction
import net.minecraft.core.SectionPos
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.*
import net.minecraft.world.level.block.piston.PistonHeadBlock
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.phys.Vec3
import kotlin.math.*

/** Existing voxel geometry with isolated ray/clearance exceptions and bounded diagnostic output.
 * Block exceptions are supported by NoammAddons PR332's author-reported SkyBlock tests;
 * local fixtures verify these rules, not equivalence to a remote server implementation.
 */
class EtherwarpRaycast(private val level: BlockGetter, private val chunk: (Int, Int) -> BlockGetter?) {
    data class Result(val succeeded: Boolean, val pos: BlockPos?, val landingFeetY: Int?,
                      val reason: String, val details: List<String>)
    companion object {
        private const val PASSABLE = 1
        private const val BLOCKS_FEET = 2

        private val etherwarpBlockFlags: IntArray by lazy {
            IntArray(Block.BLOCK_STATE_REGISTRY.size()).apply {
                Block.BLOCK_STATE_REGISTRY.forEach { state ->
                    val block = state.block
                    val passable = when (block) {
                        is AirBlock,
                        is FlowerBlock, is TallGrassBlock, is BushBlock, is TallFlowerBlock, is ShortDryGrassBlock,
                        is TorchBlock, is RedstoneTorchBlock,
                        is TripWireBlock,
                        is RailBlock, is FireBlock, is VineBlock, is LiquidBlock, is SaplingBlock,
                        is CropBlock, is StemBlock, is SeagrassBlock, is TallSeagrassBlock, is SugarCaneBlock,
                        is MushroomBlock, is NetherWartBlock,
                        is RedStoneWireBlock, is ComparatorBlock, is RepeaterBlock,
                        is SmallDripleafBlock, is BigDripleafStemBlock, is DoublePlantBlock, is LeverBlock,
                        is SnowLayerBlock, is BubbleColumnBlock, is GrowingPlantBlock, is PistonHeadBlock,
                        is DryVegetationBlock, is ButtonBlock, is LanternBlock,
                        is SkullBlock, is WallSkullBlock, is LadderBlock, is FlowerPotBlock, is WebBlock,
                        is NetherPortalBlock -> true
                        else -> false
                    }
                    val blocksFeet = block is SkullBlock || block is WallSkullBlock ||
                        block is FlowerPotBlock || block is LadderBlock ||
                        block is ComparatorBlock || block is RepeaterBlock
                    var flags = 0
                    if (passable) flags = flags or PASSABLE
                    if (blocksFeet) flags = flags or BLOCKS_FEET
                    this[Block.getId(state)] = flags
                }
            }
        }

    }

    private fun flags(state: BlockState) = etherwarpBlockFlags[Block.getId(state)]
    private fun clear(state: BlockState): Boolean {
        val block = state.block
        // These stop the ray but do not obstruct the destination's feet/head space.
        if (block is SignBlock || block is BannerBlock || block is WallBannerBlock || block is TripWireHookBlock)
            return true
        return flags(state) and (PASSABLE or BLOCKS_FEET) == PASSABLE
    }

    fun trace(start: Vec3, end: Vec3): Result {
        val path = ArrayList<String>()
        fun finish(ok: Boolean, pos: BlockPos?, y: Int?, reason: String, detail: String = ""): Result =
            Result(ok, pos, y, reason, listOf("reason=$reason visited=${path.size} target=$pos feetY=$y $detail") +
                path.chunked(16).mapIndexed { index, cells -> "pathPart=$index cells=${cells.joinToString(";")}" })
        if (!listOf(start.x, start.y, start.z, end.x, end.y, end.z).all { it.isFinite() })
            return finish(false, null, null, "non_finite")
        var x = floor(start.x).toInt()
        var y = floor(start.y).toInt()
        var z = floor(start.z).toInt()
        val endX = floor(end.x).toInt()
        val endY = floor(end.y).toInt()
        val endZ = floor(end.z).toInt()
        val dx = end.x - start.x
        val dy = end.y - start.y
        val dz = end.z - start.z
        val sx = sign(dx).toInt()
        val sy = sign(dy).toInt()
        val sz = sign(dz).toInt()
        // A stationary axis must never win a boundary comparison, even at an integer origin.
        val tx = if (sx == 0) Double.POSITIVE_INFINITY else abs(1.0 / dx)
        val ty = if (sy == 0) Double.POSITIVE_INFINITY else abs(1.0 / dy)
        val tz = if (sz == 0) Double.POSITIVE_INFINITY else abs(1.0 / dz)
        var mx = if (sx == 0) Double.POSITIVE_INFINITY else abs((x + max(sx, 0) - start.x) * (1.0 / dx))
        var my = if (sy == 0) Double.POSITIVE_INFINITY else abs((y + max(sy, 0) - start.y) * (1.0 / dy))
        var mz = if (sz == 0) Double.POSITIVE_INFINITY else abs((z + max(sz, 0) - start.z) * (1.0 / dz))
        // A supported <=61 block ray crosses at most 109 cells. Bound evidence even for invalid callers.
        repeat(192) {
            val pos = BlockPos(x, y, z)
            val view = chunk(SectionPos.blockToSectionCoord(x), SectionPos.blockToSectionCoord(z))
                ?: return finish(false, pos, null, "unloaded_chunk")
            val state = view.getBlockState(pos)
            val flags = flags(state)
            path.add("$x,$y,$z:${Block.getId(state)}:$flags")
            if (flags and PASSABLE == 0) {
                val top = state.getCollisionShape(level, pos).max(Direction.Axis.Y)
                val baseY = pos.y + max(1, ceil(top).toInt())
                val feet = view.getBlockState(BlockPos(x, baseY, z))
                val head = view.getBlockState(BlockPos(x, baseY + 1, z))
                val feetClear = clear(feet)
                val headClear = clear(head)
                fun describe(s: BlockState) = "${BuiltInRegistries.BLOCK.getKey(s.block)}#${Block.getId(s)}:$s"
                val detail = "hit=${describe(state)} collisionTop=$top feet=${describe(feet)} feetClear=$feetClear head=${describe(head)} headClear=$headClear"
                return finish(feetClear && headClear, pos, baseY,
                    if (!feetClear) "blocked_feet" else if (!headClear) "blocked_head" else "landing", detail)
            }
            if (x == endX && y == endY && z == endZ) return finish(false, null, null, "range_end")
            // Preserve the established X/Y/Z tie order; no corner-rule change in this pass.
            when {
                mx <= my && mx <= mz -> { mx += tx; x += sx }
                my <= mz -> { my += ty; y += sy }
                else -> { mz += tz; z += sz }
            }
        }
        return finish(false, null, null, "step_limit")
    }
}
