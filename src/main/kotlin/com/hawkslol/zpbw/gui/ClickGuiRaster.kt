package com.hawkslol.zpbw.gui

import java.awt.AlphaComposite
import java.awt.Color
import java.awt.Font
import java.awt.Graphics2D
import java.awt.LinearGradientPaint
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import javax.imageio.ImageIO
import kotlin.math.ceil

internal data class ClickGuiFrame(
    val page: GuiPage,
    val values: ClickGuiValues,
    val version: String,
    val hover: GuiAction? = null,
    val focus: GuiAction? = null,
    val notice: String? = null,
    val warning: Boolean = false,
    val picker: GuiPicker = GuiPicker()
)

/** Paint the same antialiased pixels in Minecraft and the offline layout tests. */
internal object ClickGuiRaster {
    private val regular by lazy { loadFont("barlow-regular") }
    private val strong by lazy { loadFont("barlow-semibold") }
    private val icons by lazy {
        GuiIcon.entries.associateWith { resource("textures/gui/${it.texture}.png").use(ImageIO::read) }
    }

    fun paint(image: BufferedImage, frame: ClickGuiFrame) {
        val graphics = image.createGraphics()
        try {
            graphics.composite = AlphaComposite.Clear
            graphics.fillRect(0, 0, image.width, image.height)
            graphics.composite = AlphaComposite.SrcOver
            graphics.scale(image.width.toDouble() / ClickGuiViewport.WIDTH, image.height.toDouble() / ClickGuiViewport.HEIGHT)
            graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON)
            graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR)
            ClickGuiView.draw(Canvas(graphics), frame.page, frame.values, frame.version, frame.hover, frame.focus,
                frame.notice, frame.warning, frame.picker)
        } finally {
            graphics.dispose()
        }
    }

    private fun resource(path: String) = checkNotNull(javaClass.getResourceAsStream("/assets/zpbw/$path"))
    private fun loadFont(name: String) = resource("font/$name.ttf").use { Font.createFont(Font.TRUETYPE_FONT, it) }

    private class Canvas(private val graphics: Graphics2D) : ClickGuiCanvas {
        override fun box(rect: GuiRect, color: Int, radius: Int) {
            graphics.color = Color(color, true)
            if (radius == 0) graphics.fillRect(rect.x, rect.y, rect.width, rect.height)
            else graphics.fillRoundRect(rect.x, rect.y, rect.width, rect.height, radius * 2, radius * 2)
        }

        override fun gradient(rect: GuiRect, stops: IntArray, vertical: Boolean) {
            graphics.paint = LinearGradientPaint(rect.x.toFloat(), rect.y.toFloat(),
                (if (vertical) rect.x else rect.right - 1).toFloat(),
                (if (vertical) rect.bottom - 1 else rect.y).toFloat(),
                FloatArray(stops.size) { it.toFloat() / stops.lastIndex },
                Array(stops.size) { Color(stops[it], true) })
            graphics.fillRect(rect.x, rect.y, rect.width, rect.height)
        }

        override fun textWidth(value: String, size: Int, strong: Boolean): Int =
            ceil(font(size, strong).getStringBounds(value, graphics.fontRenderContext).width).toInt()

        override fun text(value: String, x: Int, y: Int, size: Int, color: Int, strong: Boolean) {
            graphics.font = font(size, strong)
            graphics.color = Color(color, true)
            graphics.drawString(value, x.toFloat(), y + size * 0.84f)
        }

        override fun centeredLabel(value: String, x: Int, centerY: Int, size: Int, color: Int, strong: Boolean,
                                   centerOnCapHeight: Boolean) {
            val face = font(size, strong)
            val context = graphics.fontRenderContext
            val glyphs = face.createGlyphVector(context, value)
            // Descenders in a long description shouldn't shift its capitals above the adjacent label.
            val bounds = if (centerOnCapHeight) face.createGlyphVector(context, "H").visualBounds else glyphs.visualBounds
            graphics.color = Color(color, true)
            graphics.drawGlyphVector(glyphs, x.toFloat(), (centerY - bounds.centerY).toFloat())
        }

        override fun icon(icon: GuiIcon, rect: GuiRect) {
            graphics.drawImage(icons.getValue(icon), rect.x, rect.y, rect.width, rect.height, null)
        }

        private fun font(size: Int, bold: Boolean) = (if (bold) strong else regular).deriveFont(size.toFloat())
    }
}
