package com.hawkslol.zpbw

/** A native teleport response reports wire flags but does not simulate a physics tick. */
class TransmittedCollisionHistory {
    var simulation: Pair<Boolean, Boolean>? = null; private set
    var wire: Pair<Boolean, Boolean>? = null; private set

    fun sent(ground: Boolean, horizontal: Boolean, advancesSimulation: Boolean) {
        wire = ground to horizontal
        if (advancesSimulation) simulation = wire
    }

    fun sourceFlags(confirmedPrefix: Boolean) = if (confirmedPrefix) wire else simulation
    fun reset() { simulation = null; wire = null }
}
