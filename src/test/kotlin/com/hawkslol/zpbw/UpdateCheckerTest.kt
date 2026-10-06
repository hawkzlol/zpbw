package com.hawkslol.zpbw

import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class UpdateCheckerTest {
    private fun release(tag: String = "v1.0.1", url: String = "https://github.com/hawkzlol/zpbw/releases/tag/v1.0.1",
                        draft: Boolean = false, prerelease: Boolean = false) = UpdateResponse(200,
        """{"tag_name":"$tag","html_url":"$url","draft":$draft,"prerelease":$prerelease}""")

    private fun await(checker: UpdateChecker): UpdateCheckResult {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (checker.snapshot() == null && System.nanoTime() < end) Thread.sleep(1)
        return assertNotNull(checker.snapshot(), "The single background check did not complete")
    }

    private fun result(local: String = "1.0.0", response: UpdateResponse = release()): UpdateCheckResult {
        val checker = UpdateChecker(local) { response }
        checker.start()
        return await(checker)
    }

    @Test fun oneLookupEvenForConcurrentStartsAndReconnects() {
        val called = CountDownLatch(1)
        val finish = CountDownLatch(1)
        val calls = AtomicInteger()
        var workerDaemon = false
        val caller = Thread.currentThread()
        var background = false
        val checker = UpdateChecker("1.0.0") {
            calls.incrementAndGet()
            workerDaemon = Thread.currentThread().isDaemon
            background = Thread.currentThread() !== caller
            called.countDown()
            check(finish.await(3, TimeUnit.SECONDS))
            release()
        }
        assertNull(checker.snapshot())
        assertEquals("not_started", checker.status)
        try {
            val callers = (1..16).map { Thread { checker.start() }.apply { start() } }
            callers.forEach { it.join(1_000); assertFalse(it.isAlive) }
            assertTrue(called.await(1, TimeUnit.SECONDS))
            assertEquals("checking", checker.status)
            assertNull(checker.snapshot())
        } finally { finish.countDown() }
        assertEquals(UpdateCheckStatus.AVAILABLE, await(checker).status)
        repeat(10) { checker.start() }
        assertEquals(1, calls.get())
        assertTrue(workerDaemon)
        assertTrue(background)
        assertEquals("available", checker.status)
    }

    @Test fun comparesCoreVersionsNumericallyAndIgnoresBuildMetadata() {
        val cases = listOf(
            Triple("1.0.0", "v1.0.1", true),
            Triple("1.9.9", "1.10.0", true),
            Triple("1.0.0", "2.0.0", true),
            Triple("v1.0.0", "v1.0.0", false),
            Triple("1.0.1", "1.0.0", false),
            Triple("1.10.0", "1.9.99", false),
            Triple("1.0.0+build.6", "1.0.0+build.7", false),
            Triple("1.0.0-rc.1", "1.0.0", true),
            Triple("1.1.0-rc.1", "1.0.9", false),
            Triple("1.999999999999999999999.0", "1.1000000000000000000000.0", true)
        )
        for ((local, remote, newer) in cases) {
            assertEquals(if (newer) UpdateCheckStatus.AVAILABLE else UpdateCheckStatus.CURRENT,
                result(local, release(remote)).status, "$local compared to $remote")
        }
    }

    @Test fun availableResultContainsOnlyValidatedVersionAndLink() {
        val update = result()
        assertEquals(UpdateCheckStatus.AVAILABLE, update.status)
        assertEquals("1.0.1", update.version)
        assertEquals("https://github.com/hawkzlol/zpbw/releases/tag/v1.0.1", update.url)
        val current = result("1.0.1")
        assertNull(current.version)
        assertNull(current.url)
    }

    @Test fun ignoresDraftAndPrereleaseEntriesIncludingMislabelledTags() {
        for (response in listOf(release(draft = true), release(prerelease = true), release("2.0.0-rc.1")))
            assertEquals(UpdateCheckStatus.IGNORED_RELEASE, result(response = response).status)
    }

    @Test fun invalidInstalledVersionDoesNotMakeAnyNetworkRequest() {
        for (version in listOf("unknown", "1.0", "01.0.0", "1.0.0-01", "1.0.0+", "1.0.0\n", "9".repeat(129))) {
            val calls = AtomicInteger()
            val checker = UpdateChecker(version) { calls.incrementAndGet(); release() }
            checker.start()
            assertEquals(UpdateCheckStatus.INVALID_VERSION, await(checker).status, version)
            assertEquals(0, calls.get())
        }
    }

    @Test fun invalidRemoteVersionsAreQuiet() {
        for (version in listOf("latest", "2", "2.0", "2.00.0", "2.0.0-01", "2.0.0+", "2.0.0-"))
            assertEquals(UpdateCheckStatus.INVALID_VERSION, result(response = release(version)).status, version)
    }

    @Test fun noReleaseRateLimitsHttpErrorsAndRedirectsDoNotYieldUpdate() {
        assertEquals(UpdateCheckStatus.NO_RELEASE, result(response = UpdateResponse(404)).status)
        for (code in listOf(301, 302, 401, 403, 429, 500, 503)) {
            val value = result(response = UpdateResponse(code, "untrusted server details"))
            assertEquals(UpdateCheckStatus.HTTP_ERROR, value.status)
            assertNull(value.version)
            assertNull(value.url)
        }
    }

    @Test fun malformedOrUnexpectedPayloadsAreQuiet() {
        val bodies = listOf("", "<html>oops</html>", "null", "[]", "{}",
            """{"tag_name":2,"draft":false,"prerelease":false}""",
            """{"tag_name":"2.0.0","draft":"false","prerelease":false}""",
            """{"tag_name":"2.0.0","draft":false}""",
            """{tag_name:"2.0.0",draft:false,prerelease:false}""",
            """{"tag_name":"2.0.0","draft":false,"prerelease":false} {}""",
            """{"tag_name":"2.0.0","tag_name":"3.0.0","draft":false,"prerelease":false}""")
        for (body in bodies)
            assertEquals(UpdateCheckStatus.INVALID_RESPONSE, result(response = UpdateResponse(200, body)).status, body)
    }

    @Test fun boundsTheResponseByUtf8BytesNotJustCharacters() {
        for (body in listOf("x".repeat(UpdateChecker.MAX_RESPONSE_BYTES + 1), "é".repeat(32_769)))
            assertEquals(UpdateCheckStatus.TOO_LARGE, result(response = UpdateResponse(200, body)).status)
    }

    @Test fun networkFailureDoesNotLeakExceptionTextOrRetry() {
        val calls = AtomicInteger()
        val checker = UpdateChecker("1.0.0") { calls.incrementAndGet(); throw IOException("private proxy details") }
        checker.start()
        val value = await(checker)
        assertEquals(UpdateCheckResult(UpdateCheckStatus.NETWORK_ERROR), value)
        checker.start()
        assertEquals(1, calls.get())
        assertEquals("network_error", checker.status)
    }

    @Test fun remoteUrlCannotNavigateOutsideTheProjectReleasePages() {
        val unsafe = listOf(
            "http://github.com/hawkzlol/zpbw/releases/tag/v1.0.1",
            "https://github.com.evil.invalid/hawkzlol/zpbw/releases/tag/v1.0.1",
            "https://github.com@evil.invalid/hawkzlol/zpbw/releases/tag/v1.0.1",
            "https://user@github.com/hawkzlol/zpbw/releases/tag/v1.0.1",
            "https://github.com:444/hawkzlol/zpbw/releases/tag/v1.0.1",
            "https://github.com/other/repo/releases/tag/v1.0.1",
            "https://github.com/hawkzlol/zpbw/releases/../../../other",
            "https://github.com/hawkzlol/zpbw/releases/%2e%2e/other",
            "https://github.com/hawkzlol/zpbw/releases/tag/v1.0.1?redirect=elsewhere",
            "javascript:alert(1)", "//github.com/hawkzlol/zpbw/releases/latest", "garbage")
        for (url in unsafe) assertEquals(UpdateChecker.RELEASES_URL, result(response = release(url = url)).url, url)
        assertEquals(UpdateChecker.RELEASES_URL, result(response = UpdateResponse(200,
            """{"tag_name":"2.0.0","draft":false,"prerelease":false}""")).url)
    }
}
