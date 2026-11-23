package com.hawkslol.zpbw

import com.hawkslol.zpbw.gui.*
import com.hawkslol.zpbw.nativebridge.NativeTeleportContract
import java.nio.file.Files
import java.nio.file.AtomicMoveNotSupportedException
import kotlin.test.*

class ExperimentalRepairsTest {
    private val ms = 1_000_000L
    @Test fun normalThreeHundredMillisecondRepliesRemainEligibleUnderJitter() {
        val c=Any(); val g=ExperimentalStallGuard<Any>(); g.bind(c)
        g.ping(c,1,0); val stamp=g.stamp(c,0)!!
        for (t in listOf(70,200,300)) { g.ping(c,1,t*ms); assertFalse(g.stale(c,stamp,t*ms)) }
        assertFalse(g.blocksAdmission(c,330*ms))
    }
    @Test fun heldWorkHasASeparateBoundEvenWhenPingsContinueOrNeverExist() {
        for (pings in listOf(false,true)) {
            val c=Any();val g=ExperimentalStallGuard<Any>();g.bind(c);val stamp=g.stamp(c,0)!!
            if(pings) for(t in 0..700 step 100)g.ping(c,1,t*ms)
            assertFalse(g.stale(c,stamp,749*ms));assertTrue(g.stale(c,stamp,750*ms))
        }
    }
    @Test fun aReturnedPingBurstCannotEraseAnIssuedOutage() {
        val c=Any();val g=ExperimentalStallGuard<Any>();g.bind(c);g.ping(c,1,0)
        val old=g.stamp(c,100*ms)!!
        repeat(50){g.ping(c,it+1,600*ms)}
        assertTrue(g.blocksAdmission(c,600*ms));assertTrue(g.stale(c,old,600*ms))
        g.ping(c,1,700*ms);g.ping(c,1,800*ms)
        assertFalse(g.blocksAdmission(c,800*ms))
        assertTrue(g.stale(c,old,800*ms))
        assertFalse(g.stale(c,g.stamp(c,800*ms)!!,810*ms))
    }
    @Test fun naturalObservationLatchesGapBeforeAnyNewPingArrives() {
        val c=Any();val g=ExperimentalStallGuard<Any>();g.bind(c);g.ping(c,9,0)
        val old=g.stamp(c,100*ms)!!
        assertFalse(g.blocksAdmission(c,499*ms));assertTrue(g.blocksAdmission(c,500*ms))
        assertTrue(g.stale(c,old,500*ms))
    }
    @Test fun zeroAndForeignPingsCannotConcealAnOutage() {
        val c=Any();val g=ExperimentalStallGuard<Any>();g.bind(c);g.ping(c,1,0)
        g.ping(c,0,490*ms);g.ping(Any(),1,490*ms)
        assertTrue(g.blocksAdmission(c,500*ms))
    }
    @Test fun worldRebindAndLateDisconnectCannotContaminateSuccessor() {
        val a=Any();val b=Any();val g=ExperimentalStallGuard<Any>();g.bind(a)
        val old=g.stamp(a,0)!!;g.bind(a) // Same connection, new world identity.
        assertFalse(g.stale(a,old,900*ms))
        g.bind(b);g.ping(b,1,900*ms);g.unbind(a)
        assertFalse(g.blocksAdmission(b,901*ms));assertNull(g.stamp(a,901*ms))
        g.unbind(b);assertNull(g.stamp(b,902*ms))
    }
    @Test fun healthyObservationMustSpanTimeAndSurviveAnotherGap() {
        val c=Any();val g=ExperimentalStallGuard<Any>();g.bind(c);g.ping(c,1,0)
        g.ping(c,1,600*ms);g.ping(c,1,1200*ms)
        assertTrue(g.blocksAdmission(c,1200*ms))
        g.ping(c,1,1300*ms);assertTrue(g.blocksAdmission(c,1300*ms))
        g.ping(c,1,1400*ms);assertFalse(g.blocksAdmission(c,1400*ms))
    }
    @Test fun fiveIssuedGenerationsRemainFifoWhileObsoleteActionsNeverExecute() {
        val ledger=ChainLedger<String,Runnable>(responseContract=NativeTeleportContract.sequence)
        var executed=0
        for(i in 1L..5L){ledger.add(i,"warp$i");ledger.cut(i)}
        ledger.retain(Runnable{executed++},action=true)
        val discarded=ledger.beginNativeFallback()
        assertEquals(1,discarded.size);assertEquals(0,executed);assertEquals(5,ledger.count)
        ledger.configureMaximum(1)
        for(i in 1L..5L) {
            assertEquals(i,ledger.head!!.generation)
            ledger.genuine(i,100+i.toInt(),nativeRecovery=true)
            for(step in NativeTeleportContract.sequence) ledger.response(100+i.toInt(),step,nativeRecovery=true)
            assertTrue(ledger.settle(i,100+i.toInt(),nativeRecovery=true).isEmpty())
            assertEquals(5-i.toInt(),ledger.count);assertFalse(ledger.canAdd)
        }
        assertEquals(0,executed)
    }
    @Test fun recoveryRequiresOriginalSourceCutAndExplicitResponseScope() {
        val l=ChainLedger<String,Any>(responseContract=NativeTeleportContract.sequence)
        l.add(1,"warp");assertFailsWith<IllegalStateException>{l.beginNativeFallback()}
        assertFailsWith<IllegalStateException>{l.genuine(1,42,nativeRecovery=true)}
        l.cut(1);l.beginNativeFallback()
        assertFailsWith<IllegalStateException>{l.genuine(1,42)}
        l.genuine(1,42,nativeRecovery=true)
        assertFailsWith<IllegalStateException>{l.settle(1,42,nativeRecovery=true)}
        for(step in NativeTeleportContract.sequence)l.response(42,step,nativeRecovery=true)
        assertTrue(l.settle(1,42,nativeRecovery=true).isEmpty())
    }
    @Test fun outageRecoveryPreservesAlreadyReceivedNativeArrivalBarriers() {
        val c=Any();val clock=ServerTickTimeouts<Any>();clock.bind(c)
        for(i in 1L..2L)assertTrue(clock.start(c,i,20))
        clock.teleport(c)
        assertTrue(clock.restartForRecovery(c,listOf(1,2),20))
        repeat(20){clock.completedClientTick(c);clock.ping(c,1)}
        val ticket=clock.expiredTickets().single();assertEquals(2,ticket.generation)
        assertFalse(clock.claim(ticket));clock.cancel(1);assertTrue(clock.claim(ticket))
    }
    @Test fun lagCompensationDefaultsOnAndPreservesExplicitOffAndUnknownSettings() {
        val dir=Files.createTempDirectory("zpbw-repairs")
        Files.writeString(dir.resolve("config.json"),"""{"enabled":true,"chainMax":5,"future":42}""")
        val store=ZpbwSettingsStore(dir);val original=store.load()
        assertTrue(original.experimentalRepairs)
        assertTrue(ZpbwSettings().experimentalRepairs)
        assertTrue(ClickGuiValues(false,false,5,20).experimentalRepairs)
        assertTrue(ZpbwSettingsStore(Files.createTempDirectory("zpbw-fresh-lag")).load().experimentalRepairs)
        store.saveStrict(original.copy(experimentalRepairs=false))
        assertFalse(store.load().experimentalRepairs)
        assertTrue(Files.readString(store.path).contains("\"future\": 42"))
    }
    @Test fun failedAtomicTogglePreservesPriorDiskValue() {
        val dir=Files.createTempDirectory("zpbw-repairs-save");val store=ZpbwSettingsStore(dir)
        store.saveStrict(ZpbwSettings(experimentalRepairs=false));val before=Files.readAllBytes(store.path)
        val failing=ZpbwSettingsStore(dir){a,b->throw AtomicMoveNotSupportedException(a.toString(),b.toString(),"test")}
        assertFailsWith<AtomicMoveNotSupportedException>{failing.saveStrict(ZpbwSettings(experimentalRepairs=true))}
        assertContentEquals(before,Files.readAllBytes(store.path));assertFalse(store.load().experimentalRepairs)
    }
    @Test fun switchIsReachableInGeneralAndDoesNotOverlapProjectLinks() {
        val values=ClickGuiValues(false,false,5,20)
        val b=GuiAction.EXPERIMENTAL_REPAIRS.bounds
        assertEquals(GuiAction.EXPERIMENTAL_REPAIRS,actionAt(GuiPage.GENERAL,values,b.x+2.0,b.y+2.0))
        assertNotEquals(GuiAction.EXPERIMENTAL_REPAIRS,actionAt(GuiPage.BLINKWARP,values,b.x+2.0,b.y+2.0))
        assertTrue(b.y+b.height<GuiAction.GITHUB.bounds.y)
    }
    @Test fun diagnosticSnapshotExplainsTriggerWithoutChangingTimingOrExposingIdentity() {
        val c=Any();val g=ExperimentalStallGuard<Any>();g.bind(c);g.ping(c,7,0)
        val stamp=g.stamp(c,0)!!
        assertContains(g.diagnostics(c,600*ms,stamp),"reason=healthy") // Snapshot cannot latch a gap itself.
        assertTrue(g.stale(c,stamp,600*ms))
        val gap=g.diagnostics(c,600*ms,stamp)
        assertContains(gap,"reason=issued_outage");assertContains(gap,"pingGapUs=600000")
        assertContains(gap,"holdAgeUs=600000");assertContains(gap,"pingGapLimitMs=500 holdLimitMs=750 healthySpanMs=200")
        g.ping(c,7,600*ms);g.ping(c,8,800*ms)
        assertContains(g.diagnostics(c,800*ms,stamp),"reason=issued_outage")
        val fresh=g.stamp(c,800*ms)!!
        for(t in 900..1500 step 100)g.ping(c,9,t*ms)
        assertTrue(g.stale(c,fresh,1550*ms))
        assertContains(g.diagnostics(c,1550*ms,fresh),"reason=hold_limit")
        assertContains(g.diagnostics(Any(),1550*ms,fresh),"reason=unbound pingGapUs=unavailable")
        g.bind(c);assertContains(g.diagnostics(c,1550*ms,fresh),"reason=old_session")
    }
}
