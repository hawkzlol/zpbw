package com.hawkslol.zpbw

import com.google.gson.GsonBuilder
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.ByteArrayInputStream
import java.io.InputStreamReader
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.LinkOption.NOFOLLOW_LINKS
import java.nio.file.Path
import java.nio.file.StandardCopyOption.ATOMIC_MOVE
import java.nio.file.StandardCopyOption.REPLACE_EXISTING
import java.nio.file.StandardOpenOption.CREATE_NEW
import java.nio.file.StandardOpenOption.WRITE
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest

data class ZpbwSettings(val enabled: Boolean = false, val noSneakDelay: Boolean = false,
                        val timeoutTicks: Int = 20, val firstInstall: Boolean = true,
                        val chainMax: Int = 5) {
    init { require(timeoutTicks in 2..20); require(chainMax in 1..5) }
}

data class ZpbwSettingsLoadResult(val settings: ZpbwSettings, val normalizationPending: Boolean = false,
                                  val diagnostics: List<String> = emptyList())

/** Small, typed, atomic per-profile config for every user-configurable setting. */
class ZpbwSettingsStore(private val directory: Path,
                        private val atomicReplace: (Path, Path) -> Unit = { source, target ->
                            Files.move(source, target, ATOMIC_MOVE, REPLACE_EXISTING)
                        }) {
    val path: Path = directory.resolve("config.json")
    private val gson = GsonBuilder().setPrettyPrinting().create()
    private data class ConfigDocument(val root: JsonObject, val original: ByteArray? = null)
    private fun existing(dir: Path = directory): ConfigDocument {
        val file = dir.resolve("config.json")
        require(!Files.isSymbolicLink(dir) && !Files.isSymbolicLink(file)) { "Linked ZPBW config is not supported" }
        if (!Files.exists(file, NOFOLLOW_LINKS)) return ConfigDocument(JsonObject())
        require(Files.isRegularFile(file, NOFOLLOW_LINKS) && Files.size(file) <= 65_536) { "Invalid ZPBW config file" }
        val original = Files.newInputStream(file, NOFOLLOW_LINKS).use { it.readNBytes(65_537) }
        require(original.size <= 65_536) { "Invalid ZPBW config file" }
        val root = InputStreamReader(ByteArrayInputStream(original), UTF_8.newDecoder()).use { JsonParser.parseReader(it) }
        require(root.isJsonObject) { "ZPBW config must be an object" }
        return ConfigDocument(root.asJsonObject, original)
    }
    fun load(): ZpbwSettings = loadWithResult().settings
    fun loadWithResult(): ZpbwSettingsLoadResult {
        val document = existing()
        val hasConfig = document.original != null
        val root = document.root
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
        val diagnostics = mutableListOf<String>()
        val chainMax = root.get("chainMax")?.let {
            if (!it.isJsonPrimitive || !it.asJsonPrimitive.isNumber) {
                diagnostics += "CHAIN_MAX_CONFIG_INVALID type"
                5
            } else try {
                it.asBigDecimal.intValueExact().coerceIn(1, 5)
            } catch (_: ArithmeticException) {
                diagnostics += "CHAIN_MAX_CONFIG_INVALID non_integral_or_out_of_int"
                5
            } catch (_: NumberFormatException) {
                diagnostics += "CHAIN_MAX_CONFIG_INVALID number"
                5
            }
        } ?: 5
        val settings = ZpbwSettings(enabled = enabled, noSneakDelay = noSneakDelay,
            timeoutTicks = ticks, firstInstall = firstInstall, chainMax = chainMax)
        if (hasConfig && (!root.has("firstInstall") || root.get("timeoutTicks")?.asInt != ticks ||
                root.get("chainMax")?.toString() != chainMax.toString() ||
                listOf("fast", "intentionalFailure").any(root::has))) {
            try {
                preserveMigrationOriginal(document.original!!)
                write(settings, root, strict = true)
            } catch (failure: Exception) {
                diagnostics += "CONFIG_NORMALIZATION_PENDING ${failure.javaClass.simpleName.take(64)}"
                return ZpbwSettingsLoadResult(settings, normalizationPending = true, diagnostics = diagnostics)
            }
        }
        return ZpbwSettingsLoadResult(settings, diagnostics = diagnostics)
    }
    fun save(settings: ZpbwSettings) = write(settings, existing().root, strict = false)
    /** Explicit chain-limit changes commit only when atomic replacement succeeds. */
    fun saveStrict(settings: ZpbwSettings) = write(settings, existing().root, strict = true)
    private fun preserveMigrationOriginal(original: ByteArray) {
        val backups = directory.resolve("backups")
        require(!Files.isSymbolicLink(backups)) { "Linked ZPBW backups are not supported" }
        Files.createDirectories(backups)
        require(Files.isDirectory(backups, NOFOLLOW_LINKS)) { "Invalid ZPBW backup directory" }
        val digest = MessageDigest.getInstance("SHA-256").digest(original).joinToString("") { "%02x".format(it) }
        val backup = backups.resolve("config-pre-chainmax-1.0.3-$digest.json")
        fun validateBackup() {
            require(!Files.isSymbolicLink(backup) && Files.isRegularFile(backup, NOFOLLOW_LINKS) &&
                Files.size(backup) <= 65_536) { "Invalid ZPBW migration backup" }
            val saved = Files.newInputStream(backup, NOFOLLOW_LINKS).use { it.readNBytes(65_537) }
            require(saved.contentEquals(original)) { "ZPBW migration backup differs from original" }
        }
        if (Files.exists(backup, NOFOLLOW_LINKS)) {
            validateBackup()
            return
        }
        var created = false
        try {
            Files.newOutputStream(backup, CREATE_NEW, WRITE).use {
                created = true
                it.write(original)
            }
        } catch (failure: Exception) {
            if (created) Files.deleteIfExists(backup)
            if (failure is FileAlreadyExistsException) validateBackup() else throw failure
        }
    }
    private fun write(settings: ZpbwSettings, root: JsonObject, strict: Boolean) {
        root.addProperty("enabled", settings.enabled)
        root.addProperty("chainMax", settings.chainMax)
        root.remove("intentionalFailure")
        root.addProperty("timeoutTicks", settings.timeoutTicks)
        root.remove("fast")
        root.addProperty("noSneakDelay", settings.noSneakDelay)
        root.addProperty("firstInstall", settings.firstInstall)
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, "zpbw-config-", ".tmp")
        try {
            Files.newBufferedWriter(temporary).use { gson.toJson(root, it) }
            try { atomicReplace(temporary, path) }
            catch (failure: AtomicMoveNotSupportedException) {
                if (strict) throw failure
                Files.move(temporary, path, REPLACE_EXISTING)
            }
        } finally { Files.deleteIfExists(temporary) }
    }
}
