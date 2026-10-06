package com.hawkslol.zpbw

/** FIFO genuine confirmations, with one bounded journal for the newest speculative destination.
 * Starting another use coalesces intermediate movement; it must never replay at a later landing.
 * The caller forwards the original non-movement envelope before the new vanilla use. */
class ChainLedger<P, T>(private val capacity: Int = 512) {
    data class Entry<P>(val generation: Long, val value: P,
                        var cut: Boolean = false, var realId: Int? = null, var responses: Int = 0)
    private val pending = ArrayDeque<Entry<P>>()
    private val packets = ArrayList<T>()
    var hasActions = false; private set
    var hasConfirmedPrefix = false; private set
    val maximum = 5
    val count get() = pending.size
    val size get() = packets.size
    val head get() = pending.firstOrNull()
    val tail get() = pending.lastOrNull()
    val handling get() = head?.realId != null
    val canAdd get() = count < maximum && !handling && !hasActions && !hasConfirmedPrefix && (tail == null || tail!!.cut)
    fun add(generation: Long, value: P) {
        check(canAdd && packets.isEmpty())
        pending.addLast(Entry(generation, value))
    }
    fun cut(generation: Long) {
        val entry = tail ?: error("no source use")
        check(entry.generation == generation && !entry.cut && !handling)
        entry.cut = true
    }
    fun retain(packet: T, action: Boolean = false) {
        check(count > 0 && !handling && size < capacity)
        packets.add(packet)
        if (action) hasActions = true
    }
    fun coalesce(): List<T> {
        check(!handling && !hasActions)
        return packets.toList().also { packets.clear() }
    }
    /** An authoritative physics change invalidates journaled motion, not genuine warp ownership. */
    fun rebaseJournal(): List<T> {
        check(!handling)
        return packets.toList().also { packets.clear(); hasActions = false }
    }
    fun genuine(generation: Long, id: Int) {
        val entry = head ?: error("no pending use")
        check(entry.generation == generation && entry.cut && !handling)
        entry.realId = id
        hasConfirmedPrefix = true
    }
    fun response(id: Int, accept: Boolean) {
        val entry = head ?: error("no handler")
        check(entry.realId == id && handling)
        check((accept && entry.responses == 0) || (!accept && entry.responses == 1))
        entry.responses++
    }
    fun settle(generation: Long, id: Int): List<T> {
        val entry = head ?: error("no handler")
        check(entry.generation == generation && entry.realId == id && entry.responses == 2)
        pending.removeFirst()
        // Earlier segments were already coalesced before their successor's use. Never release
        // newest-destination physics at an earlier native confirmation.
        return if (pending.isEmpty()) packets.toList().also { packets.clear(); hasActions = false; hasConfirmedPrefix = false } else emptyList()
    }
    fun clear(): List<T> = packets.toList().also { packets.clear(); pending.clear(); hasActions = false; hasConfirmedPrefix = false }
}
