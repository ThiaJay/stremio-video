package org.pss.stremio.tv

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class NativeEpisodeWatchedActionTest {
    private val base = SeriesAccountSnapshot(
        "a".repeat(64),"b".repeat(64),"tt18","tt18",true,true,
        listOf(CanonicalEpisode("tt18:1:1",1,1,"2025-02-01T00:00:00Z"),
            CanonicalEpisode("tt18:1:2",1,2,"2035-02-01T00:00:00Z")),
        null,null,Instant.parse("2026-10-12T18:00:00Z")
    )
    private class FakeAccount(var state: SeriesAccountSnapshot):CoreEpisodeAccountPort {
        var video:Map<String,Any?> = mapOf("id" to "tt18:1:1","season" to 1,"episode" to 1,"title" to "One")
        var writes=0
        var lastAction:Map<String,Any>?=null
        var snapshotReadCount=0
        var videoReadCount=0
        var beforeVideoRead: ((Int) -> Unit)?=null
        var duringDispatch: (() -> Unit)?=null
        var snapshotFailure:Throwable?=null
        var snapshotFailureOnRead:Int?=null
        var videoFailureOnRead:Int?=null
        var dispatchFailure:Throwable?=null
        override fun currentSnapshot():SeriesAccountSnapshot {
            snapshotReadCount++
            if(snapshotFailureOnRead==snapshotReadCount) throw LinkageError("account snapshot unavailable")
            snapshotFailure?.let { throw it }
            return state
        }
        override fun canonicalVideoFromReadyMetaDetails(id:String):Map<String,Any?>? {
            videoReadCount++
            if(videoFailureOnRead==videoReadCount) throw LinkageError("canonical video unavailable")
            beforeVideoRead?.invoke(videoReadCount)
            return video.takeIf { it["id"]==id }
        }
        override fun dispatch(message:Map<String,Any>):Boolean {
            writes++
            lastAction=message
            duringDispatch?.invoke()
            dispatchFailure?.let { throw it }
            return true
        }
    }
    @Test fun individualEpisodeRequiresRealCanonicalSourceAndRemoteConfirmation() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"DPAD_DOWN",plan.fingerprint)))
        assertFalse(a.confirm(plan,TvWatchedConfirmation(false,"ENTER",plan.fingerprint)))
        assertEquals(0,p.writes)
        assertTrue(a.confirm(plan,TvWatchedConfirmation(true,"DPAD_CENTER",plan.fingerprint)))
        assertEquals(1,p.writes)
        val inner=p.lastAction!!["args"] as Map<*,*>
        assertEquals("MetaDetails",p.lastAction!!["action"])
        assertEquals("MarkVideoAsWatched",inner["action"])
        val args=inner["args"] as List<*>
        assertEquals(p.video,args[0]);assertEquals(true,args[1])
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertEquals(1,p.writes)
    }
    @Test fun futureAndUntrustedVideosFailClosed() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        assertNull(a.prepare("tt18:1:2",true))
        assertNull(a.prepare("tt18:1:2",false))
        assertNull(a.prepare("unknown",true))
        p.video=mapOf("id" to "tt18:1:1","season" to 2)
        assertNull(a.prepare("tt18:1:1",true))
        p.video=mapOf("id" to "tt18:1:1","episode" to 9)
        assertNull(a.prepare("tt18:1:1",true))
        assertEquals(0,p.writes)
    }
    @Test fun hostSnapshotCannotAdvanceIndividualEpisodeReleaseClock() {
        val runtimeNow=Instant.now()
        val futureId="tt18:1:3"
        val p=FakeAccount(base.copy(
            videos=base.videos+CanonicalEpisode(
                futureId,1,3,runtimeNow.plusSeconds(1_800).toString()),
            now=runtimeNow.plusSeconds(3_600)))
        p.video=mapOf("id" to futureId,"season" to 1,"episode" to 3,"title" to "Future")
        val a=NativeTvEpisodeWatchedActuator(p)

        assertNull(a.prepare(futureId,true))
        assertNull(a.prepare(futureId,false))
        assertEquals(0,p.writes)
    }
    @Test fun hostSnapshotAndInitialVideoFailuresFailClosedWithoutEscaping() {
        val snapshotFailure=FakeAccount(base)
        snapshotFailure.snapshotFailure=IllegalStateException("account session unavailable")
        assertNull(NativeTvEpisodeWatchedActuator(snapshotFailure).prepare("tt18:1:1",true))

        val videoFailure=FakeAccount(base)
        videoFailure.videoFailureOnRead=1
        assertNull(NativeTvEpisodeWatchedActuator(videoFailure).prepare("tt18:1:1",true))
        assertEquals(0,snapshotFailure.writes+videoFailure.writes)
    }
    @Test fun refreshedAndFinalVideoFailuresFailClosedWithoutDispatch() {
        val refreshFailure=FakeAccount(base)
        val refreshActuator=NativeTvEpisodeWatchedActuator(refreshFailure)
        val refreshPlan=refreshActuator.prepare("tt18:1:1",true)!!
        refreshFailure.videoFailureOnRead=2
        assertFalse(refreshActuator.confirm(refreshPlan,
            TvWatchedConfirmation(true,"ENTER",refreshPlan.fingerprint)))

        val finalFailure=FakeAccount(base)
        val finalActuator=NativeTvEpisodeWatchedActuator(finalFailure)
        val finalPlan=finalActuator.prepare("tt18:1:1",true)!!
        finalFailure.videoFailureOnRead=3
        assertFalse(finalActuator.confirm(finalPlan,
            TvWatchedConfirmation(true,"ENTER",finalPlan.fingerprint)))

        val finalPlanFailure=FakeAccount(base)
        val finalPlanActuator=NativeTvEpisodeWatchedActuator(finalPlanFailure)
        val finalPlanVideo=finalPlanActuator.prepare("tt18:1:1",true)!!
        finalPlanFailure.videoFailureOnRead=4
        assertFalse(finalPlanActuator.confirm(finalPlanVideo,
            TvWatchedConfirmation(true,"ENTER",finalPlanVideo.fingerprint)))
        assertEquals(0,refreshFailure.writes+finalFailure.writes+finalPlanFailure.writes)
    }
    @Test fun dispatchFailureFailsClosedAndConsumesThePlan() {
        val p=FakeAccount(base)
        p.dispatchFailure=IllegalStateException("Core dispatch unavailable")
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!
        val request=TvWatchedConfirmation(true,"ENTER",plan.fingerprint)

        assertFalse(a.confirm(plan,request))
        assertFalse(a.confirm(plan,request))
        assertEquals(1,p.writes)
    }
    @Test fun copiedOrForeignActuatorPlanCannotDispatch() {
        val p=FakeAccount(base)
        val owner=NativeTvEpisodeWatchedActuator(p)
        val foreign=NativeTvEpisodeWatchedActuator(p)
        val plan=owner.prepare("tt18:1:1",true)!!
        val request=TvWatchedConfirmation(true,"ENTER",plan.fingerprint)

        assertFalse(owner.confirm(plan.copy(),request))
        assertFalse(foreign.confirm(plan,request))
        assertEquals(0,p.writes)
        assertTrue(owner.confirm(plan,request))
        assertEquals(1,p.writes)
    }
    @Test fun duplicatePreparationIsRefused() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!

        assertNull(a.prepare("tt18:1:1",true))
        assertTrue(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertNull(a.prepare("tt18:1:1",true))
        assertEquals(1,p.writes)
    }
    @Test fun failedRefreshConsumesExactIssuedPlan() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!
        val request=TvWatchedConfirmation(true,"ENTER",plan.fingerprint)
        p.videoFailureOnRead=2

        assertFalse(a.confirm(plan,request))
        p.videoFailureOnRead=null
        assertFalse(a.confirm(plan,request))
        assertEquals(0,p.writes)
    }
    @Test fun crossThreadAccessFailsClosedWithoutConsumingOwnerPlan() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!
        val request=TvWatchedConfirmation(true,"ENTER",plan.fingerprint)
        var foreignPlan:TvEpisodeWatchedPlan?=plan
        var foreignConfirm=true
        var foreignClose=true

        Thread {
            foreignPlan=a.prepare("tt18:1:1",true)
            foreignConfirm=a.confirm(plan,request)
            foreignClose=a.close()
        }.apply { start();join() }

        assertNull(foreignPlan)
        assertFalse(foreignConfirm)
        assertFalse(foreignClose)
        assertEquals(0,p.writes)
        assertTrue(a.confirm(plan,request))
        assertEquals(1,p.writes)
    }
    @Test fun closeRevokesPreparedPlanAndAllFutureAccess() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!

        assertTrue(a.close())
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertNull(a.prepare("tt18:1:1",true))
        assertFalse(a.close())
        assertEquals(0,p.writes)
    }
    @Test fun accountOrMetadataDriftIsNotDispatched() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!
        p.state=base.copy(libraryItemFingerprint="c".repeat(64))
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        p.state=base
        p.video=p.video+mapOf("overview" to "changed")
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertEquals(0,p.writes)
    }
    @Test fun explicitUnwatchHasSecondAcknowledgement() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",false)!!
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertTrue(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint,true)))
        assertEquals(1,p.writes)
    }

    @Test fun secondVideoReadMustNotChangeEvenWhenIdentityIsStable() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!
        // Confirm() refreshes metadata first, then fetches the actual dispatch payload.
        // A changed title with the SAME stable ID after refresh is an unsafe race.
        p.beforeVideoRead={ read ->
            if(read==3) p.video=p.video+mapOf("title" to "Changed between guard and dispatch")
        }
        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertEquals(0,p.writes)
    }

    @Test fun finalAccountDriftAfterDispatchPayloadReadFailsClosed() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!
        p.beforeVideoRead={ read ->
            if(read==3) p.state=base.copy(libraryItemFingerprint="c".repeat(64))
        }

        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertEquals(0,p.writes)
        assertEquals(3,p.snapshotReadCount)
    }

    @Test fun finalAccountSnapshotFailureAfterDispatchPayloadReadFailsClosed() {
        val p=FakeAccount(base)
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!
        p.snapshotFailureOnRead=3

        assertFalse(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        assertEquals(0,p.writes)
        assertEquals(3,p.snapshotReadCount)
    }

    @Test fun dispatchedPayloadIsDeepDetachedFromMutableCoreObject() {
        val nested=mutableMapOf<String,Any?>("source" to "original")
        val video=mutableMapOf<String,Any?>(
            "id" to "tt18:1:1", "season" to 1,"episode" to 1,
            "metadata" to nested,
            "chapters" to mutableListOf<Any?>(mutableMapOf("label" to "start")))
        val p=FakeAccount(base)
        p.video=video
        val a=NativeTvEpisodeWatchedActuator(p)
        val plan=a.prepare("tt18:1:1",true)!!
        p.duringDispatch={
            nested["source"]="changed"
            (video["chapters"] as MutableList<*>).clear()
        }
        assertTrue(a.confirm(plan,TvWatchedConfirmation(true,"ENTER",plan.fingerprint)))
        val inner=p.lastAction!!["args"] as Map<*,*>
        val dispatched=(inner["args"] as List<*>)[0] as Map<*,*>
        assertEquals("original",(dispatched["metadata"] as Map<*,*>)["source"])
        assertEquals(1,(dispatched["chapters"] as List<*>).size)
        assertEquals(1,p.writes)
    }

    @Test fun malformedCanonicalNumericsAreNotSerialised() {
        val p=FakeAccount(base)
        p.video=p.video+mapOf("duration" to Double.NaN)
        assertNull(NativeTvEpisodeWatchedActuator(p).prepare("tt18:1:1",true))
        assertEquals(0,p.writes)
    }

    @Test fun cyclicCanonicalVideoGraphFailsClosedWithoutEscaping() {
        val cyclic=mutableMapOf<String,Any?>(
            "id" to "tt18:1:1", "season" to 1, "episode" to 1)
        cyclic["loop"]=cyclic
        val p=FakeAccount(base)
        p.video=cyclic

        assertNull(NativeTvEpisodeWatchedActuator(p).prepare("tt18:1:1",true))
        assertEquals(0,p.writes)
    }

    @Test fun excessiveCanonicalVideoDepthFailsClosed() {
        var nested:Any?="leaf"
        repeat(70) { nested=listOf(nested) }
        val p=FakeAccount(base)
        p.video=p.video+mapOf("nested" to nested)

        assertNull(NativeTvEpisodeWatchedActuator(p).prepare("tt18:1:1",true))
        assertEquals(0,p.writes)
    }

    @Test fun excessiveCanonicalVideoGraphOrStringFailsClosed() {
        val p=FakeAccount(base)
        p.video=p.video+mapOf("nodes" to List(10_001) { it })
        assertNull(NativeTvEpisodeWatchedActuator(p).prepare("tt18:1:1",true))

        p.video=mapOf(
            "id" to "tt18:1:1", "season" to 1, "episode" to 1,
            "overview" to "x".repeat(262_145))
        assertNull(NativeTvEpisodeWatchedActuator(p).prepare("tt18:1:1",true))
        assertEquals(0,p.writes)
    }

    @Test fun numericTypeChangesCannotReuseCanonicalVideoFingerprint() {
        val integerAccount=FakeAccount(base)
        integerAccount.video=integerAccount.video+mapOf("runtime" to 120)
        val integerActuator=NativeTvEpisodeWatchedActuator(integerAccount)
        val integerPlan=integerActuator.prepare("tt18:1:1",true)!!
        integerAccount.beforeVideoRead={ read ->
            if(read==3) integerAccount.video=integerAccount.video+mapOf("runtime" to 120L)
        }
        assertFalse(integerActuator.confirm(integerPlan,
            TvWatchedConfirmation(true,"ENTER",integerPlan.fingerprint)))
        assertEquals(0,integerAccount.writes)

        val floatingAccount=FakeAccount(base)
        floatingAccount.video=floatingAccount.video+mapOf("score" to 1.5f)
        val floatingActuator=NativeTvEpisodeWatchedActuator(floatingAccount)
        val floatingPlan=floatingActuator.prepare("tt18:1:1",true)!!
        floatingAccount.beforeVideoRead={ read ->
            if(read==3) floatingAccount.video=floatingAccount.video+mapOf("score" to 1.5)
        }
        assertFalse(floatingActuator.confirm(floatingPlan,
            TvWatchedConfirmation(true,"ENTER",floatingPlan.fingerprint)))
        assertEquals(0,floatingAccount.writes)
    }
}
