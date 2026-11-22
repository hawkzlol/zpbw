package com.hawkslol.zpbw

/** Only the original separate Rotate handler's Connection.send invocation owns this scope.
 * Packet identity alone is insufficient: a later or nested send of the same object is ordinary.
 * These thread-local tokens never leave the native invocation or survive its finally block. */
object NativeRotationResponseScope {
    class Response internal constructor(val owner: Any, val handler: Any, val packet: Any) {
        internal var claimed = false
    }
    class Send internal constructor(internal val response: Response?)
    private val response = ThreadLocal<Response>()
    private val sends = ThreadLocal<ArrayDeque<Send>>()

    @JvmStatic fun beginResponse(owner: Any, handler: Any, packet: Any): Response {
        check(response.get() == null) { "Nested native Rotate response" }
        return Response(owner, handler, packet).also { response.set(it) }
    }
    @JvmStatic fun endResponse(scope: Response) {
        check(response.get() === scope && sends.get().isNullOrEmpty())
        response.remove()
    }
    @JvmStatic fun beginSend(owner: Any, packet: Any): Send? {
        val current = response.get() ?: return null
        val stack = sends.get() ?: ArrayDeque<Send>().also { sends.set(it) }
        check(stack.size < 64) { "Native Rotate send recursion" }
        val exact = stack.isEmpty() && !current.claimed && current.owner === owner && current.packet === packet
        if (exact) current.claimed = true
        return Send(if (exact) current else null).also { stack.addLast(it) }
    }
    @JvmStatic fun endSend(scope: Send?) {
        if (scope == null) return
        val stack = checkNotNull(sends.get())
        check(stack.lastOrNull() === scope)
        stack.removeLast()
        if (stack.isEmpty()) sends.remove()
    }
    @JvmStatic fun isCurrentResponse(owner: Any, packet: Any): Boolean {
        val current = response.get() ?: return false
        return sends.get()?.lastOrNull()?.response === current && current.owner === owner && current.packet === packet
    }
}
