package com.hawkslol.zpbw.geometry

import net.minecraft.client.Minecraft
import net.minecraft.core.BlockPos
import net.minecraft.core.component.DataComponents
import net.minecraft.world.entity.Pose
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.Vec3

data class EtherwarpRotationPrediction(
    val succeeded: Boolean,
    val target: BlockPos?,
    val landingFeetPosition: Vec3?,
    val diagnostics: List<String>
)

/** Item/source selection remains separate from the testable block ray. Remote parity is unproved. */
object EtherwarpPredictor {
    private val mc get() = Minecraft.getInstance()
    @JvmStatic
    fun predictCurrentEtherwarpForRotation(
        yaw: Float,
        pitch: Float,
        sourceOverride: Vec3? = null,
        requireArmed: Boolean = true,
        countSkips: Boolean = true,
        sneakingOverride: Boolean? = null
    ): EtherwarpRotationPrediction? {
        val player = mc.player ?: return null
        val heldData = heldItemData(player.mainHandItem)
        if (!isEtherwarpItem(heldData)) return null
        val sneaking = sneakingOverride ?: (player.isShiftKeyDown || mc.options.keyShift.isDown)
        if (requireArmed && !sneaking && heldData.id != "ETHERWARP_CONDUIT") return null

        if (heldData.tunedTransmission !in 0..4) return null
        val range = 57.0 + heldData.tunedTransmission
        val position = sourceOverride ?: player.position()
        val eyePose = if (sneaking || player.isCrouching) Pose.CROUCHING else player.pose
        val eyeHeight = player.getDimensions(eyePose).eyeHeight().toDouble()
        val start = position.add(0.0, eyeHeight, 0.0)
        val look = Vec3.directionFromRotation(pitch, yaw)
        val level = mc.level ?: return null
        val hit = EtherwarpRaycast(level) { x, z -> if (level.hasChunk(x, z)) level.getChunk(x, z) else null }
            .trace(start, start.add(look.scale(range)))
        val landing = if (hit.succeeded) hit.pos?.let { Vec3(it.x + 0.5, hit.landingFeetY!!.toDouble(), it.z + 0.5) } else null
        val input = "origin=$position anchorOverride=${sourceOverride != null} eye=$eyeHeight eyePose=$eyePose playerPose=${player.pose} crouching=${player.isCrouching} shift=${player.isShiftKeyDown} keyShift=${mc.options.keyShift.isDown} effectiveSneak=$sneaking override=$sneakingOverride yaw=$yaw pitch=$pitch range=$range"
        return EtherwarpRotationPrediction(hit.succeeded, hit.pos, landing, listOf(input) + hit.details)
    }

    private data class HeldData(val id: String?, val hasEthermerge: Boolean, val tunedTransmission: Int)

    fun qualifiesForBlockUse(stack: ItemStack, sneaking: Boolean): Boolean {
        val data = heldItemData(stack)
        return isEtherwarpItem(data) && data.tunedTransmission in 0..4 &&
            (sneaking || data.id == "ETHERWARP_CONDUIT")
    }

    private fun heldItemData(stack: ItemStack): HeldData {
        if (stack.isEmpty) return HeldData(null, false, 0)
        val data = stack.get(DataComponents.CUSTOM_DATA)?.copyTag()
        val tag = data?.getCompound("ExtraAttributes")?.orElse(data)
        return HeldData(
            tag?.getStringOr("id", "")?.ifEmpty { null },
            tag?.getIntOr("ethermerge", 0) == 1,
            tag?.getIntOr("tuned_transmission", 0) ?: 0
        )
    }

    private fun isEtherwarpItem(data: HeldData): Boolean = when (data.id) {
        "ASPECT_OF_THE_END", "ASPECT_OF_THE_VOID" -> data.hasEthermerge
        "ETHERWARP_CONDUIT" -> true
        else -> false
    }

}
