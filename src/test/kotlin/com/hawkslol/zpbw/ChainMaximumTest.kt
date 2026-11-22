package com.hawkslol.zpbw

import com.hawkslol.zpbw.nativebridge.NativeResponseStep
import kotlin.test.*

class ChainMaximumTest {
    private val contracts = listOf(TestResponseContracts.PAIR, TestResponseContracts.COMBINED)
    @Test fun loweringWithFiveIssuedKeepsGenerationsResponsesAndNewestJournal() {
        for (contract in contracts) {
            val ledger = ChainLedger<Any, Any>(responseContract = contract)
            val values = List(5) { Any() }
            values.forEachIndexed { i, value -> ledger.add(i.toLong(), value); ledger.cut(i.toLong()) }
            val motion = Any(); val action = Any()
            ledger.retain(motion); ledger.retain(action, action = true)
            val entries = ledger.pendingSnapshot()
            ledger.configureMaximum(1)
            assertEquals(5, ledger.count); assertEquals(1, ledger.maximum)
            assertEquals(entries, ledger.pendingSnapshot()); assertFalse(ledger.canAdd)
            entries.forEachIndexed { index, entry ->
                assertSame(values[index], ledger.head!!.value)
                ledger.genuine(entry.generation, index + 30)
                assertFalse(ledger.canAdd)
                contract.forEach { step -> ledger.response(index + 30, step); assertFalse(ledger.canAdd) }
                val released = ledger.settle(entry.generation, index + 30)
                if (index < 4) { assertTrue(released.isEmpty()); assertFalse(ledger.canAdd) }
                else { assertSame(motion, released[0]); assertSame(action, released[1]); assertTrue(ledger.canAdd) }
            }
            assertEquals(0, ledger.size); assertEquals(0, ledger.count)
        }
    }
    @Test fun raisingDoesNotBypassSourceCutHandlingOrHeldWorldActions() {
        for (contract in contracts) {
            val ledger = ChainLedger<String, Any>(responseContract = contract)
            ledger.configureMaximum(1); ledger.add(1, "first")
            ledger.configureMaximum(5); assertFalse(ledger.canAdd)
            ledger.cut(1); assertTrue(ledger.canAdd)
            ledger.add(2, "second"); ledger.cut(2)
            ledger.genuine(1, 7); ledger.configureMaximum(3); assertFalse(ledger.canAdd)
            contract.forEach { ledger.response(7, it) }; ledger.settle(1, 7)
            val action = Any(); ledger.retain(action, action = true)
            ledger.configureMaximum(5)
            assertFalse(ledger.canDispatch(true)); assertFalse(ledger.canDispatch(false))
            ledger.genuine(2, 8); contract.forEach { ledger.response(8, it) }
            assertSame(action, ledger.settle(2, 8).single())
        }
    }
    @Test fun everyCeilingBoundsNewAdmissionAndInvalidValuesAreRejectedWithoutMutation() {
        for (contract in contracts) for (maximum in 1..5) {
            val ledger = ChainLedger<Int, Any>(responseContract = contract)
            ledger.configureMaximum(maximum)
            repeat(maximum) { ledger.add(it.toLong(), it); ledger.cut(it.toLong()) }
            assertFalse(ledger.canAdd)
            for (invalid in listOf(Int.MIN_VALUE, 0, 6, Int.MAX_VALUE)) {
                assertFailsWith<IllegalArgumentException> { ledger.configureMaximum(invalid) }
                assertEquals(maximum, ledger.maximum); assertEquals(maximum, ledger.count)
            }
        }
    }
    @Test fun changingCeilingDoesNotRenewIssuedOrCapturedInputDeadlines() {
        val stream = Any(); val timers = ServerTickTimeouts<Any>(); timers.bind(stream)
        val ledger = ChainLedger<String, Any>(responseContract = TestResponseContracts.COMBINED)
        val capturedAt = timers.stamp(stream)!!
        repeat(5) { i -> ledger.add(i.toLong(), "warp$i"); ledger.cut(i.toLong()); timers.start(stream, i.toLong(), 20) }
        repeat(19) { assertTrue(timers.nativeTickThenPing(stream, 1).isEmpty()) }
        ledger.configureMaximum(1); ledger.configureMaximum(5)
        val expired = timers.nativeTickThenPing(stream, 1)
        assertEquals((0L..4L).toList(), expired.map { it.generation })
        expired.forEach { assertEquals(20, it.ticks) }
        assertFalse(timers.startAt(stream, 99, 20, capturedAt))
        assertEquals(5, ledger.count)
    }
    @Test fun responseShapeOrderAndCompletionAreTypedForBothNativeContracts() {
        for (contract in contracts) {
            val ledger = ChainLedger<Int, Any>(responseContract = contract)
            ledger.add(1, 1); ledger.cut(1); ledger.genuine(1, 42)
            assertFailsWith<IllegalStateException> { ledger.settle(1, 42) }
            val wrong = NativeResponseStep.entries.first { it != contract.first() }
            assertFailsWith<IllegalStateException> { ledger.response(42, wrong) }
            contract.forEachIndexed { index, step ->
                assertFailsWith<IllegalStateException> { ledger.response(43, step) }
                ledger.response(42, step)
                if (index < contract.lastIndex) assertFailsWith<IllegalStateException> { ledger.settle(1, 42) }
            }
            assertFailsWith<IllegalStateException> { ledger.response(42, contract.last()) }
            assertTrue(ledger.settle(1, 42).isEmpty())
        }
    }
}
