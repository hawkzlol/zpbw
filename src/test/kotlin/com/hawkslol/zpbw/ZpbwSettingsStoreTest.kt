package com.hawkslol.zpbw

import java.nio.file.Files
import kotlin.test.*

class ZpbwSettingsStoreTest {
    @Test fun upgradePreservesPreferencesAndDoesNotPretendToBeFresh() {
        val dir=Files.createTempDirectory("zpbw-upgrade")
        val original="""{"enabled":true,"noSneakDelay":true,"timeoutTicks":30,"intentionalFailure":true,"fast":false,"chainMax":3,"other":{"keep":7}}"""
        Files.writeString(dir.resolve("config.json"),original)
        val store=ZpbwSettingsStore(dir)
        assertEquals(ZpbwSettings(true,true,20,false),store.load())
        val upgraded=Files.readString(store.path)
        assertContains(upgraded,"\"other\""); assertContains(upgraded,"\"keep\": 7")
        for (retired in listOf("intentionalFailure","chainMax","fast")) assertFalse(upgraded.contains(retired))
        assertContains(upgraded,"\"firstInstall\": false")
    }
    @Test fun firstInstallSurvivesSettingsChangesAndIsConsumedPersistently() {
        val dir=Files.createTempDirectory("zpbw-first")
        val store=ZpbwSettingsStore(dir)
        val fresh=store.load(); assertTrue(fresh.firstInstall)
        store.save(fresh.copy(enabled=true,noSneakDelay=true,timeoutTicks=7))
        val configured=ZpbwSettingsStore(dir).load(); assertTrue(configured.firstInstall)
        store.save(configured.copy(firstInstall=false))
        assertFalse(ZpbwSettingsStore(dir).load().firstInstall)
        Files.writeString(store.path,"""{"firstInstall":"false"}""")
        assertFails { store.load() }
    }
    @Test fun integerTimeoutsClampRatherThanFail() {
        val dir=Files.createTempDirectory("zpbw-clamp"); val store=ZpbwSettingsStore(dir)
        for ((input,expected) in listOf(Int.MIN_VALUE to 2,1 to 2,2 to 2,19 to 19,20 to 20,21 to 20,30 to 20,Int.MAX_VALUE to 20)) {
            Files.writeString(store.path,"""{"timeoutTicks":$input}""")
            assertEquals(expected,store.load().timeoutTicks)
        }
    }
    @Test fun everyTickLimitRoundTripsAndMalformedValuesNeverOverwriteConfig() {
        val dir=Files.createTempDirectory("zpbw-ticks"); val store=ZpbwSettingsStore(dir)
        for (ticks in 2..20) { store.save(ZpbwSettings(timeoutTicks=ticks)); assertEquals(ticks,store.load().timeoutTicks) }
        for (invalid in listOf("2.5","2147483648","true","null","\"20\"","{}")) {
            val original="""{"timeoutTicks":$invalid}"""; Files.writeString(store.path,original)
            assertFails { store.load() }; assertEquals(original,Files.readString(store.path))
        }
    }
    @Test fun defaultSettingsAndAllOptionsRoundTripAcrossNewStoreInstances() {
        val root = Files.createTempDirectory("zpbw-settings-test")
        val dir = root.resolve("config/zpbw")
        val store = ZpbwSettingsStore(dir)
        assertEquals(ZpbwSettings(), store.load())
        val configured = ZpbwSettings(true, true, 20)
        store.save(configured)
        assertEquals(configured, ZpbwSettingsStore(dir).load())
        assertEquals(1, Files.list(dir).use { it.count().toInt() }, "Atomic writer leaves no temporary files")
        store.save(configured.copy(enabled = false))
        assertEquals(ZpbwSettings(false, true, 20), ZpbwSettingsStore(dir).load(), "Disabling does not silently reset other settings")
        Files.delete(store.path); Files.delete(dir); Files.delete(dir.parent); Files.delete(root)
    }
    @Test fun olderConfigWithoutTestToggleAndUnknownFieldsArePreserved() {
        val dir = Files.createTempDirectory("zpbw-settings-legacy")
        val store = ZpbwSettingsStore(dir)
        Files.writeString(store.path, "{\"enabled\":true,\"chainMax\":5,\"futureSetting\":{\"keep\":true}}")
        assertEquals(ZpbwSettings(true, firstInstall=false), store.load())
        store.save(ZpbwSettings(false, true))
        assertContains(Files.readString(store.path), "\"futureSetting\"")
        assertContains(Files.readString(store.path), "\"keep\": true")
        Files.delete(store.path); Files.delete(dir)
    }
    @Test fun retiredOptionsCannotDisableOrderingOrChangeFixedLimit() {
        val dir = Files.createTempDirectory("zpbw-settings-migration")
        val store = ZpbwSettingsStore(dir)
        for (legacy in listOf("false", "true", "1", "{}")) {
            Files.writeString(store.path, """{"enabled":true,"fast":$legacy,"chainMax":64,"noSneakDelay":true,"futureSetting":7}""")
            assertEquals(ZpbwSettings(true, true, firstInstall=false), store.load())
            val saved = Files.readString(store.path)
            assertFalse(saved.contains("\"fast\"")); assertFalse(saved.contains("chainMax"))
            assertContains(saved, "futureSetting")
            assertEquals(5, ChainLedger<String, String>().maximum)
        }
        for (invalid in listOf("""{"enabled":"true"}""", """{"noSneakDelay":1}""")) {
            Files.writeString(store.path, invalid)
            assertFails { store.load() }
            assertEquals(invalid, Files.readString(store.path))
        }
        Files.delete(store.path); Files.delete(dir)
    }
    @Test fun corruptOrOversizedConfigIsNotOverwritten() {
        val dir = Files.createTempDirectory("zpbw-settings-broken")
        val store = ZpbwSettingsStore(dir)
        for (original in listOf("{broken", "[]", " ".repeat(65_537))) {
            Files.writeString(store.path, original)
            assertFails { store.load() }; assertFails { store.save(ZpbwSettings(true)) }
            assertEquals(original, Files.readString(store.path))
        }
        Files.delete(store.path); Files.delete(dir)
    }
}
