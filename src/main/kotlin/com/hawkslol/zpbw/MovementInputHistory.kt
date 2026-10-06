package com.hawkslol.zpbw

/** Input history at actually transmitted simulation positions, not speculative client ticks.
 * A native teleport response changes location but is not a movement simulation step. */
class MovementInputHistory {
    private var sentSneak = false
    private var simulatedSneak: Boolean? = null
    private var rebasedSneak: Boolean? = null
    val hasRebase: Boolean get() = rebasedSneak != null

    fun input(sneaking: Boolean) { sentSneak = sneaking }
    fun position(teleportResponse: Boolean) {
        if (!teleportResponse) simulatedSneak = sentSneak
    }
    fun rebase() { rebasedSneak = simulatedSneak }
    fun previousSneak(original: Boolean): Boolean = (rebasedSneak ?: original).also { clearRebase() }
    fun clearRebase() { rebasedSneak = null }
    fun reset() { sentSneak = false; simulatedSneak = null; clearRebase() }
}
