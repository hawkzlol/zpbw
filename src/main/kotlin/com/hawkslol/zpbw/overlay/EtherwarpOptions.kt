package com.hawkslol.zpbw.overlay

enum class OverlayShape(val label: String) {
    FILL("Filled"), OUTLINE("Outline"), BOTH("Filled Outline")
}

enum class EtherwarpSoundTiming(val label: String) {
    BLINKWARP("Blinkwarp"), ETHERWARP("Etherwarp")
}

data class EtherwarpOptions(
    val enabled: Boolean = false,
    val successFill: Int = 0x2800FF00,
    val successOutline: Int = 0xC800FF00.toInt(),
    val failFill: Int = 0x00FF0000,
    val failOutline: Int = 0x00FF0000,
    val style: OverlayShape = OverlayShape.BOTH,
    val lineWidth: Int = 3,
    val depth: Boolean = false,
    val fullBlock: Boolean = true,
    val customSound: Boolean = false,
    val soundTiming: EtherwarpSoundTiming = EtherwarpSoundTiming.ETHERWARP,
    val soundId: String = DEFAULT_SOUND,
    val soundVolume: Float = 1f,
    val soundPitch: Float = 1f,
    val showAlways: Boolean = false,
    val soundOnFailedWarps: Boolean = false
) {
    companion object {
        const val DEFAULT_SOUND = "minecraft:entity.experience_orb.pickup"
        private val minecraftSoundId = Regex("minecraft:[a-z0-9_/.-]+")

        fun acceptsSoundId(id: String) = id.length <= 128 && minecraftSoundId.matches(id)
        fun volume(value: Float) = if (value.isFinite()) value.coerceIn(0f, 2f) else 1f
        fun pitch(value: Float) = if (value.isFinite()) value.coerceIn(0.1f, 2f) else 1f
    }
}
