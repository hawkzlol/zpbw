package com.hawkslol.zpbw

import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.phys.Vec3
import kotlin.test.*

class ConfirmationPolicyTest {
    private val target=Vec3(-119.5,56.05,-56.5)
    private fun pose(yaw: Float=446.41476f,pitch: Float=74.281975f,velocity: Vec3=Vec3.ZERO,position: Vec3=target)=
        PositionMoveRotation(position,velocity,yaw,pitch)
    @Test fun recordedAbsoluteOldLookIsStillACorrectZeroMotionLanding() {
        val actual=pose(86.414764f,74.23678f)
        val d=ConfirmationPolicy.inspect(pose(),target,actual,emptySet())
        assertTrue(d.canReplay);assertTrue(d.positionMatches);assertFalse(d.rotationMatches)
    }
    @Test fun largeLegitimateAbsoluteAndRelativeLookChangesDoNotAlterLandingOwnership() {
        for(yaw in listOf(-720f,-180f,0f,180f,720f)) for(pitch in listOf(-90f,0f,90f)) {
            val actual=pose(yaw,pitch)
            assertTrue(ConfirmationPolicy.inspect(pose(),target,actual,emptySet()).canReplay)
            assertTrue(ConfirmationPolicy.inspect(pose(),target,actual,setOf(Relative.Y_ROT,Relative.X_ROT)).canReplay)
        }
    }
    @Test fun nonzeroAuthoritativeMotionRequiresNativePhysicsInsteadOfZeroMotionReplay() {
        for(velocity in listOf(Vec3(.12,0.0,0.0),Vec3(0.0,.2,0.0),Vec3(0.0,-.0784,0.0),Vec3(0.0,0.0,-.1))) {
            val d=ConfirmationPolicy.inspect(pose(),target,pose(velocity=velocity),setOf(Relative.Y_ROT,Relative.X_ROT))
            assertTrue(d.motionCorrection);assertFalse(d.canReplay);assertEquals("authoritative_velocity_changed",d.reason)
        }
    }
    @Test fun realPositionMismatchStillRejectsEvenWithValidChangedLook() {
        for(offset in listOf(Vec3(1.0,0.0,0.0),Vec3(0.0,1.0,0.0),Vec3(0.0,0.0,-1.0))) {
            val d=ConfirmationPolicy.inspect(pose(),target,pose(0f,0f,position=target.add(offset)),emptySet())
            assertFalse(d.canReplay);assertFalse(d.motionCorrection);assertEquals("position_mismatch",d.reason)
        }
    }
    @Test fun unsupportedRelativePositionAndVelocityCannotBecomeAnAcceptedWarp() {
        for(flag in Relative.entries.filter { it!=Relative.Y_ROT && it!=Relative.X_ROT }) {
            val d=ConfirmationPolicy.inspect(pose(),target,pose(),setOf(flag))
            assertFalse(d.canReplay);assertFalse(d.motionCorrection);assertEquals("unsupported_relative_flags",d.reason)
        }
    }
    @Test fun nonFiniteServerStateNeverEntersReplayOrPhysicsCorrection() {
        for(actual in listOf(pose(Float.NaN),pose(pitch=Float.POSITIVE_INFINITY),pose(velocity=Vec3(Double.NaN,0.0,0.0)),pose(position=Vec3(Double.POSITIVE_INFINITY,0.0,0.0)))) {
            val d=ConfirmationPolicy.inspect(pose(),target,actual,emptySet())
            assertFalse(d.canReplay);assertFalse(d.motionCorrection);assertEquals("non_finite_confirmation",d.reason)
        }
    }
    @Test fun positionalAndVelocityTolerancesRemainStrict() {
        assertTrue(ConfirmationPolicy.inspect(pose(),target,pose(position=target.add(1e-8,0.0,0.0)),emptySet()).canReplay)
        assertFalse(ConfirmationPolicy.inspect(pose(),target,pose(position=target.add(2e-7,0.0,0.0)),emptySet()).canReplay)
        assertTrue(ConfirmationPolicy.inspect(pose(),target,pose(velocity=Vec3(2e-7,0.0,0.0)),emptySet()).motionCorrection)
    }
    @Test fun unchangedPositionPhysicsChangeIsNotPresentedAsAMissedDestination() {
        val point = PredictionFailureSupport.Point(target.x, target.y, target.z)
        val message=ZpbwMessages.failure(point,point,"authoritative_velocity_changed",true).string
        assertContains(message,"Server motion changed.")
        assertFalse(message.contains("prediction failed"))
        assertContains(message,"Difference: 0.000 blocks.")
        assertContains(ZpbwMessages.failure(point,point,"position_mismatch").string,"Destination differs.")
        val reset = ZpbwMessages.failure(point,point,"unsupported_relative_flags")
        assertContains(reset.string,"Prediction reset error occurred.")
        assertFalse(reset.string.contains("unsupported relative"))
        assertContains(PredictionFailureSupport.explanation("unsupported_relative_flags"),"unsupported relative teleport")
    }
}
