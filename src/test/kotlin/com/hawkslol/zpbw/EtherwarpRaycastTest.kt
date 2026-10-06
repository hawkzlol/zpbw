package com.hawkslol.zpbw

import com.hawkslol.zpbw.geometry.EtherwarpRaycast
import net.minecraft.SharedConstants
import net.minecraft.core.BlockPos
import net.minecraft.server.Bootstrap
import net.minecraft.world.level.BlockGetter
import net.minecraft.world.level.block.Blocks
import net.minecraft.world.level.block.entity.BlockEntity
import net.minecraft.world.level.block.state.BlockState
import net.minecraft.world.level.material.FluidState
import net.minecraft.world.phys.Vec3
import kotlin.test.*

/** Real 26.2 block shapes in an independent fixture; no shared server predictor or network. */
class EtherwarpRaycastTest {
    companion object { init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() } }
    private class World : BlockGetter {
        val blocks = mutableMapOf<BlockPos, BlockState>()
        var reads = 0
        override fun getBlockEntity(pos: BlockPos): BlockEntity? = null
        override fun getBlockState(pos: BlockPos): BlockState { reads++; return blocks[pos] ?: Blocks.AIR.defaultBlockState() }
        override fun getFluidState(pos: BlockPos): FluidState = getBlockState(pos).fluidState
        override fun getHeight() = 384
        override fun getMinY() = -64
        fun ray(start: Vec3 = Vec3(0.5, 1.27, 0.5), end: Vec3 = Vec3(0.5, 1.27, 10.5)) =
            EtherwarpRaycast(this) { _, _ -> this }.trace(start, end)
    }
    private val target = BlockPos(0, 1, 5)
    private fun world() = World().also { it.blocks[target] = Blocks.STONE.defaultBlockState() }

    @Test fun integerBoundaryStationaryAxesNeverStall() {
        for (x in listOf(-1.0, 0.0, 1.0)) for (y in listOf(1.0, 1.27)) {
            val w = World(); val pos = BlockPos(x.toInt(), 1, 5)
            w.blocks[pos] = Blocks.STONE.defaultBlockState()
            assertEquals(pos, w.ray(Vec3(x, y, 0.5), Vec3(x, y, 10.5)).pos)
            assertTrue(w.reads < 20)
        }
    }
    @Test fun allAxisDirectionsFindFirstBlock() {
        for (axis in 0..2) for (sign in listOf(-1, 1)) {
            val w = World(); val coord = intArrayOf(0, 0, 0); coord[axis] = 5 * sign
            val pos = BlockPos(coord[0], coord[1], coord[2]); w.blocks[pos] = Blocks.STONE.defaultBlockState()
            val end = DoubleArray(3); end[axis] = 10.0 * sign
            assertEquals(pos, w.ray(Vec3.ZERO, Vec3(end[0], end[1], end[2])).pos)
        }
    }
    @Test fun exactCornerKeepsExistingXFirstTieRule() {
        val w = World(); val pos = BlockPos(1, 0, 0); w.blocks[pos] = Blocks.STONE.defaultBlockState()
        assertEquals(pos, w.ray(Vec3(0.5, 0.5, 0.5), Vec3(3.5, 3.5, 3.5)).pos)
    }
    @Test fun emptyZeroLengthRayStopsImmediately() {
        val w = World(); assertEquals("range_end", w.ray(Vec3.ZERO, Vec3.ZERO).reason); assertEquals(1, w.reads)
    }
    @Test fun rangeEndpointIsIncludedButNextVoxelIsNot() {
        val w = world()
        assertFalse(w.ray(end = Vec3(0.5, 1.27, 4.999)).succeeded)
        assertTrue(w.ray(end = Vec3(0.5, 1.27, 5.0)).succeeded)
    }
    @Test fun unsupportedNonfiniteInputNeverReadsWorld() {
        val w = World(); assertEquals("non_finite", w.ray(end = Vec3(Double.NaN, 0.0, 0.0)).reason)
        assertEquals(0, w.reads)
    }
    @Test fun unloadedChunkStopsBeforeAnyRead() {
        val w = world(); val r = EtherwarpRaycast(w) { _, _ -> null }.trace(Vec3.ZERO, Vec3(0.0, 0.0, 10.0))
        assertEquals("unloaded_chunk", r.reason); assertFalse(r.succeeded); assertEquals(0, w.reads)
    }
    @Test fun supportedMaximumDiagonalFitsEvidenceBudget() {
        val w = World(); val r = w.ray(Vec3.ZERO, Vec3(35.218, 35.218, 35.218))
        assertEquals("range_end", r.reason); assertTrue(w.reads < 110)
        assertTrue(r.details.all { it.length < 1600 }); assertTrue(r.details.size <= 13)
    }
    @Test fun unexpectedLongRayIsBounded() {
        val w = World(); val r = w.ray(end = Vec3(0.5, 1.27, 10000.0))
        assertEquals("step_limit", r.reason); assertEquals(192, w.reads)
    }
    @Test fun feetAndHeadSolidObstructionsRejectFirstTarget() {
        for (pos in listOf(target.above(), target.above(2))) {
            val w = world(); w.blocks[pos] = Blocks.STONE.defaultBlockState()
            val r = w.ray(); assertFalse(r.succeeded); assertEquals(target, r.pos)
            assertContains(r.details.first(), "Clear=false")
        }
    }
    @Test fun comparatorAndRepeaterBlockBothClearanceCells() {
        for (block in listOf(Blocks.COMPARATOR, Blocks.REPEATER)) for (pos in listOf(target.above(), target.above(2))) {
            val w = world(); w.blocks[pos] = block.defaultBlockState()
            assertFalse(w.ray().succeeded, "$block at $pos")
        }
    }
    @Test fun vineSignAndBannerAllowBothClearanceCells() {
        for (block in listOf(Blocks.VINE, Blocks.OAK_SIGN, Blocks.OAK_WALL_SIGN, Blocks.BANNER.white(), Blocks.WALL_BANNER.white())) {
            val w = world(); w.blocks[target.above()] = block.defaultBlockState(); w.blocks[target.above(2)] = block.defaultBlockState()
            assertTrue(w.ray().succeeded, "$block")
        }
    }
    @Test fun hookStopsRayButAllowsClearance() {
        val w = world(); val hook = BlockPos(0, 1, 2); w.blocks[hook] = Blocks.TRIPWIRE_HOOK.defaultBlockState()
        assertEquals(hook, w.ray().pos); assertTrue(w.ray().succeeded)
        w.blocks.remove(hook); w.blocks[target.above()] = Blocks.TRIPWIRE_HOOK.defaultBlockState()
        assertTrue(w.ray().succeeded)
    }
    @Test fun unchangedSkullLadderAndPotStillBlockClearance() {
        for (block in listOf(Blocks.PLAYER_HEAD, Blocks.LADDER, Blocks.FLOWER_POT)) {
            val w = world(); w.blocks[target.above()] = block.defaultBlockState(); assertFalse(w.ray().succeeded)
        }
    }
    @Test fun partialAndTallCollisionLandingHeightIsPreserved() {
        for ((block, y) in listOf(Blocks.STONE_SLAB to 2, Blocks.OAK_FENCE_GATE to 3)) {
            val w = world(); w.blocks[target] = block.defaultBlockState()
            assertEquals(y, w.ray().landingFeetY)
        }
    }
    @Test fun fullPathAndTerminalBlockStateAreRecorded() {
        val w = world()
        w.blocks[target] = Blocks.OAK_FENCE_GATE.defaultBlockState()
        w.blocks[target.above(2)] = Blocks.VINE.defaultBlockState()
        w.blocks[target.above(3)] = Blocks.OAK_WALL_SIGN.defaultBlockState()
        val r = w.ray()
        assertContains(r.details.first(), "hit=minecraft:oak_fence_gate")
        assertContains(r.details.first(), "open=false")
        assertContains(r.details.first(), "feet=minecraft:vine")
        assertContains(r.details.first(), "up=false")
        assertContains(r.details.first(), "head=minecraft:oak_wall_sign")
        assertContains(r.details.first(), "facing=north")
        assertContains(r.details.first(), "collisionTop=1.5")
        assertFalse(r.details.first().contains("java.util.stream"))
        assertTrue(r.details.first().length < 1600)
        assertContains(r.details.first(), "feetClear=true")
        assertContains(r.details.last(), "0,1,5:")
    }
}
