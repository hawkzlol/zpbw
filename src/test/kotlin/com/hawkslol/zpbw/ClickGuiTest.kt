package com.hawkslol.zpbw

import com.google.gson.JsonParser
import com.hawkslol.zpbw.gui.*
import com.hawkslol.zpbw.nativebridge.NativeIdentity
import com.hawkslol.zpbw.overlay.EtherwarpSoundTiming
import java.awt.Color
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.nio.file.Files
import java.nio.file.Path
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.*

class ClickGuiTest {
    private val resolutions = listOf(1920 to 1080, 2560 to 1440, 3440 to 1440, 3840 to 2160)
    private val values = ClickGuiValues(enabled = true, noSneakDelay = false, chainMax = 4, timeoutTicks = 19)

    @Test fun wholeWordsAndPrefixesTakePriorityOverInteriorMatches() {
        val sapling = "minecraft:block.bamboo_sapling.break"
        val pling = "minecraft:block.note_block.pling"
        val catalog = listOf(sapling, "minecraft:block.cherry_sapling.hit", pling)
        for (query in listOf("pling", "pLi")) {
            assertEquals(listOf(pling) + catalog.take(2), searchSounds(catalog, query))
        }
        assertEquals(listOf(pling), searchSounds(catalog, "NOTE BLOCK pling"))
        assertEquals(listOf(pling), searchSounds(catalog, "minecraft:block.note_block.pling"))
        assertEquals(catalog.take(2), searchSounds(catalog, "apling"))
        assertEquals(catalog, searchSounds(catalog, ""))
        assertTrue(searchSounds(catalog, "missing").isEmpty())
    }

    @Test fun framebufferBoundsAndEveryHitTargetStayFixedAcrossGuiScales() {
        for ((width, height) in resolutions + (854 to 480)) {
            val native = ClickGuiViewport(width, height, width, height)
            for (scale in 1..(height / 240)) {
                val guiWidth = (width + scale - 1) / scale
                val guiHeight = (height + scale - 1) / scale
                val viewport = ClickGuiViewport(width, height, guiWidth, guiHeight)
                val message = "${width}×$height at GUI scale $scale"
                assertEquals(native.left, viewport.left * width / guiWidth, 1e-8, message)
                assertEquals(native.top, viewport.top * height / guiHeight, 1e-8, message)
                assertEquals(native.scaleX, viewport.scaleX * width / guiWidth, 1e-8, message)
                assertEquals(native.scaleY, viewport.scaleY * height / guiHeight, 1e-8, message)
                assertTrue(native.left > 0 && native.top > 0, message)
                for (page in GuiPage.entries) {
                    val picker = GuiPicker(sounds = List(6) { "minecraft:test.sound_$it" }, soundPage = 1, soundPages = 3)
                    val actions = availableActions(page, values, picker)
                    for (action in actions) {
                        val rect = action.bounds
                        val rawX = native.left + native.scaleX * (rect.x + rect.width / 2.0)
                        val rawY = native.top + native.scaleY * (rect.y + rect.height / 2.0)
                        val x = viewport.x(rawX * guiWidth / width)
                        val y = viewport.y(rawY * guiHeight / height)
                        assertEquals(action, actionAt(page, values, x, y, picker), message)
                        assertEquals(1, actions.count { it.bounds.contains(x, y) }, message)
                    }
                }
            }
        }
    }

    @Test fun limitsAndInactivePagesHaveNoLiveHitTargets() {
        val upper = values.copy(chainMax = 5, timeoutTicks = 20)
        val lower = values.copy(chainMax = 1, timeoutTicks = 2)
        for ((settings, blocked) in listOf(
            upper to listOf(GuiAction.CHAIN_UP, GuiAction.TIMEOUT_UP),
            lower to listOf(GuiAction.CHAIN_DOWN, GuiAction.TIMEOUT_DOWN)
        )) {
            for (action in blocked) assertNull(hit(GuiPage.BLINKWARP, settings, action))
        }
        assertEquals(GuiAction.TOGGLE, hit(GuiPage.NO_SNEAK_DELAY, values, GuiAction.TOGGLE))
        assertNull(hit(GuiPage.NO_SNEAK_DELAY, values, GuiAction.CHAIN_DOWN))
        assertNull(hit(GuiPage.GENERAL, values, GuiAction.TOGGLE))
        assertEquals(GuiAction.COPY_LOGS, hit(GuiPage.GENERAL, values, GuiAction.COPY_LOGS))
        assertEquals(GuiAction.GITHUB, hit(GuiPage.GENERAL, values, GuiAction.GITHUB))
        assertEquals(GuiAction.GITHUB_ISSUES, hit(GuiPage.GENERAL, values, GuiAction.GITHUB_ISSUES))
        assertEquals(GuiAction.REDUCE_CHAT, hit(GuiPage.GENERAL, values, GuiAction.REDUCE_CHAT))
        assertEquals(GuiAction.DISABLE_CHAT, hit(GuiPage.GENERAL, values, GuiAction.DISABLE_CHAT))
        assertEquals(GuiAction.RESTRICT_LOCATIONS, hit(GuiPage.GENERAL, values, GuiAction.RESTRICT_LOCATIONS))
        assertEquals(GuiAction.SOUND, hit(GuiPage.OVERLAY, values, GuiAction.SOUND))
        assertEquals(GuiAction.SHOW_ALWAYS, hit(GuiPage.OVERLAY, values, GuiAction.SHOW_ALWAYS))
        assertNull(hit(GuiPage.SOUND, values, GuiAction.SOUND_ON_FAILED))
        val immediate = values.copy(overlay = values.overlay.copy(soundTiming = EtherwarpSoundTiming.BLINKWARP))
        assertEquals(GuiAction.SOUND_ON_FAILED, hit(GuiPage.SOUND, immediate, GuiAction.SOUND_ON_FAILED))
        assertNull(hit(GuiPage.OVERLAY, values, GuiAction.BACK))
        assertEquals(GuiAction.COLOR_ALPHA, hit(GuiPage.COLOR, values, GuiAction.COLOR_ALPHA))
        val picker = GuiPicker(sounds = listOf("minecraft:entity.enderman.teleport"), soundPage = 1, soundPages = 2)
        assertEquals(GuiAction.SOUND_SEARCH, actionAt(GuiPage.SOUND_PICKER, values, 200.0, 114.0, picker))
        assertEquals(GuiAction.SOUND_0, actionAt(GuiPage.SOUND_PICKER, values, 200.0, 149.0, picker))
        assertEquals(GuiAction.PREVIEW_0, actionAt(GuiPage.SOUND_PICKER, values, 580.0, 149.0, picker))
        assertNull(actionAt(GuiPage.SOUND_PICKER, values, 200.0, 177.0, picker))
        assertNull(actionAt(GuiPage.SOUND_PICKER, values, 580.0, 177.0, picker))
        assertTrue(GuiAction.SOUND_PREVIOUS in availableActions(GuiPage.SOUND_PICKER, values, picker))
        assertFalse(GuiAction.SOUND_NEXT in availableActions(GuiPage.SOUND_PICKER, values, picker))
        assertNull(hit(GuiPage.BLINKWARP, values, GuiAction.GITHUB))
        assertEquals(GuiAction.TOGGLE, hit(GuiPage.NO_SNEAK_DELAY, values, GuiAction.REDUCE_CHAT))
    }

    @Test fun hexSupportsExactColorAndOpacityAndRejectsPartialValues() {
        val initial = ColorPickerDraft.fromArgb(0x440077FF)
        assertEquals("#0077FF44", initial.hex)
        assertEquals(0x441A73E8, initial.withHex("1a73e8")?.argb)
        assertEquals(0x801A73E8.toInt(), initial.withHex("#1A73E880")?.argb)
        assertEquals(0x001A73E8, initial.withHex("#1A73E800")?.argb)
        assertEquals(initial.hue, initial.withHex("#808080")?.hue)
        for (hex in listOf("", "#123", "#1234567", "#12345GGG", "#123456789")) assertNull(initial.withHex(hex))
        val invalid = GuiPicker(draft = initial, hexText = "#123", hexValid = false)
        assertFalse(GuiAction.APPLY_COLOR in availableActions(GuiPage.COLOR, values, invalid))
        assertTrue(GuiAction.BACK in availableActions(GuiPage.COLOR, values, invalid))
    }

    @Test fun saturationPlanePaintsWhiteHueAndBlackAtItsCorners() {
        val picker = GuiPicker(draft = ColorPickerDraft(2f / 3, 0.5f, 0.5f, 128))
        val image = BufferedImage(640, 400, BufferedImage.TYPE_INT_ARGB)
        ClickGuiRaster.paint(image, ClickGuiFrame(GuiPage.COLOR, values, "1.0.4", picker = picker))
        val plane = GuiAction.COLOR_PLANE.bounds
        val white = Color(image.getRGB(plane.x + 2, plane.y + 2))
        val blue = Color(image.getRGB(plane.right - 3, plane.y + 2))
        val black = Color(image.getRGB(plane.right - 3, plane.bottom - 3))
        assertTrue(white.red >= 248 && white.green >= 248 && white.blue >= 248)
        assertTrue(blue.red <= 5 && blue.green <= 5 && blue.blue >= 248)
        assertTrue(black.red <= 5 && black.green <= 5 && black.blue <= 5)
    }

    @Test fun sharedViewRendersAllPagesWithRealFontsAndCleanIconMasks() {
        for (icon in GuiIcon.entries) {
            resource("textures/gui/${icon.texture}.png").use { stream ->
                ImageIO.read(stream).also { image ->
                    assertEquals(96, image.width)
                    assertEquals(96, image.height)
                    assertEquals(0, image.getRGB(0, 0).ushr(24))
                    val pixels = image.getRGB(0, 0, 96, 96, null, 0, 96)
                    val alphas = pixels.map { it.ushr(24) }
                    assertTrue(255 in alphas && alphas.any { it in 1..254 })
                    assertTrue(pixels.all { it and 0xFFFFFF == 0xFFFFFF }, "Bilinear sampling can pull color from the transparent edge")
                }
            }
        }
        val output = System.getenv("ZPBW_CLICKGUI_PREVIEW")?.let { Path.of(it, "mc${NativeIdentity.TARGET}") }
        output?.let { Files.createDirectories(it) }
        val version = checkNotNull(javaClass.getResourceAsStream("/fabric.mod.json")).bufferedReader().use {
            JsonParser.parseReader(it).asJsonObject.get("version").asString
        }
        for ((width, height) in resolutions) {
            val pages = if (width == 1920) GuiPage.entries else listOf(GuiPage.BLINKWARP)
            for (page in pages) {
                val viewport = ClickGuiViewport(width, height, width, height)
                val image = BufferedImage(width, height, BufferedImage.TYPE_INT_RGB)
                val panelImage = BufferedImage((ClickGuiViewport.WIDTH * viewport.pixelScale).roundToInt(),
                    (ClickGuiViewport.HEIGHT * viewport.pixelScale).roundToInt(), BufferedImage.TYPE_INT_ARGB)
                ClickGuiRaster.paint(panelImage, ClickGuiFrame(page, values.copy(chainMax = 5, timeoutTicks = 20), version))
                val graphics = image.createGraphics()
                try {
                    graphics.color = Color(0x182431)
                    graphics.fillRect(0, 0, width, height)
                    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
                    graphics.drawImage(panelImage, viewport.left.roundToInt(), viewport.top.roundToInt(), null)
                } finally {
                    graphics.dispose()
                    panelImage.flush()
                }
                val footer = pixel(image, viewport, ClickGuiViewport.SIDEBAR_WIDTH - 1, 390)
                val panel = pixel(image, viewport, ClickGuiViewport.SIDEBAR_WIDTH, 390)
                assertNotEquals(footer, panel, "Sidebar divider should continue below the footer line")
                for (action in listOf(GuiAction.OVERLAY, GuiAction.BLINKWARP, GuiAction.NO_SNEAK_DELAY, GuiAction.SOUND, GuiAction.GENERAL)) {
                    assertLabelCentered(image, viewport, action.bounds)
                }
                if (page == GuiPage.BLINKWARP) {
                    assertEquals(0xA8D5F2, pixel(image, viewport, 589, 113))
                }
                if (output != null) {
                    ImageIO.write(image, "png", output.resolve("${width}x${height}-${page.name.lowercase()}.png").toFile())
                }
                image.flush()
            }
        }
    }

    private fun hit(page: GuiPage, settings: ClickGuiValues, action: GuiAction) =
        actionAt(page, settings, action.bounds.x + 1.0, action.bounds.y + 1.0)

    private fun pixel(image: BufferedImage, viewport: ClickGuiViewport, x: Int, y: Int) =
        image.getRGB((viewport.left + (x + 0.5) * viewport.scaleX).toInt(),
            (viewport.top + (y + 0.5) * viewport.scaleY).toInt()) and 0xFFFFFF

    private fun assertLabelCentered(image: BufferedImage, viewport: ClickGuiViewport, row: GuiRect) {
        val startX = (viewport.left + (row.x + 26) * viewport.scaleX).toInt()
        val endX = (viewport.left + row.right * viewport.scaleX).toInt()
        val startY = (viewport.top + row.y * viewport.scaleY).toInt()
        val endY = (viewport.top + row.bottom * viewport.scaleY).toInt()
        val inkRows = (startY until endY).filter { y ->
            (startX until endX).any { x -> (image.getRGB(x, y) shr 16 and 0xFF) > 140 }
        }
        assertTrue(inkRows.isNotEmpty())
        val inkCenter = (inkRows.first() + inkRows.last() + 1) / 2.0
        val iconCenter = viewport.top + (row.y + row.height / 2.0) * viewport.scaleY
        assertTrue(abs(inkCenter - iconCenter) <= 2, "Sidebar label center $inkCenter differs from icon center $iconCenter")
    }

    private fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream("/assets/zpbw/$path"))

}
