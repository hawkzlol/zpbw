package com.hawkslol.zpbw

import kotlin.test.*

class NativeDispatchScopesTest {
    @Test fun reusedSingletonHasDistinctSendAndClickOccurrences() {
        val owner = Any(); val singleton = Any(); val observed = mutableListOf<NativeDispatchScopes.Send>()
        repeat(2) {
            val action = NativeDispatchScopes.beginAction()
            try {
                val send = NativeDispatchScopes.beginSend(owner, singleton)
                try { observed += NativeDispatchScopes.delegating(owner, singleton)!! }
                finally { NativeDispatchScopes.endSend(send) }
            } finally { NativeDispatchScopes.endAction(action) }
        }
        assertNotEquals(observed[0].id, observed[1].id); assertNotEquals(observed[0].action, observed[1].action)
        assertSame(observed[0], NativeDispatchScopes.admitted(owner, singleton))
        assertSame(observed[1], NativeDispatchScopes.admitted(owner, singleton))
        assertNull(NativeDispatchScopes.admitted(owner, singleton)); assertNull(NativeDispatchScopes.actionId)
    }
    @Test fun nestedNativeActionsShareRootButUnrelatedSendCannotInheritRetiredScope() {
        val outer = NativeDispatchScopes.beginAction()
        try {
            val inner = NativeDispatchScopes.beginAction()
            try { assertEquals(outer.id, NativeDispatchScopes.actionId) }
            finally { NativeDispatchScopes.endAction(inner) }
        } finally { NativeDispatchScopes.endAction(outer) }
        val owner = Any(); val packet = Any(); val send = NativeDispatchScopes.beginSend(owner, packet)
        try { assertNull(send.action); assertNull(NativeDispatchScopes.delegating(Any(), packet)) }
        finally { NativeDispatchScopes.endSend(send) }
        assertNull(NativeDispatchScopes.currentSend)
    }
    @Test fun admissionCanOccurBeforeDelegateReturnsWithoutLosingOccurrence() {
        val owner = Any(); val packet = Any(); val token = NativeDispatchScopes.beginSend(owner, packet)
        try {
            assertSame(token, NativeDispatchScopes.delegating(owner, packet))
            assertSame(token, NativeDispatchScopes.admitted(owner, packet))
        } finally { NativeDispatchScopes.endSend(token) }
    }
    @Test fun exceptionalNativeCallUnwindsBothScopes() {
        assertFailsWith<IllegalArgumentException> {
            val action = NativeDispatchScopes.beginAction()
            try {
                val token = NativeDispatchScopes.beginSend(Any(), Any())
                try { throw IllegalArgumentException("fixture") }
                finally { NativeDispatchScopes.endSend(token) }
            } finally { NativeDispatchScopes.endAction(action) }
        }
        assertNull(NativeDispatchScopes.currentSend); assertNull(NativeDispatchScopes.actionId)
    }
    @Test fun diagnosticReferencesAreBoundedAndRetiredByConnection() {
        val owner = Any(); val singleton = Any()
        repeat(1100) {
            val token = NativeDispatchScopes.beginSend(owner, singleton)
            try { NativeDispatchScopes.delegating(owner, singleton) }
            finally { NativeDispatchScopes.endSend(token) }
        }
        assertEquals(1024, NativeDispatchScopes.pendingObservations())
        NativeDispatchScopes.clear(owner); assertEquals(0, NativeDispatchScopes.pendingObservations())
    }
}
