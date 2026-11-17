package com.hawkslol.zpbw

/** Honor an already-sent input through its natural tick. Afterwards sample live keys;
 * an input observed during that commitment was never sent and must not be replayed. */
class TickInputLatch<T> {
    var value: T? = null
        private set
    private var consumed = false
    fun commit(input: T): Boolean {
        if (value != null) return false
        value = input; consumed = false
        return true
    }
    fun sampled() { if (value != null) consumed = true }
    fun sample(latest: T): T {
        val next = value ?: return latest
        consumed = true
        return next
    }
    fun finishTick() { if (consumed) { value = null; consumed = false } }
    fun clear() { value = null; consumed = false }
}
