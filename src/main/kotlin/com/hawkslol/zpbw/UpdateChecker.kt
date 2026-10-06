package com.hawkslol.zpbw

import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.StringReader
import java.net.URI
import java.net.SocketTimeoutException
import java.nio.charset.StandardCharsets.UTF_8
import java.util.concurrent.atomic.AtomicBoolean
import javax.net.ssl.HttpsURLConnection

data class UpdateResponse(val statusCode: Int, val body: String = "")

enum class UpdateCheckStatus {
    AVAILABLE, CURRENT, NO_RELEASE, IGNORED_RELEASE, INVALID_VERSION,
    INVALID_RESPONSE, TOO_LARGE, HTTP_ERROR, NETWORK_ERROR
}

data class UpdateCheckResult(
    val status: UpdateCheckStatus,
    val version: String? = null,
    val url: String? = null
)

/** A single anonymous release lookup for this client process; failures never affect gameplay. */
class UpdateChecker(
    private val currentVersion: String,
    private val fetch: () -> UpdateResponse = ::fetchLatestRelease
) {
    private val started = AtomicBoolean()
    @Volatile private var result: UpdateCheckResult? = null

    val status: String
        get() = result?.status?.name?.lowercase() ?: if (started.get()) "checking" else "not_started"

    fun snapshot(): UpdateCheckResult? = result

    fun start() {
        if (!started.compareAndSet(false, true)) return
        // No persistent executor or reconnect retry: this daemon exits after the one lookup.
        Thread({
            result = try {
                val local = ReleaseVersion.parse(currentVersion)
                if (local == null) UpdateCheckResult(UpdateCheckStatus.INVALID_VERSION)
                else evaluate(local, fetch())
            } catch (_: ResponseTooLarge) {
                UpdateCheckResult(UpdateCheckStatus.TOO_LARGE)
            } catch (_: Exception) {
                // Deliberately exclude URLs, response bodies, and exception text from diagnostics.
                UpdateCheckResult(UpdateCheckStatus.NETWORK_ERROR)
            }
        }, "ZPBW update check").apply { isDaemon = true }.start()
    }

    private fun evaluate(local: ReleaseVersion, response: UpdateResponse): UpdateCheckResult {
        if (response.statusCode == 404) return UpdateCheckResult(UpdateCheckStatus.NO_RELEASE)
        if (response.statusCode != 200) return UpdateCheckResult(UpdateCheckStatus.HTTP_ERROR)
        if (response.body.length > MAX_RESPONSE_BYTES || response.body.toByteArray(UTF_8).size > MAX_RESPONSE_BYTES)
            return UpdateCheckResult(UpdateCheckStatus.TOO_LARGE)
        val root = try {
            readRelease(response.body)
        } catch (_: Exception) { null }
            ?: return UpdateCheckResult(UpdateCheckStatus.INVALID_RESPONSE)
        val draft = root.draft
            ?: return UpdateCheckResult(UpdateCheckStatus.INVALID_RESPONSE)
        val prerelease = root.prerelease
            ?: return UpdateCheckResult(UpdateCheckStatus.INVALID_RESPONSE)
        if (draft || prerelease) return UpdateCheckResult(UpdateCheckStatus.IGNORED_RELEASE)
        val tag = root.tag
            ?: return UpdateCheckResult(UpdateCheckStatus.INVALID_RESPONSE)
        val remote = ReleaseVersion.parse(tag)
            ?: return UpdateCheckResult(UpdateCheckStatus.INVALID_VERSION)
        if (remote.prerelease) return UpdateCheckResult(UpdateCheckStatus.IGNORED_RELEASE)
        if (remote <= local) return UpdateCheckResult(UpdateCheckStatus.CURRENT)
        return UpdateCheckResult(UpdateCheckStatus.AVAILABLE, tag.removePrefix("v"), safeReleaseUrl(root.url))
    }

    companion object {
        const val RELEASES_URL = "https://github.com/hawkzlol/zpbw/releases/latest"
        const val MAX_RESPONSE_BYTES = 65_536

        private fun safeReleaseUrl(value: String?): String {
            if (value == null || value.length > 2_048) return RELEASES_URL
            return try {
                val uri = URI(value)
                if (uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null &&
                    uri.port == -1 && uri.rawQuery == null && uri.rawFragment == null &&
                    uri.rawPath.startsWith("/hawkzlol/zpbw/releases/") &&
                    '%' !in uri.rawPath && '\\' !in uri.rawPath && uri.normalize() == uri) value
                else RELEASES_URL
            } catch (_: Exception) { RELEASES_URL }
        }
    }
}

private data class ReleaseMetadata(var tag: String? = null, var url: String? = null,
                                   var draft: Boolean? = null, var prerelease: Boolean? = null)

/** Read only the four required fields, with strict syntax and no retained release notes/assets. */
@Suppress("DEPRECATION")
private fun readRelease(body: String): ReleaseMetadata = JsonReader(StringReader(body)).use { reader ->
    reader.isLenient = false
    val value = ReleaseMetadata()
    val seen = HashSet<String>()
    reader.beginObject()
    while (reader.hasNext()) {
        val name = reader.nextName()
        when (name) {
            "tag_name", "html_url" -> {
                require(seen.add(name))
                if (name == "html_url" && reader.peek() != JsonToken.STRING) {
                    reader.skipValue()
                } else {
                    require(reader.peek() == JsonToken.STRING)
                    val text = reader.nextString()
                    if (name == "tag_name") value.tag = text else value.url = text
                }
            }
            "draft", "prerelease" -> {
                require(seen.add(name) && reader.peek() == JsonToken.BOOLEAN)
                if (name == "draft") value.draft = reader.nextBoolean() else value.prerelease = reader.nextBoolean()
            }
            else -> reader.skipValue()
        }
    }
    reader.endObject()
    require(reader.peek() == JsonToken.END_DOCUMENT)
    value
}

/** Strict SemVer. Core numbers compare by magnitude without an integer overflow limit. */
private data class ReleaseVersion(val core: List<String>, val prerelease: Boolean) : Comparable<ReleaseVersion> {
    override fun compareTo(other: ReleaseVersion): Int {
        for (i in core.indices) {
            val lengthOrder = core[i].length.compareTo(other.core[i].length)
            if (lengthOrder != 0) return lengthOrder
            val valueOrder = core[i].compareTo(other.core[i])
            if (valueOrder != 0) return valueOrder
        }
        // Only stable remote releases are candidates. Build metadata never affects precedence.
        return other.prerelease.compareTo(prerelease)
    }

    companion object {
        private val pattern = Regex("^v?(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?(?:\\+([0-9A-Za-z-]+(?:\\.[0-9A-Za-z-]+)*))?$")
        fun parse(value: String): ReleaseVersion? {
            if (value.length > 64) return null
            val match = pattern.matchEntire(value) ?: return null
            val pre = match.groupValues[4]
            if (pre.split('.').any { it.length > 1 && it[0] == '0' && it.all(Char::isDigit) }) return null
            return ReleaseVersion((1..3).map { match.groupValues[it] }, pre.isNotEmpty())
        }
    }
}

private class ResponseTooLarge : IOException()

private fun fetchLatestRelease(): UpdateResponse {
    val connection = URI("https://api.github.com/repos/hawkzlol/zpbw/releases/latest")
        .toURL().openConnection() as HttpsURLConnection
    connection.apply {
        requestMethod = "GET"
        instanceFollowRedirects = false
        connectTimeout = 5_000
        readTimeout = 5_000
        useCaches = false
        setRequestProperty("Accept", "application/vnd.github+json")
        setRequestProperty("X-GitHub-Api-Version", "2026-03-10")
        setRequestProperty("User-Agent", "ZPBW-update-checker")
    }
    val deadline = System.nanoTime() + 10_000_000_000L
    try {
        val code = connection.responseCode
        if (code != 200) return UpdateResponse(code)
        if (connection.contentLengthLong > UpdateChecker.MAX_RESPONSE_BYTES) throw ResponseTooLarge()
        connection.inputStream.use { input ->
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(4_096)
            while (true) {
                val remainingNanos = deadline - System.nanoTime()
                if (remainingNanos <= 0) throw SocketTimeoutException()
                connection.readTimeout = (remainingNanos / 1_000_000L).coerceIn(1, 5_000).toInt()
                val read = input.read(buffer, 0, minOf(buffer.size, UpdateChecker.MAX_RESPONSE_BYTES + 1 - output.size()))
                if (read == -1) break
                output.write(buffer, 0, read)
                if (output.size() > UpdateChecker.MAX_RESPONSE_BYTES) throw ResponseTooLarge()
            }
            return UpdateResponse(code, output.toString(UTF_8))
        }
    } finally {
        connection.disconnect()
    }
}
