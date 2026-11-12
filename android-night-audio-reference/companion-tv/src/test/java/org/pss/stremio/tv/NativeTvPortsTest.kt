package org.pss.stremio.tv

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NativeTvPortsTest {
    @Test fun retainedBridgeAllFiftyThreeAssertions() { main() }

    private val state = TvPlaybackState(7,"episode",2500,12000,true)
    private val segment = CoreSkipSegment(SkipKind.Intro,7,"episode",1000,3000,12000,SkipMode.Ask,true,false,3000)

    private class Player(var state: TvPlaybackState):NativeSeekPort {
        var target:Long? = null
        var accepts = true
        override fun snapshot()=state
        override fun seekWithinSelectedVideo(ms:Long):Boolean { if(!accepts)return false;target=ms;return true }
    }
    private class Core:CoreMessagePort {
        var calls=0
        var accepts=true
        var changed: (() -> Unit)?=null
        override fun dispatch(message:Map<String,Any>):Boolean {calls++;changed?.invoke();return accepts}
    }
    private class Account(var state:SeriesAccountSnapshot?):StremioAccountPort {
        var sent=0
        var snapshotReads=0
        var beforeSnapshotRead:((Int)->Unit)?=null
        var snapshotFailure:Throwable?=null
        var dispatchFailure:Throwable?=null
        override fun currentSnapshot():SeriesAccountSnapshot? {
            snapshotReads++
            beforeSnapshotRead?.invoke(snapshotReads)
            snapshotFailure?.let { throw it }
            return state
        }
        override fun dispatch(message:Map<String,Any>):Boolean {
            sent++
            dispatchFailure?.let { throw it }
            return true
        }
    }
    private fun accountState()=SeriesAccountSnapshot(
        "a".repeat(64),"b".repeat(64),"series","series",true,true,
        listOf(CanonicalEpisode("s1e1",1,1,"2025-01-01T00:00:00Z")),
        null,null,Instant.parse("2026-10-12T16:00:00Z"))
    @Test fun sameMediaCoreFirstThenSeek() {
        val player=Player(state); val core=Core()
        val coord=TvSkipCoordinator();val actuator=NativeTvSkipActuator(coord,core,player)
        val focus=coord.presentation(segment,state)!!.focusedIdentity
        assertEquals(NativeSkipOutcome.APPLIED,actuator.manual(segment,focus,"DPAD_CENTER"))
        assertEquals(1,core.calls);assertEquals(3000L,player.target)
        assertEquals(NativeSkipOutcome.NO_ACTION,actuator.manual(segment,focus,"DPAD_CENTER"))
    }
    @Test fun refusesStaleUnfocusedAndCoreFailure() {
        val player=Player(state);val core=Core();val c=TvSkipCoordinator()
        val a=NativeTvSkipActuator(c,core,player)
        val focus=c.presentation(segment,state)!!.focusedIdentity
        assertEquals(NativeSkipOutcome.NO_ACTION,a.manual(segment,null,"ENTER"))
        assertEquals(NativeSkipOutcome.NO_ACTION,a.manual(segment,focus,"DPAD_DOWN"))
        core.accepts=false
        assertEquals(NativeSkipOutcome.CORE_REJECTED,a.manual(segment,focus,"ENTER"))
        assertNull(player.target)
    }
    @Test fun reducerSourceSwitchCannotSeekTheWrongEpisode() {
        val player=Player(state);val core=Core();val c=TvSkipCoordinator()
        val a=NativeTvSkipActuator(c,core,player)
        val focus=c.presentation(segment,state)!!.focusedIdentity
        core.changed={player.state=state.copy(videoId="another")}
        assertEquals(NativeSkipOutcome.PLAYBACK_CHANGED,a.manual(segment,focus,"ENTER"))
        assertNull(player.target)
    }
    @Test fun noCoreAccountMeansNoWatchedActions() {
        val account=object:StremioAccountPort {
            var calls=0
            override fun currentSnapshot():SeriesAccountSnapshot?=null
            override fun dispatch(message: Map<String,Any>):Boolean {calls++;return true}
        }
        val a=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
        assertNull(a.prepare(WatchedScope.Series,true))
    }

    @Test fun titleActuatorSnapshotsHostCatalogueExactlyOnce() {
        val canonical=CanonicalEpisode("s1e1",1,1,"2025-01-01T00:00:00Z")
        val replacement=CanonicalEpisode("s1e2",1,2,"2025-01-08T00:00:00Z")
        var iteratorReads=0
        val drifting=object:AbstractList<CanonicalEpisode>() {
            override val size:Int=1
            override fun get(index:Int)=if(index==0) canonical else throw IndexOutOfBoundsException()
            override fun iterator():Iterator<CanonicalEpisode> {
                iteratorReads++
                return listOf(if(iteratorReads==1) canonical else replacement).iterator()
            }
        }
        val raw=accountState().copy(videos=drifting)
        val stablePlan=TvWatchedCoordinator().prepare(
            raw.copy(videos=listOf(canonical)),WatchedScope.Series,true
        )
        val plan=NativeTvWatchedActuator(TvWatchedCoordinator(),Account(raw))
            .prepare(WatchedScope.Series,true)

        assertEquals(stablePlan,plan)
        assertEquals(1,iteratorReads)
    }

    @Test fun titleActuatorRejectsDeclaredCatalogueCardinalityMismatch() {
        val first=CanonicalEpisode("s1e1",1,1,"2025-01-01T00:00:00Z")
        val second=CanonicalEpisode("s1e2",1,2,"2025-01-08T00:00:00Z")
        val mismatched=object:AbstractList<CanonicalEpisode>() {
            override val size:Int=1
            override fun get(index:Int)=if(index==0) first else throw IndexOutOfBoundsException()
            override fun iterator()=listOf(first,second).iterator()
        }
        val account=Account(accountState().copy(videos=mismatched))

        assertNull(NativeTvWatchedActuator(TvWatchedCoordinator(),account)
            .prepare(WatchedScope.Series,true))
        assertEquals(0,account.sent)
    }
    @Test fun currentCoreAccountRequiresFreshConfirmation() {
        val base=SeriesAccountSnapshot("a".repeat(64),"b".repeat(64),"series","series",true,true,
            listOf(CanonicalEpisode("s1e1",1,1,"2025-01-01T00:00:00Z")),null,null,Instant.parse("2026-10-12T16:00:00Z"))
        val account=object:StremioAccountPort {
            var s=base;var sent=0
            override fun currentSnapshot()=s
            override fun dispatch(message:Map<String,Any>):Boolean {sent++;return true}
        }
        val a=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
        val plan=a.prepare(WatchedScope.Series,true)!!
        account.s=base.copy(libraryItemFingerprint="c".repeat(64))
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertEquals(0,account.sent)
        account.s=base
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        val freshActuator=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
        val freshPlan=freshActuator.prepare(WatchedScope.Series,true)!!
        assertTrue(freshActuator.confirm(freshPlan,
            TvWatchedConfirmation(true,"ENTER",freshPlan.fingerprint)))
        assertEquals(1,account.sent)
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertEquals(1,account.sent)
    }

    @Test fun copiedOrForeignTitlePlanCannotDispatch() {
        val account=Account(accountState())
        val owner=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
        val foreign=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
        val plan=owner.prepare(WatchedScope.Series,true)!!
        val input=TvWatchedConfirmation(true,"ENTER",plan.fingerprint)

        assertFalse(owner.confirm(plan.copy(),input))
        assertFalse(foreign.confirm(plan,input))
        assertEquals(0,account.sent)
        assertTrue(owner.confirm(plan,input))
        assertEquals(1,account.sent)
    }

    @Test fun duplicateTitlePreparationAndFailedRefreshAreConsumed() {
        val account=Account(accountState())
        val actuator=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
        val plan=actuator.prepare(WatchedScope.Series,true)!!
        val input=TvWatchedConfirmation(true,"ENTER",plan.fingerprint)

        assertNull(actuator.prepare(WatchedScope.Series,true))
        account.snapshotFailure=IllegalStateException("account unavailable")
        assertFalse(actuator.confirm(plan,input))
        account.snapshotFailure=null
        assertFalse(actuator.confirm(plan,input))
        assertNull(actuator.prepare(WatchedScope.Series,true))
        assertEquals(0,account.sent)
    }

    @Test fun titleAccountPortFailuresFailClosedAndDispatchAttemptIsOneShot() {
        val unavailable=Account(accountState()).apply {
            snapshotFailure=LinkageError("account bridge unavailable")
        }
        assertNull(NativeTvWatchedActuator(TvWatchedCoordinator(),unavailable)
            .prepare(WatchedScope.Series,true))

        val account=Account(accountState()).apply {
            dispatchFailure=IllegalStateException("dispatch unavailable")
        }
        val actuator=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
        val plan=actuator.prepare(WatchedScope.Series,true)!!
        val input=TvWatchedConfirmation(true,"ENTER",plan.fingerprint)
        assertFalse(actuator.confirm(plan,input))
        assertFalse(actuator.confirm(plan,input))
        assertEquals(1,account.sent)
    }

    @Test fun titleFinalPreDispatchSnapshotDriftFailsClosed() {
        val base=accountState()
        val account=Account(base)
        val actuator=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
        val plan=actuator.prepare(WatchedScope.Series,true)!!
        val input=TvWatchedConfirmation(true,"ENTER",plan.fingerprint)
        account.beforeSnapshotRead={ read ->
            if(read==3) account.state=base.copy(libraryItemFingerprint="c".repeat(64))
        }

        assertFalse(actuator.confirm(plan,input))
        assertFalse(actuator.confirm(plan,input))
        assertEquals(0,account.sent)
        assertEquals(3,account.snapshotReads)
    }

    @Test fun titleFinalPreDispatchSnapshotFailureFailsClosed() {
        val account=Account(accountState())
        val actuator=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
        val plan=actuator.prepare(WatchedScope.Season(1),true)!!
        val input=TvWatchedConfirmation(true,"DPAD_CENTER",plan.fingerprint)
        account.beforeSnapshotRead={ read ->
            if(read==3) throw LinkageError("final account snapshot unavailable")
        }

        assertFalse(actuator.confirm(plan,input))
        account.beforeSnapshotRead=null
        assertFalse(actuator.confirm(plan,input))
        assertEquals(0,account.sent)
        assertEquals(3,account.snapshotReads)
    }

    @Test fun titleActuatorOwnerThreadAndCloseRevokeAccess() {
        val account=Account(accountState())
        val actuator=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
        val plan=actuator.prepare(WatchedScope.Series,true)!!
        val input=TvWatchedConfirmation(true,"ENTER",plan.fingerprint)
        var foreignPlan:WatchedPlan?=plan
        var foreignConfirm=true
        var foreignClose=true

        Thread {
            foreignPlan=actuator.prepare(WatchedScope.Series,false)
            foreignConfirm=actuator.confirm(plan,input)
            foreignClose=actuator.close()
        }.apply { start();join() }

        assertNull(foreignPlan)
        assertFalse(foreignConfirm)
        assertFalse(foreignClose)
        assertEquals(0,account.sent)
        assertTrue(actuator.close())
        assertFalse(actuator.confirm(plan,input))
        assertNull(actuator.prepare(WatchedScope.Series,true))
        assertFalse(actuator.close())
        assertEquals(0,account.sent)
    }

    @Test fun finalSeekLeaseRejectsMediaSwitchAfterCoreAcknowledgement() {
        var current=state
        var reads=0
        var nativeSeekCalls=0
        val port=SnapshotBoundNativeSeekPort(
            observe={
                reads++
                if(reads==4) current=current.copy(videoId="another")
                current
            },
            applySeek={ nativeSeekCalls++;true }
        )
        val c=TvSkipCoordinator()
        val a=NativeTvSkipActuator(c,Core(),port)
        val focus=c.presentation(segment,state)!!.focusedIdentity
        assertEquals(NativeSkipOutcome.SEEK_FAILED,a.manual(segment,focus,"ENTER"))
        assertEquals(0,nativeSeekCalls)
    }

    @Test fun snapshotBoundSeekAppliesExactlyOnceForTheSelectedMedia() {
        var current=state
        val sent=mutableListOf<Long>()
        val port=SnapshotBoundNativeSeekPort(observe={current},applySeek={sent.add(it);true})
        val c=TvSkipCoordinator()
        val a=NativeTvSkipActuator(c,Core(),port)
        val focus=c.presentation(segment,state)!!.focusedIdentity
        assertEquals(NativeSkipOutcome.APPLIED,a.manual(segment,focus,"ENTER"))
        assertEquals(listOf(3000L),sent)
        assertFalse(port.seekWithinSelectedVideo(4000L))
        assertEquals(listOf(3000L),sent)
    }

    @Test fun latePlaybackOrNextEpisodePopupCannotSeekBackwards() {
        var current=state
        var reads=0
        var nativeSeekCalls=0
        val port=SnapshotBoundNativeSeekPort(
            observe={
                reads++
                if(reads==4) current=current.copy(positionMs=4000)
                current
            },
            applySeek={ nativeSeekCalls++;true }
        )
        val c=TvSkipCoordinator()
        val focus=c.presentation(segment,state)!!.focusedIdentity
        assertEquals(NativeSkipOutcome.SEEK_FAILED,NativeTvSkipActuator(c,Core(),port)
            .manual(segment,focus,"ENTER"))
        assertEquals(0,nativeSeekCalls)

        // A direct final gate also rejects Next Episode overlay and an invalid target.
        val port2=SnapshotBoundNativeSeekPort(observe={current.copy(nextVideoPopupOpen=true)},
            applySeek={nativeSeekCalls++;true})
        port2.snapshot()
        assertFalse(port2.seekWithinSelectedVideo(5000L))
        assertEquals(0,nativeSeekCalls)
    }
}
