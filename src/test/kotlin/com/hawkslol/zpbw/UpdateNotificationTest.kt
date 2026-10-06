package com.hawkslol.zpbw

import net.minecraft.network.chat.ClickEvent
import net.minecraft.network.chat.Component
import kotlin.test.*

class UpdateNotificationTest {
    private val available = UpdateCheckResult(UpdateCheckStatus.AVAILABLE, "1.1.0", "https://github.com/hawkzlol/zpbw/releases/tag/v1.1.0")
    @Test fun waitsForSkyblockAndFourContinuousSecondsThenShowsOnce() {
        val n=UpdateNotification(); val world=Any()
        assertNull(n.poll(available,false,world,false,0))
        assertNull(n.poll(available,false,world,true,10))
        assertNull(n.poll(available,false,world,true,4_000_000_009))
        assertSame(available,n.poll(available,false,world,true,4_000_000_010))
        n.reset()
        assertNull(n.poll(available,false,Any(),true,10_000_000_000))
        assertNull(n.poll(available,false,Any(),true,20_000_000_000))
    }
    @Test fun delayedResponseAndWorldChangesCannotDeliverStaleNotice() {
        val n=UpdateNotification(); val one=Any(); val two=Any()
        assertNull(n.poll(null,false,one,true,0))
        assertNull(n.poll(available,false,one,true,10_000_000_000))
        assertNull(n.poll(available,false,two,true,13_000_000_000))
        assertNull(n.poll(available,false,two,true,16_999_999_999))
        assertSame(available,n.poll(available,false,two,true,17_000_000_000))
    }
    @Test fun welcomeHasPriorityAndFailuresStayQuiet() {
        val n=UpdateNotification(); val world=Any()
        assertNull(n.poll(available,true,world,true,0))
        assertNull(n.poll(available,true,world,true,10_000_000_000))
        assertNull(n.poll(available,false,world,true,11_000_000_000))
        assertSame(available,n.poll(available,false,world,true,15_000_000_000))
        for (status in UpdateCheckStatus.entries.filter { it != UpdateCheckStatus.AVAILABLE }) {
            val quiet=UpdateNotification()
            assertNull(quiet.poll(UpdateCheckResult(status),false,world,true,0))
            assertNull(quiet.poll(UpdateCheckResult(status),false,world,true,20_000_000_000))
        }
    }
    @Test fun updateBannerUsesVersionAndClickableReleaseLinkWithoutLegacyCodes() {
        val message=ZpbwMessages.updateAvailable("1.0.0",available.version!!,available.url!!)
        assertContains(message.string,"Installed: 1.0.0 | Latest: 1.1.0")
        assertFalse(message.string.contains('&'))
        assertEquals(message.string.lines().first().length,message.string.lines().last().length)
        fun nodes(c:Component):List<Component> = listOf(c)+c.siblings.flatMap(::nodes)
        assertTrue(nodes(message).any { it.string==available.url && it.style.clickEvent is ClickEvent.OpenUrl })
    }
}
