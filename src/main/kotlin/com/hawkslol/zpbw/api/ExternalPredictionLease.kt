package com.hawkslol.zpbw.api

import com.hawkslol.zpbw.ZpbwRuntime

/** Reflection-stable Java-static API. See EXTERNAL-PREDICTION-LEASE.md for caller obligations. */
object ExternalPredictionLease {
    @JvmStatic fun protocolVersion(): Int = 1
    @JvmStatic fun tryAcquire(owner: Any?): Boolean = ZpbwRuntime.acquireExternalPrediction(owner)
    @JvmStatic fun owns(owner: Any?): Boolean = ZpbwRuntime.ownsExternalPrediction(owner)
    @JvmStatic fun release(owner: Any?): Boolean = ZpbwRuntime.releaseExternalPrediction(owner)
}
