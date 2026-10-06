package com.hawkslol.zpbw

import com.hawkslol.zpbw.mixin.ZpbwPlayerAccessor
import com.hawkslol.zpbw.mixin.ZpbwInputAccessor
import net.minecraft.client.Minecraft
import net.minecraft.client.player.KeyboardInput
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.player.ClientInput
import net.minecraft.network.Connection
import net.minecraft.network.protocol.Packet
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.world.entity.player.Input
import net.minecraft.world.phys.Vec2

/** One early input commitment per natural tick; no additional simulation or TickEnd packets. */
object NoSneakDelay {
    var enabled = false
        private set
    private val mc get() = Minecraft.getInstance()
    private val latch = TickInputLatch<Input>()
    private val binding = SneakBindingTransitions()
    private var player: LocalPlayer? = null
    private var level: Any? = null
    private var stream: Connection? = null

    fun configure(value: Boolean) { if (enabled != value) binding.reset(); enabled = value } // Honor an already-sent commitment through its tick.
    fun reset() { latch.clear(); binding.reset(); player = null; level = null; stream = null }
    fun hasCommitment() = latch.value != null || latch.deferred != null
    private fun bind(): LocalPlayer? {
        val p = mc.player
        val connection = mc.connection?.connection
        if (p !== player || mc.level !== level || connection !== stream) {
            reset(); player = p; level = mc.level; stream = connection
        }
        return p?.takeIf { connection?.isConnected == true && it.connection.hasClientLoaded() }
    }
    @JvmStatic fun frame() {
        if (!enabled || !mc.isSameThread) return
        if (ZpbwRuntime.externalPredictionActive()) { binding.reset(); return }
        val p = bind() ?: return
        binding.observe(mc.options.keyShift.isDown)
        if (latch.value != null) return
        if (!ZpbwRuntime.canSendSneakEarly() || p.input !is KeyboardInput || !p.isAlive ||
            p.isPassenger || p.isFallFlying || p.abilities.flying || p.isInWater || p.isInLava) return
        val options = mc.options
        val shift = options.keyShift.isDown
        if (shift && (mc.gui.screen() != null || !mc.isWindowActive)) return
        val delayed = latch.commitDeferred()
        if (delayed != null) {
            p.connection.send(ServerboundPlayerInputPacket(delayed))
            ZpbwRuntime.sneakEvent("DEFERRED_COMMIT shift=${delayed.shift()}")
            if (binding.pending == delayed.shift()) binding.consumed()
            return
        }
        if (binding.pending == null) return
        if (shift == p.input.keyPresses.shift()) { binding.consumed(); return }
        val early = Input(options.keyUp.isDown, options.keyDown.isDown, options.keyLeft.isDown,
            options.keyRight.isDown, options.keyJump.isDown || (p as ZpbwPlayerAccessor).`zpbw$getAutoJumpTime`() > 0,
            shift, options.keySprint.isDown)
        check(latch.commit(early))
        binding.consumed()
        p.connection.send(ServerboundPlayerInputPacket(early))
        ZpbwRuntime.sneakEvent("EARLY shift=$shift sampled=${p.input.keyPresses.shift()}")
    }
    @JvmStatic fun applySample(input: ClientInput) {
        if (ZpbwRuntime.externalPredictionActive()) return
        val p = bind() ?: return
        if (latch.value == null && latch.deferred == null) return
        if (input !== p.input) return
        val committed = latch.sample(input.keyPresses)
        input.keyPresses = committed
        fun axis(positive: Boolean, negative: Boolean) = if (positive == negative) 0f else if (positive) 1f else -1f
        (input as ZpbwInputAccessor).`zpbw$setMoveVector`(Vec2(axis(committed.left(), committed.right()),
            axis(committed.forward(), committed.backward())).normalized())
        ZpbwRuntime.sneakEvent("LATCH_APPLIED shift=${committed.shift()}")
    }
    @JvmStatic fun suppressIdenticalNativeInput(packet: Packet<*>): Boolean =
        !ZpbwRuntime.externalPredictionActive() && packet is ServerboundPlayerInputPacket && latch.value == packet.input
    fun inputForClick(): Input? = if (ZpbwRuntime.externalPredictionActive()) null else ZpbwRuntime.deferredUseInput() ?: latch.value
    fun sneakForPrediction(): Boolean? = inputForClick()?.shift()
        ?: if (latch.deferred != null) mc.player?.input?.keyPresses?.shift() else null

    /** Native click semantics see the committed input, while previous-tick physics history is restored. */
    @JvmStatic fun beginClick(): Input? {
        if (ZpbwRuntime.externalPredictionActive()) return null
        val p = bind() ?: return null
        val committed = inputForClick() ?: return null
        return p.input.keyPresses.also { p.input.keyPresses = committed }
    }
    @JvmStatic fun endClick(p: LocalPlayer?, previous: Input?) {
        if (previous != null && p != null && p === mc.player) p.input.keyPresses = previous
    }
    @JvmStatic fun finishTick() {
        if (ZpbwRuntime.externalPredictionActive()) return
        latch.finishTick()
        if (!enabled && latch.value == null) latch.clear()
    }
}
