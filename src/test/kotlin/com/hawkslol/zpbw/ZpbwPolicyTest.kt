package com.hawkslol.zpbw

import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class PublicBoundaryTest {
    @Test fun predictionAndSneakUseSessionSafetyRatherThanHostnameAdmission() {
        assertFalse(EffectiveSources.productionFiles().any { it.fileName.toString() == "ZpbwPolicy.kt" })
        val runtime = Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val sneak = Files.readString(EffectiveSources.path("src/main/kotlin/com/hawkslol/zpbw/NoSneakDelay.kt"))
        for (source in listOf(runtime, sneak)) {
            assertFalse(source.contains("ZpbwPolicy"))
            assertFalse(source.contains("currentServer"))
            assertFalse(source.contains("hypixel.net"))
            assertFalse(source.contains("127.0.0.1"))
        }
        assertContains(runtime, "mc.connection?.connection === p.stream")
        assertContains(runtime, "mc.player === p.player && mc.level === p.level && p.stream.isConnected")
        assertContains(sneak, "connection?.isConnected == true && it.connection.hasClientLoaded()")
        assertContains(sneak, "p !== player || mc.level !== level || connection !== stream")
    }
}
