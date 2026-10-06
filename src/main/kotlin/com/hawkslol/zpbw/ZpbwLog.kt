package com.hawkslol.zpbw

import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * Session-only troubleshooting history. Callers supply internal diagnostic events, never chat,
 * account/server identifiers, credentials or arbitrary exception messages. Control stripping is
 * formatting protection, not a general-purpose secret redactor.
 *
 * Wire traffic has its own small tail and counters so it cannot displace prediction or failure
 * evidence. All mutations and snapshots share one short lock; there are no files or worker threads.
 */
class ZpbwLog : AutoCloseable {
    val dropped = AtomicLong()
    val errors = AtomicLong()
    private val startedAt = Instant.now()
    private val startedNanos = System.nanoTime()
    private val history = Section(128 * 1024, 512)
    private val failures = Section(48 * 1024, 128)
    private val wire = Section(16 * 1024, 32)
    private val wireCounts = linkedMapOf<String, Long>()
    private var recorded = 0L
    private var rejected = 0L

    @Synchronized
    fun record(message: String) {
        val clean = sanitize(message)
        val event = clean.substringBefore(' ')
        // Runtime events have stable uppercase names. Do not turn free-form text into a log sink.
        if (!EVENT_NAME.matches(event) || event in PRIVATE_EVENT_NAMES || clean.isEmpty()) {
            rejected++
            dropped.incrementAndGet()
            return
        }
        recorded++
        val elapsedMicros = (System.nanoTime() - startedNanos) / 1_000
        val line = "${Instant.now()} +${elapsedMicros}us $clean\n"
        when {
            event in WIRE_EVENTS -> {
                wireCounts[event] = (wireCounts[event] ?: 0L) + 1
                wire.append(line)
            }
            FAILURE_WORDS.any { it in event } -> failures.append(line)
            else -> history.append(line)
        }
    }

    /** A consistent copy for the native Minecraft clipboard API on every supported desktop OS. */
    @Synchronized
    fun snapshot(): String = buildString {
        appendLine("ZPBW session diagnostics")
        appendLine("Started: $startedAt")
        appendLine("Captured: ${Instant.now()}")
        appendLine("Events: $recorded; details evicted/rejected: ${dropped.get()}; rejected: $rejected; errors: ${errors.get()}")
        appendLine("Wire counters describe local observations, not server acceptance.")
        appendSection("Lifecycle and prediction", history)
        appendSection("Failures and cancellations", failures)
        appendLine("\nWire event counts")
        if (wireCounts.isEmpty()) appendLine("(none)")
        wireCounts.forEach { (event, count) -> appendLine("$event=$count") }
        appendSection("Recent wire details", wire)
    }

    /** Retained for shutdown compatibility; this logger owns no external resources. */
    override fun close() = Unit

    private fun StringBuilder.appendSection(name: String, section: Section) {
        appendLine("\n$name")
        if (section.lines.isEmpty()) appendLine("(none)")
        section.lines.forEach { append(it.text) }
    }

    private inner class Section(private val maximumBytes: Int, private val maximumEntries: Int) {
        val lines = ArrayDeque<Line>()
        private var bytes = 0
        fun append(text: String) {
            val line = Line(text, text.toByteArray(Charsets.UTF_8).size)
            while (lines.isNotEmpty() && (bytes + line.bytes > maximumBytes || lines.size >= maximumEntries)) {
                bytes -= lines.removeFirst().bytes
                dropped.incrementAndGet()
            }
            lines.addLast(line)
            bytes += line.bytes
        }
    }

    private data class Line(val text: String, val bytes: Int)

    private fun sanitize(message: String): String = buildString {
        // A UTF-16 cap also bounds work and memory for malformed or enormous inputs. Even at the
        // maximum UTF-8 expansion, a line fits in the smallest (16 KiB) section.
        message.take(MAX_MESSAGE_CHARS).forEach { ch ->
            val category = Character.getType(ch)
            append(if (ch.isISOControl() || category == Character.FORMAT.toInt() ||
                category == Character.LINE_SEPARATOR.toInt() || category == Character.PARAGRAPH_SEPARATOR.toInt()) ' ' else ch)
        }
        if (message.length > MAX_MESSAGE_CHARS) append(" [truncated]")
    }.trim()

    companion object {
        const val MAX_SNAPSHOT_BYTES = 256 * 1024
        private const val MAX_MESSAGE_CHARS = 2048
        private val EVENT_NAME = Regex("[A-Z][A-Z0-9_]{0,47}")
        private val PRIVATE_EVENT_NAMES = setOf("CHAT", "USERNAME", "TOKEN", "CREDENTIALS", "SERVER_ADDRESS", "EXCEPTION")
        private val FAILURE_WORDS = listOf("FAIL", "ERROR", "MISMATCH", "CANCEL", "TIMEOUT", "BACKPRESSURE", "FALLBACK", "RESET")
        private val WIRE_EVENTS = setOf(
            "RETAIN", "FORWARD", "PASS", "SEND", "REPLAY", "NETTY_ADMITTED",
            "NSD_LATCH_APPLIED", "SOURCE_XYZ_COALESCED"
        )
    }
}
