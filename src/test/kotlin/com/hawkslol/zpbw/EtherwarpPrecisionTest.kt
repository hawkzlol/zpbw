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
import kotlin.math.*
import kotlin.random.Random
import kotlin.test.*

class EtherwarpPrecisionTest {
    companion object { init { SharedConstants.tryDetectVersion(); Bootstrap.bootStrap() } }
    private class World(private val floorY: Int? = null) : BlockGetter {
        val blocks=mutableMapOf<BlockPos,BlockState>()
        var loaded=true;var reads=0
        override fun getBlockEntity(pos:BlockPos):BlockEntity?=null
        override fun getBlockState(pos:BlockPos):BlockState {
            reads++
            return blocks[pos] ?: if(floorY!=null && pos.y<=floorY) Blocks.GRASS_BLOCK.defaultBlockState() else Blocks.AIR.defaultBlockState()
        }
        override fun getFluidState(pos:BlockPos):FluidState=getBlockState(pos).fluidState
        override fun getHeight()=384
        override fun getMinY()=-64
        fun caster()=EtherwarpRaycast(this) { _,_->if(loaded)this else null }
        fun trace(start:Vec3,yaw:Float,pitch:Float,eye:Double=0.0,capture:Boolean=false):EtherwarpRaycast.Result {
            val from=start.add(0.0,eye,0.0)
            return caster().trace(from,from.add(EtherwarpRaycast.direction(yaw,pitch).scale(61.0)),capture)
        }
    }

    @Test fun capturedRisingBoundarySelectsTheReportedAdjacentBlockWithDoubleDirection() {
        // Integer-translated reproduction; no account/server/world identity is embedded.
        val w=World(0);val feet=Vec3(7.5,2.94119998016357,6.5)
        val eye=1.2699999809265137;val yaw=104.67719f;val pitch=18.110376f
        val start=feet.add(0.0,eye,0.0)
        val old=w.caster().trace(start,start.add(Vec3.directionFromRotation(pitch,yaw).scale(61.0)))
        assertEquals(BlockPos(-3,0,4),old.pos, "Both released native sine tables reproduce the old one-block error")
        assertEquals(BlockPos(-2,0,4),w.trace(feet,yaw,pitch,eye).pos)
    }

    @Test fun doubleDirectionMatchesIndependentAnalyticalPlaneIntersectionsAcrossMovingCoordinates() {
        val random=Random(0x51A7);val w=World(0);var evaluated=0
        repeat(4096) {
            val start=Vec3(random.nextDouble(-200.0,200.0),random.nextDouble(1.3,14.0),random.nextDouble(-200.0,200.0))
            val yaw=random.nextDouble(-1080.0,1080.0).toFloat();val pitch=random.nextDouble(15.0,85.0).toFloat()
            // Independent closed-form intersection; no voxel traversal or native sine lookup.
            val p=pitch.toDouble()*Math.PI/180.0;val y=yaw.toDouble()*Math.PI/180.0
            val distance=(start.y-1.0)/sin(p)
            if(distance>60.99)return@repeat
            val x=start.x-distance*cos(p)*sin(y);val z=start.z+distance*cos(p)*cos(y)
            if(abs(x-round(x))<1e-8 || abs(z-round(z))<1e-8)return@repeat
            val hit=w.trace(start,yaw,pitch)
            assertTrue(hit.succeeded)
            assertEquals(BlockPos(floor(x).toInt(),0,floor(z).toInt()),hit.pos,"yaw=$yaw pitch=$pitch from=$start")
            evaluated++
        }
        assertTrue(evaluated>4000)
    }

    @Test fun directionHandlesCardinalAndWrappedAnglesWithoutArtificialTranslation() {
        for(yaw in listOf(-720f,-360f,0f,360f,720f)) {
            val v=EtherwarpRaycast.direction(yaw,0f)
            assertEquals(0.0,v.x,1e-12);assertEquals(0.0,v.y,1e-12);assertEquals(1.0,v.z,1e-12)
        }
        for(pitch in listOf(-90f,90f)) {
            val v=EtherwarpRaycast.direction(143.5f,pitch)
            assertEquals(-sign(pitch.toDouble()),v.y,1e-12)
            assertTrue(abs(v.x)<1e-12 && abs(v.z)<1e-12)
        }
    }

    @Test fun capturedFallingCaseSeparatesSourceHeightUncertaintyFromThePrecisionRepair() {
        val w=World(0);val feet=Vec3(9.95696894016524,5.77343236499993,20.6268220930114)
        assertEquals(BlockPos(0,0,3),w.trace(feet,-209.63086f,17.235758f,1.2699999809265137).pos)
        for(height in listOf(1.54,1.62))
            assertEquals(BlockPos(-1,0,2),w.trace(feet,-209.63086f,17.235758f,height).pos)
        // These are diagnostic hypotheses, not evidence to change every warp's eye height.
    }

    @Test fun globallyChangingEyeHeightWouldRegressAnAlreadyMatchedGroundedCase() {
        val w=World(0);val feet=Vec3(4.910534274653617,1.0,5.841855355297323)
        assertEquals(BlockPos(-1,0,11),w.trace(feet,43.760338f,8.746495f,1.2699999809265137).pos)
        assertNotEquals(BlockPos(-1,0,11),w.trace(feet,43.760338f,8.746495f,1.54).pos)
    }

    @Test fun capturedRayEvidenceDetectsOnlyActualCellChangesIncludingDestinationClearance() {
        val w=World();val target=BlockPos(0,1,5);w.blocks[target]=Blocks.STONE.defaultBlockState()
        val start=Vec3(.5,1.27,.5);val end=Vec3(.5,1.27,10.5)
        val snapshot=assertNotNull(w.caster().trace(start,end,true).snapshot)
        assertTrue(w.caster().changedCells(snapshot).isEmpty())
        w.blocks[BlockPos(30,10,30)]=Blocks.STONE.defaultBlockState()
        assertTrue(w.caster().changedCells(snapshot).isEmpty(),"Unrelated world changes are not ray mutations")
        w.blocks[target.above()]=Blocks.STONE.defaultBlockState()
        assertEquals(listOf(target.above()),w.caster().changedCells(snapshot).map {it.first.pos})
        assertEquals(Blocks.AIR.defaultBlockState(),net.minecraft.world.level.block.Block.BLOCK_STATE_REGISTRY.byId(snapshot.cells.last { it.pos==target.above() }.stateId))
    }

    @Test fun unavailableCapturedChunksRemainUnavailableWithoutLoadingOrReadingThem() {
        val w=World(0);val snapshot=assertNotNull(w.trace(Vec3(.5,3.0,.5),0f,45f,capture=true).snapshot)
        val reads=w.reads;w.loaded=false
        val unavailable=w.caster().changedCells(snapshot)
        assertEquals(snapshot.cells.size,unavailable.size);assertTrue(unavailable.all {it.second==null})
        assertEquals(reads,w.reads)
    }

    @Test fun preflightsDoNotRetainCellEvidenceAndIssuedSnapshotsAreBounded() {
        val w=World(0);assertNull(w.trace(Vec3(.5,3.0,.5),0f,45f).snapshot)
        val snapshot=assertNotNull(w.trace(Vec3(.5,3.0,.5),0f,45f,capture=true).snapshot)
        assertTrue(snapshot.cells.size in 1..194)
    }
}
