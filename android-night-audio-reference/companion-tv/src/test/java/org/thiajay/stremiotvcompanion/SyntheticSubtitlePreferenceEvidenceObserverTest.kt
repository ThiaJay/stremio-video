package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SyntheticSubtitlePreferenceEvidenceObserverTest {
    @Test fun admittedEnabledCompletesOnlyWithAutomaticSelectedTrackAndAllCues() {
        val observer = SyntheticSubtitlePreferenceEvidenceObserver()
        val context = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observe(context, preference, AUTOMATIC, evidence(SELECTED_ALL, 3), false))
        val snapshot = observer.snapshot(context)!!
        assertEquals(ENABLED_COMPLETE, snapshot.status)
        assertTrue(snapshot.callbackEvidenceComplete)
        assertFalse(snapshot.visibleRenderingAccepted)
    }

    @Test fun enabledWithoutSameInstallationReceiptIsPermanentlyHeld() {
        val observer = SyntheticSubtitlePreferenceEvidenceObserver()
        val context = context()
        val preference = preference(true, false)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observe(context, preference, AUTOMATIC, evidence(SELECTED_ALL, 3), false))
        assertEquals(UNADMITTED, observer.snapshot(context)!!.status)
        assertFalse(observer.snapshot(context)!!.callbackEvidenceComplete)
    }

    @Test fun manualSelectionCanNeverBecomeAutomaticPreferenceEvidence() {
        val observer = SyntheticSubtitlePreferenceEvidenceObserver()
        val context = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observe(context, preference, MANUAL, evidence(SELECTED_ALL, 3), false))
        assertEquals(MANUAL_HELD, observer.snapshot(context)!!.status)
        assertTrue(observer.observe(context, preference, AUTOMATIC, evidence(SELECTED_ALL, 3), false))
        assertEquals(MANUAL_HELD, observer.snapshot(context)!!.status)
    }

    @Test fun explicitOffRequiresUnselectedZeroCueEvidenceAtPlaybackEnd() {
        val observer = SyntheticSubtitlePreferenceEvidenceObserver()
        val context = context()
        val preference = preference(false, false)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observe(context, preference, NONE, evidence(UNSELECTED, 0), false))
        assertEquals(OFF_WAITING, observer.snapshot(context)!!.status)
        assertTrue(observer.observe(context, preference, NONE, evidence(UNSELECTED, 0), true))
        assertEquals(OFF_COMPLETE, observer.snapshot(context)!!.status)
        assertTrue(observer.snapshot(context)!!.callbackEvidenceComplete)
    }

    @Test fun explicitOffNeverCompletesWhileAutomaticSelectionOrCuesRemain() {
        val observer = SyntheticSubtitlePreferenceEvidenceObserver()
        val context = context()
        val preference = preference(false, false)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observe(context, preference, AUTOMATIC, evidence(SELECTED_ALL, 3), true))
        assertEquals(OFF_CLEAR, observer.snapshot(context)!!.status)
        assertFalse(observer.snapshot(context)!!.callbackEvidenceComplete)
    }

    @Test fun preferenceOrContextDriftPoisonsOnlyTheActiveGeneration() {
        val observer = SyntheticSubtitlePreferenceEvidenceObserver()
        val first = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(first, preference))
        assertFalse(observer.observe(first, preference(false, false), AUTOMATIC, evidence(SELECTED_ALL, 3), false))
        assertNull(observer.snapshot(first))
        val second = context(2L)
        assertTrue(observer.begin(second, preference))
        assertFalse(observer.observe(second, preference, AUTOMATIC, evidence(SELECTED_ALL, 3, 3L), false))
        assertNull(observer.snapshot(second))
    }

    @Test fun malformedEvidenceAndPlaybackEndRegressionFailClosed() {
        val observer = SyntheticSubtitlePreferenceEvidenceObserver()
        val first = context()
        val preference = preference(false, false)
        assertTrue(observer.begin(first, preference))
        assertFalse(observer.observe(first, preference, NONE, evidence(UNSELECTED, 4), false))
        assertNull(observer.snapshot(first))
        val second = context(2L)
        assertTrue(observer.begin(second, preference))
        assertTrue(observer.observe(second, preference, NONE, evidence(UNSELECTED, 0, 2L), true))
        assertFalse(observer.observe(second, preference, NONE, evidence(UNSELECTED, 0, 2L), false))
        assertNull(observer.snapshot(second))
    }

    @Test fun generationThreadAbandonAndCloseAreEnforced() {
        val observer = SyntheticSubtitlePreferenceEvidenceObserver()
        val first = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(first, preference))
        val foreign = AtomicReference<SyntheticSubtitlePreferenceEvidenceObserver.Snapshot?>()
        Thread { foreign.set(observer.snapshot(first)) }.apply { start(); join() }
        assertNull(foreign.get())
        assertTrue(observer.abandon(first))
        val second = context(2L)
        assertTrue(observer.begin(second, preference))
        assertTrue(observer.close())
        assertFalse(observer.close())
        assertNull(observer.snapshot(second))
    }

    @Test fun malformedContextAndImpossibleOffReceiptNeverStart() {
        val observer = SyntheticSubtitlePreferenceEvidenceObserver()
        val preference = preference(true, true)
        assertFalse(observer.begin(context().copy(playerGeneration = 0L), preference))
        assertFalse(observer.begin(context().copy(loadGeneration = 0L), preference))
        assertFalse(observer.begin(context().copy(videoId = ""), preference))
        assertFalse(observer.begin(context().copy(sourceId = " "), preference))
        assertFalse(observer.begin(context(), preference(false, true)))
    }

    private fun context(load: Long = 1L) = SyntheticSubtitlePreferenceEvidenceObserver.Context(
        playerGeneration = 1L,
        loadGeneration = load,
        videoId = "local:synthetic-media:12s",
        sourceId = "local:synthetic-wav"
    )

    private fun preference(enabled: Boolean, admitted: Boolean) =
        NativeSubtitlePlaybackPreferenceActuator.Preference(enabled, admitted)

    private fun evidence(
        status: SyntheticSubtitlePlaybackEvidenceObserver.Status,
        delivered: Int,
        load: Long = 1L
    ) = SyntheticSubtitlePlaybackEvidenceObserver.Snapshot(
        context = SyntheticSubtitlePlaybackEvidenceObserver.Context(
            1L,
            load,
            "local:synthetic-media:12s",
            "local:synthetic-wav"
        ),
        status = status,
        deliveredCueCount = delivered,
        expectedCueCount = 3,
        selectedTrackAndAllCuesObserved = status == SELECTED_ALL
    )

    private companion object {
        val AUTOMATIC = NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.AUTOMATIC
        val MANUAL = NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.MANUAL
        val NONE = NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.NONE
        val SELECTED_ALL = SyntheticSubtitlePlaybackEvidenceObserver.Status.SELECTED_ALL_CUES
        val UNSELECTED = SyntheticSubtitlePlaybackEvidenceObserver.Status.TRACK_UNSELECTED
        val ENABLED_COMPLETE =
            SyntheticSubtitlePreferenceEvidenceObserver.Status.ENABLED_CALLBACKS_COMPLETE
        val UNADMITTED =
            SyntheticSubtitlePreferenceEvidenceObserver.Status.UNADMITTED_ENABLED_HELD
        val MANUAL_HELD =
            SyntheticSubtitlePreferenceEvidenceObserver.Status.MANUAL_SELECTION_HELD
        val OFF_WAITING =
            SyntheticSubtitlePreferenceEvidenceObserver.Status.OFF_WAITING_COMPLETION
        val OFF_CLEAR =
            SyntheticSubtitlePreferenceEvidenceObserver.Status.OFF_WAITING_AUTOMATIC_CLEAR
        val OFF_COMPLETE =
            SyntheticSubtitlePreferenceEvidenceObserver.Status.OFF_CALLBACKS_COMPLETE
    }
}
