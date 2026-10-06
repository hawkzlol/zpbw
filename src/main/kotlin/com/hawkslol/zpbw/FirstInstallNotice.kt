package com.hawkslol.zpbw

/** Eligibility timer only; the caller owns displaying and persisting the notice. */
class FirstInstallNotice {
    private var activeSession: Any? = null
    private var eligibleSince: Long? = null
    private var delivered = false

    fun poll(firstInstall: Boolean, session: Any?, inSkyblock: Boolean, nowNanos: Long): Boolean {
        if (delivered) return false
        if (!firstInstall || session == null || !inSkyblock) {
            reset()
            return false
        }
        if (activeSession !== session) {
            activeSession = session
            eligibleSince = nowNanos
            return false
        }
        val start = eligibleSince ?: nowNanos.also { eligibleSince = it }
        val elapsed = nowNanos - start
        if (elapsed < 0L) {
            eligibleSince = nowNanos
            return false
        }
        if (elapsed < DELAY_NANOS) return false
        delivered = true
        reset()
        return true
    }

    /** Disconnects cancel a pending delay, but cannot redisplay a delivered notice. */
    fun reset() {
        activeSession = null
        eligibleSince = null
    }

    companion object {
        private const val DELAY_NANOS = 4_000_000_000L
    }
}
