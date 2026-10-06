package com.hawkslol.zpbw

/** Identity-only, session-scoped ownership. No game objects are inspected under this lock. */
class PredictionOwnership {
    data class DrainState(
        val pending: Int = 0, val retained: Int = 0, val actions: Boolean = false,
        val handling: Boolean = false, val replaying: Boolean = false,
        val sourcePhysics: Boolean = false, val recovery: Boolean = false,
        val inputCommitted: Boolean = false
    ) {
        val ready get() = pending == 0 && retained == 0 && !actions && !handling && !replaying &&
            !sourcePhysics && !recovery && !inputCommitted
    }
    private var connection: Any? = null
    private var world: Any? = null
    private var player: Any? = null
    private var owner: Any? = null

    @Synchronized fun bind(connection: Any?, world: Any?, player: Any?) {
        if (this.connection !== connection || this.world !== world || this.player !== player) owner = null
        this.connection = connection; this.world = world; this.player = player
        if (connection == null || world == null || player == null) owner = null
    }
    @Synchronized fun invalidateConnection(connection: Any) {
        if (this.connection === connection) bind(null, null, null)
    }
    @Synchronized fun active() = owner != null
    @Synchronized fun tryAcquire(token: Any?, clientThread: Boolean, state: DrainState): Boolean {
        if (!clientThread || token == null || connection == null || world == null || player == null) return false
        if (owner != null) return owner === token
        if (!state.ready) return false
        owner = token
        return true
    }
    @Synchronized fun owns(token: Any?, clientThread: Boolean) = clientThread && token != null && owner === token
    @Synchronized fun release(token: Any?, clientThread: Boolean): Boolean {
        if (!owns(token, clientThread)) return false
        owner = null
        return true
    }
}
