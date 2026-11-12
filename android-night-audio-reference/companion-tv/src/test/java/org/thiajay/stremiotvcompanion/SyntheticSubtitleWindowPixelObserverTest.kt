package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SyntheticSubtitleWindowPixelObserverTest {
    @Test fun admittedEnabledAcceptsOnlyBoundedLowerBandWindowPixels() {
        val observer = SyntheticSubtitleWindowPixelObserver()
        val context = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observe(context, render(context, preference, true), 1L, 32, 32, enabledFrame()))
        val snapshot = observer.snapshot(context)!!
        assertEquals(ENABLED, snapshot.status)
        assertTrue(snapshot.windowPixelEvidenceComplete)
        assertEquals(20, snapshot.metrics!!.lowerNeutralBrightPixels)
        assertEquals(0, snapshot.metrics!!.upperNeutralBrightPixels)
        assertFalse(snapshot.visibleRenderingAccepted)
        assertFalse(snapshot.physicalDisplayAccepted)
        assertFalse(snapshot.deviceAcceptance)
    }

    @Test fun explicitOffAcceptsOnlyOpaqueDarkWindowFrame() {
        val observer = SyntheticSubtitleWindowPixelObserver()
        val context = context()
        val preference = preference(false, false)
        assertTrue(observer.begin(context, preference))
        assertTrue(observer.observe(context, render(context, preference, true), 1L, 32, 32, darkFrame()))
        val snapshot = observer.snapshot(context)!!
        assertEquals(OFF, snapshot.status)
        assertTrue(snapshot.windowPixelEvidenceComplete)
        assertEquals(0, snapshot.metrics!!.lowerNeutralBrightPixels)
        assertEquals(0, snapshot.metrics!!.upperNeutralBrightPixels)
    }

    @Test fun enabledFrameWithoutEnoughLowerPixelsFailsClosed() {
        val observer = SyntheticSubtitleWindowPixelObserver()
        val context = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(context, preference))
        assertFalse(observer.observe(context, render(context, preference, true), 1L, 32, 32, brightFrame(8, false)))
        assertNull(observer.snapshot(context))
    }

    @Test fun enabledFrameDominatedByUpperPixelsFailsClosed() {
        val observer = SyntheticSubtitleWindowPixelObserver()
        val context = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(context, preference))
        assertFalse(observer.observe(context, render(context, preference, true), 1L, 32, 32, brightFrame(20, true)))
        assertNull(observer.snapshot(context))
    }

    @Test fun transparentOrBrightOffFrameFailsClosed() {
        val transparent = SyntheticSubtitleWindowPixelObserver()
        val first = context()
        val off = preference(false, false)
        assertTrue(transparent.begin(first, off))
        val transparentFrame = darkFrame().also { it[0] = 0x00000000 }
        assertFalse(transparent.observe(first, render(first, off, true), 1L, 32, 32, transparentFrame))
        assertNull(transparent.snapshot(first))

        val bright = SyntheticSubtitleWindowPixelObserver()
        val second = context(2L)
        assertTrue(bright.begin(second, off))
        assertFalse(bright.observe(second, render(second, off, true), 1L, 32, 32, brightFrame(5, false)))
        assertNull(bright.snapshot(second))
    }

    @Test fun malformedDimensionsAndPixelCountNeverPromote() {
        val preference = preference(true, true)
        val small = SyntheticSubtitleWindowPixelObserver()
        val first = context()
        assertTrue(small.begin(first, preference))
        assertFalse(small.observe(first, render(first, preference, true), 1L, 15, 32, IntArray(480)))
        assertNull(small.snapshot(first))

        val mismatch = SyntheticSubtitleWindowPixelObserver()
        val second = context(2L)
        assertTrue(mismatch.begin(second, preference))
        assertFalse(mismatch.observe(second, render(second, preference, true), 1L, 32, 32, IntArray(1023)))
        assertNull(mismatch.snapshot(second))
    }

    @Test fun incompleteOrVisuallyPromotedRenderEvidenceIsRejected() {
        val preference = preference(true, true)
        val pending = SyntheticSubtitleWindowPixelObserver()
        val first = context()
        assertTrue(pending.begin(first, preference))
        assertFalse(pending.observe(first, render(first, preference, false), 1L, 32, 32, enabledFrame()))
        assertNull(pending.snapshot(first))

        val promoted = SyntheticSubtitleWindowPixelObserver()
        val second = context(2L)
        assertTrue(promoted.begin(second, preference))
        val evidence = render(second, preference, true).copy(visibleRenderingAccepted = true)
        assertFalse(promoted.observe(second, evidence, 1L, 32, 32, enabledFrame()))
        assertNull(promoted.snapshot(second))
    }

    @Test fun revisionContextAndPreferenceDriftFailClosed() {
        val preference = preference(true, true)
        val revision = SyntheticSubtitleWindowPixelObserver()
        val first = context()
        assertTrue(revision.begin(first, preference))
        assertFalse(revision.observe(first, render(first, preference, true), 2L, 32, 32, enabledFrame()))
        assertNull(revision.snapshot(first))

        val contextDrift = SyntheticSubtitleWindowPixelObserver()
        val second = context(2L)
        assertTrue(contextDrift.begin(second, preference))
        assertFalse(contextDrift.observe(second, render(context(3L), preference, true), 1L, 32, 32, enabledFrame()))
        assertNull(contextDrift.snapshot(second))

        val preferenceDrift = SyntheticSubtitleWindowPixelObserver()
        val third = context(3L)
        assertTrue(preferenceDrift.begin(third, preference))
        assertFalse(preferenceDrift.observe(third, render(third, preference(false, false), true), 1L, 32, 32, enabledFrame()))
        assertNull(preferenceDrift.snapshot(third))
    }

    @Test fun generationThreadAbandonAndCloseRevokePixelObservation() {
        val observer = SyntheticSubtitleWindowPixelObserver()
        val first = context()
        val preference = preference(true, true)
        assertTrue(observer.begin(first, preference))
        val foreign = AtomicReference<SyntheticSubtitleWindowPixelObserver.Snapshot?>()
        Thread { foreign.set(observer.snapshot(first)) }.apply { start(); join() }
        assertNull(foreign.get())
        assertTrue(observer.abandon(first))
        val second = context(2L)
        assertTrue(observer.begin(second, preference))
        assertTrue(observer.close())
        assertFalse(observer.close())
        assertNull(observer.snapshot(second))
    }

    private fun context(load: Long = 1L) = SyntheticSubtitleWindowPixelObserver.Context(
        1L,
        load,
        "local:synthetic-media:12s",
        "local:synthetic-wav"
    )

    private fun preference(enabled: Boolean, admitted: Boolean) =
        NativeSubtitlePlaybackPreferenceActuator.Preference(enabled, admitted)

    private fun render(
        context: SyntheticSubtitleWindowPixelObserver.Context,
        preference: NativeSubtitlePlaybackPreferenceActuator.Preference,
        complete: Boolean
    ) = SyntheticSubtitleRenderPipelineObserver.Snapshot(
        context = SyntheticSubtitleRenderPipelineObserver.Context(
            context.playerGeneration,
            context.loadGeneration,
            context.videoId,
            context.sourceId
        ),
        preference = preference,
        status = if (preference.enabled) ENABLED_DRAW else OFF_DRAW,
        cueRevision = 1L,
        drawRevision = 1L,
        renderPipelineEvidenceComplete = complete
    )

    private fun darkFrame() = IntArray(32 * 32) { OPAQUE_BLACK }

    private fun enabledFrame() = brightFrame(20, false)

    private fun brightFrame(count: Int, upper: Boolean): IntArray {
        val pixels = darkFrame()
        val startRow = if (upper) 2 else 24
        repeat(count) { index ->
            val row = startRow + index / 10
            val column = 8 + index % 10
            pixels[row * 32 + column] = OPAQUE_WHITE
        }
        return pixels
    }

    private companion object {
        const val OPAQUE_BLACK = -0x1000000
        const val OPAQUE_WHITE = -0x1
        val ENABLED =
            SyntheticSubtitleWindowPixelObserver.Status.ENABLED_WINDOW_PIXELS_OBSERVED
        val OFF = SyntheticSubtitleWindowPixelObserver.Status.OFF_DARK_WINDOW_OBSERVED
        val ENABLED_DRAW =
            SyntheticSubtitleRenderPipelineObserver.Status.ENABLED_DRAW_PIPELINE_OBSERVED
        val OFF_DRAW =
            SyntheticSubtitleRenderPipelineObserver.Status.OFF_EMPTY_DRAW_PIPELINE_OBSERVED
    }
}
