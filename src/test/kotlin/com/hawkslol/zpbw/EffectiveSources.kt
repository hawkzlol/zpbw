package com.hawkslol.zpbw

import com.google.gson.JsonParser
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest

/** Source contracts inspect exactly the file selected by this native compilation. */
object EffectiveSources {
    val nativeTarget: String = System.getProperty("zpbw.nativeTarget") ?: error("Missing native test identity")
    private val root = Path.of(System.getProperty("zpbw.productRoot") ?: error("Missing product root"))
    private val selections by lazy {
        JsonParser.parseString(Files.readString(Path.of(System.getProperty("zpbw.effectiveSources")
            ?: error("Missing effective-source manifest")))).asJsonObject.getAsJsonObject("sources")
    }
    fun resolve(relative: String): Path = path(relative)
    fun path(relative: String): Path {
        require(relative.startsWith("src/main/"))
        val entry = selections.getAsJsonObject(relative.removePrefix("src/main/"))
            ?: error("No selected native source: $relative")
        val selected = Path.of(entry.get("path").asString)
        check(selected.toRealPath().startsWith(root.toRealPath()))
        val hash = MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(selected))
            .joinToString("") { "%02x".format(it) }
        check(hash == entry.get("sha256").asString) { "Selected source changed after compilation" }
        return selected
    }
    fun productionFiles(): List<Path> = selections.entrySet().map { Path.of(it.value.asJsonObject.get("path").asString) }
}
