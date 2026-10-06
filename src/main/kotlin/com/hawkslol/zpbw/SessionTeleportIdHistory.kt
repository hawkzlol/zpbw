package com.hawkslol.zpbw

/** Received IDs are unique only inside the active connection/world identity, not across transfers. */
class SessionTeleportIdHistory(private val capacity: Int = 256) {
    init { require(capacity > 0) }
    private var connection: Any? = null
    private var world: Any? = null
    private val received = LinkedHashSet<Int>()
    val size get() = received.size
    fun bind(connection: Any?, world: Any?): Boolean {
        if (connection === this.connection && world === this.world) return false
        received.clear()
        this.connection = connection; this.world = world
        return true
    }
    /** True means this actual received ID already occurred in the same session. No ID prediction. */
    fun record(id: Int): Boolean {
        val duplicate = !received.add(id)
        while (received.size > capacity) received.remove(received.first())
        return duplicate
    }
    fun clear() { received.clear(); connection = null; world = null }
}
