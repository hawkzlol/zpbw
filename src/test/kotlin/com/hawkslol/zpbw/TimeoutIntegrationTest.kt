package com.hawkslol.zpbw

import com.mojang.brigadier.CommandDispatcher
import net.fabricmc.fabric.api.client.command.v2.FabricClientCommandSource
import net.minecraft.ChatFormatting
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.TextColor
import net.minecraft.network.protocol.common.ClientboundDisconnectPacket
import net.minecraft.network.protocol.common.ClientboundKeepAlivePacket
import net.minecraft.network.protocol.common.ClientboundPingPacket
import net.minecraft.network.protocol.game.ClientboundBundlePacket
import net.minecraft.network.protocol.game.ClientboundPlayerPositionPacket
import net.minecraft.world.entity.PositionMoveRotation
import net.minecraft.world.phys.Vec3
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.*

class TimeoutIntegrationTest {
    private fun teleport() = ClientboundPlayerPositionPacket(51, PositionMoveRotation(Vec3.ZERO, Vec3.ZERO, 0f, 0f), emptySet())
    @Test fun realPacketTypesCountOnlyNonzeroPingAndPreserveZeroAndKeepalive() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session); clock.start(session,1,2)
        repeat(500) {
            assertTrue(WarpTimeoutPackets.receive(clock,session,ClientboundPingPacket(0)).isEmpty())
            assertTrue(WarpTimeoutPackets.receive(clock,session,ClientboundKeepAlivePacket(12L)).isEmpty())
        }
        assertTrue(WarpTimeoutPackets.receive(clock,session,ClientboundPingPacket(-3)).isEmpty())
        assertEquals(1L,WarpTimeoutPackets.receive(clock,session,ClientboundPingPacket(-3)).single().generation)
    }
    @Test fun bundledTeleportStopsTimerAtNettyArrivalWithoutWaitingForVanillaHandler() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session); clock.start(session,1,2)
        WarpTimeoutPackets.receive(clock,session,ClientboundPingPacket(9))
        WarpTimeoutPackets.receive(clock,session,ClientboundBundlePacket(listOf(teleport())))
        repeat(100) { assertTrue(WarpTimeoutPackets.receive(clock,session,ClientboundPingPacket(9)).isEmpty()) }
    }
    @Test fun realDisconnectCancelsQueuedExpiryAndRefusesOldSessionStarts() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session); clock.start(session,1,2)
        WarpTimeoutPackets.receive(clock,session,ClientboundPingPacket(1))
        val ticket=WarpTimeoutPackets.receive(clock,session,ClientboundPingPacket(1)).single()
        WarpTimeoutPackets.receive(clock,session,ClientboundDisconnectPacket(Component.literal("test")))
        assertFalse(clock.claim(ticket)); assertFalse(clock.start(session,2,20))
    }
    @Test fun timeoutCancelsQueuedPearlAndFiveOverlappingWarpsWithoutRunningActions() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session)
        val ledger=ChainLedger<String,Runnable>(); var pearls=16
        for (id in 1L..5L) { ledger.add(id,"warp$id"); ledger.cut(id); clock.start(session,id,20) }
        ledger.retain(Runnable { pearls-- }, action=true)
        repeat(19) { assertTrue(clock.ping(session,1).isEmpty()) }
        val expired=clock.ping(session,1); assertEquals(5,expired.size)
        var failures=0
        expired.forEach { if (clock.claim(it)) { clock.clear(); ledger.clear(); failures++ } }
        assertEquals(1,failures); assertEquals(16,pearls); assertEquals(0,ledger.count); assertEquals(0,ledger.size)
    }
    @Test fun independentClockCannotReleasePacketsBeforeBothNativeResponses() {
        val session=Any(); val clock=ServerTickTimeouts<Any>(); clock.bind(session); clock.start(session,1,2)
        val ledger=ChainLedger<String,Any>(); val packet=Any(); ledger.add(1,"warp"); ledger.cut(1); ledger.retain(packet)
        clock.teleport(session); repeat(100) { assertTrue(clock.ping(session,1).isEmpty()) }
        assertEquals(1,ledger.size); ledger.genuine(1,51)
        assertFailsWith<IllegalStateException> { ledger.settle(1,51) }
        ledger.response(51,true); assertFailsWith<IllegalStateException> { ledger.settle(1,51) }
        ledger.response(51,false); assertSame(packet,ledger.settle(1,51).single())
    }
    @Test fun publicCommandTreeAcceptsIntegersForNormalizationAndHasNoRetiredCommands() {
        val dispatcher=CommandDispatcher<FabricClientCommandSource>(); ZpbwClient().registerCommands(dispatcher)
        assertEquals(listOf("zpbw"), dispatcher.root.children.map { it.name })
        val root = dispatcher.root.getChild("zpbw")
        assertNotNull(root.command, "The bare command must show help")
        assertEquals(setOf("on", "off", "nosneakdelay", "nsd", "timeout", "logs"), root.children.map { it.name }.toSet())
        for (n in listOf(Int.MIN_VALUE, -1, 0, 1, 2, 20, 21, 30, Int.MAX_VALUE)) {
            val parsed=dispatcher.parse("zpbw timeout $n",null)
            assertTrue(parsed.exceptions.isEmpty()); assertFalse(parsed.reader.canRead())
        }
        for (invalid in listOf("2.5","abc","2147483648")) {
            val parsed=dispatcher.parse("zpbw timeout $invalid",null)
            assertTrue(parsed.exceptions.isNotEmpty() || parsed.reader.canRead())
        }
        for (option in listOf("intentionalfailure","fast","chainmax","status","observe","diagnostics")) assertNull(root.getChild(option))
        val runtime=Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val configure=runtime.substringAfter("fun configureTimeout(ticks: Int)").substringBefore("fun received(")
        assertContains(configure,"val normalized = ticks.coerceIn(2, 20)")
        assertContains(configure,"timeoutTicks = normalized")
        assertContains(configure,"Set Blinkwarp timeout to \$normalized ticks.")
    }
    @Test fun timeoutTitleIsRedAndTickExplanationIsWhite() {
        for (ticks in listOf(2,10,20)) {
            val message=ZpbwMessages.timeout(ticks)
            assertEquals("[ZPBW] Blinkwarp failed: No teleport arrived within $ticks ticks.",message.string)
            val body=message.siblings.last()
            assertEquals(TextColor.fromLegacyFormat(ChatFormatting.RED),body.siblings[0].style.color)
            assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE),body.siblings[1].style.color)
        }
    }
    @Test fun mainThreadAndNettyBoundariesContainNoOldExpiryOrFaultInjection() {
        val runtime=Files.readString(Path.of("src/main/kotlin/com/hawkslol/zpbw/ZpbwRuntime.kt"))
        val mixin=Files.readString(Path.of("src/main/java/com/hawkslol/zpbw/mixin/ZpbwConnectionMixin.java"))
        assertContains(mixin,"channelRead0(Lio/netty/channel/ChannelHandlerContext;Lnet/minecraft/network/protocol/Packet;)V")
        assertContains(mixin,"genericsFtw"); assertContains(mixin,"ZpbwRuntime.received")
        assertContains(mixin,"ZpbwRuntime.channelClosed")
        val inbound=runtime.substringAfter("fun received(").substringBefore("fun channelClosed(")
        assertContains(inbound,"mc.execute"); assertContains(inbound,"warpTimeouts.claim(expiry)")
        for (removed in listOf("chain.expired", "no_genuine_confirmation_within_1s", "intentionalFailure", "OBSERVE_TIMEOUT"))
            assertFalse(runtime.contains(removed))
        assertContains(runtime,"warpTimeouts.startAt(stream, generation, budget?.ticks ?: timeoutTicks, budget?.startedAt)")
        assertContains(runtime,"warpTimeouts.cancel(p.generation)")
        assertContains(runtime,"epoch++; warpTimeouts.bind(null)")
        assertContains(runtime,"epoch++; warpTimeouts.bind(stream)")
        assertContains(runtime,"mc.player !== sessionPlayer")
        assertContains(runtime.substringAfter("fun beforeGenuine(").substringBefore("val duplicate"),"drainTimeouts()")
        assertContains(runtime.substringAfter("event(\"VANILLA_HANDLER_COMPLETE").substringBefore("fun tick()"),"drainTimeouts()")
        assertContains(runtime.substringAfter("private fun recover("),"warpTimeouts.clear()")
    }
}
