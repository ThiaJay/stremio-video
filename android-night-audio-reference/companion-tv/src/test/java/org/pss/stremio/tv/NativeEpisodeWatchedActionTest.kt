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
        override fun currentSnapshot()=state
        override fun canonicalVideoFromReadyMetaDetails(id:String)=video.takeIf { it["id"]==id }
        override fun dispatch(message:Map<String,Any>):Boolean {writes++;lastAction=message;return true}
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
}
