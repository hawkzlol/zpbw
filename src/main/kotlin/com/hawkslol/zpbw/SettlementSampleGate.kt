package com.hawkslol.zpbw

/** Experimental admission guard only. It never owns or constructs gameplay packets.
 * A replayed envelope or native response cannot stand in for a fresh sender sample.
 */
internal class SettlementSampleGate {
    data class Sample(val generation: Long, val packets: Int, val firstPacket: String?)
    private var stream: Any? = null
    private var world: Any? = null
    private var player: Any? = null
    private var generation = 0L
    private var sampling = false
    private var sampled = false
    private var packets = 0
    private var firstPacket: String? = null

    fun arm(stream: Any, world: Any, player: Any, generation: Long) {
        reset()
        this.stream = stream; this.world = world; this.player = player
        this.generation = generation
    }

    fun pending(stream: Any?, world: Any?, player: Any?): Boolean {
        if (this.stream == null) return false
        if (stream !== this.stream || world !== this.world || player !== this.player) {
            reset(); return false
        }
        return true
    }

    fun begin(stream: Any?, world: Any?, player: Any?): Boolean {
        if (!pending(stream, world, player) || sampled || sampling) return false
        sampling = true; packets = 0; firstPacket = null
        return true
    }

    fun sent(detail: String) {
        if (!sampling) return
        if (packets++ == 0) firstPacket = detail
    }

    fun completed(stream: Any?, world: Any?, player: Any?): Sample? {
        if (!pending(stream, world, player) || !sampling) return null
        sampling = false; sampled = true
        return Sample(generation, packets, firstPacket)
    }

    fun end(stream: Any?, world: Any?, player: Any?): Long? {
        if (!pending(stream, world, player) || !sampled || sampling) return null
        return generation.also { reset() }
    }

    /** Called in the sender's finally block, including exceptional returns. */
    fun finish() { sampling = false }

    fun reset() {
        stream = null; world = null; player = null; generation = 0
        sampling = false; sampled = false; packets = 0; firstPacket = null
    }
}
