package com.hawkslol.zpbw

/** A native teleport response reports wire flags but does not simulate a physics tick. */
class TransmittedCollisionHistory {
    var simulation: Pair<Boolean, Boolean>? = null; private set
    var wire: Pair<Boolean, Boolean>? = null; private set
    var nativeAcknowledgement: Pair<Boolean, Boolean>? = null; private set

    fun sent(ground: Boolean, horizontal: Boolean, advancesSimulation: Boolean) {
        wire = ground to horizontal
        if (advancesSimulation) simulation = wire
        nativeAcknowledgement = null
    }

    /** Released 26.3's server feeds combined acceptance into its position consumer with
     * literal false,false. These are native derived semantics, not fields on that packet. */
    fun combinedAcknowledgement() { nativeAcknowledgement = false to false }

    fun sourceFlags(confirmedPrefix: Boolean) = if (confirmedPrefix) nativeAcknowledgement ?: wire else simulation
    fun reset() { simulation = null; wire = null; nativeAcknowledgement = null }
}
