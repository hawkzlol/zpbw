package com.hawkslol.zpbw

import com.google.gson.JsonParser
import kotlin.io.path.Path
import kotlin.io.path.readText
import kotlin.test.*

/** Source/packaging contracts, not Minecraft runtime or server acceptance tests. */
class ZpbwBoundaryTest {
    private val root = Path(System.getProperty("user.dir"))
    private val runtime get() = root.resolve("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt").readText()
    @Test fun separateDisabledCandidateObservesWithoutCalibrationRitual() {
        val meta = root.resolve("src/main/resources/fabric.mod.json").readText()
        assertContains(meta, "\"id\": \"zpbw\"")
        val metadata = JsonParser.parseString(meta).asJsonObject
        assertEquals(listOf("hawkslol"), metadata.getAsJsonArray("authors").map { it.asString })
        assertEquals("com.hawkslol.zpbw.ZpbwClient", metadata.getAsJsonObject("entrypoints").getAsJsonArray("client").single().asString)
        assertFalse(metadata.getAsJsonObject("breaks")?.has("blackwell") == true, "Blackwell alone must not prevent startup")
        assertFalse(meta.contains("labHarness"))
        assertContains(runtime, "private var mode = Mode.OFF")
        assertFalse(runtime.contains("calibrated < 3"))
        val client = root.resolve("src/main/kotlin/com/hawkslol/zpbw/ZpbwClient.kt").readText()
        assertContains(client, "lit(\"zpbw\")")
        assertFalse(client.contains("lit(\"chainmax\")"))
        assertContains(client, "ZpbwRuntime.joined()")
        assertContains(runtime, "ZpbwLog()")
        assertFalse(runtime.contains("gameDir.resolve(\"logs/zpbw\")"))
        assertContains(runtime, "log.snapshot()")
        assertContains(client, "lit(\"logs\")")
        assertContains(runtime, "events.size == 128")
        assertContains(runtime, "private val teleportIds = SessionTeleportIdHistory()")
    }
    @Test fun originalNativePairAndNoInventedResponses() {
        val mixin = root.resolve("src/main/java/com/hawkslol/zpbw/mixin/ZpbwTeleportMixin.java").readText()
        assertContains(mixin, "shift = At.Shift.AFTER")
        assertContains(mixin, "require = 2")
        assertContains(mixin, "original.call(connection, response)")
        listOf("ServerboundAcceptTeleportationPacket(", "ServerboundMovePlayerPacket.PosRot(", "ServerboundClientTickEndPacket(", "ServerboundPongPacket(").forEach { assertFalse(runtime.contains(it)) }
        assertContains(runtime, "p.stream.send(original.packet)")
        assertContains(runtime, "packet !== h.packet")
    }
    @Test fun drainOwnershipAndStaleCallbacksRemainGuarded() {
        assertFalse(runtime.contains(".disconnect("))
        assertContains(runtime, "epoch == owner && connected === connection")
        assertContains(runtime, "chain.retain(Held.Wire(packet), action = fastAction)")
        assertContains(runtime, "chain.settle(p.generation, packet.id())")
        assertContains(runtime, "return \"Disabled.\"")
        assertContains(runtime, "return if (requested == Mode.REPLAY) \"Enabled.\"")
        assertContains(runtime, "player as ZpbwPlayerAccessor")
        assertContains(runtime, "if (original !is ServerboundMovePlayerPacket &&")
        val connection = root.resolve("src/main/java/com/hawkslol/zpbw/mixin/ZpbwConnectionMixin.java").readText()
        assertContains(connection, "original.call(connection, packet, listener)")
        assertFalse(connection.contains("at = @At(\"RETURN\")"))
        assertContains(runtime, "!tail.cut && packet is ServerboundMovePlayerPacket && !packet.hasPosition()")
        assertContains(runtime, "p.player.yRot = yaw; p.player.xRot = pitch")
        val relocation = runtime.substringAfter("chain.cut(tail.generation)").substringBefore("event(\"LOCAL_PREDICTED")
        assertContains(relocation, "if (p.dependent)")
        assertContains(relocation, "p.player.setOnGround(p.sourcePose.ground)")
        assertContains(relocation, "p.player.verticalCollisionBelow = p.sourcePose.below")
        assertContains(runtime, "rollback.sourcePose.copy(ground = packet.isOnGround")
        val source = root.resolve("src/main/java/com/hawkslol/zpbw/mixin/ZpbwSourceTickMixin.java").readText()
        assertContains(source, "method = \"sendPosition\"")
        val sourceTick = runtime.substringAfter("fun sourceTick(player:").substringBefore("fun sourceTickFinished(")
        assertTrue(sourceTick.indexOf("chain.coalesce()") < sourceTick.indexOf("val sender ="))
        assertContains(sourceTick, "val collision = sentCollision ?:")
        assertContains(sourceTick, "player.setOnGround(collision.first)")
        assertFalse(sourceTick.contains("player.setOnGround(false)"))
        val physics = runtime.substringAfter("fun afterPhysicsUpdate()").substringBefore("fun previousSneakForPhysics(")
        assertContains(physics, "p.player.setOnGround(p.sourcePose.ground)")
        assertContains(physics, "p.player.horizontalCollision = p.sourcePose.horizontal")
        assertContains(physics, "if (!tail.cut) return")
        assertContains(physics, "`zpbw\$setPositionReminder`(20)")
        val sent = runtime.substringAfter("fun afterSend(").substringBefore("val tail = chain.tail ?: return")
        assertTrue(sent.indexOf("sentRotation =") < sent.indexOf("if (bypass || handling != null)"))
        val rebase = runtime.substringAfter("private fun rebaseSender(").substringBefore("private fun forwardEnvelope(")
        assertFalse(rebase.contains("Math.nextUp"), "Recovery must not force unchanged look onto the wire")
        assertContains(rebase, "sentRotation?.first")
        assertContains(rebase, "sentRotation?.second")
    }
    @Test fun skyblockTagsLoadedChunksAndNormalCursor() {
        val geometry = root.resolve("src/main/kotlin/com/hawkslol/zpbw/geometry/EtherwarpPredictor.kt").readText()
        assertContains(geometry, "getCompound(\"ExtraAttributes\")")
        assertContains(geometry, "if (level.hasChunk(x, z)) level.getChunk(x, z) else null")
        assertFalse(geometry.contains("com.hawkslol.blackwell"))
        assertFalse(runtime.contains("glfw"))
    }
    @Test fun failureUsesLocalChatActualServerPosition() {
        val client = root.resolve("src/main/kotlin/com/hawkslol/zpbw/ZpbwClient.kt").readText()
        assertFalse(client.contains("intentionalfailure"))
        assertContains(client, "ZpbwMessages.selection(ZpbwRuntime.select(")
        assertContains(runtime, "Observation(stream, unshifted")
        assertContains(runtime, "recover(reason, actual = absolute.position())")
        assertContains(runtime, "ZpbwMessages.failure(p.target.point(), actual?.point(), reason, positionConfirmed), false)")
        assertContains(runtime, "decision.motionCorrection")
        assertContains(runtime, "recover(decision.reason, actual = absolute.position(), positionConfirmed = true)")
        val recovery = runtime.substringAfter("private fun recover(").substringBefore("private fun prepareReplayLook(")
        assertContains(recovery, "`zpbw\$setPositionReminder`(transmittedPosition.reminder)")
        assertFalse(recovery.contains("`zpbw\$setPositionReminder`(20)"))
        assertTrue(recovery.indexOf("forwardEnvelope(") < recovery.indexOf("transmittedPosition.position?.let"))
        assertContains(runtime, "prepareReplayLook(p.player)")
        assertFalse(runtime.contains("notice(\"Prediction"))
    }
    @Test fun settingsLoadAtStartupAndSessionsClearOnlyInFlightState() {
        assertContains(runtime, "gameDir.resolve(\"config/zpbw\")")
        assertContains(runtime, "mode = if (settings.enabled) Mode.REPLAY else Mode.OFF")
        assertContains(runtime, "ZpbwSettings(mode == Mode.REPLAY, NoSneakDelay.enabled, timeoutTicks, firstInstall)")
        assertContains(runtime, "firstInstall = settings.firstInstall")
        assertContains(runtime, "firstInstall = false")
        val reset = runtime.substringAfter("fun reset() {").substringBefore("fun status()")
        assertFalse(reset.contains("mode = Mode.OFF"))
        assertContains(reset, "chain.clear()")
        val attach = runtime.substringAfter("private fun attach(").substringBefore("private fun identity(")
        assertFalse(attach.contains("mode = Mode.OFF"))
        assertContains(attach, "teleportIds.bind(stream, mc.level)")
        assertContains(attach, "sentRotation = null")
        assertContains(reset, "sentRotation = null")
        assertFalse(attach.contains("if (stream !== connected) recentIds.clear()"))
        assertContains(runtime, "val duplicate = teleportIds.record(packet.id())")
        assertContains(runtime, "if (!identityValid || !entry.cut || duplicate || !exact)")
    }
}
