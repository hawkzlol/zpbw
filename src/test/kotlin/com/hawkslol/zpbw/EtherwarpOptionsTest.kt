package com.hawkslol.zpbw

import com.google.gson.JsonParser
import com.hawkslol.zpbw.overlay.EtherwarpAudio
import com.hawkslol.zpbw.overlay.EtherwarpOptions
import com.hawkslol.zpbw.overlay.EtherwarpSoundTiming
import com.hawkslol.zpbw.overlay.OverlayShape
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import kotlin.test.*

class EtherwarpOptionsTest {
    @Test fun olderConfigEnablesGuardAndNewPreferencesRoundTripWithoutLosingOtherKeys() {
        val dir = Files.createTempDirectory("zpbw-etherwarp-options")
        val store = ZpbwSettingsStore(dir)
        Files.writeString(store.path, """{"firstInstall":false,"chainMax":4,"other":17,"etherwarpOverlay":{"future":true}}""")
        val old = store.load()
        assertTrue(old.disableInRestrictedLocations)
        assertEquals(EtherwarpOptions(), old.etherwarpOverlay)
        val overlay = EtherwarpOptions(enabled = true, successFill = 0x7FCC3377, successOutline = 0xFF221111.toInt(),
            failFill = 0x00ABCDEF, failOutline = 0x11FF4321, style = OverlayShape.OUTLINE, lineWidth = 8,
            depth = true, fullBlock = false, customSound = true, soundTiming = EtherwarpSoundTiming.BLINKWARP,
            soundId = "minecraft:entity.enderman.teleport", soundVolume = 0.56f, soundPitch = 1.75f,
            showAlways = true, soundOnFailedWarps = true)
        val wanted = old.copy(disableInRestrictedLocations = false, etherwarpOverlay = overlay)
        store.saveStrict(wanted)
        assertEquals(wanted, ZpbwSettingsStore(dir).load())
        val json = JsonParser.parseString(Files.readString(store.path)).asJsonObject
        assertEquals(17, json["other"].asInt)
        assertTrue(json.getAsJsonObject("etherwarpOverlay")["future"].asBoolean)
    }

    @Test fun soundsRejectModIdsAndNormalizeOnlyNumericVolumeAndPitch() {
        val store = ZpbwSettingsStore(Files.createTempDirectory("zpbw-sound-options"))
        Files.writeString(store.path, """{"etherwarpOverlay":{"soundId":"blackwell:custom","soundVolume":3.5,"soundPitch":0}}""")
        val options = store.load().etherwarpOverlay
        assertEquals(EtherwarpOptions.DEFAULT_SOUND, options.soundId)
        assertEquals(2f, options.soundVolume)
        assertEquals(0.1f, options.soundPitch)
        Files.writeString(store.path, """{"etherwarpOverlay":{"soundVolume":"0.25","soundPitch":1e100}}""")
        val malformed = store.load().etherwarpOverlay
        assertEquals(1f, malformed.soundVolume)
        assertEquals(1f, malformed.soundPitch)
    }

    @Test fun locationSignalsUseCurrentAreaAndKeepOwnIslandAndRegularDungeonEligible() {
        fun area(sidebar: List<String> = emptyList(), tab: List<String> = emptyList()) =
            EtherwarpLocations.classify(SkyblockDetector.Signals("SKYBLOCK", sidebar, tab))
        for (name in listOf("Dojo", "Jungle Temple", "Glacite Mineshaft")) {
            assertTrue(area(listOf("§7⏣ §a$name")).blocked)
        }
        assertTrue(area(tab = listOf("Event\nMap: Mineshaft")).blocked)
        assertTrue(area(listOf("⏣ Someone’s Island"), listOf("Area: Private Island")).blocked)
        assertFalse(area(listOf("⏣ Your Island"), listOf("Area: Private Island")).blocked)
        assertFalse(area(listOf("⏣ Hub"), listOf("Player exploring Dojo")).blocked)
        val master = area(listOf("The Catacombs (M7)"))
        assertTrue(master.dungeon && master.floorSeven && !master.blocked)
        assertEquals(EtherwarpLocations.Area(dungeon = true), area(listOf("The Catacombs (F6)")))
        for (hash in listOf(632136981, 2089453469, -1358669872, 1128554492)) {
            assertTrue(EtherwarpLocations.restrictedCore(hash))
            assertFalse(EtherwarpLocations.restrictedCore(hash + 1))
        }
        assertEquals(-185, EtherwarpLocations.roomCenter(-169, -200).x)
        assertEquals(-153, EtherwarpLocations.roomCenter(-168, -200).x)
    }

    @Test fun officialSoundMatchingDoesNotConsumeAnOrdinaryMobSoundOrDistantCue() {
        assertTrue(EtherwarpAudio.isSignature(true, 1f, 0.53968257f))
        assertFalse(EtherwarpAudio.isSignature(false, 1f, 0.53968257f))
        assertFalse(EtherwarpAudio.isSignature(true, 1f, 1f))
        assertFalse(EtherwarpAudio.isSignature(true, 0.8f, 0.53968257f))
        assertTrue(EtherwarpAudio.nearby(Vec3.ZERO, Vec3(4.0, 0.0, 0.0)))
        assertFalse(EtherwarpAudio.nearby(Vec3.ZERO, Vec3(4.1, 0.0, 0.0)))
    }
}
