package com.hawkslol.zpbw

/** Client-thread gate after recovery. Sampling alone does not finish recovery: its
 * original native TickEnd must also leave before another click may dispatch. */
internal class RecoveryResumeGate {
    enum class Completion { WAITING, KEEP_GRACE, END_PROVED_DRAIN_GRACE }

    var pending = false
        private set
    private var sampled = false
    private var provedDrain = false

    fun begin(provedGenuineDrain: Boolean) {
        pending = true
        sampled = false
        provedDrain = provedGenuineDrain
    }

    fun inputSampled() {
        if (pending) sampled = true
    }

    /** Mode/ownership or a real unowned use may invalidate proof without cancelling
     * the native sample/TickEnd commitment which still has to finish. */
    fun invalidateProof() {
        provedDrain = false
    }

    fun naturalTickEndSent(): Completion {
        if (!pending || !sampled) return Completion.WAITING
        val result = if (provedDrain) Completion.END_PROVED_DRAIN_GRACE else Completion.KEEP_GRACE
        reset()
        return result
    }

    fun reset() {
        pending = false
        sampled = false
        provedDrain = false
    }
}
