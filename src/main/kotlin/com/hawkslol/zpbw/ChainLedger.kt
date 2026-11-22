package com.hawkslol.zpbw

import com.hawkslol.zpbw.nativebridge.NativeResponseStep

/** FIFO genuine confirmations, with one bounded journal for the newest speculative destination.
 * Starting another use coalesces intermediate movement; it must never replay at a later landing.
 * The caller forwards the original non-movement envelope before the new vanilla use. */
class ChainLedger<P, T>(private val capacity: Int = 512,
                        private val responseContract: List<NativeResponseStep>) {
    init { require(capacity > 0 && responseContract.isNotEmpty() && responseContract.distinct().size == responseContract.size) }
    data class Entry<P>(val generation: Long, val value: P,
                        var cut: Boolean = false, var realId: Int? = null, var responses: Int = 0)
    private val pending = ArrayDeque<Entry<P>>()
    private val packets = ArrayList<T>()
    var hasActions = false; private set
    var hasConfirmedPrefix = false; private set
    /** Failed issued work remains owned while future gameplay returns to vanilla.
     * Its next genuine packet is authority, never evidence to replay this journal. */
    var nativeFallback = false; private set
    var maximum = 5; private set
    /** Changes admission only; issued generations, journal and response ownership survive. */
    fun configureMaximum(value: Int) { require(value in 1..5); maximum = value }
    val count get() = pending.size
    val size get() = packets.size
    fun retainedCount(predicate: (T) -> Boolean) = packets.count(predicate)
    val head get() = pending.firstOrNull()
    val tail get() = pending.lastOrNull()
    val handling get() = head?.realId != null
    fun pendingSnapshot(): List<Entry<P>> = pending.toList()
    val canAdd get() = !nativeFallback && count < maximum && !handling && !hasActions && (tail == null || tail!!.cut)
    /** Only a warp may extend outstanding speculation. World actions wait for settlement. */
    fun canDispatch(warp: Boolean) = !nativeFallback && !handling && (count == 0 || (warp && canAdd))
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
        check(!nativeFallback && count > 0 && !handling && size < capacity)
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
    /** Cancel speculation without abandoning issued identities or assigning a real ID. */
    fun beginNativeFallback(): List<T> {
        check(!nativeFallback && count > 0 && !handling && pending.all { it.cut })
        return rebaseJournal().also { nativeFallback = true }
    }
    fun genuine(generation: Long, id: Int) {
        check(!nativeFallback)
        val entry = head ?: error("no pending use")
        check(entry.generation == generation && entry.cut && !handling)
        entry.realId = id
        hasConfirmedPrefix = true
    }
    fun response(id: Int, step: NativeResponseStep) {
        check(!nativeFallback)
        val entry = head ?: error("no handler")
        check(entry.realId == id && handling)
        check(responseContract.getOrNull(entry.responses) == step)
        entry.responses++
    }
    fun settle(generation: Long, id: Int): List<T> {
        check(!nativeFallback)
        val entry = head ?: error("no handler")
        check(entry.generation == generation && entry.realId == id && entry.responses == responseContract.size)
        pending.removeFirst()
        // Earlier segments were already coalesced before their successor's use. Never release
        // newest-destination physics at an earlier native confirmation.
        return if (pending.isEmpty()) packets.toList().also { packets.clear(); hasActions = false; hasConfirmedPrefix = false } else emptyList()
    }
    fun clear(): List<T> = packets.toList().also { packets.clear(); pending.clear(); hasActions = false; hasConfirmedPrefix = false; nativeFallback = false }
}
