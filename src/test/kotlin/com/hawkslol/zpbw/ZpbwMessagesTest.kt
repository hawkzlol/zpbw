package com.hawkslol.zpbw

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.TextColor
import kotlin.test.*

class ZpbwMessagesTest {
    @Test fun prereleaseNoticeIsOrangeOptionalAndKeepsTheExactNativeDownloadLink() {
        val url="https://github.com/hawkzlol/zpbw/releases/download/v1.0.4-pre2/zpbw-mc26.3-1.0.4-pre2.jar"
        val message=ZpbwMessages.updateAvailable("1.0.4-pre1","1.0.4-pre2",url,"26.3",prerelease=true)
        assertContains(message.string,"A ZPBW prerelease update is available!")
        assertContains(message.string,"Updating is optional, but recommended.")
        assertContains(message.string,"Minecraft: 26.3")
        val pieces=flattened(message)
        val orange=pieces.filter{it.siblings.isEmpty() && (it.string.contains("prerelease update")||it.string.contains("Updating is optional"))}
        assertEquals(2,orange.size)
        for(piece in orange)
            assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GOLD),piece.style.color)
        val link=pieces.first{it.style.clickEvent is ClickEvent.OpenUrl}
        assertEquals(url,link.string)
        val stable=ZpbwMessages.updateAvailable("1.0.4-pre2","1.0.4",url).string
        assertContains(stable,"A ZPBW update is available!");assertFalse(stable.contains("prerelease update"))
    }
    @Test fun automaticChatRespectsBothPreferencesAndKeepsFullMessagesLazy() {
        val timeout = { ZpbwMessages.timeout(20) }
        assertEquals(timeout().string,
            ZpbwMessages.automaticChat(false, false, false, timeout)?.string)
        assertNull(ZpbwMessages.automaticChat(true, false, false) { error("Muted timeout was formatted") })
        assertEquals("[ZPBW] Blinkwarp Prediction Failed!",
            ZpbwMessages.automaticChat(true, false, true) { error("Detailed mismatch was formatted") }?.string)
        for (reduced in listOf(false, true)) {
            assertNull(ZpbwMessages.automaticChat(reduced, true, true) { error("Disabled notice was formatted") })
        }
    }
    private fun flattened(c: Component): List<Component> = listOf(c) + c.siblings.flatMap(::flattened)
    @Test fun suspendedPredictionHasAnExplicitPrefixedRecoveryInstruction() {
        val message = ZpbwMessages.recoverySuspended()
        assertEquals("[ZPBW] Blinkwarp recovering. Predictions resume automatically after server synchronization.", message.string)
        val colors = flattened(message).mapNotNull { it.style.color }
        assertContains(colors, TextColor.fromLegacyFormat(ChatFormatting.BLUE))
        assertContains(colors, TextColor.fromLegacyFormat(ChatFormatting.RED))
        assertContains(colors, TextColor.fromLegacyFormat(ChatFormatting.WHITE))
    }
    @Test fun clipboardFailureKeepsTheOriginalTextAndExplainsTheConsoleFallback() {
        assertEquals("[ZPBW] Could not copy ZPBW logs. Check clipboard access and try again. Pasted logs to console instead.",
            ZpbwMessages.clipboardFallback().string)
    }
    @Test fun prefixAndTogglesUseBlueGrayWhite() {
        val message=ZpbwMessages.selection("Enabled Zero Ping Blinkwarp.")
        assertEquals("[ZPBW] Enabled Zero Ping Blinkwarp.",message.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY),message.siblings[0].style.color)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.BLUE),message.siblings[1].style.color)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE),message.siblings.last().style.color)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE),ZpbwMessages.selection("Disabled Zero Ping Blinkwarp.").siblings.last().style.color)
        assertFalse(message.string.contains('&'))
    }
    @Test fun helpHasOnlyPublicCommandsAndMatchingBorder() {
        val help=ZpbwMessages.help().string
        val lines=help.lines()
        assertEquals("===== Zero Ping Blinkwarp =====",lines.first())
        assertEquals(lines.first().length,lines.last().length)
        assertTrue(lines.last().all { it=='=' })
        for(command in listOf("on","off","nosneakdelay","nsd","timeout <ticks>","logs")) assertContains(help,command)
        assertFalse(help.contains("status")); assertFalse(help.contains("[[")); assertFalse(help.contains("]]"))
        assertFalse(help.contains("[ZPBW]"))
        val pixelWidth: (String)->Int={ text->text.sumOf { if(it=='=') 6 else 5 } }
        val rendered=ZpbwMessages.help(pixelWidth).string.lines()
        assertTrue(kotlin.math.abs(pixelWidth(rendered.first())-pixelWidth(rendered.last()))<=3)
    }
    @Test fun resetDetailsAreHoverOnlyAndCoordinatesStayHonest() {
        val p=PredictionFailureSupport.Point(1.0,2.0,3.0)
        val reset=ZpbwMessages.failure(p,null,"unsupported_outgoing_ExamplePacket")
        assertContains(reset.string,"Prediction reset error occurred.")
        assertFalse(reset.string.contains("ExamplePacket"))
        val hover=flattened(reset).mapNotNull { it.style.hoverEvent as? HoverEvent.ShowText }.first()
        assertContains(hover.value().string,"unsupported_outgoing_ExamplePacket")
        assertContains(reset.string,"Received: (unavailable). Difference: unavailable.")
        assertFalse(reset.string.contains("0.000 blocks"))
        val mismatch=ZpbwMessages.failure(p,p.copy(x=4.0,y=6.0))
        assertEquals("[ZPBW] Blinkwarp prediction failed: Destination differs. Guessed: (1.000, 2.000, 3.000), Received: (4.000, 6.000, 3.000). Difference: 5.000 blocks.",mismatch.string)
        val motion=ZpbwMessages.failure(p,p,"authoritative_velocity_changed",true)
        assertContains(motion.string,"Server motion changed."); assertFalse(motion.string.contains("prediction failed"))
    }
    @Test fun queueTimeoutWaitingAndCopyMessagesMatchPublicWording() {
        assertEquals("[ZPBW] Waiting for server! (3/5)",ZpbwMessages.waiting(3, 5).string)
        assertEquals("[ZPBW] Waiting for server! (5/1)",ZpbwMessages.waiting(5, 1).string)
        assertEquals("[ZPBW] Blinkwarp failed: No teleport arrived within 20 ticks.",ZpbwMessages.timeout(20).string)
        assertEquals("[ZPBW] Copied ZPBW logs to clipboard. You can now share it with a developer.",ZpbwMessages.copiedLogs().string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN),ZpbwMessages.copiedLogs().siblings.last().style.color)
    }
    @Test fun cancelledQueuedActionsDoNotClaimAnIssuedBlinkwarpFailed() {
        val message=ZpbwMessages.queuedCancellation("The target or player state changed.","queued_click_context_changed")
        assertEquals("[ZPBW] Queued action cancelled: The target or player state changed.",message.string)
        assertFalse(message.string.contains("Blinkwarp failed"))
        assertFalse(message.string.contains("queued_click_context_changed"))
        val pieces=flattened(message)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY),pieces.first { it.string=="Queued action cancelled: " }.style.color)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE),pieces.first { it.string=="The target or player state changed." }.style.color)
        val hover=pieces.mapNotNull { it.style.hoverEvent as? HoverEvent.ShowText }.first()
        assertEquals("Reason: queued_click_context_changed",hover.value().string)
        assertEquals("[ZPBW] Queued action cancelled: A screen was opened.",ZpbwMessages.queuedCancellation("A screen was opened.").string)
        assertEquals("[ZPBW] Blinkwarp failed: No teleport arrived within 20 ticks.",ZpbwMessages.timeout(20).string)
    }
    @Test fun welcomeHasVersionAndClickableUnderlinedProjectLink() {
        val c=ZpbwMessages.welcome("1.0.0")
        assertContains(c.string,"You just installed ZPBW 1.0.0!")
        assertContains(c.string,"To get started, run /zpbw to open settings or /zpbw help for commands.")
        val pieces=flattened(c)
        val installed=pieces.first { it.string=="You just installed ZPBW 1.0.0!\n" }
        assertTrue(installed.style.isBold)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN),installed.style.color)
        val command=pieces.first { it.string=="/zpbw" }
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE),command.style.color)
        assertFalse(command.style.isBold)
        val continuation=pieces.first { it.string.startsWith(" to open settings or ") }
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN),continuation.style.color)
        assertFalse(continuation.style.isBold)
        assertEquals(c.string.lines().first().length,c.string.lines().last().length)
        val link=flattened(c).first { it.style.clickEvent is ClickEvent.OpenUrl }
        assertEquals(ZpbwMessages.REPOSITORY,link.string)
        assertTrue(link.style.isUnderlined)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.AQUA),link.style.color)
        assertFalse(c.string.contains('&')); assertFalse(c.string.contains("[ZPBW]"))
    }
}
