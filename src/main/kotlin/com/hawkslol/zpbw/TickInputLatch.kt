package com.hawkslol.zpbw

/** Later input changes remain in vanilla bindings for the following tick. */
class TickInputLatch<T> {
    var value: T? = null
        private set
    private var consumed = false
    var deferred: T? = null
        private set
    fun commit(input: T): Boolean {
        if (value != null || deferred != null) return false
        value = input; consumed = false
        return true
    }
    fun sampled() { if (value != null) consumed = true }
    fun commitDeferred(): T? {
        if (value != null) return null
        val next = deferred ?: return null
        deferred = null; value = next; consumed = false
        return next
    }
    fun sample(latest: T): T {
        val next = value ?: deferred ?: return latest
        if (value != null) consumed = true
        deferred = latest.takeUnless { it == next }
        return next
    }
    fun finishTick() { if (consumed) { value = null; consumed = false } }
    fun clear() { value = null; deferred = null; consumed = false }
}
