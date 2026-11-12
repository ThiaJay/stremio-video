package org.thiajay.stremiotvcompanion

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class SyntheticSubtitlePlaybackEvidenceObserverTest {
    private fun context(load: Long = 1L) = SyntheticSubtitlePlaybackEvidenceObserver.Context(
        playerGeneration = 1L,
        loadGeneration = load,
        videoId = "local:synthetic-media:12s",
        sourceId = "local:synthetic-wav"
    )

    @Test fun selectedTrackAndAllCuesCorrelateWithoutVisualClaim() {
        val observer = SyntheticSubtitlePlaybackEvidenceObserver()
        val active = context()
        assertTrue(observer.begin(active))
        assertTrue(observer.observeTrack(active, SyntheticSubtitleTrackStateObserver.State.PRESENT_SELECTED))
        assertTrue(observer.observeCues(active, 3, 3, true))
        val snapshot = observer.snapshot(active)!!
        assertEquals(SyntheticSubtitlePlaybackEvidenceObserver.Status.SELECTED_ALL_CUES, snapshot.status)
        assertTrue(snapshot.selectedTrackAndAllCuesObserved)
    }

    @Test fun cuesMayArriveBeforeTrackSelectionAndRemainBounded() {
        val observer = SyntheticSubtitlePlaybackEvidenceObserver()
        val active = context()
        assertTrue(observer.begin(active))
        assertTrue(observer.observeCues(active, 1, 3, false))
        assertEquals(SyntheticSubtitlePlaybackEvidenceObserver.Status.CUES_WITHOUT_TRACK_REPORT, observer.snapshot(active)!!.status)
        assertTrue(observer.observeTrack(active, SyntheticSubtitleTrackStateObserver.State.PRESENT_SELECTED))
        assertEquals(SyntheticSubtitlePlaybackEvidenceObserver.Status.SELECTED_PARTIAL_CUES, observer.snapshot(active)!!.status)
    }

    @Test fun absentOrUnselectedTrackNeverProducesCorrelatedAcceptance() {
        val observer = SyntheticSubtitlePlaybackEvidenceObserver()
        val active = context()
        assertTrue(observer.begin(active))
        assertTrue(observer.observeCues(active, 3, 3, true))
        assertTrue(observer.observeTrack(active, SyntheticSubtitleTrackStateObserver.State.ABSENT))
        assertEquals(SyntheticSubtitlePlaybackEvidenceObserver.Status.TRACK_ABSENT, observer.snapshot(active)!!.status)
        assertFalse(observer.snapshot(active)!!.selectedTrackAndAllCuesObserved)
        assertTrue(observer.observeTrack(active, SyntheticSubtitleTrackStateObserver.State.PRESENT_UNSELECTED))
        assertEquals(SyntheticSubtitlePlaybackEvidenceObserver.Status.TRACK_UNSELECTED, observer.snapshot(active)!!.status)
        assertFalse(observer.snapshot(active)!!.selectedTrackAndAllCuesObserved)
    }

    @Test fun malformedCueEvidencePoisonsOnlyItsGeneration() {
        val observer = SyntheticSubtitlePlaybackEvidenceObserver()
        val first = context()
        assertTrue(observer.begin(first))
        assertFalse(observer.observeCues(first, 3, 4, false))
        assertNull(observer.snapshot(first))
        val second = context(2L)
        assertTrue(observer.begin(second))
        assertFalse(observer.observeCues(second, 2, 3, true))
        assertNull(observer.snapshot(second))
    }

    @Test fun cueCountRegressionPoisonsActiveGeneration() {
        val observer = SyntheticSubtitlePlaybackEvidenceObserver()
        val active = context()
        assertTrue(observer.begin(active))
        assertTrue(observer.observeCues(active, 2, 3, false))
        assertFalse(observer.observeCues(active, 1, 3, false))
        assertNull(observer.snapshot(active))
    }

    @Test fun notReportedTrackAndExplicitInvalidationFailClosed() {
        val observer = SyntheticSubtitlePlaybackEvidenceObserver()
        val first = context()
        assertTrue(observer.begin(first))
        assertFalse(observer.observeTrack(first, SyntheticSubtitleTrackStateObserver.State.NOT_REPORTED))
        assertNull(observer.snapshot(first))
        val second = context(2L)
        assertTrue(observer.begin(second))
        assertTrue(observer.invalidate(second))
        assertNull(observer.snapshot(second))
    }

    @Test fun generationThreadAbandonAndCloseAreEnforced() {
        val observer = SyntheticSubtitlePlaybackEvidenceObserver()
        val first = context()
        assertTrue(observer.begin(first))
        assertFalse(observer.begin(first))
        val foreign = AtomicReference<SyntheticSubtitlePlaybackEvidenceObserver.Snapshot?>()
        Thread { foreign.set(observer.snapshot(first)) }.apply { start(); join() }
        assertNull(foreign.get())
        assertTrue(observer.abandon(first))
        val second = context(2L)
        assertTrue(observer.begin(second))
        assertTrue(observer.close())
        assertFalse(observer.close())
        assertNull(observer.snapshot(second))
    }

    @Test fun malformedContextNeverStartsPlaybackEvidence() {
        val observer = SyntheticSubtitlePlaybackEvidenceObserver()
        assertFalse(observer.begin(context().copy(playerGeneration = 0L)))
        assertFalse(observer.begin(context().copy(loadGeneration = 0L)))
        assertFalse(observer.begin(context().copy(videoId = "")))
        assertFalse(observer.begin(context().copy(sourceId = " ")))
    }
}
