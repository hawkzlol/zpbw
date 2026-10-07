package com.hawkslol.zpbw

/** Only queued mining uses this gate; ordinary native mining remains untouched. */
class MiningReplayGate {
    private var lastWorld: Any? = null
    private var lastTick = Long.MIN_VALUE
    private var continuedWorld: Any? = null
    private var continuedTick = Long.MIN_VALUE

    fun continuedAt(world: Any, tick: Long) = continuedWorld === world && continuedTick == tick

    fun replay(world: Any, tick: Long, continuing: Boolean, validTarget: Boolean,
               stillMiningTarget: Boolean, action: () -> Unit): Boolean {
        if (!validTarget) return false
        if (continuing && (!stillMiningTarget || (lastWorld === world && lastTick == tick))) return false
        action()
        if (continuing) { continuedWorld = world; continuedTick = tick }
        lastWorld = world
        lastTick = tick
        return true
    }

    fun reset() {
        lastWorld = null; lastTick = Long.MIN_VALUE
        continuedWorld = null; continuedTick = Long.MIN_VALUE
    }
}
