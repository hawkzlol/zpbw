package com.hawkslol.zpbw

/** Every nonzero Ping is counted at Netty; existing completed native client ticks provide
 * a minimum grace against packet bursts. Both budgets must be spent, without assuming a
 * tick duration or advancing the movement journal. Netty stops timers at genuine arrival.
 * The client thread claims expiry tickets before
 * touching gameplay. Recovery invalidates queued tickets under the same lock. Session identity
 * is referential, so a retired connection cannot advance a successor's clock.
 */
class ServerTickTimeouts<S : Any> {
    class Expiry internal constructor(val generation: Long, val ticks: Int)
    class Stamp internal constructor(val pings: Long, val nativeTicks: Long, internal val binding: Any)
    private class Timer(val ticket: Expiry, var remaining: Int, var remainingNativeTicks: Int, var arrived: Boolean = false,
                        var expired: Boolean = false)
    private var session: S? = null
    private var receivedPings = 0L
    private var completedNativeTicks = 0L
    private var binding = Any()
    private val timers = linkedMapOf<Long, Timer>()
    @Synchronized fun bind(session: S?) {
        timers.clear(); this.session = session; receivedPings = 0; completedNativeTicks = 0; binding = Any()
    }
    @Synchronized fun sample(session: S): Long? = receivedPings.takeIf { this.session === session }
    @Synchronized fun stamp(session: S): Stamp? =
        if (this.session === session) Stamp(receivedPings, completedNativeTicks, binding) else null
    @Synchronized fun deadlineReached(session: S, startedAt: Stamp, ticks: Int): Boolean? {
        require(ticks in 2..30)
        if (this.session !== session || startedAt.binding !== binding) return null
        check(receivedPings >= startedAt.pings && completedNativeTicks >= startedAt.nativeTicks)
        return receivedPings - startedAt.pings >= ticks && completedNativeTicks - startedAt.nativeTicks >= ticks
    }
    @Synchronized fun clear() { timers.clear() }
    @Synchronized fun suspend(session: S) {
        if (this.session === session) { timers.clear(); this.session = null }
    }
    @Synchronized fun start(session: S, generation: Long, ticks: Int): Boolean {
        return startAt(session, generation, ticks, null)
    }
    /** Reject stale queued input against BOTH original anchors. Its Ping budget stays
     * anchored to capture, but the issued response gets its own minimum native-tick grace:
     * time spent waiting for admission cannot consume time needed for a genuine reply. */
    @Synchronized fun startAt(session: S, generation: Long, ticks: Int, startedAt: Stamp?): Boolean {
        require(ticks in 2..30)
        if (this.session !== session || (startedAt != null && startedAt.binding !== binding)) return false
        val elapsed = if (startedAt == null) 0 else receivedPings - startedAt.pings
        val nativeElapsed = if (startedAt == null) 0 else completedNativeTicks - startedAt.nativeTicks
        check(elapsed >= 0 && nativeElapsed >= 0)
        if (elapsed >= ticks && nativeElapsed >= ticks) return false
        check(generation !in timers && timers.size < 5)
        timers[generation] = Timer(Expiry(generation, ticks),
            (ticks.toLong() - elapsed).coerceAtLeast(0).toInt(),
            ticks)
        return true
    }
    @Synchronized fun ping(session: S, id: Int): List<Expiry> {
        if (this.session !== session || id == 0) return emptyList()
        receivedPings++
        return timers.values.mapNotNull { timer ->
            if (!timer.arrived && !timer.expired) {
                timer.remaining = (timer.remaining - 1).coerceAtLeast(0)
                expireReady(timer)
            } else null
        }
    }
    /** Called only by the already registered END_CLIENT_TICK callback, never by a packet. */
    @Synchronized fun completedClientTick(session: S): List<Expiry> {
        if (this.session !== session) return emptyList()
        completedNativeTicks++
        return timers.values.mapNotNull { timer ->
            if (!timer.arrived && !timer.expired) {
                timer.remainingNativeTicks = (timer.remainingNativeTicks - 1).coerceAtLeast(0)
                expireReady(timer)
            } else null
        }
    }
    private fun expireReady(timer: Timer): Expiry? {
        if (timer.remaining != 0 || timer.remainingNativeTicks != 0) return null
        timer.expired = true
        return timer.ticket
    }
    @Synchronized fun teleport(session: S) {
        if (this.session !== session) return
        val entry = timers.entries.firstOrNull { !it.value.arrived } ?: return
        // Stop counting immediately. Keep a receipt barrier until the main-thread handler
        // retires it; Minecraft's packet queue and ordinary task queue are separate.
        entry.value.arrived = true
    }
    @Synchronized fun cancel(generation: Long) { timers.remove(generation) }
    @Synchronized fun expiredTickets(): List<Expiry> = timers.values.filter { it.expired }.map { it.ticket }
    private fun claimable(ticket: Expiry): Boolean {
        val timer = timers[ticket.generation] ?: return false
        if (timer.ticket !== ticket || !timer.expired) return false
        // A predecessor's already-arrived response must run before recovery of this suffix.
        if (timers.values.takeWhile { it !== timer }.any { it.arrived }) return false
        return true
    }
    @Synchronized fun claim(ticket: Expiry): Boolean {
        if (!claimable(ticket)) return false
        timers.remove(ticket.generation)
        return true
    }

    /** Retire an expiry and atomically establish a bounded transport-drain interval.
     * Preserve packets already received by Netty but still queued for their native handler.
     * Fresh ticket identity invalidates every callback from the failed prediction interval. */
    @Synchronized fun restartAfterExpiry(session: S, ticket: Expiry, generations: List<Long>, ticks: Int): Boolean {
        require(ticks in 2..30)
        require(generations.size in 1..5 && generations.distinct().size == generations.size && ticket.generation in generations)
        if (this.session !== session || !claimable(ticket) || generations != timers.keys.toList()) return false
        replaceRecoveryTimers(generations, ticks)
        return true
    }
    /** A real native physics boundary invalidates speculation before its deadline. */
    @Synchronized fun restartForRecovery(session: S, generations: List<Long>, ticks: Int): Boolean {
        require(ticks in 2..30)
        require(generations.size in 1..5 && generations.distinct().size == generations.size)
        if (this.session !== session || generations != timers.keys.toList()) return false
        replaceRecoveryTimers(generations, ticks)
        return true
    }
    /** Both callers hold this clock's monitor across receipt preservation and replacement. */
    private fun replaceRecoveryTimers(generations: List<Long>, ticks: Int) {
        val arrived = generations.associateWith { timers[it]?.arrived == true }
        timers.clear()
        for (generation in generations)
            timers[generation] = Timer(Expiry(generation, ticks), ticks, ticks, arrived = arrived.getValue(generation))
    }
}
