package com.hawkslol.zpbw

import java.util.concurrent.atomic.AtomicLong

/** Bounded observation provenance. Tokens describe occurrences, never authorize gameplay.
 * In particular a singleton Punch may belong to many independent native sends. */
object NativeDispatchScopes {
    data class Action(val id: Long, val root: Long)
    data class Send(val id: Long, val action: Long?, val owner: Any, val packet: Any)
    private val ids = AtomicLong()
    private val actions = ThreadLocal.withInitial { ArrayDeque<Action>() }
    private val sends = ThreadLocal.withInitial { ArrayDeque<Send>() }
    private val delegated = ArrayDeque<Send>()
    val actionId: Long? get() = actions.get().lastOrNull()?.root
    val currentSend: Send? get() = sends.get().lastOrNull()
    fun occurrenceId(): Long = ids.incrementAndGet()
    @JvmStatic fun beginAction(): Action {
        val stack = actions.get()
        check(stack.size < 64) { "Native action scope recursion" }
        val id = occurrenceId()
        return Action(id, stack.lastOrNull()?.root ?: id).also { stack.addLast(it) }
    }
    @JvmStatic fun endAction(scope: Action) {
        val stack = actions.get()
        check(stack.lastOrNull() === scope)
        stack.removeLast()
        if (stack.isEmpty()) actions.remove()
    }
    @JvmStatic fun beginSend(owner: Any, packet: Any): Send {
        val stack = sends.get()
        check(stack.size < 64) { "Native send scope recursion" }
        return Send(occurrenceId(), actionId, owner, packet).also { stack.addLast(it) }
    }
    @JvmStatic fun endSend(scope: Send) {
        val stack = sends.get()
        check(stack.lastOrNull() === scope)
        stack.removeLast()
        if (stack.isEmpty()) sends.remove()
    }
    /** Register BEFORE invoking the delegate: Netty may run before the caller returns. */
    @Synchronized fun delegating(owner: Any, packet: Any): Send? {
        val token = currentSend?.takeIf { it.owner === owner && it.packet === packet } ?: return null
        if (delegated.size == 1024) delegated.removeFirst() // Diagnostics only; no packet is touched.
        delegated.addLast(token)
        return token
    }
    @Synchronized fun admitted(owner: Any, packet: Any): Send? {
        val iterator = delegated.iterator()
        while (iterator.hasNext()) {
            val token = iterator.next()
            if (token.owner === owner && token.packet === packet) { iterator.remove(); return token }
        }
        return null
    }
    @Synchronized fun clear(owner: Any) { delegated.removeAll { it.owner === owner } }
    @Synchronized fun pendingObservations(): Int = delegated.size
}
