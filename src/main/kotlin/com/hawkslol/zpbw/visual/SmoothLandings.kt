package com.hawkslol.zpbw.visual

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.phys.Vec3

/** Client-thread state for the extracted render camera only. */
object SmoothLandings {
    internal var enabled = false
        private set
    private var player: LocalPlayer? = null
    private var settle: LandingCameraSettle? = null

    internal fun configure(value: Boolean) {
        enabled = value
        clear()
    }

    internal fun begin(owner: LocalPlayer, heightOffset: Double) {
        if (!enabled) return
        player = owner
        settle = LandingCameraSettle(owner.y, heightOffset, System.nanoTime())
    }

    internal fun clear() {
        player = null
        settle = null
    }

    @JvmStatic
    fun renderPosition(camera: Camera, native: Vec3, partialTick: Float): Vec3 {
        val current = settle ?: return native
        val client = Minecraft.getInstance()
        val owner = player
        if (owner == null || owner !== client.player || owner.level() !== client.level ||
            camera.entity() !== owner || camera.isDetached || owner.isSpectator || owner.isPassenger || owner.isSleeping) {
            clear()
            return native
        }
        val offset = current.offset(owner.yo, owner.y, partialTick, System.nanoTime())
        if (offset == null) {
            clear()
            return native
        }
        return if (offset == 0.0) native else Vec3(native.x, native.y - offset, native.z)
    }
}

/** Remove the remaining landing offset from the render camera, including the first frame. */
internal class LandingCameraSettle(private val arrivalY: Double, private val heightOffset: Double, private val startedAt: Long) {
    private val floorY = arrivalY - heightOffset

    /** Null releases the camera on native motion outside the settling interval. */
    fun offset(previousFeetY: Double, feetY: Double, partialTick: Float, now: Long): Double? {
        if (now - startedAt !in 0L until 220_000_000L || heightOffset <= 0.0 || partialTick !in 0f..1f) return null
        val epsilon = 1e-7
        val arrivalBand = (floorY - epsilon)..(arrivalY + epsilon)
        if (previousFeetY !in arrivalBand || feetY !in arrivalBand ||
            feetY > previousFeetY + epsilon || previousFeetY <= floorY + epsilon) return null
        val interpolatedFeetY = previousFeetY + (feetY - previousFeetY) * partialTick
        return (interpolatedFeetY - floorY).coerceIn(0.0, heightOffset)
    }
}
