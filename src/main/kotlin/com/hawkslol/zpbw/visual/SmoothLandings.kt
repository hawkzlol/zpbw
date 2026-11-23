package com.hawkslol.zpbw.visual

import net.minecraft.client.Camera
import net.minecraft.client.Minecraft
import net.minecraft.client.player.LocalPlayer
import net.minecraft.world.phys.Vec3
import kotlin.math.abs
import kotlin.math.exp

/** Client-thread state for the extracted render camera only. */
object SmoothLandings {
    internal var enabled = false
        private set
    private var player: LocalPlayer? = null
    private var filter: LandingVerticalSmoother? = null

    internal fun configure(value: Boolean) {
        enabled = value
        clear()
    }

    internal fun begin(owner: LocalPlayer) {
        if (!enabled) return
        player = owner
        filter = LandingVerticalSmoother(System.nanoTime())
    }

    internal fun clear() {
        player = null
        filter = null
    }

    @JvmStatic
    fun renderPosition(camera: Camera, native: Vec3): Vec3 {
        val current = filter ?: return native
        val client = Minecraft.getInstance()
        val owner = player
        if (owner == null || owner !== client.player || owner.level() !== client.level ||
            camera.entity() !== owner || camera.isDetached || owner.isSpectator) {
            clear()
            return native
        }
        val y = current.sample(native.y, System.nanoTime())
        if (y == null) {
            clear()
            return native
        }
        return if (y == native.y) native else Vec3(native.x, y, native.z)
    }
}

/** Pass the first destination frame through, then damp only small deviations from it. */
internal class LandingVerticalSmoother(private val startedAt: Long) {
    private var landingY: Double? = null
    private var lastSample = startedAt
    private var smoothed = 0.0

    /** Null ends the effect and passes control back to the native renderer immediately. */
    fun sample(y: Double, now: Long): Double? {
        val age = now - startedAt
        if (!y.isFinite() || age < 0 || age >= 220_000_000L) return null
        val anchor = landingY
        if (anchor == null) {
            landingY = y
            smoothed = y
            lastSample = now
            return y
        }
        if (abs(y - anchor) > 0.10) return null
        val elapsed = now - lastSample
        if (elapsed < 0) return null
        lastSample = now
        smoothed += (y - smoothed) * (1.0 - exp(-elapsed / 45_000_000.0))
        val weight = ((220_000_000L - age) / 65_000_000.0).coerceIn(0.0, 1.0)
        return y + (smoothed - y) * weight
    }
}
