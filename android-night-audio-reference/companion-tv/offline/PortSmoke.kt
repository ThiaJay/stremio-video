package org.pss.stremio.tv

import java.time.Instant

private var total = 0
private fun ok(value:Boolean,label:String){total++;check(value){label}}

fun main(){
    val base=TvPlaybackState(1,"e1",1500,12000,true)
    class FakePlayer(var current:TvPlaybackState=base):NativeSeekPort {
        var seek:Long?=null
        var accept=true
        override fun snapshot()=current
        override fun seekWithinSelectedVideo(ms:Long):Boolean {if(!accept)return false;seek=ms;return true}
    }
    class FakeCore:CoreMessagePort {
        var calls=0
        var success=true
        var hook:(()->Unit)?=null
        override fun dispatch(message:Map<String,Any>):Boolean {calls++;hook?.invoke();return success}
    }
    val skip=CoreSkipSegment(SkipKind.Intro,1,"e1",1000,3200,12000,SkipMode.Ask,true,false,3200)
    val player=FakePlayer();val core=FakeCore();val coordinator=TvSkipCoordinator()
    val actuator=NativeTvSkipActuator(coordinator,core,player)
    val focus=coordinator.presentation(skip,player.snapshot())!!.focusedIdentity
    ok(actuator.manual(skip,focus,"DPAD_DOWN")==NativeSkipOutcome.NO_ACTION,"navigation")
    ok(actuator.manual(skip,null,"ENTER")==NativeSkipOutcome.NO_ACTION,"missing focus")
    ok(actuator.manual(skip,focus,"DPAD_CENTER")==NativeSkipOutcome.APPLIED,"same media seek")
    ok(player.seek==3200L,"ms intact")
    ok(core.calls==1,"one Core dispatch")
    ok(actuator.manual(skip,focus,"DPAD_CENTER")==NativeSkipOutcome.NO_ACTION,"no repeat")

    val player2=FakePlayer();val core2=FakeCore();val c2=TvSkipCoordinator()
    val focus2=c2.presentation(skip,player2.snapshot())!!.focusedIdentity
    core2.hook={player2.current=base.copy(videoId="e2")}
    ok(NativeTvSkipActuator(c2,core2,player2).manual(skip,focus2,"ENTER")==NativeSkipOutcome.PLAYBACK_CHANGED,"switch after Core")
    ok(player2.seek==null,"never seek new item")

    val account=object:StremioAccountPort {
        var s:SeriesAccountSnapshot?=null
        var sent=0
        override fun currentSnapshot()=s
        override fun dispatch(message:Map<String,Any>):Boolean {sent++;return true}
    }
    val watched=NativeTvWatchedActuator(TvWatchedCoordinator(),account)
    ok(watched.prepare(WatchedScope.Series,true)==null,"no account - no plan")
    val snapshot=SeriesAccountSnapshot("a".repeat(64),"b".repeat(64),"tt100","tt100",true,true,
        listOf(CanonicalEpisode("tt100:1:1",1,1,"2025-01-01T00:00:00Z")),null,null,Instant.parse("2026-10-12T18:00:00Z"))
    account.s=snapshot
    val plan=watched.prepare(WatchedScope.Series,true)!!
    account.s=snapshot.copy(libraryItemFingerprint="c".repeat(64))
    ok(!watched.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)),"changed history")
    ok(account.sent==0,"no account write when changed")
    account.s=snapshot
    ok(watched.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)),"ready fake Core")
    ok(!watched.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)),"no duplicate watched")
    ok(account.sent==1,"exactly one fake dispatch")
    println("PASS: $total Android TV host adapter offline safety checks")
}
