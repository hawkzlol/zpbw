package com.hawkslol.zpbw

import java.nio.file.Files
import kotlin.test.*

class CaptureSessionTransitionTest {
    private fun chain() = ChainLedger<String, Any>(responseContract = TestResponseContracts.PAIR)
    private fun ChainLedger<String, Any>.use(generation: Long) {
        add(generation, "use$generation"); cut(generation)
    }

    @Test fun partialConfirmationThenLifecycleRefreshDiscardsSuffixWithoutWarpFailure() {
        val chain = chain(); chain.use(1); chain.use(2); chain.use(3)
        chain.genuine(1, 4)
        TestResponseContracts.PAIR.forEach { chain.response(4, it) }
        assertTrue(chain.settle(1, 4).isEmpty())
        val staleOwner = chain.tail!!
        val held = Any(); chain.retain(held, action = true)
        var failures = 0
        reconcileCaptureSession(staleOwner, { chain.clear() }, { chain.tail }) { failures++ }
        assertEquals(0, failures)
        assertEquals(0, chain.count); assertEquals(0, chain.size)
        assertFalse(chain.hasActions)
    }

    @Test fun unchangedOwnerStillEntersOrdinaryRecoveryExactlyOnceAfterRefresh() {
        val chain = chain(); chain.use(1)
        val order = mutableListOf<String>()
        reconcileCaptureSession(chain.tail!!, { order += "refresh" }, { chain.tail }) { order += "recover" }
        assertEquals(listOf("refresh", "recover"), order)
        assertEquals(1, chain.count)
    }

    @Test fun refreshedSuccessorIsNotRecoveredAsTheOldOwner() {
        val chain = chain(); chain.use(1)
        val old = chain.tail!!
        var failures = 0
        reconcileCaptureSession(old, { chain.clear(); chain.use(2) }, { chain.tail }) { failures++ }
        assertEquals(2L, chain.tail!!.generation)
        assertEquals(0, failures)
    }

    @Test fun equalEntryValuesDoNotGrantOldReferenceOwnership() {
        val chain = chain(); chain.use(1)
        val old = chain.tail!!
        var failures = 0
        reconcileCaptureSession(old, { chain.clear(); chain.use(1) }, { chain.tail }) { failures++ }
        assertEquals(old, chain.tail)
        assertNotSame(old, chain.tail)
        assertEquals(0, failures)
    }

    @Test fun refreshFailureDoesNotMaskItsCauseOrStartAnotherRecovery() {
        val owner = Any(); var failures = 0
        assertFailsWith<IllegalStateException> {
            reconcileCaptureSession(owner, { error("refresh failed") }, { owner }) { failures++ }
        }
        assertEquals(0, failures)
    }

    @Test fun missingCurrentOwnerDoesNotManufactureAnIssuedFailure() {
        var failures = 0
        reconcileCaptureSession(Any(), {}, { null }) { failures++ }
        assertEquals(0, failures)
    }

    @Test fun effectiveNativeCaptureWiresRefreshOnlyAtItsStaleIdentityBranch() {
        val runtime = Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val capture = runtime.substringAfter("fun capture(").substringBefore("fun afterSend(")
        val invalid = capture.substringAfter("if (!identity(tail.value))").substringBefore("if (packet is ServerboundUseItemPacket")
        assertContains(invalid, "reconcileCaptureSession(tail, { attach() }, { chain.tail }) { recover(\"session_changed\") }")
        assertTrue(capture.indexOf("NativeRotationResponseScope.isCurrentResponse") < capture.indexOf("reconcileCaptureSession"))
        assertTrue(capture.indexOf("if (!mc.isSameThread)") < capture.indexOf("reconcileCaptureSession"))
    }
}
