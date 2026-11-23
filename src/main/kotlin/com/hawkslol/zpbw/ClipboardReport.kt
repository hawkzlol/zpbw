package com.hawkslol.zpbw

import java.awt.GraphicsEnvironment
import java.awt.Toolkit
import java.awt.datatransfer.DataFlavor
import java.awt.datatransfer.StringSelection
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Native Windows clipboard backends may return CRLF for the LF text submitted. */
internal object ClipboardReport {
    private val desktopBusy = AtomicBoolean()

    fun matches(expected: String, received: String): Boolean =
        expected.replace("\r\n", "\n") == received.replace("\r\n", "\n")

    /** Export failure must still leave the complete requested report available. */
    fun copyOrLog(
        report: String,
        write: (String) -> Unit,
        read: () -> String,
        console: (String) -> Unit,
        desktop: (String) -> CompletableFuture<Boolean> = { CompletableFuture.completedFuture(false) }
    ): CompletableFuture<Boolean> {
        if (runCatching { write(report); matches(report, read()) }.getOrDefault(false)) {
            return CompletableFuture.completedFuture(true)
        }
        val attempt = runCatching { desktop(report) }.getOrElse { CompletableFuture.failedFuture(it) }
        return attempt.handle { copied, _ ->
            if (copied != true) console(report)
            copied == true
        }
    }

    /** A second backend for Windows and X11. Other desktops use the game's native clipboard. */
    fun copyThroughDesktop(report: String): CompletableFuture<Boolean> {
        val os = System.getProperty("os.name", "").lowercase(java.util.Locale.ROOT)
        val windows = os.startsWith("windows")
        val x11 = os.startsWith("linux") && !System.getenv("DISPLAY").isNullOrBlank()
        if ((!windows && !x11) || !desktopBusy.compareAndSet(false, true)) {
            return CompletableFuture.completedFuture(false)
        }
        val result = CompletableFuture<Boolean>()
        result.completeOnTimeout(false, 1, TimeUnit.SECONDS)
        CompletableFuture.runAsync {
            val copied = try {
                runCatching {
                    if (GraphicsEnvironment.isHeadless()) return@runCatching false
                    val clipboard = Toolkit.getDefaultToolkit().systemClipboard
                    if (result.isDone) return@runCatching false
                    clipboard.setContents(StringSelection(report), null)
                    matches(report, clipboard.getData(DataFlavor.stringFlavor) as? String ?: "")
                }.getOrDefault(false)
            } finally {
                desktopBusy.set(false)
            }
            result.complete(copied)
        }
        return result
    }
}
