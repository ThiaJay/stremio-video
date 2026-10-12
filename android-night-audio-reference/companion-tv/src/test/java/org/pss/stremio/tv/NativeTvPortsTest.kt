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
        assertTrue(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertEquals(1,account.sent)
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertEquals(1,account.sent)
    }
}
