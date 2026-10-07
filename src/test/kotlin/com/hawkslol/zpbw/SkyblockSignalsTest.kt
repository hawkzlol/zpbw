package com.hawkslol.zpbw

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SkyblockSignalsTest {
    @Test fun recognizesSidebarTitleWithFormatting() {
        assertTrue(SkyblockSignals.matches("§e§lSKYBLOCK", emptyList(), emptyList()))
        assertTrue(SkyblockSignals.matches("SkyBlock CO-OP", emptyList(), emptyList()))
    }

    @Test fun recognizesSidebarHintsWithoutOtherMods() {
        listOf("Purse: 100", "Piggy: 5", "Bits: 0", "The Catacombs (F7)", "Dungeon: Master Mode").forEach {
            assertTrue(SkyblockSignals.matches("", listOf("§7$it"), emptyList()), it)
        }
    }

    @Test fun recognizesNonemptyTabArea() {
        assertTrue(SkyblockSignals.matches("", emptyList(), listOf("§bArea: §7Hub")))
        assertTrue(SkyblockSignals.matches("", emptyList(), listOf("Other line\n Area : Dungeon Hub ")))
    }

    @Test fun recognizesMultilineHeaderAndFooterSignals() {
        assertTrue(SkyblockSignals.matches("", emptyList(), listOf("Players online\n§bArea: §7Hub\nProfile: Apple")))
        assertTrue(SkyblockSignals.matches("", emptyList(), listOf("", "Visit the store\r\nArea: The Catacombs")))
        assertFalse(SkyblockSignals.matches("", emptyList(), listOf("Welcome to Hypixel\nStore: example.invalid")))
    }

    @Test fun normalizesUnicodeSpacingAndRejectsDecorativeAreaLabels() {
        assertTrue(SkyblockSignals.matches("", emptyList(), listOf("\u00a0Area\u00a0:\u2007Hub\u00a0")))
        assertTrue(SkyblockSignals.matches("", listOf("The\u00a0Catacombs (F7)"), emptyList()))
        listOf("Area: §7   ", "Area: ---", "Area: ⏣", "Area: \u00a0\u2007").forEach {
            assertFalse(SkyblockSignals.matches("", emptyList(), listOf(it)), it)
        }
    }

    @Test fun recognizesDungeonFloorCodesOnlyInCatacombsContext() {
        listOf("(E)", "(F1)", "(F7)", "(M1)", "(M7)").forEach {
            assertTrue(SkyblockSignals.matches("", listOf("Catacombs $it"), emptyList()), it)
            assertFalse(SkyblockSignals.matches("", listOf("Arena $it"), emptyList()), it)
        }
        assertFalse(SkyblockSignals.matches("", listOf("(F8)", "(M0)", "(Easy)"), emptyList()))
        assertFalse(SkyblockSignals.matches("Other Game", listOf("(F1)"), emptyList()))
    }

    @Test fun unrelatedAndBlankSignalsDoNotStartOnboarding() {
        assertFalse(SkyblockSignals.matches("BED WARS", listOf("Kills: 1"), listOf("Players: 12")))
        assertFalse(SkyblockSignals.matches("", emptyList(), listOf("Area:", "Area: §7  ", "Unknown Area: Hub")))
        assertFalse(SkyblockSignals.matches("", emptyList(), emptyList()))
    }
}
