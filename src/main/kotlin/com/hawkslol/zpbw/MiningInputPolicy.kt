package com.hawkslol.zpbw

/** Queued mining is a future native input, never an edit inserted into old movement history. */
object MiningInputPolicy {
    fun isMining(name: String) = name == "start_dig" || name == "continue_dig" ||
        name == "block_attack_click" || name == "block_continue_click"

    /** One pending mining invocation already represents held attack. Later ordinary ticks
     * sample the live button/target; a delayed hold must not accumulate progress requests. */
    fun coalesce(continuing: Boolean, queuedNames: Sequence<String>): Boolean =
        continuing && queuedNames.any(::isMining)

    fun canContinue(attackHeld: Boolean, targetUnderCrosshair: Boolean, nativeTargetMatches: Boolean) =
        attackHeld && targetUnderCrosshair && nativeTargetMatches

    /** A delayed held-button sample is disposable, unlike a distinct pressed click.
     * Other queued inputs own their own validation and must not be discarded with it. */
    fun obsoleteContinuation(name: String, reason: String, sameContext: Boolean) =
        sameContext && (name == "continue_dig" || name == "block_continue_click") &&
            reason in setOf("queued_mining_context_changed", "queued_mining_ray_changed")
}
