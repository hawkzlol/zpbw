package dev.zpbw.candidate.api

/** Binary compatibility for already-integrated external prediction owners. */
object ExternalPredictionLease {
    @JvmStatic fun protocolVersion() = com.hawkslol.zpbw.api.ExternalPredictionLease.protocolVersion()
    @JvmStatic fun tryAcquire(owner: Any?) = com.hawkslol.zpbw.api.ExternalPredictionLease.tryAcquire(owner)
    @JvmStatic fun owns(owner: Any?) = com.hawkslol.zpbw.api.ExternalPredictionLease.owns(owner)
    @JvmStatic fun release(owner: Any?) = com.hawkslol.zpbw.api.ExternalPredictionLease.release(owner)
}
