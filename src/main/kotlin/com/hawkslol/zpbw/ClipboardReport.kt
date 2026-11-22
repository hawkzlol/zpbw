package com.hawkslol.zpbw

/** Native Windows clipboard backends may return CRLF for the LF text submitted. */
internal object ClipboardReport {
    fun matches(expected: String, received: String): Boolean =
        expected.replace("\r\n", "\n") == received.replace("\r\n", "\n")

    /** Export failure must still leave the complete requested report available. */
    fun copyOrLog(report: String, write: (String) -> Unit, read: () -> String,
                  console: (String) -> Unit): Boolean {
        val copied = runCatching { write(report); matches(report, read()) }.getOrDefault(false)
        if (!copied) console(report)
        return copied
    }
}
