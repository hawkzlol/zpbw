package com.hawkslol.zpbw

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.io.IOException
import java.security.MessageDigest
import kotlin.test.*

class ZpbwSettingsStoreTest {
    @Test fun chatPreferencesDefaultToFullAndPersistIndependently() {
        val directory = Files.createTempDirectory("zpbw-chat-options")
        val store = ZpbwSettingsStore(directory)
        Files.writeString(store.path, """{"enabled":true,"chainMax":4,"timeoutTicks":16,"firstInstall":false,"custom":42}""")
        val start = store.load()
        assertFalse(start.reducedChatLogs)
        assertFalse(start.disableChatLogs)
        val muted = start.copy(reducedChatLogs = true, disableChatLogs = true)
        store.saveStrict(muted)
        assertEquals(muted, ZpbwSettingsStore(directory).load())
        store.saveStrict(muted.copy(disableChatLogs = false))
        assertEquals(muted.copy(disableChatLogs = false), ZpbwSettingsStore(directory).load())
        assertEquals(42, JsonParser.parseString(Files.readString(store.path)).asJsonObject.get("custom").asInt)
    }
    private fun assertBackup(dir: Path, original: ByteArray): Path {
        val digest=MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        val backup=dir.resolve("backups/config-pre-chainmax-1.0.3-$digest.json")
        assertTrue(Files.isRegularFile(backup,NOFOLLOW_LINKS)); assertFalse(Files.isSymbolicLink(backup))
        assertContentEquals(original,Files.readAllBytes(backup),"Rollback preserves the entire original document byte for byte")
        assertTrue(Files.list(dir.resolve("backups")).use { entries -> entries.allMatch { it.fileName.toString().endsWith(".json") } })
        return backup
    }
    private fun removeBackups(dir: Path) {
        val backups=dir.resolve("backups")
        if (Files.isDirectory(backups,NOFOLLOW_LINKS)) {
            Files.list(backups).use { entries -> entries.forEach { Files.delete(it) } }
        }
        Files.deleteIfExists(backups)
    }
    @Test fun upgradePreservesPreferencesAndDoesNotPretendToBeFresh() {
        val dir=Files.createTempDirectory("zpbw-upgrade")
        val original="""{"enabled":true,"noSneakDelay":true,"timeoutTicks":30,"intentionalFailure":true,"fast":false,"chainMax":3,"other":{"keep":7}}"""
        Files.writeString(dir.resolve("config.json"),original)
        val store=ZpbwSettingsStore(dir)
        assertEquals(ZpbwSettings(enabled=true,noSneakDelay=true,timeoutTicks=20,firstInstall=false,chainMax=3),store.load())
        val upgraded=Files.readString(store.path)
        assertContains(upgraded,"\"other\""); assertContains(upgraded,"\"keep\": 7")
        for (retired in listOf("intentionalFailure","fast")) assertFalse(upgraded.contains(retired))
        assertContains(upgraded,"\"chainMax\": 3")
        assertContains(upgraded,"\"firstInstall\": false")
        assertBackup(dir,original.toByteArray(Charsets.UTF_8))
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
        assertFalse(Files.exists(dir.resolve("backups")),"Explicit settings writes do not create migration backups")
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
        removeBackups(dir); Files.delete(store.path); Files.delete(dir)
    }
    @Test fun retiredPredictionSettingAloneTriggersMigrationWithoutLosingPreferences() {
        val dir = Files.createTempDirectory("zpbw-retired-prediction")
        val store = ZpbwSettingsStore(dir)
        val original = """{"enabled":true,"noSneakDelay":true,"timeoutTicks":12,"firstInstall":false,"chainMax":2,"experimentalPredictions":{"old":true},"futureSetting":7}"""
        Files.writeString(store.path, original)
        val result = store.loadWithResult()
        assertEquals(ZpbwSettings(true, true, 12, false, 2), result.settings)
        assertFalse(result.normalizationPending)
        assertTrue(result.diagnostics.isEmpty())
        val saved = Files.readString(store.path)
        assertFalse(saved.contains("experimentalPredictions"))
        assertContains(saved, "\"futureSetting\": 7")
        assertBackup(dir, original.toByteArray(Charsets.UTF_8))
        removeBackups(dir)
        Files.delete(store.path)
        Files.delete(dir)
    }

    @Test fun retiredOptionsAreRemovedWithoutDiscardingTheActiveChainLimit() {
        val dir = Files.createTempDirectory("zpbw-settings-migration")
        val store = ZpbwSettingsStore(dir)
        for (legacy in listOf("false", "true", "1", "{}")) {
            Files.writeString(store.path, """{"enabled":true,"fast":$legacy,"chainMax":64,"noSneakDelay":true,"futureSetting":7}""")
            assertEquals(ZpbwSettings(true, true, firstInstall=false), store.load())
            val saved = Files.readString(store.path)
            assertFalse(saved.contains("\"fast\"")); assertContains(saved,"\"chainMax\": 5")
            assertContains(saved, "futureSetting")
        }
        for (invalid in listOf("""{"enabled":"true"}""", """{"noSneakDelay":1}""")) {
            Files.writeString(store.path, invalid)
            assertFails { store.load() }
            assertEquals(invalid, Files.readString(store.path))
        }
        removeBackups(dir); Files.delete(store.path); Files.delete(dir)
    }
    @Test fun corruptOrOversizedConfigIsNotOverwritten() {
        val dir = Files.createTempDirectory("zpbw-settings-broken")
        val store = ZpbwSettingsStore(dir)
        for (original in listOf("{broken", "[]", " ".repeat(65_537))) {
            Files.writeString(store.path, original)
            assertFails { store.load() }; assertFails { store.save(ZpbwSettings(true)) }
            assertFails { store.saveStrict(ZpbwSettings(chainMax=1)) }
            assertEquals(original, Files.readString(store.path))
        }
        Files.delete(store.path); Files.delete(dir)
    }

    @Test fun missingChainLimitDefaultsToFiveWithoutCreatingAFreshConfig() {
        val parent=Files.createTempDirectory("zpbw-chain-default")
        val dir=parent.resolve("zpbw"); val store=ZpbwSettingsStore(dir)
        assertEquals(ZpbwSettingsLoadResult(ZpbwSettings()),store.loadWithResult())
        assertFalse(Files.exists(dir))
        Files.createDirectory(dir)
        val original="""{"enabled":true,"noSneakDelay":true,"timeoutTicks":7,"unknown":{"keep":[1,2]}}"""
        Files.writeString(store.path,original)
        val result=store.loadWithResult()
        assertEquals(ZpbwSettings(enabled=true,noSneakDelay=true,timeoutTicks=7,firstInstall=false,chainMax=5),result.settings)
        assertFalse(result.normalizationPending); assertTrue(result.diagnostics.isEmpty())
        assertContains(Files.readString(store.path),"\"chainMax\": 5")
        assertContains(Files.readString(store.path),"\"unknown\"")
        assertBackup(dir,original.toByteArray(Charsets.UTF_8))
        removeBackups(dir); Files.delete(store.path); Files.delete(dir); Files.delete(parent)
    }

    @Test fun everyChainLimitRoundTripsAndRequiresTheValidatedRange() {
        val dir=Files.createTempDirectory("zpbw-chain-roundtrip"); val store=ZpbwSettingsStore(dir)
        for (limit in 1..5) {
            val settings=ZpbwSettings(enabled=true,noSneakDelay=true,timeoutTicks=9,firstInstall=false,chainMax=limit)
            store.saveStrict(settings)
            assertEquals(settings,ZpbwSettingsStore(dir).load())
        }
        for (invalid in listOf(Int.MIN_VALUE,0,6,Int.MAX_VALUE)) {
            assertFailsWith<IllegalArgumentException> { ZpbwSettings(chainMax=invalid) }
        }
        assertEquals(listOf("config.json"),Files.list(dir).use { it.map { file -> file.fileName.toString() }.toList() })
        Files.delete(store.path); Files.delete(dir)
    }

    @Test fun normalizedConfigLoadsWithoutCallingTheWriter() {
        val dir=Files.createTempDirectory("zpbw-chain-read-only")
        val path=dir.resolve("config.json")
        val original="""{"enabled":true,"noSneakDelay":true,"timeoutTicks":8,"firstInstall":false,"chainMax":2,"unknown":{"keep":7}}"""
        Files.writeString(path,original)
        val originalTime=Files.getLastModifiedTime(path)
        val store=ZpbwSettingsStore(dir) { _,_ -> fail("Normalized config must not be rewritten") }
        assertEquals(ZpbwSettingsLoadResult(ZpbwSettings(enabled=true,noSneakDelay=true,timeoutTicks=8,firstInstall=false,chainMax=2)),store.loadWithResult())
        assertEquals(original,Files.readString(path)); assertEquals(originalTime,Files.getLastModifiedTime(path))
        assertFalse(Files.exists(dir.resolve("backups")),"A nonmigration load is read only")
        Files.delete(path); Files.delete(dir)
    }

    @Test fun signedIntegralChainLimitsClampAndDecimalIntegralNumbersNormalize() {
        val dir=Files.createTempDirectory("zpbw-chain-clamp"); val store=ZpbwSettingsStore(dir)
        val values=listOf(Int.MIN_VALUE.toString() to 1,"-1" to 1,"0" to 1,"1" to 1,"2.0" to 2,
            "3" to 3,"4" to 4,"5" to 5,"6" to 5,"64" to 5,Int.MAX_VALUE.toString() to 5)
        for ((input,expected) in values) {
            Files.writeString(store.path,"""{"chainMax":$input,"firstInstall":false,"timeoutTicks":11}""")
            val loaded=store.loadWithResult()
            assertEquals(expected,loaded.settings.chainMax,input)
            assertFalse(loaded.normalizationPending,input); assertTrue(loaded.diagnostics.isEmpty(),input)
            assertEquals(expected.toString(),JsonParser.parseString(Files.readString(store.path)).asJsonObject.get("chainMax").toString())
            assertEquals(expected,ZpbwSettingsStore(dir).load().chainMax)
        }
        removeBackups(dir); Files.delete(store.path); Files.delete(dir)
    }

    @Test fun malformedChainFieldsDefaultIndependentlyWithBoundedDiagnostics() {
        val dir=Files.createTempDirectory("zpbw-chain-invalid"); val store=ZpbwSettingsStore(dir)
        for (invalid in listOf("2.5","2147483648","-2147483649","true","null","\"2\"","{}","[]","1e999999")) {
            Files.writeString(store.path,"""{"chainMax":$invalid,"enabled":true,"noSneakDelay":true,"timeoutTicks":7,"firstInstall":true,"unknown":{"keep":7},"fast":{},"intentionalFailure":true}""")
            val result=store.loadWithResult()
            assertEquals(ZpbwSettings(enabled=true,noSneakDelay=true,timeoutTicks=7,firstInstall=true,chainMax=5),result.settings,invalid)
            assertFalse(result.normalizationPending,invalid)
            assertEquals(1,result.diagnostics.size,invalid)
            assertTrue(result.diagnostics.single().startsWith("CHAIN_MAX_CONFIG_INVALID "),invalid)
            assertTrue(result.diagnostics.single().length<=100,invalid)
            val saved=Files.readString(store.path)
            assertContains(saved,"\"chainMax\": 5"); assertContains(saved,"\"keep\": 7")
            assertFalse(saved.contains("\"fast\"")); assertFalse(saved.contains("\"intentionalFailure\""))
            assertTrue(store.loadWithResult().diagnostics.isEmpty())
        }
        removeBackups(dir); Files.delete(store.path); Files.delete(dir)
    }

    @Test fun migrationFailureKeepsParsedPreferencesOriginalBytesAndNoTemporaryFiles() {
        val dir=Files.createTempDirectory("zpbw-chain-migration-fail")
        for ((input,expected) in listOf("2.5" to 5,"2.0" to 2)) for (unsupported in listOf(false,true)) {
            val original="""{"enabled":true,"noSneakDelay":true,"timeoutTicks":7,"chainMax":$input,"unknown":{"keep":7}}"""
            val path=dir.resolve("config.json"); Files.writeString(path,original)
            val originalTime=Files.getLastModifiedTime(path)
            var calls=0
            val store=ZpbwSettingsStore(dir) { source,target ->
                calls++
                assertEquals(dir,source.parent); assertEquals(path,target)
                if (unsupported) throw AtomicMoveNotSupportedException("private-source","private-target","private-reason")
                throw IOException("private migration failure")
            }
            val result=store.loadWithResult()
            assertEquals(ZpbwSettings(enabled=true,noSneakDelay=true,timeoutTicks=7,firstInstall=false,chainMax=expected),result.settings)
            assertTrue(result.normalizationPending)
            assertEquals(input=="2.5",result.diagnostics.any { it.startsWith("CHAIN_MAX_CONFIG_INVALID ") })
            assertTrue(result.diagnostics.any { it.startsWith("CONFIG_NORMALIZATION_PENDING ") })
            assertTrue(result.diagnostics.all { it.length<=100 && !it.contains("private") })
            assertEquals(original,Files.readString(path)); assertEquals(originalTime,Files.getLastModifiedTime(path))
            val backup=assertBackup(dir,original.toByteArray(Charsets.UTF_8))
            val preservedTime=FileTime.fromMillis(1_234)
            Files.setLastModifiedTime(backup,preservedTime)
            assertEquals(result.settings,store.load(),"Backward-compatible load retains parsed preferences")
            assertEquals(preservedTime,Files.getLastModifiedTime(backup),"Repeated migration failure reuses the preserved copy without overwriting")
            assertEquals(2,calls)
            assertEquals(setOf("config.json","backups"),Files.list(dir).use { entries -> entries.map { it.fileName.toString() }.toList().toSet() },"Only the original config and verified backups remain; staging files are removed")
        }
        removeBackups(dir); Files.delete(dir.resolve("config.json")); Files.delete(dir)
    }

    @Test fun strictAtomicFailureDoesNotReplaceConfigAndLegacySaveRetainsItsFallback() {
        val dir=Files.createTempDirectory("zpbw-chain-save-fail")
        val original=ZpbwSettings(enabled=true,noSneakDelay=true,timeoutTicks=8,firstInstall=false,chainMax=3)
        ZpbwSettingsStore(dir).saveStrict(original)
        val path=dir.resolve("config.json"); val originalBytes=Files.readAllBytes(path)
        val originalTime=Files.getLastModifiedTime(path)
        for (unsupported in listOf(false,true)) {
            val store=ZpbwSettingsStore(dir) { source,target ->
                assertEquals(dir,source.parent); assertEquals(path,target)
                if (unsupported) throw AtomicMoveNotSupportedException("source","target","unsupported")
                throw IOException("controlled replacement failure")
            }
            assertFails { store.saveStrict(original.copy(chainMax=1)) }
            assertContentEquals(originalBytes,Files.readAllBytes(path))
            assertEquals(originalTime,Files.getLastModifiedTime(path))
            assertEquals(original,ZpbwSettingsStore(dir).load())
            assertEquals(1,Files.list(dir).use { it.count().toInt() })
        }
        val legacyStore=ZpbwSettingsStore(dir) { _,_ -> throw AtomicMoveNotSupportedException("source","target","unsupported") }
        legacyStore.save(original.copy(enabled=false))
        assertEquals(original.copy(enabled=false),ZpbwSettingsStore(dir).load())
        assertEquals(1,Files.list(dir).use { it.count().toInt() })
        Files.delete(path); Files.delete(dir)
    }

    @Test fun migrationBackupsPreserveExactBytesReuseIdenticalOriginalsAndKeepDifferentOriginals() {
        val dir=Files.createTempDirectory("zpbw-backup-bytes"); val store=ZpbwSettingsStore(dir)
        val original="{\r\n  \"enabled\": true, \"chainMax\": 3, \"unknown\": {\"text\":\"Å字\"}\r\n}\r\n".toByteArray(Charsets.UTF_8)
        Files.write(store.path,original)
        assertFalse(store.loadWithResult().normalizationPending)
        val backup=assertBackup(dir,original)
        val originalBackupTime=FileTime.fromMillis(5_678)
        Files.setLastModifiedTime(backup,originalBackupTime)
        Files.write(store.path,original)
        assertFalse(store.loadWithResult().normalizationPending)
        assertEquals(originalBackupTime,Files.getLastModifiedTime(backup))
        assertEquals(1,Files.list(dir.resolve("backups")).use { it.count().toInt() })
        val different=original+" \n".toByteArray(Charsets.UTF_8)
        Files.write(store.path,different)
        assertFalse(store.loadWithResult().normalizationPending)
        assertNotEquals(backup,assertBackup(dir,different))
        assertContentEquals(original,Files.readAllBytes(backup))
        assertEquals(2,Files.list(dir.resolve("backups")).use { it.count().toInt() })
        removeBackups(dir); Files.delete(store.path); Files.delete(dir)
    }

    @Test fun blockedBackupDirectoryKeepsParsedSettingsAndOriginalAndDoesNotBlockExplicitSave() {
        val dir=Files.createTempDirectory("zpbw-backup-blocked")
        val path=dir.resolve("config.json")
        val original="""{"enabled":true,"noSneakDelay":true,"timeoutTicks":8,"chainMax":3,"unknown":7}"""
        Files.writeString(path,original)
        val backups=dir.resolve("backups"); Files.writeString(backups,"preserve existing user data")
        val store=ZpbwSettingsStore(dir) { _,_ -> fail("Backup failure must stop before config replacement") }
        val result=store.loadWithResult()
        assertEquals(ZpbwSettings(enabled=true,noSneakDelay=true,timeoutTicks=8,firstInstall=false,chainMax=3),result.settings)
        assertTrue(result.normalizationPending)
        assertTrue(result.diagnostics.single().startsWith("CONFIG_NORMALIZATION_PENDING "))
        assertEquals(original,Files.readString(path))
        assertEquals("preserve existing user data",Files.readString(backups))
        assertEquals(setOf("config.json","backups"),Files.list(dir).use { entries -> entries.map { it.fileName.toString() }.toList().toSet() })
        ZpbwSettingsStore(dir).saveStrict(result.settings.copy(chainMax=1))
        assertEquals(1,ZpbwSettingsStore(dir).load().chainMax)
        assertEquals("preserve existing user data",Files.readString(backups))
        Files.delete(backups); Files.delete(path); Files.delete(dir)
    }

    @Test fun invalidExistingHashAddressedBackupIsNeverOverwritten() {
        for (invalid in listOf("mismatched","oversized","directory")) {
            val dir=Files.createTempDirectory("zpbw-backup-invalid")
            val store=ZpbwSettingsStore(dir)
            val original="""{"enabled":true,"chainMax":3,"unknown":{"keep":7}}""".toByteArray(Charsets.UTF_8)
            Files.write(store.path,original)
            assertFalse(store.loadWithResult().normalizationPending)
            val backup=assertBackup(dir,original)
            Files.write(store.path,original)
            Files.delete(backup)
            if (invalid=="directory") Files.createDirectory(backup)
            else Files.writeString(backup,if (invalid=="oversized") "x".repeat(65_537) else "preserve corrupt original backup")
            val before=if (invalid=="directory") null else Files.readAllBytes(backup)
            val result=store.loadWithResult()
            assertEquals(3,result.settings.chainMax); assertTrue(result.settings.enabled)
            assertTrue(result.normalizationPending,invalid)
            assertTrue(result.diagnostics.single().startsWith("CONFIG_NORMALIZATION_PENDING "))
            assertContentEquals(original,Files.readAllBytes(store.path))
            if (before!=null) assertContentEquals(before,Files.readAllBytes(backup))
            else assertTrue(Files.isDirectory(backup,NOFOLLOW_LINKS))
            assertEquals(1,Files.list(dir.resolve("backups")).use { it.count().toInt() })
            removeBackups(dir); Files.delete(store.path); Files.delete(dir)
        }
    }
}
