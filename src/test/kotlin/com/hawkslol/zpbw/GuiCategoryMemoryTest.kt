package com.hawkslol.zpbw

import com.hawkslol.zpbw.gui.GuiCategoryMemory
import com.hawkslol.zpbw.gui.GuiPage
import kotlin.test.*

class GuiCategoryMemoryTest {
    @Test fun eachMainCategorySurvivesReopening() {
        val memory=GuiCategoryMemory()
        assertEquals(GuiPage.BLINKWARP,memory.category)
        for(page in listOf(GuiPage.OVERLAY,GuiPage.BLINKWARP,GuiPage.NO_SNEAK_DELAY,GuiPage.SOUND,GuiPage.GENERAL)) {
            memory.remember(page)
            assertEquals(page,memory.category)
        }
    }
    @Test fun transientPickersRetainTheirParentRatherThanReopeningADraft() {
        val memory=GuiCategoryMemory()
        memory.remember(GuiPage.COLOR);assertEquals(GuiPage.OVERLAY,memory.category)
        memory.remember(GuiPage.SOUND_PICKER);assertEquals(GuiPage.SOUND,memory.category)
        memory.remember(GuiPage.GENERAL);assertEquals(GuiPage.GENERAL,memory.category)
    }
    @Test fun aFreshClientSessionStartsWithTheDefaultCategory() {
        val old=GuiCategoryMemory();old.remember(GuiPage.SOUND)
        assertEquals(GuiPage.BLINKWARP,GuiCategoryMemory().category)
        assertEquals(GuiPage.SOUND,old.category)
    }
}
