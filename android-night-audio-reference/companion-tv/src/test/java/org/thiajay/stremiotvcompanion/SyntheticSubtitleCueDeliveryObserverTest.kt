package org.thiajay.stremiotvcompanion

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class SyntheticSubtitleCueDeliveryObserverTest {
    private fun context(load: Long = 1L) =
        SyntheticSubtitleCueDeliveryObserver.Context(
            playerGeneration = 1L,
            loadGeneration = load,
            videoId = "local:synthetic-media:12s",
            sourceId = "local:synthetic-wav"
        )

    @Test fun exactFixtureCuesCompleteOnlyTheActiveGeneration() {
        val observer = SyntheticSubtitleCueDeliveryObserver()
        val active = context()
        assertTrue(observer.begin(active))

        for (cue in SyntheticSubtitleCueDeliveryObserver.EXPECTED_CUES) {
            assertEquals(
                SyntheticSubtitleCueDeliveryObserver.Observation.ACCEPTED,
                observer.observe(active, listOf(cue))
            )
        }

        val snapshot = observer.snapshot(active)
        assertNotNull(snapshot)
        assertEquals(3, snapshot!!.deliveredCueCount)
        assertEquals(3, snapshot.expectedCueCount)
        assertTrue(snapshot.allExpectedCuesObserved)
    }

    @Test fun emptyAndDuplicateCallbacksAreBoundedAndIdempotent() {
        val observer = SyntheticSubtitleCueDeliveryObserver()
        val active = context()
        assertTrue(observer.begin(active))
        assertEquals(
            SyntheticSubtitleCueDeliveryObserver.Observation.NO_CUES,
            observer.observe(active, emptyList())
        )
        repeat(2) {
            assertEquals(
                SyntheticSubtitleCueDeliveryObserver.Observation.ACCEPTED,
                observer.observe(active, listOf("Synthetic subtitle cue one."))
            )
        }
        assertEquals(1, observer.snapshot(active)!!.deliveredCueCount)
    }

    @Test fun changedPlayerIdentityCannotAffectActiveObservation() {
        val observer = SyntheticSubtitleCueDeliveryObserver()
        val active = context()
        val changed = active.copy(sourceId = "local:other-source")
        assertTrue(observer.begin(active))
        assertEquals(
            SyntheticSubtitleCueDeliveryObserver.Observation.REFUSED,
            observer.observe(changed, listOf("Synthetic subtitle cue one."))
        )
        assertEquals(0, observer.snapshot(active)!!.deliveredCueCount)
    }

    @Test fun unknownOrOversizedCueInputPoisonsOnlyThatGeneration() {
        val observer = SyntheticSubtitleCueDeliveryObserver()
        val first = context()
        assertTrue(observer.begin(first))
        assertEquals(
            SyntheticSubtitleCueDeliveryObserver.Observation.REFUSED,
            observer.observe(first, listOf("unexpected user or provider cue"))
        )
        assertNull(observer.snapshot(first))
        assertEquals(
            SyntheticSubtitleCueDeliveryObserver.Observation.REFUSED,
            observer.observe(first, listOf("Synthetic subtitle cue one."))
        )

        val second = context(2L)
        assertTrue(observer.begin(second))
        assertEquals(
            SyntheticSubtitleCueDeliveryObserver.Observation.REFUSED,
            observer.observe(second, List(4) { "Synthetic subtitle cue one." })
        )
        assertNull(observer.snapshot(second))
    }

    @Test fun loadGenerationMustIncreaseAndNewGenerationStartsEmpty() {
        val observer = SyntheticSubtitleCueDeliveryObserver()
        val first = context()
        assertTrue(observer.begin(first))
        assertEquals(
            SyntheticSubtitleCueDeliveryObserver.Observation.ACCEPTED,
            observer.observe(first, listOf("Synthetic subtitle cue one."))
        )
        assertFalse(observer.begin(first))

        val second = context(2L)
        assertTrue(observer.begin(second))
        assertNull(observer.snapshot(first))
        assertEquals(0, observer.snapshot(second)!!.deliveredCueCount)
        assertFalse(observer.begin(context(1L)))
    }

    @Test fun abandonThreadOwnershipAndCloseFailClosed() {
        val observer = SyntheticSubtitleCueDeliveryObserver()
        val first = context()
        assertTrue(observer.begin(first))
        val foreign = AtomicReference<SyntheticSubtitleCueDeliveryObserver.Snapshot?>()
        val foreignClose = AtomicReference<Boolean>()
        Thread {
            foreign.set(observer.snapshot(first))
            foreignClose.set(observer.close())
        }.apply { start(); join() }
        assertNull(foreign.get())
        assertFalse(foreignClose.get())
        assertTrue(observer.abandon(first))
        assertNull(observer.snapshot(first))

        val second = context(2L)
        assertTrue(observer.begin(second))
        assertTrue(observer.close())
        assertFalse(observer.close())
        assertFalse(observer.begin(context(3L)))
        assertNull(observer.snapshot(second))
    }

    @Test fun malformedContextNeverStartsObservation() {
        val observer = SyntheticSubtitleCueDeliveryObserver()
        assertFalse(observer.begin(context().copy(playerGeneration = 0L)))
        assertFalse(observer.begin(context().copy(loadGeneration = 0L)))
        assertFalse(observer.begin(context().copy(videoId = "")))
        assertFalse(observer.begin(context().copy(sourceId = " ")))
    }
}
