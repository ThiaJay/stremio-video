package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSubtitlePreferenceChoiceSessionTest {
    @Test fun enabledChoiceRequiresExactIssuedLeaseAndStableSnapshot() {
        val preferences = FakeSharedPreferences()
        var current = enabledSnapshot()
        val session = session(preferences, 0x21) { current }!!

        val lease = session.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        )

        assertNotNull(lease)
        assertTrue(preferences.snapshot().isEmpty())
        val committed = session.commitChoice(lease)
        assertTrue(committed.ok)
        assertEquals("same-installation-setting", committed.provenance?.get("kind"))
        assertEquals("21".repeat(32), preferences.value(DIGEST_KEY))
        assertTrue((preferences.value(RECEIPT_KEY) as String).startsWith("nsp1:"))
        assertEquals(committed.provenance, session.loadStoredPreference())
        assertTrue(session.close())
    }

    @Test fun duplicatePreparationCannotMintParallelConsent() {
        val preferences = FakeSharedPreferences()
        val session = session(preferences, 0x22) { enabledSnapshot() }!!
        val first = session.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        )

        assertNotNull(first)
        assertNull(session.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        ))
        assertTrue(session.commitChoice(first).ok)
        assertFalse(session.commitChoice(first).ok)
        assertTrue(session.close())
    }

    @Test fun forgedOrCrossSessionLeaseRevokesDisplayedGeneration() {
        val firstPreferences = FakeSharedPreferences()
        val secondPreferences = FakeSharedPreferences()
        val first = session(firstPreferences, 0x31) { enabledSnapshot() }!!
        val second = session(secondPreferences, 0x32) { enabledSnapshot() }!!
        val firstLease = first.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        )!!
        val secondLease = second.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        )!!

        assertFalse(second.commitChoice(firstLease).ok)
        assertFalse(second.commitChoice(secondLease).ok)
        assertTrue(secondPreferences.snapshot().isEmpty())
        assertTrue(first.commitChoice(firstLease).ok)
        assertTrue(first.close())
        assertTrue(second.close())
    }

    @Test fun finalSnapshotDriftConsumesLeaseBeforeStorage() {
        val preferences = FakeSharedPreferences()
        var current = enabledSnapshot()
        val session = session(preferences, 0x41) { current }!!
        val lease = session.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        )!!

        current = current.copy(globalLanguage = "French")
        assertFalse(session.commitChoice(lease).ok)
        current = enabledSnapshot()
        assertFalse(session.commitChoice(lease).ok)
        assertTrue(preferences.snapshot().isEmpty())
        assertTrue(session.close())
    }

    @Test fun unstableAndThrowingSnapshotsFailClosedWithoutMutation() {
        val unstablePreferences = FakeSharedPreferences()
        var reads = 0
        val unstable = session(unstablePreferences, 0x51) {
            reads += 1
            if (reads % 2 == 1) enabledSnapshot() else enabledSnapshot().copy(globalLanguage = "French")
        }!!
        assertNull(unstable.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        ))
        assertTrue(unstablePreferences.snapshot().isEmpty())
        assertTrue(unstable.close())

        val throwingPreferences = FakeSharedPreferences()
        val throwing = session(throwingPreferences, 0x52) {
            throw IllegalStateException("refuse snapshot")
        }!!
        assertNull(throwing.loadStoredPreference())
        assertNull(throwing.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        ))
        assertTrue(throwingPreferences.snapshot().isEmpty())
        assertTrue(throwing.close())
    }

    @Test fun explicitOffRequiresPresentedOffAndClearsOnlyReceipt() {
        val preferences = FakeSharedPreferences()
        preferences.seed("unrelated", "preserved")
        var current = enabledSnapshot()
        val session = session(preferences, 0x61) { current }!!
        val enabled = session.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        )!!
        assertTrue(session.commitChoice(enabled).ok)

        current = offSnapshot()
        assertNull(session.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        ))
        val off = session.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS,
            AndroidSubtitlePreferenceChoiceSession.Choice.OFF
        )!!
        assertTrue(session.commitChoice(off).ok)
        assertEquals("61".repeat(32), preferences.value(DIGEST_KEY))
        assertNull(preferences.value(RECEIPT_KEY))
        assertEquals("preserved", preferences.value("unrelated"))
        assertTrue(session.close())
    }

    @Test fun cancellationConsumesOnlyTheExactPresentedLease() {
        val preferences = FakeSharedPreferences()
        val session = session(preferences, 0x71) { enabledSnapshot() }!!
        val lease = session.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        )!!

        assertTrue(session.cancelChoice(lease))
        assertFalse(session.cancelChoice(lease))
        assertFalse(session.commitChoice(lease).ok)
        assertTrue(preferences.snapshot().isEmpty())
        assertTrue(session.close())
    }

    @Test fun foreignThreadCannotPrepareCommitCancelOrClose() {
        val preferences = FakeSharedPreferences()
        val session = session(preferences, 0x72) { enabledSnapshot() }!!
        val lease = session.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        )!!
        val foreignPrepare = AtomicReference<AndroidSubtitlePreferenceChoiceSession.ChoiceLease?>()
        val foreignCommit = AtomicReference<NativeSubtitlePreferenceStore.CommitResult>()
        val foreignCancel = AtomicBoolean(true)
        val foreignClose = AtomicBoolean(true)

        Thread {
            foreignPrepare.set(session.prepareChoice(
                AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
                AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
            ))
            foreignCommit.set(session.commitChoice(lease))
            foreignCancel.set(session.cancelChoice(lease))
            foreignClose.set(session.close())
        }.apply { start(); join() }

        assertNull(foreignPrepare.get())
        assertFalse(foreignCommit.get().ok)
        assertFalse(foreignCancel.get())
        assertFalse(foreignClose.get())
        assertTrue(preferences.snapshot().isEmpty())
        assertTrue(session.commitChoice(lease).ok)
        assertTrue(session.close())
    }

    @Test fun closeRevokesPendingChoiceAndReleasesStorageOwner() {
        val preferences = FakeSharedPreferences()
        val first = session(preferences, 0x73) { enabledSnapshot() }!!
        val lease = first.prepareChoice(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        )!!

        assertTrue(first.close())
        assertFalse(first.commitChoice(lease).ok)
        assertFalse(first.close())
        assertTrue(preferences.snapshot().isEmpty())

        val second = session(preferences, 0x74) { enabledSnapshot() }
        assertNotNull(second)
        assertTrue(second!!.close())
    }

    private fun session(
        preferences: FakeSharedPreferences,
        entropyByte: Int,
        snapshot: () -> AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot?
    ): AndroidSubtitlePreferenceChoiceSession? =
        AndroidSubtitlePreferenceChoiceSession.openForTest(
            preferences,
            AndroidSubtitlePreferenceChoiceSession.SnapshotPort { snapshot() }
        ) { target ->
            target.fill(entropyByte.toByte())
            true
        }

    private fun enabledSnapshot() =
        AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot(
            sessionPreference = NativeSubtitlePreferenceProvenance.SessionPreference(
                enabled = true,
                language = "English",
                source = "embedded"
            ),
            globalLanguage = "English"
        )

    private fun offSnapshot() =
        AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot(
            sessionPreference = NativeSubtitlePreferenceProvenance.SessionPreference(
                enabled = false,
                language = "English",
                source = "embedded"
            ),
            globalLanguage = "English"
        )

    private companion object {
        const val DIGEST_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.installation_digest.v1"
        const val RECEIPT_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.receipt.v1"
    }
}
