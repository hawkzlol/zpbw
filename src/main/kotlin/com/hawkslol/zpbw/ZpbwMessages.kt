package com.hawkslol.zpbw

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.MutableComponent
import java.net.URI

object ZpbwMessages {
    private const val TITLE = "===== Zero Ping Blinkwarp ====="
    private const val HEADER = "&7=====&9 Zero Ping Blinkwarp&7 ====="
    const val REPOSITORY = "https://github.com/hawkzlol/zpbw"

    fun legacy(text: String): MutableComponent {
        val result = Component.empty()
        var formats = listOf(ChatFormatting.WHITE)
        var start = 0
        var index = 0
        while (index < text.length - 1) {
            val format = if (text[index] == '&') ChatFormatting.getByCode(text[index + 1]) else null
            if (format != null) {
                if (index > start) result.append(Component.literal(text.substring(start, index)).withStyle(*formats.toTypedArray()))
                formats = if (text[index + 1].lowercaseChar() in "0123456789abcdef" || format == ChatFormatting.RESET) listOf(format) else formats + format
                index += 2; start = index
            } else index++
        }
        if (start < text.length) result.append(Component.literal(text.substring(start)).withStyle(*formats.toTypedArray()))
        return result
    }
    fun prefix(body: Component): MutableComponent = Component.empty()
        .append(Component.literal("[").withStyle(ChatFormatting.GRAY))
        .append(Component.literal("ZPBW").withStyle(ChatFormatting.BLUE))
        .append(Component.literal("] ").withStyle(ChatFormatting.GRAY)).append(body)
    fun plain(text: String) = prefix(Component.literal(text).withStyle(ChatFormatting.WHITE))
    fun selection(text: String) = plain(text)
    fun error(text: String) = prefix(Component.literal(text).withStyle(ChatFormatting.RED))

    private fun footer(width: (String) -> Int): Component {
        val count = (2..100).minBy { kotlin.math.abs(width("=".repeat(it)) - width(TITLE)) }
        return Component.literal("=".repeat(count)).withStyle(ChatFormatting.GRAY)
    }
    fun help(width: (String) -> Int = { it.length }): Component = legacy(HEADER +
        "\n&f/zpbw &7[on &f|&7 off]&f - Toggles ZPBW" +
        "\n&f/zpbw &7[nosneakdelay &f|&7 nsd]&f - Toggles NoSneakDelay" +
        "\n&f/zpbw &7[timeout <ticks>]&f - Sets Blinkwarp Timeout in Ticks." +
        "\n&f/zpbw &7[logs] &f- Copies logs to clipboard for troubleshooting.\n")
        .append(footer(width))

    fun welcome(version: String, width: (String) -> Int = { it.length }): Component = legacy(HEADER +
        "\n&aYou just installed ZPBW $version!" +
        "\n&aTo get started, run /zpbw to" +
        "\n&asee all available commands." +
        "\n&aAs of build version 1.0.0, there have been" +
        "\n&ano recorded ZPBW bans. Please understand" +
        "\n&athat this can change at any time. If you" +
        "\n&aencounter any bugs, report them at\n")
        .append(Component.literal(REPOSITORY).withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE)
            .withStyle { it.withClickEvent(ClickEvent.OpenUrl(URI.create(REPOSITORY))) })
        .append("\n").append(footer(width))

    fun copiedLogs() = prefix(Component.literal("Copied ZPBW logs to clipboard. You can now share it with a developer.")
        .withStyle(ChatFormatting.GREEN))
    fun updateAvailable(installed: String, available: String, url: String, width: (String) -> Int = { it.length }): Component =
        legacy(HEADER + "\n&aA ZPBW update is available!" +
            "\n&7Installed: &f$installed &7| Latest: &f$available" +
            "\n&fGet the latest version here:\n")
            .append(Component.literal(url).withStyle(ChatFormatting.BLUE, ChatFormatting.UNDERLINE)
                .withStyle { it.withClickEvent(ClickEvent.OpenUrl(URI.create(url))) })
            .append("\n").append(footer(width))
    fun waiting(pending: Int, maximum: Int = 5) = error("Waiting for server! ($pending/$maximum)")
    fun queueFailure(reason: String) = prefix(legacy("&cBlinkwarp failed: &f$reason"))
    fun timeout(ticks: Int) = queueFailure("No teleport arrived within $ticks ticks.")
    fun failure(guess: PredictionFailureSupport.Point, actual: PredictionFailureSupport.Point?, reason: String = "position_mismatch", positionConfirmed: Boolean = false): Component {
        val body = when {
            positionConfirmed -> legacy("&cBlinkwarp failed: &fServer motion changed.")
            reason == "position_mismatch" -> legacy("&cBlinkwarp prediction failed: &fDestination differs.")
            else -> Component.literal("Prediction reset error occurred.").withStyle(ChatFormatting.RED)
                .withStyle { it.withHoverEvent(HoverEvent.ShowText(Component.literal(PredictionFailureSupport.explanation(reason))
                    .withStyle(ChatFormatting.WHITE))) }
        }
        val received = actual?.coordinates()?.drop(1)?.dropLast(1) ?: "unavailable"
        val guessed = guess.coordinates().drop(1).dropLast(1)
        body.append(legacy(" &7Guessed: (&f$guessed&7), Received: (&f$received&7). &7Difference: &f" +
            if (actual == null) "unavailable&7." else "${PredictionFailureSupport.distance(guess, actual)} &7blocks."))
        return prefix(body)
    }
}
