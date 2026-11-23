package com.hawkslol.zpbw.gui

import com.hawkslol.zpbw.overlay.EtherwarpOptions
import com.hawkslol.zpbw.overlay.EtherwarpSoundTiming
import java.awt.Color
import java.util.Locale
import kotlin.math.roundToInt

internal interface ClickGuiCanvas {
    fun box(rect: GuiRect, color: Int, radius: Int = 0)
    fun gradient(rect: GuiRect, stops: IntArray, vertical: Boolean)
    fun textWidth(value: String, size: Int, strong: Boolean = false): Int
    fun text(value: String, x: Int, y: Int, size: Int, color: Int, strong: Boolean = false)
    fun centeredLabel(value: String, x: Int, centerY: Int, size: Int, color: Int, strong: Boolean = true)
    fun icon(icon: GuiIcon, rect: GuiRect)
}

internal enum class GuiIcon(val texture: String) { SETTINGS("settings"), COPY("content_copy"), HEART("favorite") }

internal object ClickGuiView {
    const val SCRIM = 0xA50C1520.toInt()
    private const val CONTENT_LEFT = ClickGuiViewport.SIDEBAR_WIDTH + 13
    private const val PANEL = 0xFF202C3A.toInt()
    private const val BORDER = 0xFF5B6B7C.toInt()
    private const val LINE = 0xFF435466.toInt()
    private const val SELECTED = 0xFF3B546C.toInt()
    private const val HOVER = 0xFF304356.toInt()
    private const val FIELD = 0xFF263646.toInt()
    private const val PRIMARY = 0xFFF1F5F9.toInt()
    private const val MUTED = 0xFFB2C3D4.toInt()
    private const val DISABLED = 0xFF8498AC.toInt()
    private const val BLUE = 0xFFA8D5F2.toInt()
    private const val WARNING = 0xFFF4C49E.toInt()

    fun draw(canvas: ClickGuiCanvas, page: GuiPage, values: ClickGuiValues, version: String,
             hover: GuiAction?, focus: GuiAction?, notice: String?, warning: Boolean,
             picker: GuiPicker = GuiPicker()) = with(canvas) {
        val panel = GuiRect(0, 0, ClickGuiViewport.WIDTH, ClickGuiViewport.HEIGHT)
        box(panel, BORDER, 10)
        box(panel.inset(1), PANEL, 9)
        box(GuiRect(1, 37, 638, 1), LINE)
        box(GuiRect(1, 357, 638, 1), LINE)
        box(GuiRect(ClickGuiViewport.SIDEBAR_WIDTH - 1, 38, 1, 361), LINE)

        text("ZPBW", 16, 7, 22, PRIMARY, true)
        text("v$version", 22 + textWidth("ZPBW", 22, true), 13, 13, MUTED)
        if (hover == GuiAction.CLOSE) box(GuiAction.CLOSE.bounds, HOVER, 5)
        centered("×", GuiAction.CLOSE.bounds, 21, MUTED)
        text("FEATURES", 17, 52, 11, MUTED, true)
        feature("Overlay", GuiAction.OVERLAY, page == GuiPage.OVERLAY || page == GuiPage.COLOR, hover)
        feature("Blinkwarp", GuiAction.BLINKWARP, page == GuiPage.BLINKWARP, hover)
        feature("NoSneakDelay", GuiAction.NO_SNEAK_DELAY, page == GuiPage.NO_SNEAK_DELAY, hover)
        feature("Sounds", GuiAction.SOUND, page == GuiPage.SOUND || page == GuiPage.SOUND_PICKER, hover)

        val general = GuiAction.GENERAL.bounds
        if (page == GuiPage.GENERAL || hover == GuiAction.GENERAL) {
            box(general, if (page == GuiPage.GENERAL) SELECTED else HOVER, 6)
        }
        val generalCenter = general.y + general.height / 2
        icon(GuiIcon.SETTINGS, GuiRect(general.x + 5, generalCenter - 9, 18, 18))
        centeredLabel("General", general.x + 26, generalCenter, 14, PRIMARY)

        when (page) {
            GuiPage.BLINKWARP -> {
                heading("Blinkwarp", "Predict your landing while the server confirms.")
                toggle(values.enabled, hover == GuiAction.TOGGLE)
                stepper("Chain maximum", "Maximum overlapping warps.", values.chainMax, 1..5,
                    GuiAction.CHAIN_DOWN, GuiAction.CHAIN_UP, hover)
                stepper("Timeout (Ticks)", "How long to wait for confirmation.", values.timeoutTicks, 2..20,
                    GuiAction.TIMEOUT_DOWN, GuiAction.TIMEOUT_UP, hover)
                chatSwitch("Smooth Landings (Visual)", "Softens the small vertical camera hop after a Blinkwarp.",
                    GuiAction.SMOOTH_LANDINGS, values.smoothLandings, hover)
            }
            GuiPage.NO_SNEAK_DELAY -> {
                heading("NoSneakDelay", "Safely send sneak input as early as possible.")
                toggle(values.noSneakDelay, hover == GuiAction.TOGGLE)
            }
            GuiPage.GENERAL -> {
                heading("General", "Chat, location safeguards, and project links.")
                chatSwitch("Reduced Chat Logs", "Show a short message only when the destination differs.",
                    GuiAction.REDUCE_CHAT, values.reducedChatLogs, hover)
                chatSwitch("Disable Chat Logs", "Mute all automatic ZPBW chat messages.", GuiAction.DISABLE_CHAT,
                    values.disableChatLogs, hover)
                chatSwitch("Disable in Puzzles, Bosses & Similar", "Prevents Blinkwarp from running in locations where you can't Etherwarp.",
                    GuiAction.RESTRICT_LOCATIONS, values.disableInRestrictedLocations, hover)
                text("Run /zpbw help to see available commands.", CONTENT_LEFT, 278, 13, MUTED)
                button("GitHub", GuiAction.GITHUB, true, hover)
                button("Report Issues", GuiAction.GITHUB_ISSUES, true, hover)
                val credit = "ZPBW, made with"
                text(credit, CONTENT_LEFT, 335, 12, MUTED)
                val heartX = CONTENT_LEFT + textWidth(credit, 12) + 4
                icon(GuiIcon.HEART, GuiRect(heartX, 336, 12, 12))
                text("by zhxwks", heartX + 16, 335, 12, MUTED)
            }
            GuiPage.OVERLAY -> overlay(values.overlay, hover)
            GuiPage.SOUND -> sound(values.overlay, picker, hover)
            GuiPage.COLOR -> colorEditor(picker, hover)
            GuiPage.SOUND_PICKER -> soundPicker(values.overlay.soundId, picker, hover)
        }
        if (notice != null) text(notice, CONTENT_LEFT, 371, 12, if (warning) WARNING else MUTED)
        button("Copy debug logs", GuiAction.COPY_LOGS, true, hover)
        if (focus != null && focus != GuiAction.SOUND_SEARCH && focus != GuiAction.COLOR_HEX) outline(focus.bounds, BLUE)
    }

    private fun ClickGuiCanvas.overlay(options: EtherwarpOptions, hover: GuiAction?) {
        heading("Overlay", "Show the landing target using Blinkwarp’s prediction.")
        overlaySwitch("Enabled", GuiAction.OVERLAY_TOGGLE, options.enabled, hover)
        overlaySwitch("Show Always", GuiAction.SHOW_ALWAYS, options.showAlways, hover, "Preview while holding an Etherwarp item.")
        for (color in OverlayColor.entries) {
            rowLabel(color.label, color.action)
            val rect = color.action.bounds
            val argb = color.read(options)
            box(rect, BORDER, 5)
            box(rect.inset(1), if (hover == color.action) HOVER else FIELD, 4)
            centeredLabel(formatRgba(argb), rect.x + 9, rect.y + rect.height / 2, 12, MUTED)
            swatch(GuiRect(rect.right - 25, rect.y + 3, 20, 15), argb)
        }
        rowLabel("Render Mode", GuiAction.RENDER_MODE)
        button("${options.style.label}  ›", GuiAction.RENDER_MODE, true, hover)
        rowLabel("Line Width", GuiAction.LINE_WIDTH)
        slider(GuiAction.LINE_WIDTH.bounds, (options.lineWidth - 1) / 7.0)
        centeredLabel("${options.lineWidth}", 455, GuiAction.LINE_WIDTH.bounds.y + 10, 13, PRIMARY)
        overlaySwitch("Depth", GuiAction.DEPTH, options.depth, hover, "Hide the overlay behind blocks.")
        overlaySwitch("Full Block", GuiAction.FULL_BLOCK, options.fullBlock, hover, "Draw a full cube instead of the block’s outline shape.")
    }

    private fun ClickGuiCanvas.sound(options: EtherwarpOptions, picker: GuiPicker, hover: GuiAction?) {
        heading("Sounds", "Choose a Minecraft sound to play for Etherwarp.")
        overlaySwitch("Custom Sound", GuiAction.SOUND_TOGGLE, options.customSound, hover)
        rowLabel("Play Sound on", GuiAction.SOUND_TIMING)
        button("${options.soundTiming.label}  ›", GuiAction.SOUND_TIMING, true, hover)
        text(if (options.soundTiming == EtherwarpSoundTiming.BLINKWARP) "Play immediately when Blinkwarp predicts a landing."
            else "Play when the server’s Etherwarp sound arrives.", CONTENT_LEFT, 170, 13, MUTED)
        centeredLabel(fit(options.soundId, 443), CONTENT_LEFT, 201, 14, PRIMARY)
        button("Choose sound", GuiAction.PICK_SOUND, true, hover)
        button(if (picker.previewSound == options.soundId) "Stop" else "Preview", GuiAction.PREVIEW_SOUND, true, hover)
        divider(250)
        soundAdjustment("Volume", GuiAction.SOUND_VOLUME, options.soundVolume, options.soundVolume / 2.0)
        soundAdjustment("Pitch", GuiAction.SOUND_PITCH, options.soundPitch, (options.soundPitch - 0.1) / 1.9)
        if (options.soundTiming == EtherwarpSoundTiming.BLINKWARP) {
            divider(323)
            overlaySwitch("Play Sound on Failed Guesses.", GuiAction.SOUND_ON_FAILED, options.soundOnFailedWarps, hover)
        }
    }

    private fun ClickGuiCanvas.soundAdjustment(label: String, action: GuiAction, value: Float, fraction: Double) {
        rowLabel(label, action)
        text(String.format(Locale.ROOT, "%.2f×", value), action.bounds.x - 48, action.bounds.y + 4, 14, MUTED)
        slider(action.bounds, fraction.coerceIn(0.0, 1.0))
    }

    private fun ClickGuiCanvas.colorEditor(picker: GuiPicker, hover: GuiAction?) {
        heading(picker.color.label, "Choose a color or enter its hex value.")
        val draft = picker.draft
        val plane = GuiAction.COLOR_PLANE.bounds
        val hue = GuiAction.COLOR_HUE.bounds
        val alpha = GuiAction.COLOR_ALPHA.bounds
        box(plane, Color.HSBtoRGB(draft.hue, 1f, 1f))
        gradient(plane, intArrayOf(0xFFFFFFFF.toInt(), 0x00FFFFFF), vertical = false)
        gradient(plane, intArrayOf(0, 0xFF000000.toInt()), vertical = true)
        gradient(hue, IntArray(7) { Color.HSBtoRGB(it / 6f, 1f, 1f) }, vertical = true)
        val x = plane.x + ((plane.width - 1) * draft.saturation).roundToInt()
        val y = plane.y + ((plane.height - 1) * (1f - draft.brightness)).roundToInt()
        box(GuiRect(x - 6, y - 6, 12, 12), PANEL, 6)
        box(GuiRect(x - 5, y - 5, 10, 10), PRIMARY, 5)
        box(GuiRect(x - 3, y - 3, 6, 6), draft.argb or 0xFF000000.toInt(), 3)
        val selectedHue = hue.y + ((hue.height - 1) * draft.hue).roundToInt()
        box(GuiRect(hue.x - 2, selectedHue - 3, hue.width + 4, 7), PANEL, 2)
        box(GuiRect(hue.x - 1, selectedHue - 1, hue.width + 2, 3), PRIMARY, 1)

        swatch(GuiRect(alpha.x, plane.y, alpha.width, 48), draft.argb)
        hexField(picker, hover)
        val opacity = "${(draft.opacity * 100f / 255f).roundToInt()}%"
        text("Opacity", alpha.x, 239, 14, PRIMARY, true)
        text(opacity, alpha.right - textWidth(opacity, 14), 239, 14, MUTED)
        checker(alpha)
        gradient(alpha.inset(1), intArrayOf(draft.argb and 0xFFFFFF, draft.argb or 0xFF000000.toInt()), vertical = false)
        val opacityX = alpha.x + ((alpha.width - 1) * draft.opacity / 255f).roundToInt()
        box(GuiRect(opacityX - 4, alpha.y - 2, 9, alpha.height + 4), PANEL, 3)
        box(GuiRect(opacityX - 2, alpha.y - 1, 5, alpha.height + 2), PRIMARY, 2)
        button("Apply", GuiAction.APPLY_COLOR, picker.hexValid, hover)
        button("Cancel", GuiAction.BACK, true, hover)
    }

    private fun ClickGuiCanvas.hexField(picker: GuiPicker, hover: GuiAction?) {
        val field = GuiAction.COLOR_HEX.bounds
        val x = field.x + 9
        val y = field.y + 5
        text("Hex", field.x, 179, 14, PRIMARY, true)
        box(field, when {
            !picker.hexValid -> WARNING
            picker.hexCursor != null -> BLUE
            else -> BORDER
        }, 5)
        box(field.inset(1), if (hover == GuiAction.COLOR_HEX) HOVER else FIELD, 4)
        val caret = picker.hexCursor
        val selection = picker.hexSelection
        var caretX = x
        if (caret != null) {
            caretX += textWidth(picker.hexText.take(caret), 16, true)
            if (selection != null && selection != caret) {
                val end = x + textWidth(picker.hexText.take(selection), 16, true)
                box(GuiRect(minOf(caretX, end), y, kotlin.math.abs(caretX - end), 17), SELECTED, 2)
            }
        }
        text(picker.hexText, x, y, 16, PRIMARY, true)
        if (caret != null) box(GuiRect(caretX, y, 1, 17), PRIMARY)
        if (!picker.hexValid) text("Use 6 or 8 hex digits.", field.x, 285, 13, WARNING)
    }

    private fun ClickGuiCanvas.soundPicker(selected: String, picker: GuiPicker, hover: GuiAction?) {
        heading("Choose sound", "Preview any Minecraft sound before selecting it.")
        soundSearchField(picker, hover)
        if (picker.sounds.isEmpty()) text("No matching sounds.", CONTENT_LEFT, 142, 14, MUTED)
        for ((index, id) in picker.sounds.withIndex()) {
            val action = soundRows[index]
            val rect = action.bounds
            box(rect, BORDER, 5)
            box(rect.inset(1), if (id == selected) SELECTED else if (hover == action) HOVER else FIELD, 4)
            centeredLabel(fit(id.removePrefix("minecraft:"), rect.width - 20), rect.x + 10, rect.y + rect.height / 2, 14, PRIMARY)
            button(if (picker.previewSound == id) "Stop" else "Preview", soundPreviews[index], true, hover)
        }
        val next = GuiAction.SOUND_NEXT.bounds
        centeredLabel("${picker.soundPage + 1} / ${picker.soundPages}", next.right + 12, next.y + next.height / 2, 14, MUTED)
        button("‹", GuiAction.SOUND_PREVIOUS, picker.soundPage > 0, hover)
        button("›", GuiAction.SOUND_NEXT, picker.soundPage + 1 < picker.soundPages, hover)
        button("Back", GuiAction.BACK, true, hover)
    }

    private fun ClickGuiCanvas.soundSearchField(picker: GuiPicker, hover: GuiAction?) {
        val field = GuiAction.SOUND_SEARCH.bounds
        val active = picker.searchCursor != null
        box(field, if (active) BLUE else BORDER, 6)
        box(field.inset(1), if (hover == GuiAction.SOUND_SEARCH) FIELD else PANEL, 5)
        val x = field.x + 10
        val y = field.y + 5
        val width = field.width - 20
        val caret = (picker.searchCursor ?: 0).coerceIn(0, picker.search.length)
        var first = 0
        while (first < caret && textWidth(picker.search.substring(first, caret), 14, true) >= width) first++
        var end = picker.search.length
        while (end > caret && textWidth(picker.search.substring(first, end), 14, true) >= width) end--
        fun position(index: Int) = x + textWidth(picker.search.substring(first, index.coerceIn(first, end)), 14, true)
        val caretX = position(caret)
        val selection = picker.searchSelection
        if (active && selection != null && position(selection) != caretX) {
            val other = position(selection)
            box(GuiRect(minOf(caretX, other), y, kotlin.math.abs(caretX - other), 16), SELECTED, 2)
        }
        val empty = picker.search.isEmpty()
        val label = if (empty) "Search sounds…" else picker.search.substring(first, end)
        centeredLabel(label, x + if (empty && active) 4 else 0, field.y + field.height / 2, 14,
            if (empty) MUTED else PRIMARY, strong = !empty)
        if (active) box(GuiRect(caretX, y + 2, 1, 13), BLUE)
    }

    private fun ClickGuiCanvas.fit(label: String, width: Int): String {
        if (textWidth(label, 14, true) <= width) return label
        var end = label.length - 1
        while (end > 0 && textWidth(label.take(end) + "…", 14, true) > width) end--
        return label.take(end) + "…"
    }

    private fun ClickGuiCanvas.rowLabel(label: String, action: GuiAction) {
        val rect = action.bounds
        centeredLabel(label, CONTENT_LEFT, rect.y + rect.height / 2, 15, PRIMARY)
    }

    private fun ClickGuiCanvas.overlaySwitch(label: String, action: GuiAction, enabled: Boolean, hover: GuiAction?,
                                             description: String? = null) {
        rowLabel(label, action)
        if (description != null) {
            val rect = action.bounds
            centeredLabel(description, CONTENT_LEFT + textWidth(label, 15, true) + 12, rect.y + rect.height / 2,
                13, MUTED, strong = false)
        }
        switch(action.bounds, enabled, hover == action)
    }

    private fun ClickGuiCanvas.slider(rect: GuiRect, fraction: Double) {
        val y = rect.y + rect.height / 2
        val knob = rect.x + ((rect.width - 1) * fraction).roundToInt()
        box(GuiRect(rect.x, y - 2, rect.width, 4), LINE, 2)
        if (knob > rect.x) box(GuiRect(rect.x, y - 2, knob - rect.x, 4), BLUE, 2)
        box(GuiRect(knob - 5, y - 5, 10, 10), PRIMARY, 5)
    }

    private fun ClickGuiCanvas.swatch(rect: GuiRect, argb: Int) {
        checker(rect)
        box(rect.inset(1), argb)
    }

    private fun ClickGuiCanvas.checker(rect: GuiRect) {
        box(rect, BORDER, 3)
        val area = rect.inset(1)
        for (y in 0 until area.height step 5) for (x in 0 until area.width step 5) {
            box(GuiRect(area.x + x, area.y + y, minOf(5, area.width - x), minOf(5, area.height - y)),
                if ((x / 5 + y / 5) % 2 == 0) MUTED else LINE)
        }
    }

    private fun ClickGuiCanvas.heading(title: String, summary: String) {
        text(title, CONTENT_LEFT, 47, 24, PRIMARY, true)
        text(summary, CONTENT_LEFT, 72, 14, MUTED)
        divider(92)
    }

    private fun ClickGuiCanvas.feature(label: String, action: GuiAction, selected: Boolean, hover: GuiAction?, size: Int = 13) {
        val rect = action.bounds
        if (selected || hover == action) box(rect, if (selected) SELECTED else HOVER, 6)
        val centerY = rect.y + rect.height / 2
        val mark = GuiRect(rect.x + 9, centerY - 5, 10, 10)
        box(mark, if (selected) BLUE else MUTED, 2)
        if (!selected) box(mark.inset(1), if (hover == action) HOVER else PANEL, 1)
        centeredLabel(label, rect.x + 26, centerY, size, PRIMARY)
    }

    private fun ClickGuiCanvas.toggle(enabled: Boolean, hovered: Boolean) {
        text("Enabled", CONTENT_LEFT, 105, 16, PRIMARY, true)
        switch(GuiAction.TOGGLE.bounds, enabled, hovered)
        divider(132)
    }

    private fun ClickGuiCanvas.chatSwitch(label: String, summary: String, action: GuiAction, enabled: Boolean,
                                          hover: GuiAction?) {
        val rect = action.bounds
        rowDescription(label, summary, rect.y + rect.height / 2)
        switch(rect, enabled, hover == action)
        divider(rect.y + 32)
    }

    private fun ClickGuiCanvas.switch(rect: GuiRect, enabled: Boolean, hovered: Boolean) {
        box(rect, if (enabled) BLUE else if (hovered) BORDER else LINE, 10)
        val knobX = if (enabled) rect.right - 18 else rect.x + 2
        box(GuiRect(knobX, rect.y + 2, 16, 16), if (enabled || hovered) PRIMARY else MUTED, 8)
    }

    private fun ClickGuiCanvas.stepper(label: String, summary: String, value: Int, range: IntRange,
                                       minus: GuiAction, plus: GuiAction, hover: GuiAction?) {
        val rect = minus.bounds
        rowDescription(label, summary, rect.y + rect.height / 2)
        button("−", minus, value > range.first, hover)
        button("+", plus, value < range.last, hover)
        val field = GuiRect(rect.right + 3, rect.y, plus.bounds.x - rect.right - 6, 24)
        box(field, LINE, 5)
        box(field.inset(1), PANEL, 4)
        centered(value.toString(), field, 15, PRIMARY)
        divider(rect.y + 32)
    }

    private fun ClickGuiCanvas.rowDescription(label: String, summary: String, centerY: Int) {
        centeredLabel(label, CONTENT_LEFT, centerY - 8, 16, PRIMARY)
        centeredLabel(summary, CONTENT_LEFT, centerY + 9, 13, MUTED, strong = false)
    }

    private fun ClickGuiCanvas.button(label: String, action: GuiAction, enabled: Boolean, hover: GuiAction?) {
        val rect = action.bounds
        box(rect, if (enabled) BORDER else LINE, 5)
        box(rect.inset(1), when {
            !enabled -> PANEL
            hover == action -> HOVER
            else -> FIELD
        }, 4)
        if (action == GuiAction.COPY_LOGS) {
            val start = rect.x + (rect.width - 20 - textWidth(label, 14, true)) / 2
            icon(GuiIcon.COPY, GuiRect(start, rect.y + 6, 14, 14))
            centeredLabel(label, start + 20, rect.y + rect.height / 2, 14, PRIMARY)
        } else {
            val link = action == GuiAction.GITHUB || action == GuiAction.GITHUB_ISSUES
            val size = if (action in listOf(GuiAction.CHAIN_UP, GuiAction.CHAIN_DOWN, GuiAction.TIMEOUT_UP, GuiAction.TIMEOUT_DOWN)) 18 else 14
            centered(label, rect, size, if (link) BLUE else if (enabled) PRIMARY else DISABLED)
        }
    }

    private fun ClickGuiCanvas.centered(label: String, rect: GuiRect, size: Int, color: Int) {
        text(label, rect.x + (rect.width - textWidth(label, size, true)) / 2,
            rect.y + (rect.height - size) / 2, size, color, true)
    }

    private fun ClickGuiCanvas.divider(y: Int) = box(GuiRect(CONTENT_LEFT, y, 624 - CONTENT_LEFT, 1), LINE)

    private fun ClickGuiCanvas.outline(rect: GuiRect, color: Int) {
        box(GuiRect(rect.x, rect.y, rect.width, 1), color)
        box(GuiRect(rect.x, rect.bottom - 1, rect.width, 1), color)
        box(GuiRect(rect.x, rect.y + 1, 1, rect.height - 2), color)
        box(GuiRect(rect.right - 1, rect.y + 1, 1, rect.height - 2), color)
    }
}
