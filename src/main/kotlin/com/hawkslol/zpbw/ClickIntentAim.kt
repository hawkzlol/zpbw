package com.hawkslol.zpbw

import net.minecraft.world.phys.Vec3
import kotlin.math.atan2
import kotlin.math.sqrt

/** Re-express the same clicked world point after a confirmed warp changes the origin.
 * Target identity, reach, world state and a fresh native pick are validated by the caller.
 */
object ClickIntentAim {
    fun toward(origin: Vec3, point: Vec3): Pair<Float, Float>? {
        val d = point.subtract(origin)
        if (!d.x.isFinite() || !d.y.isFinite() || !d.z.isFinite() || d.lengthSqr() < 1e-12) return null
        return Math.toDegrees(atan2(-d.x,d.z)).toFloat() to
            (-Math.toDegrees(atan2(d.y,sqrt(d.x*d.x+d.z*d.z)))).toFloat()
    }
}
