package com.hawkslol.zpbw

import kotlin.test.*

class ChainLedgerTest {
    @Test fun authoritativePhysicsRebaseDropsOldMotionWithoutSerializingOrLosingGenuineWarps() {
        val c = ChainLedger<String, Any>()
        c.use(1); c.use(2); c.use(3)
        val oldMotion = Any(); val oldAction = Any(); val newMotion = Any()
        c.retain(oldMotion); c.retain(oldAction, action = true)
        assertTrue(c.confirm(1, 90).isEmpty())
        assertEquals(listOf(oldMotion, oldAction), c.rebaseJournal())
        assertEquals(2, c.count); assertEquals(2L, c.head!!.generation); assertEquals(3L, c.tail!!.generation)
        assertFalse(c.hasActions); assertEquals(0, c.size)
        c.retain(newMotion)
        assertTrue(c.confirm(2, 91).isEmpty())
        assertSame(newMotion, c.confirm(3, 92).single())
    }

    @Test fun physicsRebaseCannotInterruptNativeResponsePair() {
        val c = ChainLedger<String, Any>(); c.use(1); c.retain(Any())
        c.genuine(1, 30)
        assertFailsWith<IllegalStateException> { c.rebaseJournal() }
        c.response(30, true)
        assertFailsWith<IllegalStateException> { c.rebaseJournal() }
        c.response(30, false); assertEquals(1, c.settle(1, 30).size)
    }
    private fun ChainLedger<String, Any>.use(g: Long) { add(g, "use$g"); cut(g) }
    private fun ChainLedger<String, Any>.confirm(g: Long, id: Int): List<Any> {
        genuine(g, id); response(id, true); response(id, false); return settle(g, id)
    }
    @Test fun fiveUsesBeforeAnyConfirmationAndSixthBackpressured() {
        val c = ChainLedger<String, Any>()
        assertEquals(5, c.maximum)
        (1L..5L).forEach { c.use(it) }
        assertEquals(5, c.count); assertFalse(c.canAdd)
        assertFailsWith<IllegalStateException> { c.add(6, "use6") }
        c.confirm(1, 90); assertFalse(c.canAdd); assertTrue(c.hasConfirmedPrefix)
        assertFailsWith<IllegalStateException> { c.use(6) }
        assertEquals(4, c.count)
        (2L..5L).forEach { c.confirm(it, it.toInt()+90) }
        assertFalse(c.hasConfirmedPrefix); c.use(6); c.confirm(6, 96)
        assertEquals(0, c.count)
    }
    @Test fun naturalSourceCutRequiredBeforeAnotherUse() {
        val c = ChainLedger<String, Any>(); c.add(1, "use1")
        assertFalse(c.canAdd)
        assertFailsWith<IllegalStateException> { c.genuine(1, 99) }
        c.cut(1); assertTrue(c.canAdd)
        assertFailsWith<IllegalStateException> { c.cut(1) }
    }
    @Test fun coalescingKeepsExactObjectsAndNewestMovementWaitsForLastPair() {
        val c = ChainLedger<String, Any>(); c.use(1)
        val first = Any(); c.retain(first)
        assertSame(first, c.coalesce().single())
        c.use(2)
        val second = Any(); val third = Any(); c.retain(second); c.retain(third)
        assertTrue(c.confirm(1, 10).isEmpty()); assertEquals(2, c.size)
        c.genuine(2, 12)
        assertFailsWith<IllegalStateException> { c.settle(2, 12) }
        c.response(12, true)
        assertFailsWith<IllegalStateException> { c.settle(2, 12) }
        c.response(12, false)
        val held = c.settle(2, 12)
        assertSame(second, held[0]); assertSame(third, held[1]); assertEquals(0, c.size)
    }
    @Test fun idsMayHaveGapsButStaleHeadOrWrongOrderNeverReleases() {
        val c = ChainLedger<String, Any>(); c.use(1); c.use(2)
        assertFailsWith<IllegalStateException> { c.genuine(2, 300) }
        c.genuine(1, 77)
        assertFailsWith<IllegalStateException> { c.response(78, true) }
        assertFailsWith<IllegalStateException> { c.response(77, false) }
        c.response(77, true)
        assertFailsWith<IllegalStateException> { c.response(77, true) }
        c.response(77, false); c.settle(1, 77)
        c.confirm(2, 999); assertEquals(0, c.count)
    }
    @Test fun anyFailureClearsDependentSuffixAndBoundedReferences() {
        val c = ChainLedger<String, Any>(2); c.use(1); c.use(2); c.use(3)
        val a = Any(); val b = Any(); c.retain(a); c.retain(b)
        assertFailsWith<IllegalStateException> { c.retain(Any()) }
        val held = c.clear(); assertSame(a, held[0]); assertSame(b, held[1])
        assertEquals(0, c.count); assertEquals(0, c.size); assertFalse(c.handling)
        assertFailsWith<IllegalStateException> { c.response(5, true) }
        c.use(4); assertTrue(c.canAdd)
    }
    @Test fun rollingLimitNotLifetimeLimit() {
        val c = ChainLedger<String, Any>()
        repeat(1000) { i -> c.use(i.toLong()); c.confirm(i.toLong(), i * 7) }
        assertEquals(0, c.count)
    }
}
