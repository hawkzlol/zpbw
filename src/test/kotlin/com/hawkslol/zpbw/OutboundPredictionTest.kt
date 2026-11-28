package com.hawkslol.zpbw

import com.hawkslol.zpbw.geometry.EtherwarpRotationPrediction
import com.hawkslol.zpbw.gui.*
import net.minecraft.core.BlockPos
import net.minecraft.world.phys.Vec3
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import kotlin.test.*

class OutboundPredictionTest {
    private fun ray(x: Double, valid: Boolean = true) =
        EtherwarpRotationPrediction(valid, BlockPos(x.toInt(), 99, 0), Vec3(x, 100.0, 0.5), emptyList())

    @Test fun sentOriginWinsEvenWhenLocalRayHasAnotherDestination() {
        val local = ray(2.5)
        val sent = ray(3.5)
        val comparison = OutboundPredictionComparison(Vec3.ZERO, Vec3(1.0, 0.0, 0.0), 45f, 12f, local, sent)
        assertSame(sent, comparison.selected)
        assertEquals("sent_origin", comparison.selection)
        val result = comparison.result(Vec3(3.5, 100.05, 0.5), 0.05)
        assertContains(result, "localError=1.0")
        assertContains(result, "sentError=0.0")
    }

    @Test fun noSentHistoryOrInvalidSentRayRetainsPreparedPrediction() {
        for (sent in listOf(null, ray(3.5, valid = false))) {
            val comparison = OutboundPredictionComparison(Vec3.ZERO, null, 45f, 12f, ray(2.5), sent)
            assertNull(comparison.selected)
            assertEquals("prepared_fallback", comparison.selection)
            assertContains(comparison.result(Vec3(2.5, 100.05, 0.5), 0.05), "sentError=null")
        }
    }

    @Test fun validSentRayCanBeUsedWhenLocalShadowMisses() {
        val sent = ray(3.5)
        assertSame(sent, OutboundPredictionComparison(Vec3.ZERO, Vec3.ZERO, 0f, 0f, null, sent).selected)
    }

    @Test fun genuineMismatchRemainsVisibleForBothOrigins() {
        val comparison = OutboundPredictionComparison(Vec3.ZERO, Vec3.ZERO, 0f, 0f, ray(2.5), ray(3.5))
        val result = comparison.result(Vec3(5.5, 100.05, 0.5), 0.05)
        assertContains(result, "localError=3.0")
        assertContains(result, "sentError=2.0")
        val log = ZpbwLog()
        log.record("OUTBOUND_PREDICTION_RESULT generation=1 id=2 $result")
        assertContains(log.snapshot(), "OUTBOUND_PREDICTION_RESULT generation=1 id=2")
    }

    @Test fun defaultsOnForFreshAndExistingConfigsWithoutAnExplicitChoice() {
        val directory = Files.createTempDirectory("zpbw-outbound")
        val store = ZpbwSettingsStore(directory)
        assertTrue(store.load().outboundPrediction)
        assertFalse(store.load().enabled)
        Files.writeString(store.path, """{"chainMax":3,"future":42}""")
        assertTrue(store.load().outboundPrediction)
        store.saveStrict(store.load().copy(outboundPrediction = false))
        assertFalse(store.load().outboundPrediction)
        assertEquals(3, store.load().chainMax)
        assertContains(Files.readString(store.path), "\"future\": 42")
    }

    @Test fun failedPersistenceRetainsTheStoredSwitch() {
        val directory = Files.createTempDirectory("zpbw-outbound-atomic")
        val store = ZpbwSettingsStore(directory)
        store.saveStrict(ZpbwSettings(outboundPrediction = false))
        val before = Files.readAllBytes(store.path)
        val failing = ZpbwSettingsStore(directory) { a, b ->
            throw AtomicMoveNotSupportedException(a.toString(), b.toString(), "test")
        }
        assertFailsWith<AtomicMoveNotSupportedException> {
            failing.saveStrict(store.load().copy(outboundPrediction = true))
        }
        assertContentEquals(before, Files.readAllBytes(store.path))
        assertFalse(store.load().outboundPrediction)
    }

    @Test fun switchIsReachableWithoutCoveringTheProjectLinks() {
        val values = ClickGuiValues(false, false, 5, 20)
        assertTrue(values.outboundPrediction)
        val bounds = GuiAction.OUTBOUND_PREDICTION.bounds
        assertEquals(GuiAction.OUTBOUND_PREDICTION,
            actionAt(GuiPage.GENERAL, values, bounds.x + 2.0, bounds.y + 2.0))
        assertTrue(bounds.bottom < GuiAction.GITHUB.bounds.y)
    }
}
