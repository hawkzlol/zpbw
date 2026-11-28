package com.hawkslol.zpbw

import com.hawkslol.zpbw.geometry.EtherwarpRotationPrediction
import net.minecraft.world.phys.Vec3

/** Immutable same-use evidence. Missing or invalid sent-origin rays retain the prepared prediction. */
internal data class OutboundPredictionComparison(
    val local: Vec3, val sent: Vec3?, val yaw: Float, val pitch: Float,
    val localRay: EtherwarpRotationPrediction?, val sentRay: EtherwarpRotationPrediction?
) {
    val selected get() = sentRay?.takeIf { it.succeeded && it.landingFeetPosition != null }
    val selection get() = if (selected != null) "sent_origin" else "prepared_fallback"

    fun result(actual: Vec3, landingOffset: Double): String {
        fun error(ray: EtherwarpRotationPrediction?) = ray?.takeIf { it.succeeded }
            ?.landingFeetPosition?.add(0.0, landingOffset, 0.0)?.distanceTo(actual)
        return "selection=$selection localError=${error(localRay)} sentError=${error(sentRay)} actual=$actual"
    }
}
