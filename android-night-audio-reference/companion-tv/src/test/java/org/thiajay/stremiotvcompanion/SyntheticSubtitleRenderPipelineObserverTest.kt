package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SyntheticSubtitleRenderPipelineObserverTest {
    @Test fun admittedEnabledRequiresReadyViewAndExactDrawAfterActiveCue() {
        val observer = SyntheticSubtitleRenderPipelineObserver()
        val context = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observePreferenceEvidence(context, preference, evidence(true, true)))
        assertTrue(observer.observeCueGroup(context, 1L, 1))
        assertTrue(observer.observeView(context, readyView()))
        assertTrue(observer.observeDraw(context, 1L))
        val snapshot = observer.snapshot(context)!!
        assertEquals(ENABLED_COMPLETE, snapshot.status)
        assertTrue(snapshot.renderPipelineEvidenceComplete)
        assertFalse(snapshot.visibleRenderingAccepted)
        assertFalse(snapshot.pixelOutputAccepted)
        assertFalse(snapshot.deviceAcceptance)
    }

    @Test fun drawBeforePreferenceCallbacksCannotComplete() {
        val observer = SyntheticSubtitleRenderPipelineObserver()
        val context = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observeCueGroup(context, 1L, 1))
        assertTrue(observer.observeView(context, readyView()))
        assertTrue(observer.observeDraw(context, 1L))
        assertEquals(CALLBACKS_PENDING, observer.snapshot(context)!!.status)
        assertTrue(observer.observePreferenceEvidence(context, preference, evidence(true, true)))
        assertTrue(observer.observeCueGroup(context, 2L, 1))
        assertTrue(observer.observeDraw(context, 2L))
        assertEquals(ENABLED_COMPLETE, observer.snapshot(context)!!.status)
    }

    @Test fun hiddenDetachedTransparentOrZeroSizedViewNeverCompletes() {
        val states = listOf(
            readyView().copy(attached = false),
            readyView().copy(shown = false),
            readyView().copy(width = 0),
            readyView().copy(height = 0),
            readyView().copy(alphaPermille = 999)
        )
        states.forEachIndexed { index, state ->
            val observer = SyntheticSubtitleRenderPipelineObserver()
            val context = context(index.toLong() + 1L)
            val preference = preference(true, true)
            assertTrue(observer.begin(context, preference))
            assertTrue(observer.observePreferenceEvidence(context, preference, evidence(true, true, context.loadGeneration)))
            assertTrue(observer.observeCueGroup(context, 1L, 1))
            assertTrue(observer.observeView(context, state))
            assertTrue(observer.observeDraw(context, 1L))
            assertEquals(VIEW_NOT_READY, observer.snapshot(context)!!.status)
            assertFalse(observer.snapshot(context)!!.renderPipelineEvidenceComplete)
        }
    }

    @Test fun unadmittedAndManualPreferenceEvidenceRemainHeld() {
        val unadmitted = SyntheticSubtitleRenderPipelineObserver()
        val first = context()
        val unadmittedPreference = preference(true, false)
        assertTrue(unadmitted.begin(first, unadmittedPreference))
        assertTrue(unadmitted.observePreferenceEvidence(first, unadmittedPreference, evidence(true, false)))
        assertTrue(unadmitted.observeCueGroup(first, 1L, 1))
        assertTrue(unadmitted.observeView(first, readyView()))
        assertTrue(unadmitted.observeDraw(first, 1L))
        assertEquals(PREFERENCE_HELD, unadmitted.snapshot(first)!!.status)

        val manual = SyntheticSubtitleRenderPipelineObserver()
        val second = context(2L)
        val admitted = preference(true, true)
        assertTrue(manual.begin(second, admitted))
        assertTrue(manual.observePreferenceEvidence(second, admitted, evidence(true, true, 2L, manual = true)))
        assertTrue(manual.observeCueGroup(second, 1L, 1))
        assertTrue(manual.observeView(second, readyView()))
        assertTrue(manual.observeDraw(second, 1L))
        assertEquals(PREFERENCE_HELD, manual.snapshot(second)!!.status)
    }

    @Test fun explicitOffRequiresCompletedCallbacksAndEmptyCueDraw() {
        val observer = SyntheticSubtitleRenderPipelineObserver()
        val context = context()
        val preference = preference(false, false)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observePreferenceEvidence(context, preference, evidence(false, true)))
        assertTrue(observer.observeCueGroup(context, 1L, 0))
        assertTrue(observer.observeView(context, readyView()))
        assertTrue(observer.observeDraw(context, 1L))
        assertEquals(OFF_COMPLETE, observer.snapshot(context)!!.status)
        assertTrue(observer.snapshot(context)!!.renderPipelineEvidenceComplete)
    }

    @Test fun enabledEmptyCueAndOffActiveCueStayWaiting() {
        val enabled = SyntheticSubtitleRenderPipelineObserver()
        val first = context()
        val on = preference(true, true)
        assertTrue(enabled.begin(first, on))
        assertTrue(enabled.observePreferenceEvidence(first, on, evidence(true, true)))
        assertTrue(enabled.observeCueGroup(first, 1L, 0))
        assertTrue(enabled.observeView(first, readyView()))
        assertTrue(enabled.observeDraw(first, 1L))
        assertEquals(ENABLED_WAITING, enabled.snapshot(first)!!.status)

        val disabled = SyntheticSubtitleRenderPipelineObserver()
        val second = context(2L)
        val off = preference(false, false)
        assertTrue(disabled.begin(second, off))
        assertTrue(disabled.observePreferenceEvidence(second, off, evidence(false, true, 2L)))
        assertTrue(disabled.observeCueGroup(second, 1L, 1))
        assertTrue(disabled.observeView(second, readyView()))
        assertTrue(disabled.observeDraw(second, 1L))
        assertEquals(OFF_WAITING, disabled.snapshot(second)!!.status)
    }

    @Test fun staleDrawIsIgnoredWithoutPromotingOrPoisoning() {
        val observer = SyntheticSubtitleRenderPipelineObserver()
        val context = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observePreferenceEvidence(context, preference, evidence(true, true)))
        assertTrue(observer.observeCueGroup(context, 2L, 1))
        assertTrue(observer.observeView(context, readyView()))
        assertFalse(observer.observeDraw(context, 1L))
        assertEquals(ENABLED_WAITING, observer.snapshot(context)!!.status)
        assertTrue(observer.observeDraw(context, 2L))
        assertEquals(ENABLED_COMPLETE, observer.snapshot(context)!!.status)
    }

    @Test fun contextPreferenceEvidenceAndRevisionDriftFailClosed() {
        val preference = preference(true, true)
        val firstObserver = SyntheticSubtitleRenderPipelineObserver()
        val first = context()
        assertTrue(firstObserver.begin(first, preference))
        assertFalse(firstObserver.observePreferenceEvidence(first, preference(false, false), evidence(true, true)))
        assertNull(firstObserver.snapshot(first))

        val secondObserver = SyntheticSubtitleRenderPipelineObserver()
        val second = context(2L)
        assertTrue(secondObserver.begin(second, preference))
        assertFalse(secondObserver.observePreferenceEvidence(second, preference, evidence(true, true, 3L)))
        assertNull(secondObserver.snapshot(second))

        val thirdObserver = SyntheticSubtitleRenderPipelineObserver()
        val third = context(3L)
        assertTrue(thirdObserver.begin(third, preference))
        assertTrue(thirdObserver.observeCueGroup(third, 1L, 1))
        assertFalse(thirdObserver.observeCueGroup(third, 1L, 1))
        assertNull(thirdObserver.snapshot(third))
    }

    @Test fun malformedContextViewAndCueInputNeverPromote() {
        val preference = preference(true, true)
        val observer = SyntheticSubtitleRenderPipelineObserver()
        assertFalse(observer.begin(context().copy(playerGeneration = 0L), preference))
        assertFalse(observer.begin(context().copy(loadGeneration = 0L), preference))
        assertFalse(observer.begin(context().copy(videoId = ""), preference))
        assertFalse(observer.begin(context().copy(sourceId = " "), preference))
        assertFalse(observer.begin(context(), preference(false, true)))

        val active = SyntheticSubtitleRenderPipelineObserver()
        val context = context()
        assertTrue(active.begin(context, preference))
        assertFalse(active.observeView(context, readyView().copy(alphaPermille = 1001)))
        assertNull(active.snapshot(context))

        val cue = SyntheticSubtitleRenderPipelineObserver()
        assertTrue(cue.begin(context, preference))
        assertFalse(cue.observeCueGroup(context, 1L, 2))
        assertNull(cue.snapshot(context))
    }

    @Test fun ownerThreadAbandonAndCloseRevokeObservation() {
        val observer = SyntheticSubtitleRenderPipelineObserver()
        val first = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(first, preference))
        val foreign = AtomicReference<SyntheticSubtitleRenderPipelineObserver.Snapshot?>()
        Thread { foreign.set(observer.snapshot(first)) }.apply { start(); join() }
        assertNull(foreign.get())
        assertTrue(observer.abandon(first))
        val second = context(2L)
        assertTrue(observer.begin(second, preference))
        assertTrue(observer.close())
        assertFalse(observer.close())
        assertNull(observer.snapshot(second))
    }

    private fun context(load: Long = 1L) = SyntheticSubtitleRenderPipelineObserver.Context(
        1L,
        load,
        "local:synthetic-media:12s",
        "local:synthetic-wav"
    )

    private fun preference(enabled: Boolean, admitted: Boolean) =
        NativeSubtitlePlaybackPreferenceActuator.Preference(enabled, admitted)

    private fun evidence(
        enabled: Boolean,
        complete: Boolean,
        load: Long = 1L,
        manual: Boolean = false
    ): SyntheticSubtitlePreferenceEvidenceObserver.Snapshot {
        val status = when {
            manual -> SyntheticSubtitlePreferenceEvidenceObserver.Status.MANUAL_SELECTION_HELD
            enabled && complete ->
                SyntheticSubtitlePreferenceEvidenceObserver.Status.ENABLED_CALLBACKS_COMPLETE
            enabled ->
                SyntheticSubtitlePreferenceEvidenceObserver.Status.UNADMITTED_ENABLED_HELD
            complete -> SyntheticSubtitlePreferenceEvidenceObserver.Status.OFF_CALLBACKS_COMPLETE
            else -> SyntheticSubtitlePreferenceEvidenceObserver.Status.OFF_WAITING_COMPLETION
        }
        val admitted = enabled && (complete || manual)
        return SyntheticSubtitlePreferenceEvidenceObserver.Snapshot(
            context = SyntheticSubtitlePreferenceEvidenceObserver.Context(
                1L,
                load,
                "local:synthetic-media:12s",
                "local:synthetic-wav"
            ),
            preference = preference(enabled, admitted),
            status = status,
            callbackEvidenceComplete = complete && !manual
        )
    }

    private fun readyView() = SyntheticSubtitleRenderPipelineObserver.ViewState(
        attached = true,
        shown = true,
        width = 1280,
        height = 720,
        alphaPermille = 1000
    )

    private companion object {
        val CALLBACKS_PENDING = SyntheticSubtitleRenderPipelineObserver.Status.CALLBACKS_PENDING
        val PREFERENCE_HELD = SyntheticSubtitleRenderPipelineObserver.Status.PREFERENCE_HELD
        val VIEW_NOT_READY = SyntheticSubtitleRenderPipelineObserver.Status.VIEW_NOT_READY
        val ENABLED_WAITING = SyntheticSubtitleRenderPipelineObserver.Status.ENABLED_WAITING_DRAW
        val ENABLED_COMPLETE =
            SyntheticSubtitleRenderPipelineObserver.Status.ENABLED_DRAW_PIPELINE_OBSERVED
        val OFF_WAITING = SyntheticSubtitleRenderPipelineObserver.Status.OFF_WAITING_EMPTY_DRAW
        val OFF_COMPLETE =
            SyntheticSubtitleRenderPipelineObserver.Status.OFF_EMPTY_DRAW_PIPELINE_OBSERVED
    }
}
