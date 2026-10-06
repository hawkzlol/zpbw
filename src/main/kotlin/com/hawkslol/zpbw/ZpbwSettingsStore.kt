package com.hawkslol.zpbw

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING

data class ZpbwSettings(val enabled: Boolean = false, val noSneakDelay: Boolean = false,
                        val timeoutTicks: Int = 20, val firstInstall: Boolean = true) {
    init { require(timeoutTicks in 2..20) }
}

/** Small, typed, atomic per-profile config for every user-configurable setting. */
class ZpbwSettingsStore(private val directory: Path) {
    val path: Path = directory.resolve("config.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private fun existing(dir: Path = directory): JsonObject {
        val file = dir.resolve("config.json")
        require(!Files.isSymbolicLink(dir) && !Files.isSymbolicLink(file)) { "Linked ZPBW config is not supported" }
        if (!Files.exists(file, NOFOLLOW_LINKS)) return JsonObject()
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 65_536) { "Invalid ZPBW config file" }
        val root = Files.newBufferedReader(file).use { JsonParser.parseReader(it) }
        require(root.isJsonObject) { "ZPBW config must be an object" }
        return root.asJsonObject
    }
    fun load(): ZpbwSettings {
        val hasConfig = Files.exists(path, NOFOLLOW_LINKS)
        val root = existing()
        val enabled = root.get("enabled")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "enabled must be a boolean" }
            it.asBoolean
        } ?: false
        val ticks = root.get("timeoutTicks")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isNumber) { "timeoutTicks must be an integer" }
            it.asBigDecimal.intValueExact().coerceIn(2, 20)
        } ?: 20
        val noSneakDelay = root.get("noSneakDelay")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "noSneakDelay must be a boolean" }
            it.asBoolean
        } ?: false
        val firstInstall = root.get("firstInstall")?.let {
            require(it.isJsonPrimitive && it.asJsonPrimitive.isBoolean) { "firstInstall must be a boolean" }
            it.asBoolean
        } ?: !hasConfig
        val settings = ZpbwSettings(enabled, noSneakDelay, ticks, firstInstall)
        if (hasConfig && (!root.has("firstInstall") || root.get("timeoutTicks")?.asInt != ticks ||
                listOf("fast", "chainMax", "intentionalFailure").any(root::has))) write(settings, root)
        return settings
    }
    fun save(settings: ZpbwSettings) = write(settings, existing())
    private fun write(settings: ZpbwSettings, root: JsonObject) {
        root.addProperty("enabled", settings.enabled)
        root.remove("chainMax")
        root.remove("intentionalFailure")
        root.addProperty("timeoutTicks", settings.timeoutTicks)
        root.remove("fast")
        root.addProperty("noSneakDelay", settings.noSneakDelay)
        root.addProperty("firstInstall", settings.firstInstall)
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "zpbw-config-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { gson.toJson(root, it) }
            try { Files.move(temporary, path, ATOMIC_MOVE, REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temporary, path, REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temporary) }
    }
}
