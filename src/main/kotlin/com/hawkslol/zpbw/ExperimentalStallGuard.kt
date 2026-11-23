package com.hawkslol.zpbw

/** Timing observations only. A receipt burst cannot erase an issued generation's outage. */
internal class ExperimentalStallGuard<C : Any> {
    class Stamp internal constructor(internal val binding: Any, internal val serial: Long, internal val issued: Long)
    private var session: C? = null
    private var binding = Any()
    private var lastPing: Long? = null
    private var outage = false
    private var serial = 0L
    private var healthySince: Long? = null
    @Synchronized fun bind(connection: C?) {
        session = connection; binding = Any(); lastPing = null
        outage = false; serial = 0; healthySince = null
    }
    @Synchronized fun unbind(connection: C) { if (session === connection) bind(null) }
    private fun observeGap(now: Long) {
        if (!outage && lastPing?.let { now - it >= PING_GAP_NS } == true) {
            outage = true; serial++; healthySince = null
        }
    }
    @Synchronized fun ping(connection: C, id: Int, now: Long) {
        if (session !== connection || id == 0) return
        observeGap(now)
        if (outage) {
            if (lastPing?.let { now - it >= PING_GAP_NS } == true) healthySince = null
            val start = healthySince ?: now.also { healthySince = it }
            if (now - start >= HEALTHY_SPAN_NS) { outage = false; healthySince = null }
        }
        lastPing = now
    }
    @Synchronized fun stamp(connection: C, now: Long): Stamp? {
        if (session !== connection) return null
        observeGap(now)
        return Stamp(binding, serial, now)
    }
    @Synchronized fun blocksAdmission(connection: C, now: Long): Boolean {
        if (session !== connection) return true
        observeGap(now)
        return outage
    }
    @Synchronized fun stale(connection: C, stamp: Stamp, now: Long): Boolean {
        if (session !== connection || stamp.binding !== binding) return false
        observeGap(now)
        return serial != stamp.serial || outage || now - stamp.issued >= MAX_HOLD_NS
    }
    /** Read-only, bounded diagnostics: never exposes connection identity or changes timing state. */
    @Synchronized fun diagnostics(connection: C?, now: Long, stamp: Stamp? = null): String {
        val bound = connection != null && session === connection
        val current = bound && stamp?.binding === binding
        val reason = when {
            !bound -> "unbound"
            stamp != null && !current -> "old_session"
            current && stamp.serial != serial -> "issued_outage"
            outage -> "ping_gap"
            current && now - stamp.issued >= MAX_HOLD_NS -> "hold_limit"
            else -> "healthy"
        }
        fun micros(time: Long?) = time?.let { ((now - it).coerceAtLeast(0L) / 1000).toString() } ?: "unavailable"
        return "reason=$reason pingGapUs=${if (bound) micros(lastPing) else "unavailable"} " +
            "holdAgeUs=${if (current) micros(stamp.issued) else "unavailable"} " +
            "outage=${bound && outage} outageSerial=${if (bound) serial else 0} " +
            "healthySpanUs=${if (bound) micros(healthySince) else "unavailable"} " +
            "pingGapLimitMs=500 holdLimitMs=750 healthySpanMs=200"
    }
    companion object {
        const val PING_GAP_NS = 500_000_000L
        const val MAX_HOLD_NS = 750_000_000L
        const val HEALTHY_SPAN_NS = 200_000_000L
    }
}
