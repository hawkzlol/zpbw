package com.hawkslol.zpbw

/** Refresh real lifecycle ownership before treating a stale capture as a warp failure. */
internal inline fun <T : Any> reconcileCaptureSession(
    previousOwner: T,
    refresh: () -> Unit,
    currentOwner: () -> T?,
    recoverUnchangedOwner: () -> Unit
) {
    refresh()
    if (currentOwner() === previousOwner) recoverUnchangedOwner()
}
