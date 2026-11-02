package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.*
import org.junit.Test

class SyntheticSubtitleWindowPixelCaptureLeaseTest {
    @Test fun exactCurrentLeaseCompletesOnce() {
        val gate = SyntheticSubtitleWindowPixelCaptureLease()
        val context = context()
        val lease = gate.begin(context)!!
        assertTrue(gate.pending(context))
        assertEquals(SUCCESS, gate.resolve(lease, context, true))
        assertFalse(gate.pending(context))
        assertEquals(STALE, gate.resolve(lease, context, true))
    }

    @Test fun currentCopyFailureClosesOnlyThatAttempt() {
        val gate = SyntheticSubtitleWindowPixelCaptureLease()
        val context = context()
        val lease = gate.begin(context)!!
        assertEquals(FAILURE, gate.resolve(lease, context, false))
        assertFalse(gate.pending(context))
    }

    @Test fun duplicateCaptureForSameCueRevisionIsDenied() {
        val gate = SyntheticSubtitleWindowPixelCaptureLease()
        val context = context()
        val lease = gate.begin(context)!!
        assertNull(gate.begin(context))
        assertEquals(SUCCESS, gate.resolve(lease, context, true))
        assertNull(gate.begin(context))
    }

    @Test fun newerLoadSupersedesWithoutLateCallbackClearingIt() {
        val gate = SyntheticSubtitleWindowPixelCaptureLease()
        val oldContext = context(load = 1L)
        val oldLease = gate.begin(oldContext)!!
        val newContext = context(load = 2L)
        val newLease = gate.begin(newContext)!!
        assertEquals(STALE, gate.resolve(oldLease, oldContext, true))
        assertTrue(gate.pending(newContext))
        assertEquals(SUCCESS, gate.resolve(newLease, newContext, true))
    }

    @Test fun olderLoadCannotReplaceNewerPendingCapture() {
        val gate = SyntheticSubtitleWindowPixelCaptureLease()
        val current = context(load = 2L)
        assertNotNull(gate.begin(current))
        assertNull(gate.begin(context(load = 1L, revision = 2L)))
        assertTrue(gate.pending(current))
    }

    @Test fun laterCueRevisionMayStartAfterResolvedAttempt() {
        val gate = SyntheticSubtitleWindowPixelCaptureLease()
        val first = context(revision = 1L)
        val lease = gate.begin(first)!!
        assertEquals(SUCCESS, gate.resolve(lease, first, true))
        val second = context(revision = 2L)
        assertNotNull(gate.begin(second))
        assertTrue(gate.pending(second))
    }

    @Test fun currentIdentityOrRevisionDriftFailsCurrentLease() {
        val gate = SyntheticSubtitleWindowPixelCaptureLease()
        val context = context()
        val lease = gate.begin(context)!!
        assertEquals(FAILURE, gate.resolve(lease, context(revision = 2L), true))
        assertFalse(gate.pending(context))
    }

    @Test fun malformedContextNeverCreatesLease() {
        val gate = SyntheticSubtitleWindowPixelCaptureLease()
        assertNull(gate.begin(context().copy(loadGeneration = 0L)))
        assertNull(gate.begin(context().copy(videoId = "")))
        assertNull(gate.begin(context().copy(cueRevision = 0L)))
    }

    @Test fun foreignThreadCannotResolveOrClearCurrentLease() {
        val gate = SyntheticSubtitleWindowPixelCaptureLease()
        val context = context()
        val lease = gate.begin(context)!!
        val result = AtomicReference<SyntheticSubtitleWindowPixelCaptureLease.Resolution>()
        Thread { result.set(gate.resolve(lease, context, true)) }.apply { start(); join() }
        assertEquals(STALE, result.get())
        assertTrue(gate.pending(context))
    }

    @Test fun exactAbandonAndCloseRevokePendingCapture() {
        val gate = SyntheticSubtitleWindowPixelCaptureLease()
        val context = context()
        assertNotNull(gate.begin(context))
        assertFalse(gate.abandon(context(revision = 2L)))
        assertTrue(gate.abandon(context))
        val later = context(load = 2L)
        assertNotNull(gate.begin(later))
        assertTrue(gate.close())
        assertFalse(gate.close())
        assertFalse(gate.pending(later))
        assertNull(gate.begin(context(load = 3L)))
    }

    private fun context(load: Long = 1L, revision: Long = 1L) =
        SyntheticSubtitleWindowPixelCaptureLease.Context(
            playerGeneration = 1L,
            loadGeneration = load,
            videoId = "local:synthetic-media:12s",
            sourceId = "local:synthetic-wav",
            cueRevision = revision
        )

    private companion object {
        val SUCCESS =
            SyntheticSubtitleWindowPixelCaptureLease.Resolution.CURRENT_SUCCESS
        val FAILURE =
            SyntheticSubtitleWindowPixelCaptureLease.Resolution.CURRENT_FAILURE
        val STALE =
            SyntheticSubtitleWindowPixelCaptureLease.Resolution.STALE_IGNORED
    }
}
