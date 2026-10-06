package com.hawkslol.zpbw

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import net.minecraft.network.chat.HoverEvent
import net.minecraft.network.chat.TextColor
import kotlin.test.*

class ZpbwMessagesTest {
    private fun flattened(c: Component): List<Component> = listOf(c) + c.siblings.flatMap(::flattened)
    @Test fun prefixAndTogglesUseBlueGrayWhite() {
        val message=ZpbwMessages.selection("Enabled.")
        assertEquals("[ZPBW] Enabled.",message.string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GRAY),message.siblings[0].style.color)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.BLUE),message.siblings[1].style.color)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE),message.siblings.last().style.color)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.WHITE),ZpbwMessages.selection("Disabled.").siblings.last().style.color)
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
        assertEquals("[ZPBW] Waiting for server! (3/5)",ZpbwMessages.waiting(3).string)
        assertEquals("[ZPBW] Blinkwarp failed: No teleport arrived within 20 ticks.",ZpbwMessages.timeout(20).string)
        assertEquals("[ZPBW] Copied ZPBW logs to clipboard. You can now share it with a developer.",ZpbwMessages.copiedLogs().string)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.GREEN),ZpbwMessages.copiedLogs().siblings.last().style.color)
    }
    @Test fun welcomeHasVersionAndClickableUnderlinedProjectLink() {
        val c=ZpbwMessages.welcome("1.0.0")
        assertContains(c.string,"You just installed ZPBW 1.0.0!")
        assertEquals(c.string.lines().first().length,c.string.lines().last().length)
        val link=flattened(c).first { it.style.clickEvent is ClickEvent.OpenUrl }
        assertEquals(ZpbwMessages.REPOSITORY,link.string)
        assertTrue(link.style.isUnderlined)
        assertEquals(TextColor.fromLegacyFormat(ChatFormatting.BLUE),link.style.color)
        assertFalse(c.string.contains('&')); assertFalse(c.string.contains("[ZPBW]"))
    }
}
