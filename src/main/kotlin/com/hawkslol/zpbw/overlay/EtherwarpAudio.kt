package com.hawkslol.zpbw.overlay

import com.hawkslol.zpbw.SkyblockDetector
import com.hawkslol.zpbw.ZpbwRuntime
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.multiplayer.ClientPacketListener
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.resources.sounds.SimpleSoundInstance
import net.minecraft.client.sounds.SoundEngine
import net.minecraft.core.registries.BuiltInRegistries
import net.minecraft.network.protocol.game.ClientboundSoundPacket
import net.minecraft.resources.Identifier
import net.minecraft.sounds.SoundEvent
import net.minecraft.sounds.SoundEvents
import net.minecraft.world.phys.Vec3

/** Client playback only. A sound is never a teleport confirmation for the predictor. */
internal object EtherwarpAudio {
    private val immediate = ArrayDeque<Cue>()
    private data class Cue(val player: LocalPlayer, val level: ClientLevel, val connection: ClientPacketListener,
                           val source: Vec3, val landing: Vec3, val time: Long)

    fun clear() = immediate.clear()

    fun onLocalWarp(player: LocalPlayer, source: Vec3, landing: Vec3, options: EtherwarpOptions) {
        if (!options.customSound) return
        val client = Minecraft.getInstance()
        if (!client.isSameThread || client.player !== player) return
        val level = client.level ?: return
        val connection = client.connection ?: return
        if (options.soundTiming == EtherwarpSoundTiming.BLINKWARP && !play(options)) return
        val now = System.nanoTime()
        prune(player, level, connection, now)
        if (immediate.size == 8) immediate.removeFirst()
        immediate.addLast(Cue(player, level, connection, source, landing, now))
    }

    internal fun isSignature(enderDragonHurt: Boolean, volume: Float, pitch: Float) =
        enderDragonHurt && volume == 1f && pitch == 0.53968257f

    @JvmStatic
    fun replace(listener: ClientPacketListener, packet: ClientboundSoundPacket): Boolean {
        val client = Minecraft.getInstance()
        if (!client.isSameThread || client.connection !== listener) return false
        val options = ZpbwRuntime.overlayOptions
        if (!options.customSound || !isSignature(packet.sound.value() == SoundEvents.ENDER_DRAGON_HURT, packet.volume, packet.pitch))
            return false
        val player = client.player ?: return false
        val level = client.level ?: return false
        val sound = Vec3(packet.x, packet.y, packet.z)
        prune(player, level, listener, System.nanoTime())
        val index = immediate.indexOfFirst { nearby(sound, it.source) || nearby(sound, it.landing) }
        if (index >= 0) {
            immediate.removeAt(index)
            return options.soundTiming == EtherwarpSoundTiming.BLINKWARP || play(options)
        }
        if (options.soundTiming == EtherwarpSoundTiming.BLINKWARP && !options.soundOnFailedWarps) return false
        if (!nearby(sound, player.position()) || !SkyblockDetector.inSkyblock(client)) return false
        return play(options)
    }

    fun catalog(): List<String> = Minecraft.getInstance().soundManager.availableSounds.asSequence()
        .filter(::isVanillaSound).map(Identifier::toString).sorted().toList()

    fun preview(id: String, options: EtherwarpOptions): SimpleSoundInstance? {
        val key = availableSound(id) ?: return null
        return start(key, options)
    }

    fun effectiveSoundId(id: String) = availableSound(id)?.toString() ?: EtherwarpOptions.DEFAULT_SOUND

    private fun isVanillaSound(id: Identifier) =
        id.namespace == "minecraft" && BuiltInRegistries.SOUND_EVENT.containsKey(id)

    private fun availableSound(id: String): Identifier? = Identifier.tryParse(id)
        ?.takeIf { isVanillaSound(it) && it in Minecraft.getInstance().soundManager.availableSounds }

    private fun play(options: EtherwarpOptions): Boolean {
        val key = availableSound(options.soundId) ?: availableSound(EtherwarpOptions.DEFAULT_SOUND) ?: return false
        return options.soundVolume == 0f || start(key, options) != null
    }

    private fun start(id: Identifier, options: EtherwarpOptions): SimpleSoundInstance? = runCatching {
        val manager = Minecraft.getInstance().soundManager
        val instance = SimpleSoundInstance.forUI(SoundEvent.createVariableRangeEvent(id), options.soundPitch, options.soundVolume)
        instance.takeIf { manager.play(it) == SoundEngine.PlayResult.STARTED }
    }.getOrNull()

    internal fun nearby(a: Vec3, b: Vec3) = a.distanceToSqr(b) <= 16.0

    private fun prune(player: LocalPlayer, level: ClientLevel, connection: ClientPacketListener, now: Long) {
        immediate.removeAll { it.player !== player || it.level !== level || it.connection !== connection ||
            now - it.time > 2_500_000_000L }
    }
}
