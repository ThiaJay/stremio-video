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
        var videoReadCount=0
        var beforeVideoRead: ((Int) -> Unit)?=null
        var duringDispatch: (() -> Unit)?=null
        override fun currentSnapshot()=state
        override fun canonicalVideoFromReadyMetaDetails(id:String):Map<String,Any?>? {
            videoReadCount++
            beforeVideoRead?.invoke(videoReadCount)
            return video.takeIf { it["id"]==id }
        }
        override fun dispatch(message:Map<String,Any>):Boolean {
            writes++
            lastAction=message
            duringDispatch?.invoke()
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
}
