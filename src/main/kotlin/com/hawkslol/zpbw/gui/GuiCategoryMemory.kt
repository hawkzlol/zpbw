package com.hawkslol.zpbw.gui

/** Session-only navigation: retain a category, never a transient picker or its draft. */
internal class GuiCategoryMemory {
    var category = GuiPage.BLINKWARP
        private set

    fun remember(page: GuiPage) {
        category = when (page) {
            GuiPage.COLOR -> GuiPage.OVERLAY
            GuiPage.SOUND_PICKER -> GuiPage.SOUND
            else -> page
        }
    }
}
