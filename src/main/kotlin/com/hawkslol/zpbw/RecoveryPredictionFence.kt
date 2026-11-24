package com.hawkslol.zpbw

import java.lang.ref.WeakReference
import com.hawkslol.zpbw.nativebridge.NativeResponseStep
import com.hawkslol.zpbw.nativebridge.NativeTeleportContract

/** Error-only admission gate. Native gameplay continues while fresh authority and
 * its following native input boundary rebuild a coherent prediction baseline. */
internal class RecoveryPredictionFence<C : Any>(
    private val responseContract: List<NativeResponseStep> = NativeTeleportContract.sequence
) {
    private var owner: WeakReference<C>? = null
    private var suspended = false
    private var noticePending = false
    private var reason: String? = null
    private var elapsedTicks = 0
    private var handler: Any? = null
    private var responseIndex = 0
    private var invalidResponse = false
    private var authoritative = false
    private var sampled = false
    private var receivedPositions = 0L
    private var processedPositions = 0L

    /** Caller must positively identify the actual current native Connection. */
    @Synchronized fun bindCurrent(connection: C?): Boolean {
        if (connection == null || owner?.get() === connection) return false
        val cleared = suspended
        owner = WeakReference(connection)
        suspended = false
        noticePending = false
        reason = null
        elapsedTicks = 0
        receivedPositions = 0
        processedPositions = 0
        clearAuthority()
        return cleared
    }

    /** A stale callback cannot claim a successor connection or clear its state. */
    fun suspend(connection: C?, reason: String): Boolean {
        if (connection == null || owner?.get() !== connection) return false
        if (suspended) { clearAuthority(); return false }
        suspended = true
        noticePending = true
        this.reason = reason.take(96)
        elapsedTicks = 0
        clearAuthority()
        return true
    }

    fun isSuspended(connection: C?) = connection != null && owner?.get() === connection && suspended
    fun reason(connection: C?): String? = reason.takeIf { isSuspended(connection) }

    /** Unresolved retirement, replacement or an actually sent native use requires
     * new genuine authority. Time alone never supplies that proof. */
    fun restart(connection: C?) {
        if (!isSuspended(connection)) return
        clearAuthority()
    }

    fun invalidateAuthority(connection: C?) {
        if (isSuspended(connection)) clearAuthority()
    }

    fun beginAuthority(connection: C?, token: Any) {
        if (!isSuspended(connection)) return
        clearAuthority()
        handler = token
    }

    /** The token is the current original handler occurrence, never a predicted ID. */
    fun nativeResponse(connection: C?, token: Any, step: NativeResponseStep?) {
        if (!isSuspended(connection) || handler !== token) return
        if (invalidResponse || responseIndex >= responseContract.size || responseContract[responseIndex] != step)
            invalidResponse = true
        else responseIndex++
    }

    fun handlerCompleted(connection: C?, token: Any): Boolean {
        if (!isSuspended(connection) || handler !== token) return false
        val complete = !invalidResponse && responseIndex == responseContract.size
        clearAuthority()
        authoritative = complete
        return complete
    }

    /** Counts existing completed client ticks, never creates a tick or sends anything. */
    fun nativeTick(connection: C?) {
        if (isSuspended(connection) && elapsedTicks < Int.MAX_VALUE) elapsedTicks++
    }

    fun inputSampled(connection: C?) {
        if (isSuspended(connection) && authoritative) sampled = true
    }

    fun awaitingFreshBoundary(connection: C?) = isSuspended(connection) && authoritative

    /** Netty only records ingress; it never touches player state or grants authority. */
    @Synchronized fun received(connection: C?) {
        if (connection != null && owner?.get() === connection) receivedPositions++
    }

    /** The real original handler finished or aborted on the client thread. */
    @Synchronized fun processed(connection: C?) {
        if (connection != null && owner?.get() === connection && processedPositions < receivedPositions)
            processedPositions++
    }

    @Synchronized fun naturalTickEnd(connection: C?, noOutstandingWork: Boolean): Boolean {
        if (blocker(connection, noOutstandingWork) != "ready") return false
        suspended = false
        noticePending = false
        reason = null
        clearAuthority()
        return true
    }

    @Synchronized fun blocker(connection: C?, noOutstandingWork: Boolean): String = when {
        !isSuspended(connection) -> "not_suspended"
        !noOutstandingWork -> "outstanding_state"
        receivedPositions != processedPositions -> "incoming_position_queue"
        handler != null -> "native_handler"
        !authoritative -> "native_authority"
        !sampled -> "fresh_input"
        else -> "ready"
    }

    @Synchronized fun diagnostics(connection: C?): String =
        if (!isSuspended(connection)) "suspended=false"
        else "suspended=true reason=$reason elapsedTicks=$elapsedTicks authority=$authoritative sampled=$sampled " +
            "handlerActive=${handler != null} responseSteps=$responseIndex/${responseContract.size} " +
            "ingressPending=${receivedPositions - processedPositions}"

    private fun clearAuthority() {
        handler = null
        responseIndex = 0
        invalidResponse = false
        authoritative = false
        sampled = false
    }

    /** Consume only when the caller can actually show chat on this connection. */
    fun takeNotice(connection: C?): Boolean {
        if (!isSuspended(connection) || !noticePending) return false
        noticePending = false
        return true
    }
}
