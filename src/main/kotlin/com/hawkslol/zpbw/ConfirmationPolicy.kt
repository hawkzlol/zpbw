package com.hawkslol.zpbw

import net.minecraft.util.Mth
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.entity.Relative
import net.minecraft.world.phys.Vec3
import kotlin.math.abs

/** Position ownership and replay safety are distinct from the server's authoritative look. */
object ConfirmationPolicy {
    data class Result(val finite: Boolean, val supportedFlags: Boolean, val positionMatches: Boolean,
                      val zeroVelocity: Boolean, val rotationMatches: Boolean) {
        val canReplay get() = finite && supportedFlags && positionMatches && zeroVelocity
        val motionCorrection get() = finite && supportedFlags && positionMatches && !zeroVelocity
        val reason get() = when {
            !finite -> "non_finite_confirmation"
            !supportedFlags -> "unsupported_relative_flags"
            !positionMatches -> "position_mismatch"
            !zeroVelocity -> "authoritative_velocity_changed"
            else -> "matched"
        }
    }
    fun inspect(source: PositionMoveRotation, target: Vec3, actual: PositionMoveRotation, flags: Set<Relative>): Result {
        val position = actual.position(); val velocity = actual.deltaMovement()
        val finite = listOf(position.x, position.y, position.z, velocity.x, velocity.y, velocity.z,
            actual.yRot().toDouble(), actual.xRot().toDouble()).all { it.isFinite() }
        return Result(finite, flags.all { it == Relative.Y_ROT || it == Relative.X_ROT },
            position.distanceToSqr(target) <= 1e-14, velocity.lengthSqr() <= 1e-14,
            abs(Mth.wrapDegrees(actual.yRot() - source.yRot())) <= 1e-4 && abs(actual.xRot() - source.xRot()) <= 1e-4)
    }
}
