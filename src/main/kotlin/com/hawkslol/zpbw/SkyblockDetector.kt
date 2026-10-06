package com.hawkslol.zpbw

import net.fabricmc.loader.api.FabricLoader
import net.minecraft.client.Minecraft
import net.minecraft.world.scores.DisplaySlot
import java.lang.reflect.Modifier

/** Visible server signals, independent of hostname and of Blackwell installation. */
object SkyblockSignals {
    private val formatting = Regex("§[0-9A-FK-OR]", RegexOption.IGNORE_CASE)
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
        return tab.any { raw -> raw.lineSequence().any { area.matches(clean(it)) } }
    }

    private fun clean(value: String): String = formatting.replace(value, "").trim()
}

object SkyblockDetector {
    // Resolve once, with no required Blackwell dependency. Its property includes the developer override.
    private val blackwell: (() -> Boolean?)? by lazy {
        if (!FabricLoader.getInstance().isModLoaded("blackwell")) null
        else runCatching {
            val type = Class.forName("com.hawkslol.blackwell.utils.LocationUtils")
            val getter = type.getMethod("getInSkyblock")
            val receiver = if (Modifier.isStatic(getter.modifiers)) null else type.getField("INSTANCE").get(null)
            val read: () -> Boolean? = { runCatching { getter.invoke(receiver) as? Boolean }.getOrNull() }
            read
        }.getOrNull()
    }

    fun inSkyblock(client: Minecraft): Boolean {
        val level = client.level ?: return false
        blackwell?.invoke()?.let { return it }
        val scoreboard = level.scoreboard
        val objective = scoreboard.getDisplayObjective(DisplaySlot.SIDEBAR)
        val sidebar = objective?.let {
            scoreboard.listPlayerScores(it).filterNot { entry -> entry.isHidden }.map { entry ->
                val owner = entry.owner()
                val team = scoreboard.getPlayersTeam(owner)
                val teamText = team?.let { value -> value.playerPrefix.string + value.playerSuffix.string }
                teamText?.takeIf { value -> value.isNotBlank() } ?: entry.ownerName().string
            }
        }.orEmpty()
        val tab = client.connection?.listedOnlinePlayers?.mapNotNull { it.tabListDisplayName?.string }.orEmpty()
        return SkyblockSignals.matches(objective?.displayName?.string.orEmpty(), sidebar, tab)
    }
}
