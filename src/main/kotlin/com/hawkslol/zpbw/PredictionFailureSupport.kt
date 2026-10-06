package com.hawkslol.zpbw

import java.util.Locale
import kotlin.math.sqrt

/** Pure presentation/test geometry: no packets, IDs or genuine server coordinates are changed. */
object PredictionFailureSupport {
    fun explanation(reason: String): String = "Reason: $reason\n" + when (reason) {
        "packet_buffer_limit", "action_buffer_limit" -> "The pending packet buffer reached its limit."
        "unsupported_relative_flags" -> "The server returned an unsupported relative teleport."
        "invalid_dependent_prediction" -> "The next blinkwarp target is no longer valid."
        "use_outside_envelope" -> "The current player state cannot continue this blinkwarp."
        "vanilla_use_after_off" -> "Another item was used after disabling prediction."
        "off_thread_gameplay" -> "A gameplay action arrived outside the normal client tick."
        "confirmation_before_source_tick" -> "The teleport arrived before the prediction source tick finished."
        "duplicate_confirmation" -> "The server repeated a teleport confirmation."
        "non_finite_confirmation" -> "The server returned invalid position or motion values."
        "native_response_or_session_contract" -> "The native teleport response or session changed."
        "settle_failed" -> "The queued prediction could not finish."
        "player_lifecycle_changed" -> "The player died, changed worlds or entered a vehicle."
        "input_dispatch_failed" -> "A queued action could not be performed."
        "session_transition", "session_changed" -> "The game session changed."
        else -> if (reason.startsWith("unsupported_outgoing_")) "An unsupported outgoing packet interrupted prediction."
            else reason.replace('_', ' ')
    }
    fun distance(guessed: Point, actual: Point): String = String.format(Locale.ROOT, "%.3f", guessed.distanceTo(actual))
    data class Point(val x: Double, val y: Double, val z: Double) {
        fun distanceTo(other: Point): Double {
            val dx = x - other.x; val dy = y - other.y; val dz = z - other.z
            return sqrt(dx * dx + dy * dy + dz * dz)
        }
        fun coordinates() = String.format(Locale.ROOT, "(%.3f, %.3f, %.3f)", x, y, z)
    }
}
