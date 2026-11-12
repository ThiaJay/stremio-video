package org.thiajay.stremiotvcore

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class CoreReadOnlyGateTest {
    private fun signal(epoch:Long=4,auth:Boolean=true,meta:String?="tt:synthetic",video:String?="tt:synthetic:1",lists:Int=2) =
        SafeCoreObservation(epoch,auth,meta,video,lists)
    private class Fake(private val captures: List<SafeCoreObservation?>): CoreReadOnlySignals {
        var calls=0
        override fun observe(): SafeCoreObservation? = captures.getOrNull(calls++)
    }
    @Test fun stableReadOnlyMetadataCanBeObservedWithoutStreamUrl() {
        val fake=Fake(listOf(signal(),signal()));val out=CoreReadOnlyGate(fake).observe(4,"tt:synthetic","tt:synthetic:1")
        assertEquals(CoreReadOnlyResult.Available("tt:synthetic","tt:synthetic:1",2),out)
        assertEquals(2,fake.calls)
        assertTrue(out.toString().contains("streamListsObserved"))
        assertTrue(!out.toString().contains("http"))
    }
    @Test fun absentNativeSdkFailsClosed() { assertEquals(CoreReadOnlyResult.Held(CoreReadOnlyFailure.SOURCE_UNAVAILABLE),CoreReadOnlyGate(Fake(listOf(null))).observe(4,"id","vid")) }
    @Test fun absentAuthFailsClosed() { assertEquals(CoreReadOnlyResult.Held(CoreReadOnlyFailure.NOT_AUTHENTICATED),CoreReadOnlyGate(Fake(listOf(signal(auth=false),signal(auth=false)))).observe(4,"tt:synthetic","tt:synthetic:1")) }
    @Test fun accountSwitchOrEpochChangeFailsClosed() { assertEquals(CoreReadOnlyResult.Held(CoreReadOnlyFailure.STALE_OR_SWITCHED_SESSION),CoreReadOnlyGate(Fake(listOf(signal(),signal(epoch=5)))).observe(4,"tt:synthetic","tt:synthetic:1")) }
    @Test fun newVideoWithSameMetadataFailsClosed() { assertEquals(CoreReadOnlyResult.Held(CoreReadOnlyFailure.WRONG_VIDEO_OR_METADATA),CoreReadOnlyGate(Fake(listOf(signal(),signal(video="tt:synthetic:2")))).observe(4,"tt:synthetic","tt:synthetic:1")) }
    @Test fun providerListChangeDuringReadFailsClosed() { assertEquals(CoreReadOnlyResult.Held(CoreReadOnlyFailure.WRONG_VIDEO_OR_METADATA),CoreReadOnlyGate(Fake(listOf(signal(),signal(lists=3)))).observe(4,"tt:synthetic","tt:synthetic:1")) }
    @Test fun missingSelectionFailsClosedBeforeSourceRead() { val f=Fake(listOf(signal(),signal()));assertEquals(CoreReadOnlyResult.Held(CoreReadOnlyFailure.NO_SAFE_SELECTION),CoreReadOnlyGate(f).observe(4,"","tt:synthetic:1"));assertEquals(0,f.calls) }
    @Test fun noPlayableUrlIsInferredFromObservedProviderLists() { val out=CoreReadOnlyGate(Fake(listOf(signal(lists=0),signal(lists=0)))).observe(4,"tt:synthetic","tt:synthetic:1");assertEquals(CoreReadOnlyResult.Available("tt:synthetic","tt:synthetic:1",0),out) }
}
