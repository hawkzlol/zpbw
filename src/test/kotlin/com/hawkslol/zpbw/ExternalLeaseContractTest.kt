package com.hawkslol.zpbw

import com.hawkslol.zpbw.api.ExternalPredictionLease
import java.lang.reflect.Modifier
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.*

/** Packaging and hook-wiring checks supplement the executable ownership tests; not live co-load proof. */
class ExternalLeaseContractTest {
    private val root = Path(System.getProperty("user.dir"))
    private val runtime get() = root.resolve("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt").readText().replace("\r\n", "\n")
    @Test fun javaStaticReflectionContractNeedsNoKotlinInstance() {
        val type = ExternalPredictionLease::class.java
        assertEquals(1, type.getMethod("protocolVersion").invoke(null))
        for (name in listOf("tryAcquire", "owns", "release")) {
            val method = type.getMethod(name, Any::class.java)
            assertTrue(Modifier.isPublic(method.modifiers)); assertTrue(Modifier.isStatic(method.modifiers))
            assertEquals(Boolean::class.javaPrimitiveType, method.returnType)
        }
    }
    @Test fun establishedReflectionAddressRemainsCompatible() {
        val type = Class.forName("dev.zpbw.candidate.api.ExternalPredictionLease")
        assertEquals(1, type.getMethod("protocolVersion").invoke(null))
        for (name in listOf("tryAcquire", "owns", "release")) {
            val method = type.getMethod(name, Any::class.java)
            assertTrue(Modifier.isStatic(method.modifiers))
            assertEquals(Boolean::class.javaPrimitiveType, method.returnType)
        }
    }
    @Test fun modifyingHooksReturnBeforeTouchingExternalTraffic() {
        val names = listOf("redirectShovelBlockUse", "beforeUse", "capture", "sourceTick", "sourceTickFinished",
            "nativeResponse", "afterGenuine", "deferUseClick", "deferEntityClick", "deferAction",
            "beforePhysicsUpdate", "afterPhysicsUpdate")
        for (name in names) {
            val body = runtime.substringAfter("fun $name(").substringAfter("{\n").trimStart()
            assertTrue(body.startsWith("if (externalPredictionActive()) return"), name)
        }
        val receive = runtime.substringAfter("fun beforeGenuine(").substringBefore("fun nativeResponse(")
        assertTrue(receive.indexOf("if (externalPredictionActive()) return false") < receive.indexOf("val entry = chain.head"))
        assertContains(receive, "teleportIds.record(packet.id())")
        val sent = runtime.substringAfter("fun afterSend(").substringBefore("fun sourceTick(")
        assertTrue(sent.indexOf("if (externalPredictionActive()) return") < sent.indexOf("val tail = chain.tail"))
        val acquire = runtime.substringAfter("fun acquireExternalPrediction(").substringBefore("fun ownsExternalPrediction(")
        for (condition in listOf("chain.count", "chain.size", "chain.hasActions", "queuedUse", "handling != null",
            "bypass", "replayingUseClick", "replayUseInput != null", "sourceCollision != null", "physicsBefore != null", "movementInputs.hasRebase",
            "recoveringUntil", "NoSneakDelay.hasCommitment()", "hasClientLoaded()")) assertContains(acquire, condition)
        assertFalse(acquire.contains("saveSettings")); assertFalse(acquire.contains("chain.clear()"))
    }
    @Test fun nsdCannotCommitOrOverrideInputWhileLeased() {
        val nsd = root.resolve("src/main/kotlin/com/hawkslol/zpbw/NoSneakDelay.kt").readText()
        assertContains(nsd, "fun hasCommitment() = latch.value != null || latch.deferred != null")
        for (name in listOf("frame", "applySample", "beginClick", "finishTick")) {
            val body = nsd.substringAfter("fun $name(").substringBefore("\n    }")
            assertContains(body, "ZpbwRuntime.externalPredictionActive()")
        }
        assertContains(nsd, "!ZpbwRuntime.externalPredictionActive() && packet is ServerboundPlayerInputPacket")
        assertContains(nsd, "if (ZpbwRuntime.externalPredictionActive()) null else")
        assertContains(runtime.substringAfter("fun reset()"), "externalOwnership.bind(null, null, null)")
        assertContains(runtime.substringAfter("private fun attach("), "externalOwnership.bind(null, null, null)")
        assertContains(runtime, "externalOwnership.invalidateConnection(connection)")
    }
}
