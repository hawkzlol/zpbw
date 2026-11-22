package com.hawkslol.zpbw

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.test.assertEquals

class ClipboardReportTest {
    private val report = "ZPBW troubleshooting report v1\nVersion: 1.0.3\nPosition: -1.5, 100.05, 12.5\n"

    @Test fun exactLinuxAndMacTextMatches() = assertTrue(ClipboardReport.matches(report, report))
    @Test fun windowsReadbackMatchesSubmittedReport() = assertTrue(ClipboardReport.matches(report, report.replace("\n", "\r\n")))
    @Test fun mixedNativeNewlinesMatch() = assertTrue(ClipboardReport.matches(report, report.replaceFirst("\n", "\r\n")))
    @Test fun expectedNativeNewlinesAlsoMatch() = assertTrue(ClipboardReport.matches(report.replace("\n", "\r\n"), report))
    @Test fun truncatedReadbackFails() = assertFalse(ClipboardReport.matches(report, report.dropLast(1)))
    @Test fun changedReportDataFails() = assertFalse(ClipboardReport.matches(report, report.replace("100.05", "100.5")))
    @Test fun unrelatedClipboardFails() = assertFalse(ClipboardReport.matches(report, "previous clipboard content"))
    @Test fun bareCarriageReturnAndNulAreNotDiscarded() {
        assertFalse(ClipboardReport.matches(report, report.replace("\n", "\r")))
        assertFalse(ClipboardReport.matches(report, report + '\u0000'))
    }
    @Test fun verifiedCopyDoesNotPrintToConsole() {
        val written = mutableListOf<String>(); val console = mutableListOf<String>()
        assertTrue(ClipboardReport.copyOrLog(report, written::add, { report }, console::add))
        assertEquals(listOf(report), written); assertTrue(console.isEmpty())
    }
    @Test fun windowsCopyDoesNotProduceAnUnnecessaryConsoleDump() {
        val console = mutableListOf<String>()
        assertTrue(ClipboardReport.copyOrLog(report, {}, { report.replace("\n", "\r\n") }, console::add))
        assertTrue(console.isEmpty())
    }
    @Test fun mismatchedCopyPrintsTheCompleteUnchangedReportOnce() {
        val console = mutableListOf<String>()
        assertFalse(ClipboardReport.copyOrLog(report, {}, { report.dropLast(1) }, console::add))
        assertEquals(listOf(report), console)
    }
    @Test fun failedClipboardWriteStillPrintsTheReportWithoutReadingTheClipboard() {
        val console = mutableListOf<String>(); var reads = 0
        assertFalse(ClipboardReport.copyOrLog(report, { error("clipboard unavailable") }, { reads++; report }, console::add))
        assertEquals(0, reads); assertEquals(listOf(report), console)
    }
    @Test fun failedClipboardReadStillPrintsTheReport() {
        val console = mutableListOf<String>()
        assertFalse(ClipboardReport.copyOrLog(report, {}, { error("clipboard unavailable") }, console::add))
        assertEquals(listOf(report), console)
    }
}
