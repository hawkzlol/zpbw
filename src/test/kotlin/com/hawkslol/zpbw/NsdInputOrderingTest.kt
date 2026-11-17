package com.hawkslol.zpbw

import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.world.entity.player.Input
import kotlin.test.*

/** Exercises the real latch and send-history classes in native dispatch order.
 * This is an offline input/packet-budget model, not Minecraft physics or Grim acceptance. */
class NsdInputOrderingTest {
    private fun input(bits: Int, shift: Boolean = true) = Input(
        bits and 1 != 0, bits and 2 != 0, bits and 4 != 0, bits and 8 != 0,
        bits and 16 != 0, shift, bits and 32 != 0)

    private class Window(initial: Input = Input.EMPTY) {
        val latch = TickInputLatch<Input>()
        val sent = TransmittedControlHistory()
        val movement = MovementInputHistory()
        var sampled = initial
        var nativeCache = initial
        var inputPackets = 0
        var tickEnds = 0
        val wire = mutableListOf<String>()

        init { sent.sent(ServerboundPlayerInputPacket(initial)) }
        private fun send(value: Input) {
            inputPackets++
            assertEquals(1, inputPackets, "No second input is allowed before the real TickEnd")
            sent.sent(ServerboundPlayerInputPacket(value))
            movement.input(value.shift())
            wire += "input"
        }
        fun early(value: Input) { assertTrue(latch.commit(value)); send(value) }
        fun click(deferredUse: Input? = null): Input = deferredUse ?: latch.value ?: sampled
        fun naturalTick(latest: Input): Input {
            val selected = latch.sample(latest)
            sampled = selected
            if (nativeCache != selected) {
                // LocalPlayer.tick sends changed input after sampling/physics;
                // the existing wrapper suppresses only the identical early send.
                if (latch.value != selected) send(selected)
                nativeCache = selected // Vanilla updates this even on a suppressed send.
            }
            assertEquals(selected, sent.input, "Movement must use the actual transmitted input")
            assertEquals(selected, nativeCache)
            movement.position(teleportResponse = false)
            wire += "movement"
            wire += "TickEnd"
            tickEnds++
            inputPackets = 0
            latch.finishTick()
            return selected
        }
    }

    @Test fun allMovementPressesReleasesReversalsJumpAndSprintUseNewestInputAfterRequiredSample() {
        // Every combination of forward/back/strafe/jump/sprint, including opposite keys.
        for (before in 0..63) for (after in 0..63) {
            val window = Window(input(before, shift = false))
            val committed = input(before)
            window.early(committed)
            assertEquals(committed, window.naturalTick(input(after)))
            val newest = input(after xor 63)
            assertEquals(newest, window.naturalTick(newest), "Stale movement must not be replayed: $before/$after")
            assertEquals(newest, window.sent.input)
            assertEquals(2, window.tickEnds)
            val expected = mutableListOf("input", "movement", "TickEnd")
            if (newest != committed) expected += "input"
            expected += listOf("movement", "TickEnd")
            assertEquals(expected, window.wire)
        }
    }

    @Test fun aFurtherMovementChangeIsNotDelayedBehindAnUnsentReleaseSnapshot() {
        val window = Window(input(1, shift = false)) // W held.
        window.early(input(1))
        assertEquals(input(1), window.naturalTick(input(0)), "First W release must still honor the sent W input")
        // W was released during the commitment, then S pressed before the next sample.
        assertEquals(input(2), window.naturalTick(input(2)))
        assertEquals(input(0), window.naturalTick(input(0)))
    }

    @Test fun briefSneakTapStillGetsOneCommittedSampleThenAFreshEarlyRelease() {
        val bindings = SneakBindingTransitions()
        val window = Window()
        bindings.observe(false); bindings.observe(true)
        assertEquals(true, bindings.pending)
        window.early(input(0)); bindings.consumed()
        bindings.observe(false)
        assertEquals(false, bindings.pending)
        assertFalse(window.sampled.shift(), "Early send must not change previous-tick physics input")
        assertTrue(window.click().shift(), "Same-frame native chest use must see secondary action")
        assertTrue(window.naturalTick(Input.EMPTY).shift())
        // No queued old movement: release uses today's backward/jump/sprint bindings.
        val release = input(2 or 16 or 32, shift = false)
        window.early(release); bindings.consumed()
        assertEquals(release, window.click())
        assertEquals(release, window.naturalTick(release))
    }

    @Test fun rapidRepressCoalescesUnsentEdgesInsteadOfReplayingAnObsoleteRelease() {
        val bindings = SneakBindingTransitions()
        val window = Window()
        bindings.observe(false); bindings.observe(true)
        window.early(input(0)); bindings.consumed()
        bindings.observe(false)
        window.naturalTick(Input.EMPTY)
        bindings.observe(true); bindings.observe(false); bindings.observe(true)
        assertEquals(true, bindings.pending)
        assertEquals(window.sampled.shift(), bindings.pending)
        bindings.consumed() // Current frame already matches the committed shift.
        assertEquals(input(4), window.naturalTick(input(4)))
        assertNull(bindings.pending)
        assertFalse(window.wire.contains("use"))
    }

    @Test fun sameFrameAndPostTickClicksPreserveDeferredUsePrecedence() {
        val window = Window()
        val early = input(1)
        window.early(early)
        val previousPhysics = window.sampled
        assertEquals(early, window.click())
        assertEquals(previousPhysics, window.sampled, "Click scope must not replace previous physics input")
        val replay = input(8, shift = false)
        assertEquals(replay, window.click(replay), "Existing deferred-use input takes precedence over NSD")
        window.naturalTick(Input.EMPTY)
        assertEquals(early, window.click(), "After one tick, native sampled input has the committed sneak")
        window.naturalTick(Input.EMPTY)
        assertEquals(Input.EMPTY, window.click())
    }

    @Test fun identicalInputIsSuppressedButChangedInputUsesTheNextNaturalSend() {
        val window = Window()
        window.early(input(0))
        window.naturalTick(input(0))
        assertEquals(listOf("input", "movement", "TickEnd"), window.wire)
        window.naturalTick(input(0)) // No native change; no redundant input.
        assertEquals(1, window.wire.count { it == "input" })
        window.naturalTick(input(16 or 32))
        assertEquals(2, window.wire.count { it == "input" })
        assertEquals(input(16 or 32), window.sent.input)
    }

    @Test fun guiNeutralSampleCannotLeaveADeferredSnapshotToReplayAfterClosing() {
        val window = Window(input(1, shift = false))
        window.early(input(1))
        window.naturalTick(Input.EMPTY) // GUI interruption cannot unsend this commitment.
        assertEquals(Input.EMPTY, window.naturalTick(Input.EMPTY))
        assertEquals(input(8, shift = false), window.naturalTick(input(8, shift = false)))
        assertNull(window.latch.value)
    }

    @Test fun ownershipWaitsForSentCommitmentButNotForAnUnsentDeferredSnapshot() {
        val window = Window()
        val ownership = PredictionOwnership().apply { bind(Any(), Any(), Any()) }
        val owner = Any()
        fun acquire() = ownership.tryAcquire(owner, true,
            PredictionOwnership.DrainState(inputCommitted = window.latch.value != null))
        window.early(input(0))
        assertFalse(acquire())
        window.naturalTick(input(16))
        assertTrue(acquire())
        assertNull(window.latch.value)
        assertTrue(ownership.release(owner, true))
        assertEquals(Input.EMPTY, window.naturalTick(Input.EMPTY))
    }
}
