package com.hawkslol.zpbw.gui

import com.hawkslol.zpbw.overlay.EtherwarpOptions
import com.hawkslol.zpbw.overlay.EtherwarpSoundTiming
import kotlin.math.min

/** One design coordinate space for painting and pointer input, independent of Minecraft's GUI scale. */
internal class ClickGuiViewport(frameWidth: Int, frameHeight: Int, guiWidth: Int, guiHeight: Int) {
    val pixelScale = min(frameWidth.toDouble() / (WIDTH + 116), frameHeight.toDouble() / 540)
    val scaleX = pixelScale * guiWidth / frameWidth
    val scaleY = pixelScale * guiHeight / frameHeight
    val left = (guiWidth - WIDTH * scaleX) / 2
    val top = (guiHeight - HEIGHT * scaleY) / 2

    fun x(guiX: Double) = (guiX - left) / scaleX
    fun y(guiY: Double) = (guiY - top) / scaleY

    companion object {
        const val WIDTH = 640
        const val HEIGHT = 400
        const val SIDEBAR_WIDTH = 138
    }
}

internal data class GuiRect(val x: Int, val y: Int, val width: Int, val height: Int) {
    val right get() = x + width
    val bottom get() = y + height
    fun contains(px: Double, py: Double) = px >= x && px < right && py >= y && py < bottom
    fun inset(n: Int) = GuiRect(x + n, y + n, width - n * 2, height - n * 2)
}

internal enum class GuiPage { OVERLAY, BLINKWARP, NO_SNEAK_DELAY, GENERAL, SOUND, COLOR, SOUND_PICKER }

internal enum class GuiAction(val bounds: GuiRect) {
    OVERLAY(GuiRect(11, 73, ClickGuiViewport.SIDEBAR_WIDTH - 18, 28)),
    BLINKWARP(GuiRect(11, 105, ClickGuiViewport.SIDEBAR_WIDTH - 18, 28)),
    NO_SNEAK_DELAY(GuiRect(11, 137, ClickGuiViewport.SIDEBAR_WIDTH - 18, 28)),
    GENERAL(GuiRect(11, 364, ClickGuiViewport.SIDEBAR_WIDTH - 22, 28)),
    TOGGLE(GuiRect(578, 103, 46, 20)),
    CHAIN_DOWN(GuiRect(519, 144, 24, 24)),
    CHAIN_UP(GuiRect(600, 144, 24, 24)),
    TIMEOUT_DOWN(GuiRect(519, 188, 24, 24)),
    TIMEOUT_UP(GuiRect(600, 188, 24, 24)),
    SMOOTH_LANDINGS(GuiRect(578, 232, 46, 20)),
    REDUCE_CHAT(GuiRect(578, 108, 46, 20)),
    DISABLE_CHAT(GuiRect(578, 152, 46, 20)),
    RESTRICT_LOCATIONS(GuiRect(578, 196, 46, 20)),
    OVERLAY_TOGGLE(GuiRect(578, 98, 46, 20)),
    SHOW_ALWAYS(GuiRect(578, 124, 46, 20)),
    SUCCESS_FILL(GuiRect(512, 151, 112, 21)),
    SUCCESS_OUTLINE(GuiRect(512, 177, 112, 21)),
    FAIL_FILL(GuiRect(512, 203, 112, 21)),
    FAIL_OUTLINE(GuiRect(512, 229, 112, 21)),
    RENDER_MODE(GuiRect(494, 255, 130, 21)),
    LINE_WIDTH(GuiRect(478, 281, 146, 21)),
    DEPTH(GuiRect(578, 307, 46, 20)),
    FULL_BLOCK(GuiRect(578, 333, 46, 20)),
    SOUND(GuiRect(11, 169, ClickGuiViewport.SIDEBAR_WIDTH - 18, 28)),
    SOUND_TOGGLE(GuiRect(578, 103, 46, 20)),
    SOUND_TIMING(GuiRect(494, 144, 130, 24)),
    PICK_SOUND(GuiRect(151, 218, 101, 25)),
    PREVIEW_SOUND(GuiRect(261, 218, 78, 25)),
    SOUND_VOLUME(GuiRect(435, 257, 189, 22)),
    SOUND_PITCH(GuiRect(435, 296, 189, 22)),
    SOUND_ON_FAILED(GuiRect(578, 330, 46, 20)),
    BACK(GuiRect(540, 319, 84, 26)),
    COLOR_PLANE(GuiRect(151, 111, 249, 177)),
    COLOR_HUE(GuiRect(415, 111, 19, 177)),
    COLOR_HEX(GuiRect(458, 198, 166, 28)),
    COLOR_ALPHA(GuiRect(458, 260, 166, 22)),
    APPLY_COLOR(GuiRect(448, 319, 84, 26)),
    SOUND_SEARCH(GuiRect(151, 101, 473, 26)),
    SOUND_PREVIOUS(GuiRect(151, 319, 34, 26)),
    SOUND_NEXT(GuiRect(193, 319, 34, 26)),
    SOUND_0(GuiRect(151, 137, 395, 23)),
    SOUND_1(GuiRect(151, 165, 395, 23)),
    SOUND_2(GuiRect(151, 193, 395, 23)),
    SOUND_3(GuiRect(151, 221, 395, 23)),
    SOUND_4(GuiRect(151, 249, 395, 23)),
    SOUND_5(GuiRect(151, 277, 395, 23)),
    PREVIEW_0(GuiRect(554, 137, 70, 23)),
    PREVIEW_1(GuiRect(554, 165, 70, 23)),
    PREVIEW_2(GuiRect(554, 193, 70, 23)),
    PREVIEW_3(GuiRect(554, 221, 70, 23)),
    PREVIEW_4(GuiRect(554, 249, 70, 23)),
    PREVIEW_5(GuiRect(554, 277, 70, 23)),
    GITHUB(GuiRect(151, 296, 90, 26)),
    GITHUB_ISSUES(GuiRect(249, 296, 96, 26)),
    COPY_LOGS(GuiRect(482, 365, 142, 26)),
    CLOSE(GuiRect(601, 5, 30, 28))
}

internal data class ClickGuiValues(
    val enabled: Boolean, val noSneakDelay: Boolean, val chainMax: Int, val timeoutTicks: Int,
    val reducedChatLogs: Boolean = false, val disableChatLogs: Boolean = false,
    val disableInRestrictedLocations: Boolean = true, val overlay: EtherwarpOptions = EtherwarpOptions(),
    val smoothLandings: Boolean = false
)

internal enum class OverlayColor(val action: GuiAction, val label: String, val read: (EtherwarpOptions) -> Int) {
    SUCCESS_FILL(GuiAction.SUCCESS_FILL, "Success Fill", EtherwarpOptions::successFill),
    SUCCESS_OUTLINE(GuiAction.SUCCESS_OUTLINE, "Success Outline", EtherwarpOptions::successOutline),
    FAIL_FILL(GuiAction.FAIL_FILL, "Fail Fill", EtherwarpOptions::failFill),
    FAIL_OUTLINE(GuiAction.FAIL_OUTLINE, "Fail Outline", EtherwarpOptions::failOutline);

    fun write(options: EtherwarpOptions, color: Int) = when (this) {
        SUCCESS_FILL -> options.copy(successFill = color)
        SUCCESS_OUTLINE -> options.copy(successOutline = color)
        FAIL_FILL -> options.copy(failFill = color)
        FAIL_OUTLINE -> options.copy(failOutline = color)
    }
}

internal val colorControls = listOf(GuiAction.COLOR_PLANE, GuiAction.COLOR_HUE, GuiAction.COLOR_HEX, GuiAction.COLOR_ALPHA)
internal val soundRows = listOf(GuiAction.SOUND_0, GuiAction.SOUND_1, GuiAction.SOUND_2, GuiAction.SOUND_3, GuiAction.SOUND_4, GuiAction.SOUND_5)
internal val soundPreviews = listOf(GuiAction.PREVIEW_0, GuiAction.PREVIEW_1, GuiAction.PREVIEW_2,
    GuiAction.PREVIEW_3, GuiAction.PREVIEW_4, GuiAction.PREVIEW_5)
internal data class GuiPicker(
    val color: OverlayColor = OverlayColor.SUCCESS_FILL,
    val draft: ColorPickerDraft = ColorPickerDraft.fromArgb(0),
    val hexText: String = draft.hex,
    val hexValid: Boolean = true,
    val hexCursor: Int? = null,
    val hexSelection: Int? = null,
    val search: String = "",
    val searchCursor: Int? = null,
    val searchSelection: Int? = null,
    val sounds: List<String> = emptyList(),
    val soundPage: Int = 0,
    val soundPages: Int = 1,
    val previewSound: String? = null
)

internal fun availableActions(page: GuiPage, values: ClickGuiValues, picker: GuiPicker = GuiPicker()): List<GuiAction> = buildList {
    add(GuiAction.OVERLAY)
    add(GuiAction.BLINKWARP)
    add(GuiAction.NO_SNEAK_DELAY)
    add(GuiAction.SOUND)
    add(GuiAction.GENERAL)
    if (page == GuiPage.BLINKWARP || page == GuiPage.NO_SNEAK_DELAY) add(GuiAction.TOGGLE)
    if (page == GuiPage.BLINKWARP) {
        if (values.chainMax > 1) add(GuiAction.CHAIN_DOWN)
        if (values.chainMax < 5) add(GuiAction.CHAIN_UP)
        if (values.timeoutTicks > 2) add(GuiAction.TIMEOUT_DOWN)
        if (values.timeoutTicks < 20) add(GuiAction.TIMEOUT_UP)
        add(GuiAction.SMOOTH_LANDINGS)
    }
    if (page == GuiPage.GENERAL) {
        add(GuiAction.REDUCE_CHAT)
        add(GuiAction.DISABLE_CHAT)
        add(GuiAction.RESTRICT_LOCATIONS)
        add(GuiAction.GITHUB)
        add(GuiAction.GITHUB_ISSUES)
    }
    when (page) {
        GuiPage.OVERLAY -> {
            add(GuiAction.OVERLAY_TOGGLE)
            add(GuiAction.SHOW_ALWAYS)
            addAll(OverlayColor.entries.map { it.action })
            addAll(listOf(GuiAction.RENDER_MODE, GuiAction.LINE_WIDTH, GuiAction.DEPTH, GuiAction.FULL_BLOCK))
        }
        GuiPage.SOUND -> {
            addAll(listOf(GuiAction.SOUND_TOGGLE, GuiAction.SOUND_TIMING, GuiAction.PICK_SOUND,
                GuiAction.PREVIEW_SOUND, GuiAction.SOUND_VOLUME, GuiAction.SOUND_PITCH))
            if (values.overlay.soundTiming == EtherwarpSoundTiming.BLINKWARP) add(GuiAction.SOUND_ON_FAILED)
        }
        GuiPage.COLOR -> {
            addAll(colorControls)
            if (picker.hexValid) add(GuiAction.APPLY_COLOR)
            add(GuiAction.BACK)
        }
        GuiPage.SOUND_PICKER -> {
            add(GuiAction.SOUND_SEARCH)
            for (index in picker.sounds.indices) {
                add(soundRows[index])
                add(soundPreviews[index])
            }
            if (picker.soundPage > 0) add(GuiAction.SOUND_PREVIOUS)
            if (picker.soundPage + 1 < picker.soundPages) add(GuiAction.SOUND_NEXT)
            add(GuiAction.BACK)
        }
        else -> Unit
    }
    add(GuiAction.COPY_LOGS)
    add(GuiAction.CLOSE)
}

internal fun actionAt(page: GuiPage, values: ClickGuiValues, x: Double, y: Double, picker: GuiPicker = GuiPicker()): GuiAction? =
    availableActions(page, values, picker).firstOrNull { it.bounds.contains(x, y) }
