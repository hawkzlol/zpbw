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
    private val history = Section(32 * 1024, 128)
    private val prediction = Section(24 * 1024, 128)
    private val actions = Section(24 * 1024, 96)
    private val recovery = Section(24 * 1024, 128)
    private val failures = Section(48 * 1024, 128)
    private val wire = Section(16 * 1024, 32, wireSection = true)
    private val chronology = Section(16 * 1024, 64, primary = false)
    private val semanticContext = Section(8 * 1024, 16, primary = false)
    private val incidents = linkedMapOf<String, Section>()
    private var activeIncident: Section? = null
    private var postRemaining = 0
    private var incidentTypesEvicted = 0L
    private var wireEvicted = 0L
    private var detailEvicted = 0L
    private val eventCounts = linkedMapOf<String, Long>()
    private val wireCounts = linkedMapOf<String, Long>()
    private val wireKinds = linkedMapOf<String, Long>()
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
        count(eventCounts, event, 128)
        val elapsedMicros = (System.nanoTime() - startedNanos) / 1_000
        val line = "${Instant.now()} +${elapsedMicros}us $clean logSeq=$recorded\n"
        val isWire = event in WIRE_EVENTS
        if (!isWire) {
            chronology.append(line)
            if (event != "PREDICTION_RAY") {
                if (postRemaining > 0) { activeIncident?.append(contextLine(line)); postRemaining-- }
                if (event in INCIDENT_EVENTS) {
                    val context = Section(4 * 1024, 16, primary = false)
                    semanticContext.lines.takeLast(2).forEach { context.append(contextLine(it.text)) }
                    wire.lines.takeLast(2).forEach { context.append(contextLine(it.text)) }
                    context.append(contextLine(line))
                    if (event !in incidents && incidents.size == 8) {
                        incidents.remove(incidents.keys.first()); incidentTypesEvicted++
                    }
                    incidents[event] = context
                    activeIncident = context; postRemaining = 2
                }
                semanticContext.append(line)
            }
        }
        when {
            isWire -> {
                wireCounts[event] = (wireCounts[event] ?: 0L) + 1
                // Native occurrence/action metadata precedes the kind in admitted events;
                // send requests use kind= instead. Neither is itself a packet type.
                val kind = clean.substringAfter(' ', "").split(' ').firstNotNullOfOrNull { token ->
                    token.removePrefix("kind=").takeIf { PACKET_KIND.matches(it) }
                } ?: "detail"
                count(wireKinds, "$event:$kind", 64)
                wire.append(line)
            }
            FAILURE_WORDS.any { it in event } -> failures.append(line)
            event.startsWith("RECOVERY_") || event.startsWith("REPAIR_") -> recovery.append(line)
            event.startsWith("INPUT_") || event.startsWith("ACTION_") || event.startsWith("USE_") ||
                event.startsWith("ATTACK_") || event.startsWith("BLOCK_") || event.startsWith("ENTITY_") -> actions.append(line)
            event.startsWith("PREDICTION_") || event in PREDICTION_EVENTS -> prediction.append(line)
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
        appendLine("Primary detail evictions: wire=$wireEvicted; other=$detailEvicted. Context views do not inflate these counts.")
        appendLine("Issue types evicted: $incidentTypesEvicted. Counts cover the session; bounded context keeps the latest occurrence per retained issue type.")
        appendLine("Wire counters describe local observations, not server acceptance.")
        appendLine("\nEvent counts")
        eventCounts.forEach { (event, count) -> appendLine("$event=$count") }
        appendSection("Lifecycle and prediction", history)
        appendSection("Prediction and native responses", prediction)
        appendSection("Clicks and queued actions", actions)
        appendSection("Recovery and admission", recovery)
        appendSection("Failures and cancellations", failures)
        appendLine("\nWire event counts")
        if (wireCounts.isEmpty()) appendLine("(none)")
        wireCounts.forEach { (event, count) -> appendLine("$event=$count") }
        appendLine("\nWire counts by event and packet kind")
        wireKinds.forEach { (event, count) -> appendLine("$event=$count") }
        appendSection("Recent wire details", wire)
        appendSection("Chronological non-wire tail (same logSeq denotes the same event)", chronology)
        incidents.forEach { (event, context) -> appendSection("Issue context: $event (bounded before/after)", context) }
    }

    /** Retained for shutdown compatibility; this logger owns no external resources. */
    override fun close() = Unit

    private fun StringBuilder.appendSection(name: String, section: Section) {
        appendLine("\n$name")
        if (section.lines.isEmpty()) appendLine("(none)")
        section.lines.forEach { append(it.text) }
    }

    private fun count(counts: MutableMap<String, Long>, key: String, maximum: Int) {
        val bounded = if (key in counts || counts.size < maximum - 1) key else "OTHER"
        counts[bounded] = (counts[bounded] ?: 0L) + 1
    }

    private fun contextLine(line: String) = if (line.length <= 160) line else
        line.take(160) + " [context clipped; logSeq=" + line.substringAfterLast(" logSeq=").trim() + "]\n"

    private inner class Section(private val maximumBytes: Int, private val maximumEntries: Int,
                                private val primary: Boolean = true, private val wireSection: Boolean = false) {
        val lines = ArrayDeque<Line>()
        private var bytes = 0
        fun append(text: String) {
            val line = Line(text, text.toByteArray(Charsets.UTF_8).size)
            while (lines.isNotEmpty() && (bytes + line.bytes > maximumBytes || lines.size >= maximumEntries)) {
                bytes -= lines.removeFirst().bytes
                if (primary) {
                    dropped.incrementAndGet()
                    if (wireSection) wireEvicted++ else detailEvicted++
                }
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
        private val PACKET_KIND = Regex("[A-Za-z][A-Za-z0-9_.$]{0,95}")
        private val PRIVATE_EVENT_NAMES = setOf("CHAT", "USERNAME", "TOKEN", "CREDENTIALS", "SERVER_ADDRESS", "EXCEPTION")
        private val FAILURE_WORDS = listOf("FAIL", "ERROR", "MISMATCH", "CANCEL", "TIMEOUT", "BACKPRESSURE", "FALLBACK", "RESET", "EXPERIMENTAL_STALL", "STALE_SETTLEMENT", "REPAIR_GENUINE_DRAIN")
        private val WIRE_EVENTS = setOf(
            "RETAIN", "FORWARD", "PASS", "SEND", "SEND_REQUEST", "REPLAY", "NETTY_ADMITTED",
            "NSD_LATCH_APPLIED", "SOURCE_XYZ_COALESCED"
        )
        private val INCIDENT_EVENTS = setOf("MISMATCH", "FALLBACK", "TIMEOUT", "NATIVE_HANDLER_ABORTED",
            "EXPERIMENTAL_STALL", "EXPERIMENTAL_STALE_SETTLEMENT", "RECOVERY_NATIVE_OWNERSHIP_RETIRED", "INPUT_TIMEOUT")
        private val PREDICTION_EVENTS = setOf("PREPARED", "GENUINE_MATCH", "NATIVE_RESPONSE", "NATIVE_ACK_POSE",
            "TELEPORT", "VANILLA_HANDLER_COMPLETE", "SETTLED")
    }
}
