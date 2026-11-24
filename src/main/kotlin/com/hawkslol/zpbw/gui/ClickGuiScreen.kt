package com.hawkslol.zpbw.gui

import com.hawkslol.zpbw.ZpbwClient
import com.hawkslol.zpbw.ZpbwMessages
import com.hawkslol.zpbw.ZpbwRuntime
import com.hawkslol.zpbw.nativebridge.NativeTextFocus
import com.hawkslol.zpbw.overlay.EtherwarpAudio
import com.hawkslol.zpbw.overlay.EtherwarpOptions
import com.hawkslol.zpbw.overlay.EtherwarpSoundTiming
import com.hawkslol.zpbw.overlay.OverlayShape
import com.mojang.blaze3d.platform.InputConstants
import net.minecraft.client.gui.GuiGraphicsExtractor
import net.minecraft.client.gui.font.TextFieldHelper
import net.minecraft.client.gui.screens.ConfirmLinkScreen
import net.minecraft.client.gui.screens.Screen
import net.minecraft.client.input.CharacterEvent
import net.minecraft.client.input.KeyEvent
import net.minecraft.client.input.MouseButtonEvent
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.network.chat.Component
import net.minecraft.client.renderer.texture.DynamicTexture
import net.minecraft.resources.Identifier
import org.lwjgl.system.MemoryUtil
import java.awt.image.BufferedImage
import java.awt.image.DataBufferInt
import java.net.URI
import kotlin.math.roundToInt

internal class ClickGuiScreen : Screen(Component.literal("ZPBW Settings")) {
    private var page = categoryMemory.category
        set(value) { field = value; categoryMemory.remember(value) }
    private var focus: GuiAction? = null
        set(value) {
            val hadTextFocus = isEditor(field)
            field = value
            val hasTextFocus = isEditor(value)
            if (hadTextFocus != hasTextFocus) NativeTextFocus.set(minecraft, this, hasTextFocus)
        }
    private var notice: String? = null
    private var warning = false
    private var exportingLogs = false
    private val version = ZpbwClient.version
    private var panel: PanelTexture? = null
    private var picker = GuiPicker()
    private var soundCatalog = emptyList<String>()
    private var activeSlider: GuiAction? = null
    private var pendingLineWidth: Int? = null
    private var pendingSoundAdjustment: Float? = null
    private var preview: Pair<String, SimpleSoundInstance>? = null
    private val editingHex get() = focus == GuiAction.COLOR_HEX
    private val hexEditor by lazy {
        TextFieldHelper({ picker.hexText }, ::updateHex, TextFieldHelper.createClipboardGetter(minecraft),
            TextFieldHelper.createClipboardSetter(minecraft), { HEX_INPUT.matches(it) })
    }
    private val soundSearch by lazy {
        TextFieldHelper({ picker.search }, { filterSounds(it, 0) }, TextFieldHelper.createClipboardGetter(minecraft),
            TextFieldHelper.createClipboardSetter(minecraft), { SOUND_QUERY.matches(it) })
    }

    override fun isPauseScreen() = false

    override fun extractRenderState(graphics: GuiGraphicsExtractor, mouseX: Int, mouseY: Int, delta: Float) {
        val viewport = viewport() ?: return
        val values = values()
        val hover = actionAt(page, values, viewport.x(mouseX.toDouble()), viewport.y(mouseY.toDouble()), picker)
        val pixelScale = viewport.pixelScale.coerceAtMost(4.0)
        val rasterWidth = (ClickGuiViewport.WIDTH * pixelScale).roundToInt().coerceAtLeast(1)
        val rasterHeight = (ClickGuiViewport.HEIGHT * pixelScale).roundToInt().coerceAtLeast(1)
        val surface = panel?.takeIf { it.width == rasterWidth && it.height == rasterHeight } ?: run {
            releasePanel()
            PanelTexture(rasterWidth, rasterHeight).also {
                minecraft.textureManager.register(PANEL_TEXTURE, it.texture)
                panel = it
            }
        }
        val shownPicker = picker.copy(
            hexCursor = if (editingHex) hexEditor.cursorPos else null,
            hexSelection = if (editingHex) hexEditor.selectionPos else null,
            searchCursor = if (focus == GuiAction.SOUND_SEARCH) soundSearch.cursorPos else null,
            searchSelection = if (focus == GuiAction.SOUND_SEARCH) soundSearch.selectionPos else null,
            previewSound = preview?.takeIf { minecraft.soundManager.isActive(it.second) }?.first
        )
        val shownValues = if (page == GuiPage.SOUND || page == GuiPage.SOUND_PICKER) {
            val id = EtherwarpAudio.effectiveSoundId(values.overlay.soundId)
            values.copy(overlay = values.overlay.copy(soundId = id))
        } else values
        surface.update(ClickGuiFrame(page, shownValues, version, hover, focus, notice, warning, shownPicker))
        graphics.fill(0, 0, width, height, ClickGuiView.SCRIM)
        graphics.pose().pushMatrix()
        try {
            graphics.pose().translate(viewport.left.toFloat(), viewport.top.toFloat())
                .scale(viewport.scaleX.toFloat(), viewport.scaleY.toFloat())
            graphics.blit(RenderPipelines.GUI_TEXTURED, PANEL_TEXTURE, 0, 0, 0f, 0f,
                ClickGuiViewport.WIDTH, ClickGuiViewport.HEIGHT, rasterWidth, rasterHeight, rasterWidth, rasterHeight)
        } finally {
            graphics.pose().popMatrix()
        }
    }

    override fun mouseClicked(event: MouseButtonEvent, doubleClick: Boolean): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
        val viewport = viewport() ?: return false
        focus = null
        val values = values()
        val x = viewport.x(event.x())
        val y = viewport.y(event.y())
        val action = actionAt(page, values, x, y, picker) ?: return false
        if (action in DRAG_CONTROLS) {
            activeSlider = action
            updateSlider(action, x, y)
        } else activate(action, values)
        return true
    }

    override fun mouseDragged(event: MouseButtonEvent, dragX: Double, dragY: Double): Boolean {
        val slider = activeSlider ?: return false
        val viewport = viewport() ?: return false
        updateSlider(slider, viewport.x(event.x()), viewport.y(event.y()))
        return true
    }

    override fun mouseReleased(event: MouseButtonEvent): Boolean {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return false
        val slider = activeSlider ?: return false
        activeSlider = null
        val width = pendingLineWidth
        pendingLineWidth = null
        val adjustment = pendingSoundAdjustment
        pendingSoundAdjustment = null
        if (width != null) {
            val values = values()
            if (width != values.overlay.lineWidth) save(values, values.overlay.copy(lineWidth = width))
        }
        if (adjustment != null) {
            val values = values()
            val updated = updateSoundOption(values.overlay, slider, adjustment)
            if (updated != values.overlay) save(values, updated)
        }
        return true
    }

    private fun updateSlider(action: GuiAction, x: Double, y: Double) {
        val rect = action.bounds
        val along = ((x - rect.x) / (rect.width - 1)).coerceIn(0.0, 1.0)
        val down = ((y - rect.y) / (rect.height - 1)).coerceIn(0.0, 1.0)
        val draft = picker.draft
        when (action) {
            GuiAction.LINE_WIDTH -> pendingLineWidth = (along * 7).roundToInt() + 1
            GuiAction.SOUND_VOLUME -> pendingSoundAdjustment = (along * 200).roundToInt() / 100f
            GuiAction.SOUND_PITCH -> pendingSoundAdjustment = (10 + (along * 190).roundToInt()) / 100f
            GuiAction.COLOR_PLANE -> setColor(draft.copy(saturation = along.toFloat(), brightness = (1.0 - down).toFloat()))
            GuiAction.COLOR_HUE -> setColor(draft.copy(hue = down.toFloat()))
            GuiAction.COLOR_ALPHA -> setColor(draft.copy(opacity = (along * 255).roundToInt()))
            else -> Unit
        }
    }

    private fun setColor(draft: ColorPickerDraft) {
        picker = picker.copy(draft = draft, hexText = draft.hex, hexValid = true)
    }

    private fun updateHex(text: String) {
        val accepted = picker.draft.withHex(text)
        picker = picker.copy(draft = accepted ?: picker.draft, hexText = text.uppercase(), hexValid = accepted != null)
    }

    private fun updateSoundOption(options: EtherwarpOptions, action: GuiAction, value: Float) =
        if (action == GuiAction.SOUND_VOLUME) options.copy(soundVolume = EtherwarpOptions.volume(value))
        else options.copy(soundPitch = EtherwarpOptions.pitch(value))

    private fun stopPreview() {
        preview?.second?.let(minecraft.soundManager::stop)
        preview = null
    }

    private fun playPreview(id: String, options: EtherwarpOptions) {
        val wasPlaying = preview?.let { it.first == id && minecraft.soundManager.isActive(it.second) } == true
        stopPreview()
        if (wasPlaying) return
        if (options.soundVolume == 0f) {
            notice = "Volume is set to zero."
            return
        }
        val instance = EtherwarpAudio.preview(id, options)
        if (instance != null) preview = id to instance
        else {
            warning = true
            notice = "Sound playback is unavailable."
        }
    }

    override fun charTyped(event: CharacterEvent): Boolean {
        if (page == GuiPage.COLOR && editingHex) return hexEditor.charTyped(event)
        if (focus == GuiAction.SOUND_SEARCH) return soundSearch.charTyped(event)
        return super.charTyped(event)
    }

    override fun keyPressed(event: KeyEvent): Boolean {
        val values = values()
        if (event.key() == InputConstants.KEY_ESCAPE && page in listOf(GuiPage.COLOR, GuiPage.SOUND_PICKER)) {
            activate(GuiAction.BACK, values)
            return true
        }
        if (editingHex && !event.isCycleFocus()) {
            if (event.isSelection() && picker.hexValid) activate(GuiAction.APPLY_COLOR, values)
            else hexEditor.keyPressed(event)
            return true
        }
        if (focus == GuiAction.SOUND_SEARCH && !event.isCycleFocus()) {
            soundSearch.keyPressed(event)
            return true
        }
        val actions = availableActions(page, values, picker)
        if (event.isCycleFocus()) {
            val current = actions.indexOf(focus)
            val next = if (event.hasShiftDown()) {
                if (current <= 0) actions.lastIndex else current - 1
            } else (current + 1) % actions.size
            focus = actions[next]
            return true
        }
        val action = focus
        if (action == GuiAction.COLOR_PLANE && (event.key() == InputConstants.KEY_UP || event.key() == InputConstants.KEY_DOWN)) {
            val step = if (event.key() == InputConstants.KEY_UP) 0.01f else -0.01f
            val draft = picker.draft
            setColor(draft.copy(brightness = (draft.brightness + step).coerceIn(0f, 1f)))
            return true
        }
        if (action != null && (event.key() == InputConstants.KEY_LEFT || event.key() == InputConstants.KEY_RIGHT)) {
            val direction = if (event.key() == InputConstants.KEY_RIGHT) 1 else -1
            if (action == GuiAction.LINE_WIDTH) {
                save(values, values.overlay.copy(lineWidth = (values.overlay.lineWidth + direction).coerceIn(1, 8)))
                return true
            }
            val draft = picker.draft
            if (action == GuiAction.COLOR_ALPHA || action == GuiAction.COLOR_HUE || action == GuiAction.COLOR_PLANE) {
                setColor(when (action) {
                    GuiAction.COLOR_ALPHA -> draft.copy(opacity = (draft.opacity + direction).coerceIn(0, 255))
                    GuiAction.COLOR_HUE -> draft.copy(hue = (draft.hue + direction / 360f).mod(1f))
                    else -> draft.copy(saturation = (draft.saturation + direction / 100f).coerceIn(0f, 1f))
                })
                return true
            }
            if (action == GuiAction.SOUND_VOLUME || action == GuiAction.SOUND_PITCH) {
                val current = if (action == GuiAction.SOUND_VOLUME) values.overlay.soundVolume else values.overlay.soundPitch
                save(values, updateSoundOption(values.overlay, action, ((current * 100).roundToInt() + direction) / 100f))
                return true
            }
        }
        if (event.isSelection() && action != null && action in actions) {
            activate(action, values)
            return true
        }
        return super.keyPressed(event)
    }

    private fun activate(action: GuiAction, values: ClickGuiValues) {
        if (action.isHelp) return
        notice = null
        warning = false
        val previousPage = page
        when (action) {
            GuiAction.CHAIN_HELP, GuiAction.TIMEOUT_HELP, GuiAction.SOUND_ON_FAILED_HELP -> Unit
            GuiAction.OVERLAY -> page = GuiPage.OVERLAY
            GuiAction.BLINKWARP -> page = GuiPage.BLINKWARP
            GuiAction.NO_SNEAK_DELAY -> page = GuiPage.NO_SNEAK_DELAY
            GuiAction.GENERAL -> page = GuiPage.GENERAL
            GuiAction.TOGGLE -> {
                val message = if (page == GuiPage.BLINKWARP) {
                    ZpbwRuntime.select(if (values.enabled) ZpbwRuntime.Mode.OFF else ZpbwRuntime.Mode.REPLAY)
                } else ZpbwRuntime.configureNoSneakDelay(!values.noSneakDelay)
                showSaveNotice(message)
                if (!warning && message.endsWith("Waiting for server synchronization.")) notice = "Waiting for server synchronization."
            }
            GuiAction.CHAIN_DOWN, GuiAction.CHAIN_UP -> {
                val step = if (action == GuiAction.CHAIN_UP) 1 else -1
                if (!ZpbwRuntime.configureChainMaximum(values.chainMax + step)) {
                    warning = true
                    notice = "Could not save chain maximum."
                }
            }
            GuiAction.TIMEOUT_DOWN, GuiAction.TIMEOUT_UP -> {
                val step = if (action == GuiAction.TIMEOUT_UP) 1 else -1
                showSaveNotice(ZpbwRuntime.configureTimeout(values.timeoutTicks + step))
            }
            GuiAction.REDUCE_CHAT, GuiAction.DISABLE_CHAT -> {
                val reduced = values.reducedChatLogs xor (action == GuiAction.REDUCE_CHAT)
                val disabled = values.disableChatLogs xor (action == GuiAction.DISABLE_CHAT)
                if (!ZpbwRuntime.configureChatLogs(reduced, disabled)) {
                    warning = true
                    notice = "Could not save chat settings."
                }
            }
            GuiAction.RESTRICT_LOCATIONS -> save(values, restrict = !values.disableInRestrictedLocations)
            GuiAction.SMOOTH_LANDINGS -> if (!ZpbwRuntime.configureSmoothLandings(!values.smoothLandings)) {
                warning = true
                notice = "Could not save Smooth Landings (Visual)."
            }
            GuiAction.RESET_BLINKWARP -> {
                warning = !ZpbwRuntime.resetBlinkwarpToOptimizedDefaults()
                notice = if (warning) "Could not save optimized Blinkwarp defaults." else "Optimized Blinkwarp defaults applied."
            }
            GuiAction.EXPERIMENTAL_REPAIRS -> if (!ZpbwRuntime.configureExperimentalRepairs(!values.experimentalRepairs)) {
                warning = true
                notice = "Could not save Server Lag Compensation."
            }
            GuiAction.OVERLAY_TOGGLE -> save(values, values.overlay.copy(enabled = !values.overlay.enabled))
            GuiAction.SHOW_ALWAYS -> save(values, values.overlay.copy(showAlways = !values.overlay.showAlways))
            GuiAction.DEPTH -> save(values, values.overlay.copy(depth = !values.overlay.depth))
            GuiAction.FULL_BLOCK -> save(values, values.overlay.copy(fullBlock = !values.overlay.fullBlock))
            GuiAction.RENDER_MODE -> {
                val style = OverlayShape.entries[(values.overlay.style.ordinal + 1) % OverlayShape.entries.size]
                save(values, values.overlay.copy(style = style))
            }
            GuiAction.SUCCESS_FILL, GuiAction.SUCCESS_OUTLINE, GuiAction.FAIL_FILL, GuiAction.FAIL_OUTLINE -> {
                val color = OverlayColor.entries.first { it.action == action }
                picker = GuiPicker(color, ColorPickerDraft.fromArgb(color.read(values.overlay)))
                page = GuiPage.COLOR
            }
            GuiAction.COLOR_HEX -> {
                focus = action
                hexEditor.selectAll()
            }
            GuiAction.APPLY_COLOR -> {
                if (picker.hexValid && save(values, picker.color.write(values.overlay, picker.draft.argb))) page = GuiPage.OVERLAY
            }
            GuiAction.SOUND -> page = GuiPage.SOUND
            GuiAction.SOUND_TOGGLE -> save(values, values.overlay.copy(customSound = !values.overlay.customSound))
            GuiAction.SOUND_ON_FAILED -> save(values, values.overlay.copy(soundOnFailedWarps = !values.overlay.soundOnFailedWarps))
            GuiAction.SOUND_TIMING -> {
                val timing = if (values.overlay.soundTiming == EtherwarpSoundTiming.BLINKWARP) EtherwarpSoundTiming.ETHERWARP
                    else EtherwarpSoundTiming.BLINKWARP
                save(values, values.overlay.copy(soundTiming = timing))
            }
            GuiAction.PICK_SOUND -> {
                soundCatalog = EtherwarpAudio.catalog()
                filterSounds("", 0)
                page = GuiPage.SOUND_PICKER
                focus = GuiAction.SOUND_SEARCH
                soundSearch.selectAll()
            }
            GuiAction.SOUND_SEARCH -> {
                focus = GuiAction.SOUND_SEARCH
                soundSearch.selectAll()
            }
            GuiAction.PREVIEW_SOUND -> playPreview(EtherwarpAudio.effectiveSoundId(values.overlay.soundId), values.overlay)
            GuiAction.PREVIEW_0, GuiAction.PREVIEW_1, GuiAction.PREVIEW_2,
            GuiAction.PREVIEW_3, GuiAction.PREVIEW_4, GuiAction.PREVIEW_5 -> {
                picker.sounds.getOrNull(soundPreviews.indexOf(action))?.let { playPreview(it, values.overlay) }
            }
            GuiAction.SOUND_PREVIOUS -> filterSounds(picker.search, picker.soundPage - 1)
            GuiAction.SOUND_NEXT -> filterSounds(picker.search, picker.soundPage + 1)
            GuiAction.SOUND_0, GuiAction.SOUND_1, GuiAction.SOUND_2, GuiAction.SOUND_3,
            GuiAction.SOUND_4, GuiAction.SOUND_5 -> {
                val selected = picker.sounds.getOrNull(soundRows.indexOf(action)) ?: return
                if (save(values, values.overlay.copy(soundId = selected))) page = GuiPage.SOUND
            }
            GuiAction.BACK -> page = if (page == GuiPage.SOUND_PICKER) GuiPage.SOUND else GuiPage.OVERLAY
            GuiAction.LINE_WIDTH, GuiAction.COLOR_PLANE, GuiAction.COLOR_HUE, GuiAction.COLOR_ALPHA,
            GuiAction.SOUND_PITCH, GuiAction.SOUND_VOLUME -> Unit
            GuiAction.COPY_LOGS -> {
                notice = COPYING_LOGS
                if (!exportingLogs) {
                    exportingLogs = true
                    ZpbwClient.copyLogs(minecraft) { copied ->
                        exportingLogs = false
                        if (notice == COPYING_LOGS) {
                            warning = !copied
                            notice = if (copied) "Copied debug logs." else "Clipboard unavailable. Logs printed to console."
                        }
                    }
                }
            }
            GuiAction.GITHUB -> ConfirmLinkScreen.confirmLinkNow(this, URI.create(ZpbwMessages.REPOSITORY))
            GuiAction.GITHUB_ISSUES -> ConfirmLinkScreen.confirmLinkNow(this, URI.create("${ZpbwMessages.REPOSITORY}/issues"))
            GuiAction.CLOSE -> onClose()
        }
        if (page != previousPage) {
            stopPreview()
        }
        if (focus !in availableActions(page, values(), picker)) focus = null
    }

    private fun filterSounds(search: String, page: Int) {
        val matches = searchSounds(soundCatalog, search)
        val batch = soundRows.size
        val pages = ((matches.size + batch - 1) / batch).coerceAtLeast(1)
        val current = page.coerceIn(0, pages - 1)
        picker = GuiPicker(search = search, sounds = matches.drop(current * batch).take(batch), soundPage = current, soundPages = pages)
    }

    private fun save(values: ClickGuiValues, overlay: EtherwarpOptions = values.overlay,
                     restrict: Boolean = values.disableInRestrictedLocations): Boolean {
        if (ZpbwRuntime.configureEtherwarpOptions(restrict, overlay)) return true
        warning = true
        notice = "Could not save Etherwarp settings."
        return false
    }

    private fun showSaveNotice(message: String) {
        if (message.endsWith("config could not be saved.", ignoreCase = true)) {
            warning = true
            notice = "Could not save to config."
        }
    }

    private fun values(): ClickGuiValues = with(ZpbwRuntime.settingsSnapshot()) {
        var overlay = pendingLineWidth?.let { etherwarpOverlay.copy(lineWidth = it) } ?: etherwarpOverlay
        pendingSoundAdjustment?.let { overlay = updateSoundOption(overlay, checkNotNull(activeSlider), it) }
        ClickGuiValues(enabled, noSneakDelay, chainMax, timeoutTicks, reducedChatLogs, disableChatLogs,
            disableInRestrictedLocations, overlay, smoothLandings, experimentalRepairs)
    }

    private fun viewport(): ClickGuiViewport? {
        val window = minecraft.window
        if (window.width <= 0 || window.height <= 0 || width <= 0 || height <= 0) return null
        return ClickGuiViewport(window.width, window.height, width, height)
    }

    override fun removed() {
        activeSlider = null
        pendingLineWidth = null
        pendingSoundAdjustment = null
        focus = null
        stopPreview()
        releasePanel()
        super.removed()
    }

    private fun releasePanel() {
        if (panel == null) return
        panel = null
        minecraft.textureManager.release(PANEL_TEXTURE)
    }

    private class PanelTexture(val width: Int, val height: Int) {
        val texture = DynamicTexture("ZPBW menu", width, height, true)
        private val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
        private var previous: ClickGuiFrame? = null

        fun update(frame: ClickGuiFrame) {
            if (frame == previous) return
            ClickGuiRaster.paint(image, frame)
            val source = (image.raster.dataBuffer as DataBufferInt).data
            val pixels = checkNotNull(texture.pixels)
            val abgr = MemoryUtil.memIntBuffer(pixels.pointer, source.size)
            for (i in source.indices) {
                val argb = source[i]
                abgr.put(i, (argb and 0xFF00FF00.toInt()) or ((argb and 0x00FF0000) ushr 16) or ((argb and 0xFF) shl 16))
            }
            texture.upload()
            previous = frame
        }
    }

    companion object {
        private val categoryMemory = GuiCategoryMemory()
        private fun isEditor(action: GuiAction?) = action == GuiAction.SOUND_SEARCH || action == GuiAction.COLOR_HEX
        private val HEX_INPUT = Regex("#?[0-9a-fA-F]{0,8}")
        private val SOUND_QUERY = Regex("[A-Za-z0-9:._/ -]{0,80}")
        private val DRAG_CONTROLS = setOf(GuiAction.LINE_WIDTH, GuiAction.SOUND_VOLUME, GuiAction.SOUND_PITCH,
            GuiAction.COLOR_PLANE, GuiAction.COLOR_HUE, GuiAction.COLOR_ALPHA)
        private const val COPYING_LOGS = "Copying debug logs…"
        private val PANEL_TEXTURE = Identifier.fromNamespaceAndPath("zpbw", "dynamic/menu")
    }
}
