package com.hawkslol.zpbw

/** A mod-forced simulation shift is not a physical binding release. */
class SneakBindingTransitions {
    private var previous: Boolean? = null
    var pending: Boolean? = null
        private set
    fun observe(binding: Boolean) {
        if (previous != null && previous != binding) pending = binding
        previous = binding
    }
    fun consumed() { pending = null }
    fun reset() { previous = null; pending = null }
}
