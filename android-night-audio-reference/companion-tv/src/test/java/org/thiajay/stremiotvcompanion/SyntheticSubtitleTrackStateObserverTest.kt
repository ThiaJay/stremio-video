package org.thiajay.stremiotvcompanion

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class SyntheticSubtitleTrackStateObserverTest {
    private fun context(load: Long = 1L) =
        SyntheticSubtitleTrackStateObserver.Context(
            playerGeneration = 1L,
            loadGeneration = load,
            videoId = "local:synthetic-media:12s",
            sourceId = "local:synthetic-wav"
        )

    private fun expected(selected: Boolean) =
        SyntheticSubtitleTrackStateObserver.TextTrack(
            sampleMimeType = "text/vtt",
            language = "en",
            label = "English (synthetic lab)",
            selected = selected
        )

    @Test fun exactLocalTrackReportsSelectedAndUnselectedWithoutVisualClaim() {
        val observer = SyntheticSubtitleTrackStateObserver()
        val active = context()
        assertTrue(observer.begin(active))
        assertTrue(observer.observe(active, listOf(expected(false))))
        assertEquals(
            SyntheticSubtitleTrackStateObserver.State.PRESENT_UNSELECTED,
            observer.snapshot(active)!!.state
        )
        assertTrue(observer.observe(active, listOf(expected(true))))
        assertEquals(
            SyntheticSubtitleTrackStateObserver.State.PRESENT_SELECTED,
            observer.snapshot(active)!!.state
        )
    }

    @Test fun emptyTrackReportIsAbsentRatherThanAcceptedSelection() {
        val observer = SyntheticSubtitleTrackStateObserver()
        val active = context()
        assertTrue(observer.begin(active))
        assertTrue(observer.observe(active, emptyList()))
        assertEquals(
            SyntheticSubtitleTrackStateObserver.State.ABSENT,
            observer.snapshot(active)!!.state
        )
    }

    @Test fun unexpectedOrDuplicateTextTrackPoisonsOnlyThatGeneration() {
        val observer = SyntheticSubtitleTrackStateObserver()
        val first = context()
        assertTrue(observer.begin(first))
        assertFalse(observer.observe(first, listOf(expected(false).copy(language = "es"))))
        assertNull(observer.snapshot(first))

        val second = context(2L)
        assertTrue(observer.begin(second))
        assertFalse(observer.observe(second, listOf(expected(false), expected(false))))
        assertNull(observer.snapshot(second))
    }

    @Test fun oversizedTextTrackInputIsRefusedWithoutStatePromotion() {
        val observer = SyntheticSubtitleTrackStateObserver()
        val active = context()
        assertTrue(observer.begin(active))
        assertFalse(observer.observe(active, List(5) { expected(false) }))
        assertNull(observer.snapshot(active))
    }

    @Test fun changedIdentityCannotReadOrMutateActiveState() {
        val observer = SyntheticSubtitleTrackStateObserver()
        val active = context()
        val changed = active.copy(videoId = "local:other-video")
        assertTrue(observer.begin(active))
        assertFalse(observer.observe(changed, listOf(expected(true))))
        assertNull(observer.snapshot(changed))
        assertEquals(
            SyntheticSubtitleTrackStateObserver.State.NOT_REPORTED,
            observer.snapshot(active)!!.state
        )
    }

    @Test fun loadGenerationMustIncreaseAndStartsNotReported() {
        val observer = SyntheticSubtitleTrackStateObserver()
        val first = context()
        assertTrue(observer.begin(first))
        assertTrue(observer.observe(first, listOf(expected(true))))
        assertFalse(observer.begin(first))
        val second = context(2L)
        assertTrue(observer.begin(second))
        assertNull(observer.snapshot(first))
        assertEquals(
            SyntheticSubtitleTrackStateObserver.State.NOT_REPORTED,
            observer.snapshot(second)!!.state
        )
        assertFalse(observer.begin(context(1L)))
    }

    @Test fun ownerThreadAbandonAndCloseRevokeObservation() {
        val observer = SyntheticSubtitleTrackStateObserver()
        val first = context()
        assertTrue(observer.begin(first))
        val foreignSnapshot =
            AtomicReference<SyntheticSubtitleTrackStateObserver.Snapshot?>()
        val foreignClose = AtomicReference<Boolean>()
        Thread {
            foreignSnapshot.set(observer.snapshot(first))
            foreignClose.set(observer.close())
        }.apply { start(); join() }
        assertNull(foreignSnapshot.get())
        assertFalse(foreignClose.get())
        assertTrue(observer.abandon(first))
        assertNull(observer.snapshot(first))

        val second = context(2L)
        assertTrue(observer.begin(second))
        assertTrue(observer.close())
        assertFalse(observer.close())
        assertFalse(observer.begin(context(3L)))
    }

    @Test fun malformedContextNeverStartsTrackObservation() {
        val observer = SyntheticSubtitleTrackStateObserver()
        assertFalse(observer.begin(context().copy(playerGeneration = 0L)))
        assertFalse(observer.begin(context().copy(loadGeneration = 0L)))
        assertFalse(observer.begin(context().copy(videoId = "")))
        assertFalse(observer.begin(context().copy(sourceId = " ")))
    }
}
