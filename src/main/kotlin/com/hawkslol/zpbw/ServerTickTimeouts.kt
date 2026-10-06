package com.hawkslol.zpbw

/** Packet-count clock, independent of client ticks, wall time and the movement journal.
 * Netty advances it and stops timers at arrival. The client thread claims expiry tickets before
 * touching gameplay. Recovery invalidates queued tickets under the same lock. Session identity
 * is referential, so a retired connection cannot advance a successor's clock.
 */
class ServerTickTimeouts<S : Any> {
    class Expiry internal constructor(val generation: Long, val ticks: Int)
    private class Timer(val ticket: Expiry, var remaining: Int, var arrived: Boolean = false,
                        var expired: Boolean = false)
    private var session: S? = null
    private var receivedPings = 0L
    private val timers = linkedMapOf<Long, Timer>()
    @Synchronized fun bind(session: S?) { timers.clear(); this.session = session; receivedPings = 0 }
    @Synchronized fun sample(session: S): Long? = receivedPings.takeIf { this.session === session }
    @Synchronized fun clear() { timers.clear() }
    @Synchronized fun suspend(session: S) {
        if (this.session === session) { timers.clear(); this.session = null }
    }
    @Synchronized fun start(session: S, generation: Long, ticks: Int): Boolean {
        return startAt(session, generation, ticks, null)
    }
    /** Queued input keeps its original Ping-count deadline when it becomes a dispatched warp. */
    @Synchronized fun startAt(session: S, generation: Long, ticks: Int, startedAt: Long?): Boolean {
        require(ticks in 2..30)
        if (this.session !== session) return false
        val elapsed = if (startedAt == null) 0 else receivedPings - startedAt
        check(elapsed >= 0)
        if (elapsed >= ticks) return false
        check(generation !in timers && timers.size < 5)
        timers[generation] = Timer(Expiry(generation, ticks), ticks - elapsed.toInt())
        return true
    }
    @Synchronized fun ping(session: S, id: Int): List<Expiry> {
        if (this.session !== session || id == 0) return emptyList()
        receivedPings++
        return timers.values.mapNotNull { timer ->
            if (!timer.arrived && !timer.expired && --timer.remaining == 0) {
                timer.expired = true
                timer.ticket
            } else null
        }
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
    @Synchronized fun claim(ticket: Expiry): Boolean {
        val timer = timers[ticket.generation] ?: return false
        if (timer.ticket !== ticket || !timer.expired) return false
        // A predecessor's already-arrived response must run before recovery of this suffix.
        if (timers.values.takeWhile { it !== timer }.any { it.arrived }) return false
        timers.remove(ticket.generation)
        return true
    }
}
