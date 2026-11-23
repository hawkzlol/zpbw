package com.hawkslol.zpbw.overlay

import com.hawkslol.zpbw.ZpbwRuntime
import com.hawkslol.zpbw.geometry.EtherwarpPredictor
import com.hawkslol.zpbw.geometry.EtherwarpRotationPrediction
import com.hawkslol.zpbw.nativebridge.OverlayBlend
import com.hawkslol.zpbw.nativebridge.OverlayColorTarget
import com.hawkslol.zpbw.nativebridge.OverlayCompare
import com.hawkslol.zpbw.nativebridge.OverlayDepth
import com.hawkslol.zpbw.nativebridge.OverlayPipeline
import com.mojang.blaze3d.vertex.PoseStack
import com.mojang.blaze3d.vertex.VertexConsumer
import net.fabricmc.fabric.api.client.rendering.v1.FabricOrderedSubmitNodeCollector
import net.fabricmc.fabric.api.client.rendering.v1.SubmitRenderPhases
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderContext
import net.fabricmc.fabric.api.client.rendering.v1.level.LevelRenderEvents
import net.minecraft.client.Minecraft
import net.minecraft.client.multiplayer.ClientLevel
import net.minecraft.client.player.LocalPlayer
import net.minecraft.client.renderer.RenderPipelines
import net.minecraft.client.renderer.SubmitNodeCollector
import net.minecraft.client.renderer.feature.CustomFeatureRenderer
import net.minecraft.client.renderer.rendertype.LayeringTransform
import net.minecraft.client.renderer.rendertype.RenderSetup
import net.minecraft.client.renderer.rendertype.RenderType
import net.minecraft.world.item.ItemStack
import net.minecraft.world.phys.AABB
import net.minecraft.world.phys.Vec3

internal object EtherwarpOverlay {
    private data class Query(val world: ClientLevel, val player: LocalPlayer, val tick: Int,
                             val position: Vec3, val anchor: Vec3?, val yaw: Float, val pitch: Float,
                             val sneaking: Boolean, val showAlways: Boolean, val item: ItemStack)
    private var previous: Query? = null
    private var lastResult: EtherwarpRotationPrediction? = null
    private val depthFill by lazy { renderType(false, true) }
    private val unoccludedFill by lazy { renderType(false, false) }
    private val depthLines by lazy { renderType(true, true) }
    private val unoccludedLines by lazy { renderType(true, false) }

    fun register() {
        // The submit lists are consumed before END_MAIN. Collect geometry while this frame still owns them.
        LevelRenderEvents.COLLECT_SUBMITS.register(LevelRenderEvents.CollectSubmits(::render))
    }

    fun clear() {
        previous = null
        lastResult = null
    }

    private fun render(context: LevelRenderContext) {
        val options = ZpbwRuntime.overlayOptions
        if (!options.enabled) return
        val client = Minecraft.getInstance()
        val player = client.player ?: return
        val level = client.level ?: return
        if (client.gui.screen() != null || client.cameraEntity !== player || player.isSpectator ||
            ZpbwRuntime.etherwarpLocationRestricted()) return
        val poseStack = context.poseStack()
        val position = player.position()
        val anchor = ZpbwRuntime.etherwarpOverlayOrigin()
        val sneaking = client.options.keyShift.isDown || player.isShiftKeyDown
        val old = previous
        if (old == null || old.world !== level || old.player !== player || old.tick != player.tickCount ||
            old.position != position || old.anchor != anchor || old.yaw != player.yRot || old.pitch != player.xRot ||
            old.sneaking != sneaking || old.showAlways != options.showAlways ||
            !ItemStack.isSameItemSameComponents(old.item, player.mainHandItem)) {
            previous = Query(level, player, player.tickCount, position, anchor, player.yRot, player.xRot,
                sneaking, options.showAlways, player.mainHandItem.copy())
            lastResult = EtherwarpPredictor.predictCurrentEtherwarpForRotation(player.yRot, player.xRot, anchor,
                requireArmed = !options.showAlways, countSkips = false, sneakingOverride = sneaking)
        }
        val prediction = lastResult ?: return
        val target = prediction.target ?: return
        if (!level.hasChunk(target.x shr 4, target.z shr 4)) return
        val fill = if (prediction.succeeded) options.successFill else options.failFill
        val outline = if (prediction.succeeded) options.successOutline else options.failOutline
        val drawFill = fill ushr 24 != 0 && options.style != OverlayShape.OUTLINE
        val drawOutline = outline ushr 24 != 0 && options.style != OverlayShape.FILL
        if (!drawFill && !drawOutline) return
        val shape = if (options.fullBlock) null else level.getBlockState(target).getShape(level, target).takeUnless { it.isEmpty }
        val eye = context.gameRenderer().mainCamera().position()
        val offset = Vec3(target.x - eye.x, target.y - eye.y, target.z - eye.z)
        val relative = AABB(0.0, 0.0, 0.0, 1.0, 1.0, 1.0).move(offset).inflate(0.002)
        val collector = context.submitNodeCollector()
        if (drawFill) submit(collector, poseStack, if (options.depth) depthFill else unoccludedFill) { pose, vertices ->
            if (shape == null) emitFaces(pose, vertices, relative, fill)
            else shape.forAllBoxes { x0, y0, z0, x1, y1, z1 ->
                emitFaces(pose, vertices, AABB(x0, y0, z0, x1, y1, z1).move(offset).inflate(0.002), fill)
            }
        }
        if (drawOutline) submit(collector, poseStack, if (options.depth) depthLines else unoccludedLines) { pose, vertices ->
            val width = options.lineWidth.toFloat()
            if (shape == null) emitEdges(pose, vertices, relative, outline, width)
            else shape.forAllEdges { x0, y0, z0, x1, y1, z1 ->
                val dx = (x1 - x0).toFloat()
                val dy = (y1 - y0).toFloat()
                val dz = (z1 - z0).toFloat()
                vertices.addVertex(pose, (x0 + offset.x).toFloat(), (y0 + offset.y).toFloat(), (z0 + offset.z).toFloat())
                    .setColor(outline).setNormal(pose, dx, dy, dz).setLineWidth(width)
                vertices.addVertex(pose, (x1 + offset.x).toFloat(), (y1 + offset.y).toFloat(), (z1 + offset.z).toFloat())
                    .setColor(outline).setNormal(pose, dx, dy, dz).setLineWidth(width)
            }
        }
    }

    private fun submit(collector: SubmitNodeCollector, stack: PoseStack, type: RenderType,
                       draw: SubmitNodeCollector.CustomGeometryRenderer) {
        // The native always-on-top pass may be skipped unless vanilla produced its own gizmos.
        (collector as FabricOrderedSubmitNodeCollector).submitCustom(SubmitRenderPhases.AFTER_TERRAIN,
            CustomFeatureRenderer.Submit(stack.last().copy(), type, draw))
    }

    private fun renderType(lines: Boolean, depth: Boolean): RenderType {
        val name = "etherwarp_${if (lines) "outline" else "fill"}_${if (depth) "depth" else "visible"}"
        val pipeline = OverlayPipeline.builder(if (lines) RenderPipelines.LINES_SNIPPET else RenderPipelines.DEBUG_FILLED_SNIPPET)
            .withLocation("zpbw/$name")
            .withColorTargetState(OverlayColorTarget(OverlayBlend.TRANSLUCENT))
            .withDepthStencilState(OverlayDepth(if (depth) OverlayDepth.DEFAULT.depthTest() else OverlayCompare.ALWAYS_PASS, false))
            .withCull(false)
            .build()
        val setup = RenderSetup.builder(RenderPipelines.register(pipeline)).setLayeringTransform(LayeringTransform.VIEW_OFFSET_Z_LAYERING)
        if (!lines) setup.sortOnUpload()
        return RenderType.create("zpbw_$name", setup.createRenderSetup())
    }

    private val faces = intArrayOf(0, 4, 6, 2, 1, 3, 7, 5, 0, 1, 5, 4, 2, 6, 7, 3, 0, 2, 3, 1, 4, 5, 7, 6)

    private fun emitFaces(pose: PoseStack.Pose, consumer: VertexConsumer, box: AABB, color: Int) {
        for (index in faces) vertex(pose, consumer, box, index).setColor(color)
    }

    private fun emitEdges(pose: PoseStack.Pose, consumer: VertexConsumer, box: AABB, color: Int, width: Float) {
        for (axis in 0..2) {
            val bit = 1 shl axis
            for (corner in 0..7) if (corner and bit == 0) {
                for (end in 0..1) {
                    vertex(pose, consumer, box, corner or (end * bit)).setColor(color)
                        .setNormal(pose, if (axis == 0) 1f else 0f, if (axis == 1) 1f else 0f, if (axis == 2) 1f else 0f)
                        .setLineWidth(width)
                }
            }
        }
    }

    private fun vertex(pose: PoseStack.Pose, consumer: VertexConsumer, box: AABB, corner: Int) =
        consumer.addVertex(pose, (if (corner and 1 == 0) box.minX else box.maxX).toFloat(),
            (if (corner and 2 == 0) box.minY else box.maxY).toFloat(),
            (if (corner and 4 == 0) box.minZ else box.maxZ).toFloat())
}
