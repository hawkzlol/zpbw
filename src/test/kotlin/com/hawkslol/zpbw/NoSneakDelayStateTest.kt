package com.hawkslol.zpbw

import net.minecraft.world.entity.player.Input
import kotlin.test.*

/** Calls the production configuration/reset entrypoints without starting Minecraft. */
class NoSneakDelayStateTest {
    @Suppress("UNCHECKED_CAST")
    private fun latch() = NoSneakDelay::class.java.getDeclaredField("latch").run {
        isAccessible = true
        get(NoSneakDelay) as TickInputLatch<Input>
    }

    @Test fun disablingHonorsAlreadyTransmittedInputButCannotReplayAnUnsentSnapshot() {
        NoSneakDelay.reset()
        try {
            NoSneakDelay.configure(true)
            val latch = latch()
            val sent = Input(false, false, false, false, false, true, false)
            assertTrue(latch.commit(sent))
            NoSneakDelay.configure(false)
            assertTrue(NoSneakDelay.hasCommitment())
            assertEquals(sent, latch.sample(Input.EMPTY))
            latch.finishTick()
            assertFalse(NoSneakDelay.hasCommitment())
            assertEquals(Input.EMPTY, latch.sample(Input.EMPTY))
            NoSneakDelay.configure(true)
            assertFalse(NoSneakDelay.hasCommitment())
        } finally { NoSneakDelay.configure(false); NoSneakDelay.reset() }
    }

    @Test fun sessionResetDiscardsAnUnconsumedCommitmentAndBindingEdges() {
        NoSneakDelay.reset()
        try {
            assertTrue(latch().commit(Input.EMPTY))
            assertTrue(NoSneakDelay.hasCommitment())
            NoSneakDelay.reset()
            assertFalse(NoSneakDelay.hasCommitment())
            assertNull(latch().value)
            for (name in listOf("player", "level", "stream")) {
                val field = NoSneakDelay::class.java.getDeclaredField(name).apply { isAccessible = true }
                assertNull(field.get(NoSneakDelay), name)
            }
        } finally { NoSneakDelay.configure(false); NoSneakDelay.reset() }
    }
}
