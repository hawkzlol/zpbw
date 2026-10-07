package com.hawkslol.zpbw

import kotlin.test.*

class CarriedSlotHistoryTest {
    @Test fun worldReplacementRestoresOnlyActuallySentSlotOnce() {
        val stream=Any(); val history=CarriedSlotHistory<Any>()
        history.bind(stream);history.sent(1);history.captured()
        history.bind(stream)
        assertEquals(1,history.abandon(stream));assertNull(history.abandon(stream))
    }
    @Test fun successfulReplayLeavesNativeCacheAlone() {
        val stream=Any();val history=CarriedSlotHistory<Any>()
        history.bind(stream);history.sent(1);history.captured();history.sent(6)
        assertNull(history.abandon(stream))
    }
    @Test fun newConnectionCannotInheritOldSlotOrInventZero() {
        val first=Any();val second=Any();val history=CarriedSlotHistory<Any>()
        history.bind(first);history.sent(6);history.captured()
        assertNull(history.abandon(second));history.bind(second);history.captured()
        assertNull(history.abandon(second))
    }
}
