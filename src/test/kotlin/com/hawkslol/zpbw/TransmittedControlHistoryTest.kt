package com.hawkslol.zpbw

import net.minecraft.network.protocol.game.ServerboundPlayerCommandPacket.Action
import net.minecraft.network.protocol.game.ServerboundPlayerInputPacket
import net.minecraft.world.entity.player.Input
import kotlin.test.*

class TransmittedControlHistoryTest {
    private val sneak = Input(false, false, false, false, false, true, false)
    private val forwardJump = Input(true, false, false, false, true, false, true)

    @Test fun abandonedInputDoesNotBecomeTheSenderBaselineAndNextLiveSampleCanSend() {
        val history = TransmittedControlHistory()
        history.sent(ServerboundPlayerInputPacket(sneak))
        val abandoned = listOf(ServerboundPlayerInputPacket(forwardJump), ServerboundPlayerInputPacket(Input.EMPTY))
        abandoned.filterNot(RecoveryPacketPolicy::discard).forEach(history::sent)
        assertEquals(sneak, history.input)
        assertNotEquals(forwardJump, history.input) // Vanilla's equality check must request the new input.
        history.sent(ServerboundPlayerInputPacket(forwardJump))
        assertEquals(forwardJump, history.input)
    }

    @Test fun sentEarlySneakCommitmentSurvivesCancellationWithoutAnExtraInput() {
        val history = TransmittedControlHistory()
        val latch = TickInputLatch<Input>()
        assertTrue(latch.commit(sneak))
        history.sent(ServerboundPlayerInputPacket(sneak))
        val restoredCache = history.input
        assertEquals(latch.value, restoredCache)
        assertEquals(sneak, latch.sample(forwardJump))
        assertEquals(restoredCache, latch.value) // Identical native input remains suppressed for this tick.
        latch.finishTick()
        assertEquals(forwardJump, latch.commitDeferred())
        assertNotEquals(restoredCache, latch.value)
    }

    @Test fun sprintCacheReflectsOnlyTransmittedTransitionsAndUnrelatedCommandsDoNotChangeIt() {
        val history = TransmittedControlHistory()
        history.sentCommand(Action.START_SPRINTING)
        for (action in listOf(Action.STOP_SPRINTING, Action.START_SPRINTING, Action.STOP_SPRINTING))
            if (!RecoveryPacketPolicy.sprintTransition(action)) history.sentCommand(action)
        assertTrue(history.sprinting)
        history.sentCommand(Action.OPEN_INVENTORY)
        assertTrue(history.sprinting)
        history.sentCommand(Action.STOP_SPRINTING)
        assertFalse(history.sprinting)
    }

    @Test fun newSessionHasNoPriorInputOrSprintCommitment() {
        val history = TransmittedControlHistory()
        history.sent(ServerboundPlayerInputPacket(sneak)); history.sentCommand(Action.START_SPRINTING)
        history.reset()
        assertEquals(Input.EMPTY, history.input)
        assertFalse(history.sprinting)
    }
}
