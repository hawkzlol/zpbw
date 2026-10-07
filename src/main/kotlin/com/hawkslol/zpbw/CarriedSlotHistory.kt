package com.hawkslol.zpbw

/** The native cache advances before send; a captured slot did not reach the send delegate. */
class CarriedSlotHistory<C : Any> {
    private var connection: C? = null
    private var sent: Int? = null
    private var captured = false
    fun bind(owner: C?) {
        if (connection !== owner) { connection = owner; sent = null; captured = false }
    }
    fun sent(slot: Int) { sent = slot; captured = false }
    fun captured() { captured = true }
    fun abandon(owner: C): Int? {
        if (connection !== owner || !captured) return null
        captured = false
        return sent
    }
}
