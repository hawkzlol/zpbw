package com.hawkslol.zpbw

import com.google.gson.Gson
import java.io.IOException
import java.net.SocketTimeoutException
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
    private fun asset(target: String, version: String = "1.0.1", tag: String = "v$version",
                      name: String = "zpbw-mc$target-$version.jar", state: String = "uploaded",
                      size: String = "123", url: String = "https://github.com/hawkzlol/zpbw/releases/download/$tag/$name"): String =
        """{"name":${Gson().toJson(name)},"state":${Gson().toJson(state)},"size":$size,"browser_download_url":${Gson().toJson(url)}}"""

    private fun pair(version: String = "1.0.1", tag: String = "v$version") =
        listOf(asset("26.2",version,tag),asset("26.3",version,tag)).joinToString(",", "[", "]")

    private fun release(tag: String = "v1.0.1", draft: Boolean = false, prerelease: Boolean = false,
                        assets: String? = pair(tag.removePrefix("v"),tag)) = UpdateResponse(200,
        """{"tag_name":"$tag","draft":$draft,"prerelease":$prerelease${assets?.let { ",\"assets\":$it" } ?: ""}}""")

    private fun await(checker: UpdateChecker): UpdateCheckResult {
        val end = System.nanoTime() + TimeUnit.SECONDS.toNanos(3)
        while (checker.snapshot() == null && System.nanoTime() < end) Thread.sleep(1)
        return assertNotNull(checker.snapshot(), "The single background check did not complete")
    }

    private fun result(local: String = "1.0.0", response: UpdateResponse = release(), target: String = "26.2"): UpdateCheckResult {
        val checker = UpdateChecker(local,target) { response }
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
        val checker = UpdateChecker("1.0.0","26.3") {
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
            Triple("1.9.9", "v1.10.0", true),
            Triple("1.0.0", "v2.0.0", true),
            Triple("v1.0.0", "v1.0.0", false),
            Triple("1.0.1", "1.0.0", false),
            Triple("1.10.0", "1.9.99", false),
            Triple("1.0.0+build.6", "1.0.0+build.7", false),
            Triple("1.0.0-rc.1", "v1.0.0", true),
            Triple("1.1.0-rc.1", "1.0.9", false),
            Triple("1.999999999999999999999.0", "v1.1000000000000000000000.0", true)
        )
        for ((local, remote, newer) in cases) {
            assertEquals(if (newer) UpdateCheckStatus.AVAILABLE else UpdateCheckStatus.CURRENT,
                result(local, release(remote)).status, "$local compared to $remote")
        }
    }

    @Test fun availableResultContainsOnlyValidatedVersionAndLink() {
        for (target in listOf("26.2","26.3")) {
            val update = result(target=target)
            assertEquals(UpdateCheckStatus.AVAILABLE, update.status)
            assertEquals("1.0.1", update.version)
            assertEquals("https://github.com/hawkzlol/zpbw/releases/download/v1.0.1/zpbw-mc$target-1.0.1.jar", update.url)
        }
        val current = result("1.0.1")
        assertNull(current.version)
        assertNull(current.url)
    }

    @Test fun ignoresDraftButLabelsBothMetadataAndVersionPrereleases() {
        assertEquals(UpdateCheckStatus.IGNORED_RELEASE,result(response=release(draft=true)).status)
        for(response in listOf(release(prerelease=true),release("v2.0.0-rc.1"))) {
            val update=result(response=response)
            assertEquals(UpdateCheckStatus.AVAILABLE,update.status);assertTrue(update.prerelease)
        }
    }
    private fun releases(vararg entries: UpdateResponse)=UpdateResponse(200,entries.joinToString(",","[","]"){it.body})
    @Test fun selectsHighestCompleteNativePairFromBoundedListRegardlessOfPublicationOrder() {
        val response=releases(release("v1.0.4-pre1",prerelease=true),release("v1.0.3"),
            release("v1.0.4-pre2",prerelease=true),release("v9.0.0",draft=true))
        for(target in listOf("26.2","26.3")) {
            val update=result("1.0.4-pre1",response,target)
            assertEquals("1.0.4-pre2",update.version);assertTrue(update.prerelease)
            assertEquals("https://github.com/hawkzlol/zpbw/releases/download/v1.0.4-pre2/zpbw-mc$target-1.0.4-pre2.jar",update.url)
        }
        assertEquals(UpdateCheckStatus.NO_RELEASE,result(response=UpdateResponse(200,"[]")).status)
        assertEquals(UpdateCheckStatus.CURRENT,result("1.0.4-pre2",response).status)
        val incomplete=release("v1.0.5-pre1",prerelease=true,assets="[${asset("26.2","1.0.5-pre1")}]")
        assertEquals("1.0.4-pre2",result(response=releases(incomplete,release("v1.0.4-pre2",prerelease=true))).version)
    }
    @Test fun prereleasePrecedenceAndLegacyPre1DoNotCreateDowngradeNotices() {
        for((local,remote,newer) in listOf(
            Triple("1.0.4-pre1","v1.0.4-pre2",true),Triple("1.0.4-pre2","v1.0.4-pre1",false),
            Triple("1.0.4-pre2","v1.0.4-pre10",true),Triple("1.0.4-rc.2","v1.0.4-rc.10",true),
            Triple("1.0.4-rc.10","v1.0.4-rc.2",false),Triple("1.0.4-rc.1","v1.0.4-rc.1.1",true),
            Triple("1.0.4-alpha","v1.0.4-beta",true),Triple("1.0.4","v1.0.4-pre2",false)
        )) assertEquals(if(newer)UpdateCheckStatus.AVAILABLE else UpdateCheckStatus.CURRENT,
            result(local,release(remote,prerelease=true)).status,"$local -> $remote")
        assertEquals(UpdateCheckStatus.CURRENT,result("1.0.4-pre2",release("v1.0.4",prerelease=true)).status)
        val stable=result("1.0.4-pre2",release("v1.0.4"))
        assertEquals(UpdateCheckStatus.AVAILABLE,stable.status);assertFalse(stable.prerelease)
        assertEquals(UpdateCheckStatus.TOO_LARGE,result(response=releases(*Array(11){release()})).status)
    }

    @Test fun invalidInstalledVersionDoesNotMakeAnyNetworkRequest() {
        for (version in listOf("unknown", "1.0", "01.0.0", "1.0.0-01", "1.0.0+", "1.0.0\n", "9".repeat(129))) {
            val calls = AtomicInteger()
            val checker = UpdateChecker(version,"26.2") { calls.incrementAndGet(); release() }
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
        val bodies = listOf("", "<html>oops</html>", "null", "{}",
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
        val checker = UpdateChecker("1.0.0","26.2") { calls.incrementAndGet(); throw IOException("private proxy details") }
        checker.start()
        val value = await(checker)
        assertEquals(UpdateCheckResult(UpdateCheckStatus.NETWORK_ERROR), value)
        checker.start()
        assertEquals(1, calls.get())
        assertEquals("network_error", checker.status)
    }

    @Test fun remoteUrlMustBeTheExactCanonicalNativeAssetWithoutAnyFallback() {
        val expected="https://github.com/hawkzlol/zpbw/releases/download/v1.0.1/zpbw-mc26.2-1.0.1.jar"
        val unsafe = listOf(
            expected.replace("https:","http:"), expected.replace("github.com","github.com.evil.invalid"),
            expected.replace("github.com","github.com@evil.invalid"),expected.replace("github.com","user@github.com"),
            expected.replace("github.com","github.com:443"),expected.replace("hawkzlol/zpbw","other/repo"),
            expected.replace("/v1.0.1/","/v1.0.0/"), expected.replace("mc26.2","mc26.3"),
            expected.replace("/download/","/download/../download/"), expected.replace("/download/","/download/%2e%2e/download/"),
            expected.replace("/download/","/download\\"),expected.replace("mc26.2","mc26%2e2"),
            "$expected?redirect=elsewhere", "$expected#fragment", "$expected/extra", "$expected/",
            "https://github.com/hawkzlol/zpbw/releases/latest",
            "https://github.com/hawkzlol/zpbw/releases/tag/v1.0.1",
            "javascript:alert(1)", "//github.com/hawkzlol/zpbw/releases/latest", "garbage")
        for (url in unsafe) for (target in listOf("26.2","26.3")) {
            val value=result(response=release(assets="[${asset("26.2",url=url)},${asset("26.3")}]"),target=target)
            assertEquals(UpdateCheckResult(UpdateCheckStatus.NO_COMPATIBLE_ASSETS),value,url)
        }
    }

    @Test fun missingWrongDuplicateAndLegacyAssetsRemainQuietForBothLanes() {
        val invalid=listOf<String?>(null,"[]","[${asset("26.2")}]","[${asset("26.3")}]",
            "[${asset("26.2")},${asset("26.2")},${asset("26.3")}]",
            "[${asset("26.2")},${asset("26.3")},${asset("26.3")}]",
            "[${asset("26.2",version="1.0.0")},${asset("26.3")}]",
            "[${asset("26.2",name="zpbw-1.0.1.jar")},${asset("26.3")}]",
            "[${asset("26.2",name="zpbw-mc26.2-1.0.1-sources.jar")},${asset("26.3",name="zpbw-mc26.3-1.0.1-debug.jar")}]",
            "[${asset("26.2",state="new")},${asset("26.3")}]",
            "[${asset("26.2")},${asset("26.3",state="uploading")}]",
            "[${asset("26.2",size="0")},${asset("26.3")}]",
            "[${asset("26.2")},${asset("26.3",size="-1")}]",
            "[${asset("26.2",size="1.5")},${asset("26.3")}]",
            "[${asset("26.2",size="null")},${asset("26.3")}]",
            "[${asset("26.2",size="true")},${asset("26.3")}]",
            "[${asset("26.2",size="\"123\"")},${asset("26.3")}]",
            "[${asset("26.2",size="9223372036854775808")},${asset("26.3")}]",
            "[{\"name\":\"zpbw-mc26.2-1.0.1.jar\"},${asset("26.3")}]")
        for (assets in invalid) for (target in listOf("26.2","26.3"))
            assertEquals(UpdateCheckResult(UpdateCheckStatus.NO_COMPATIBLE_ASSETS),result(response=release(assets=assets),target=target),assets)
        assertEquals(UpdateCheckStatus.NO_COMPATIBLE_ASSETS,result(response=release("1.0.1")).status)
    }

    @Test fun unrelatedAssetsDoNotReplaceOrInvalidateACompletePair() {
        val assets="[${asset("26.2")},${asset("26.3")},${asset("26.2",name="zpbw-mc26.2-1.0.1-sources.jar")}]"
        for (target in listOf("26.2","26.3"))
            assertEquals(UpdateCheckStatus.AVAILABLE,result(response=release(assets=assets),target=target).status)
    }

    @Test fun releaseNotesAndGenericPageUrlCannotReplaceNativeAssetIdentity() {
        val response=release()
        val withUntrustedMetadata=response.copy(body=response.body.dropLast(1)+
            ""","body":"private release notes","html_url":"https://untrusted.invalid/redirect"}""")
        for (target in listOf("26.2","26.3")) {
            val value=result(response=withUntrustedMetadata,target=target)
            assertEquals(UpdateCheckStatus.AVAILABLE,value.status)
            assertEquals("https://github.com/hawkzlol/zpbw/releases/download/v1.0.1/zpbw-mc$target-1.0.1.jar",value.url)
            assertFalse(value.toString().contains("private"))
        }
        val overlongUrl="https://github.com/"+"x".repeat(2_049)
        assertEquals(UpdateCheckStatus.NO_COMPATIBLE_ASSETS,
            result(response=release(assets="[${asset("26.2",url=overlongUrl)},${asset("26.3")}]" )).status)
    }

    @Test fun unsupportedNativeTargetDoesNotFetchOrChooseAnotherLane() {
        for (target in listOf("26.1","26.3.1","26.2 ","unknown","")) {
            val calls=AtomicInteger()
            val checker=UpdateChecker("1.0.0",target) { calls.incrementAndGet(); release() }
            checker.start()
            assertEquals(UpdateCheckResult(UpdateCheckStatus.NO_COMPATIBLE_ASSETS),await(checker),target)
            assertEquals(0,calls.get(),target)
        }
    }

    @Test fun boundsAssetCountEvenWhenTheResponseByteLimitIsNotReached() {
        val assets=List(UpdateChecker.MAX_RELEASE_ASSETS+1) { "{}" }.joinToString(",","[","]")
        assertEquals(UpdateCheckStatus.TOO_LARGE,result(response=release(assets=assets)).status)
        val allowed=List(UpdateChecker.MAX_RELEASE_ASSETS) { "{}" }.joinToString(",","[","]")
        assertEquals(UpdateCheckStatus.NO_COMPATIBLE_ASSETS,result(response=release(assets=allowed)).status)
    }

    @Test fun malformedDuplicateAssetMetadataIsRejectedWithoutDisclosure() {
        for (assets in listOf("null","{}","[null]","[\"asset\"]","[{\"name\":\"a\",\"name\":\"b\"}]",
            "[{\"state\":\"uploaded\",\"state\":\"uploaded\"}]","[{\"size\":1,\"size\":2}]"))
            assertEquals(UpdateCheckResult(UpdateCheckStatus.INVALID_RESPONSE),result(response=release(assets=assets)))
    }

    @Test fun timeoutFailureIsBoundedQuietAndDoesNotRetry() {
        val calls=AtomicInteger()
        val checker=UpdateChecker("1.0.0","26.3") { calls.incrementAndGet(); throw SocketTimeoutException("private timeout") }
        checker.start()
        assertEquals(UpdateCheckResult(UpdateCheckStatus.NETWORK_ERROR),await(checker))
        checker.start(); assertEquals(1,calls.get())
    }
}
