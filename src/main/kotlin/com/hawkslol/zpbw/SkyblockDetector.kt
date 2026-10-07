package com.hawkslol.zpbw

import com.hawkslol.zpbw.mixin.ZpbwTabOverlayAccessor
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import net.minecraft.world.scores.PlayerTeam

/** Visible server signals read directly from Minecraft, independent of other mods. */
object SkyblockSignals {
    private val formatting = Regex("§[0-9A-FK-OR]", RegexOption.IGNORE_CASE)
    private val whitespace = Regex("[\\s\\p{Z}]+")
    private val area = Regex("^area\\s*:\\s*(\\S.*)$", RegexOption.IGNORE_CASE)
    private val dungeonFloor = Regex("\\((E|F[1-7]|M[1-7])\\)", RegexOption.IGNORE_CASE)
    private val hints = listOf("Purse:", "Piggy:", "Bits:", "The Catacombs", "Dungeon:")

    fun matches(title: String, sidebar: Iterable<String>, tab: Iterable<String>): Boolean {
        if (clean(title).contains("SKYBLOCK", ignoreCase = true)) return true
        if (sidebar.any { raw ->
                val line = clean(raw)
                (line.contains("catacombs", ignoreCase = true) && dungeonFloor.containsMatchIn(line)) ||
                    hints.any { line.contains(it, ignoreCase = true) }
            }) return true
        return tab.any { raw -> raw.lineSequence().any {
            val label = area.matchEntire(clean(it))?.groupValues?.get(1)
            label != null && label.any(Char::isLetterOrDigit)
        } }
    }

    private fun clean(value: String): String = whitespace.replace(formatting.replace(value, ""), " ").trim()
}

object SkyblockDetector {
    fun inSkyblock(client: Minecraft): Boolean {
        val level = client.level ?: return false
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        val sidebar = objective?.let {
            scoreboard.listPlayerScores(it).filterNot { entry -> entry.isHidden }.map { entry ->
                PlayerTeam.formatNameForTeam(scoreboard.getPlayersTeam(entry.owner()), entry.ownerName()).string
            }
        }.orEmpty()
        val overlay = client.gui.hud.tabList
        val tab = buildList {
            val components = overlay as ZpbwTabOverlayAccessor
            components.`zpbw$getHeader`()?.string?.let(::add)
            components.`zpbw$getFooter`()?.string?.let(::add)
            client.connection?.listedOnlinePlayers?.forEach { add(overlay.getNameForDisplay(it).string) }
        }
        return SkyblockSignals.matches(objective?.displayName?.string.orEmpty(), sidebar, tab)
    }
}
