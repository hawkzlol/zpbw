package com.hawkslol.zpbw.gui

import java.awt.Color

internal data class ColorPickerDraft(val hue: Float, val saturation: Float, val brightness: Float, val opacity: Int) {
    val argb: Int get() = (Color.HSBtoRGB(hue, saturation, brightness) and 0xFFFFFF) or (opacity shl 24)
    val hex: String get() = formatRgba(argb)

    fun withHex(text: String): ColorPickerDraft? {
        val value = text.removePrefix("#")
        if (!HEX.matches(value)) return null
        val number = value.toLong(16).toInt()
        val argb = if (value.length == 6) (opacity shl 24) or number else (number ushr 8) or (number shl 24)
        return fromArgb(argb, hue)
    }

    companion object {
        private val HEX = Regex("(?:[0-9a-fA-F]{6}|[0-9a-fA-F]{8})")

        fun fromArgb(argb: Int, previousHue: Float = 0f): ColorPickerDraft {
            val hsb = Color.RGBtoHSB(argb ushr 16 and 255, argb ushr 8 and 255, argb and 255, null)
            return ColorPickerDraft(if (hsb[1] == 0f) previousHue else hsb[0], hsb[1], hsb[2], argb ushr 24)
        }
    }
}

internal fun formatRgba(argb: Int) = "#%08X".format((argb shl 8) or (argb ushr 24))
