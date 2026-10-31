package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSubtitlePreferenceChoiceTransactionTest {
    @Test fun enabledApplicationCommitsOnlyAfterExactPreferenceReadback() {
        val fixture = fixture(offSnapshot(), 0x11)

        val result = fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED)

        assertTrue(result.ok)
        assertEquals(true, fixture.host.current.sessionPreference?.enabled)
        assertEquals("same-installation-setting", result.provenance?.get("kind"))
        assertEquals(result.provenance, fixture.session.loadStoredPreference())
        assertTrue(fixture.transaction.close())
    }

    @Test fun explicitOffAppliesPreferenceThenClearsOnlyReceipt() {
        val fixture = fixture(offSnapshot(), 0x12)
        assertTrue(fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED).ok)

        val result = fixture.transaction.applyAndCommitChoice(SUBTITLE_SETTINGS, OFF)

        assertTrue(result.ok)
        assertNull(result.provenance)
        assertEquals(false, fixture.host.current.sessionPreference?.enabled)
        assertNull(fixture.preferences.value(RECEIPT_KEY))
        assertEquals("12".repeat(32), fixture.preferences.value(DIGEST_KEY))
        assertTrue(fixture.transaction.close())
    }

    @Test fun alreadySelectedPreferenceMayRecordOnlyTheExplicitClick() {
        val fixture = fixture(enabledSnapshot(), 0x13)

        val result = fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED)

        assertTrue(result.ok)
        assertEquals(1, fixture.host.applyCalls)
        assertEquals(enabledSnapshot(), fixture.host.current)
        assertNotNull(fixture.preferences.value(RECEIPT_KEY))
        assertTrue(fixture.transaction.close())
    }

    @Test fun bindingMutationIsRejectedAndExactPreferenceRollbackSucceeds() {
        val fixture = fixture(offSnapshot(), 0x14)
        fixture.host.mutateBinding = true

        val result = fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED)

        assertFalse(result.ok)
        assertTrue(result.rollbackVerified)
        assertEquals(offSnapshot(), fixture.host.current)
        assertEquals(1, fixture.host.rollbackCalls)
        assertTrue(fixture.preferences.snapshot().isEmpty())
        assertTrue(fixture.transaction.close())
    }

    @Test fun receiptCommitFailureRollsBackAppliedPreference() {
        val fixture = fixture(offSnapshot(), 0x15)
        fixture.preferences.setFailCommit(true)

        val result = fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED)

        assertFalse(result.ok)
        assertTrue(result.rollbackVerified)
        assertEquals(offSnapshot(), fixture.host.current)
        assertEquals(1, fixture.host.rollbackCalls)
        assertTrue(fixture.preferences.snapshot().isEmpty())
        assertTrue(fixture.transaction.close())
    }

    @Test fun concurrentPreferenceDriftIsPreservedWithoutRollbackClaim() {
        val fixture = fixture(offSnapshot(), 0x16)
        fixture.host.driftAfterApply = true

        val result = fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED)

        assertFalse(result.ok)
        assertFalse(result.rollbackVerified)
        assertEquals("French", fixture.host.current.globalLanguage)
        assertEquals(0, fixture.host.rollbackCalls)
        assertTrue(fixture.preferences.snapshot().isEmpty())
        assertTrue(fixture.transaction.close())
    }

    @Test fun throwingApplicationPortFailsClosedBeforeReceipt() {
        val fixture = fixture(offSnapshot(), 0x17)
        fixture.host.throwOnApply = true

        val result = fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED)

        assertFalse(result.ok)
        assertTrue(result.rollbackVerified)
        assertEquals(offSnapshot(), fixture.host.current)
        assertTrue(fixture.preferences.snapshot().isEmpty())
        assertTrue(fixture.transaction.close())
    }

    @Test fun rollbackRefusalIsReportedWithoutOverwritingAppliedState() {
        val fixture = fixture(offSnapshot(), 0x18)
        fixture.preferences.setFailCommit(true)
        fixture.host.refuseRollback = true

        val result = fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED)

        assertFalse(result.ok)
        assertFalse(result.rollbackVerified)
        assertEquals(true, fixture.host.current.sessionPreference?.enabled)
        assertEquals(1, fixture.host.rollbackCalls)
        assertTrue(fixture.transaction.close())
    }

    @Test fun mismatchedTransitionCannotInventChoiceAuthority() {
        val fixture = fixture(offSnapshot(), 0x19)
        fixture.host.mismatchSurface = true

        val result = fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED)

        assertFalse(result.ok)
        assertTrue(result.rollbackVerified)
        assertEquals(offSnapshot(), fixture.host.current)
        assertTrue(fixture.preferences.snapshot().isEmpty())
        assertTrue(fixture.transaction.close())
    }

    @Test fun ownerThreadAndCloseRevokeTransaction() {
        val fixture = fixture(offSnapshot(), 0x1a)
        val foreignResult = AtomicReference<NativeSubtitlePreferenceStore.CommitResult>()
        val foreignClose = AtomicBoolean(true)

        Thread {
            foreignResult.set(fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED))
            foreignClose.set(fixture.transaction.close())
        }.apply { start(); join() }

        assertFalse(foreignResult.get().ok)
        assertFalse(foreignClose.get())
        assertEquals(0, fixture.host.applyCalls)
        assertTrue(fixture.transaction.close())
        assertFalse(fixture.transaction.applyAndCommitChoice(FIRST_RUN, ENABLED).ok)
        assertTrue(fixture.preferences.snapshot().isEmpty())
    }

    private fun fixture(
        initial: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot,
        entropyByte: Int
    ): Fixture {
        val preferences = FakeSharedPreferences()
        val host = PreferenceHost(initial)
        val snapshotPort = AndroidSubtitlePreferenceChoiceSession.SnapshotPort { host.current }
        val session = AndroidSubtitlePreferenceChoiceSession.openForTest(
            preferences,
            snapshotPort
        ) { target ->
            target.fill(entropyByte.toByte())
            true
        }!!
        val transaction = AndroidSubtitlePreferenceChoiceTransaction(
            session,
            snapshotPort,
            host
        )
        return Fixture(preferences, host, session, transaction)
    }

    private data class Fixture(
        val preferences: FakeSharedPreferences,
        val host: PreferenceHost,
        val session: AndroidSubtitlePreferenceChoiceSession,
        val transaction: AndroidSubtitlePreferenceChoiceTransaction
    )

    private class PreferenceHost(
        var current: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot
    ) : AndroidSubtitlePreferenceChoiceTransaction.ApplicationPort {
        var applyCalls = 0
        var rollbackCalls = 0
        var mutateBinding = false
        var driftAfterApply = false
        var throwOnApply = false
        var refuseRollback = false
        var mismatchSurface = false

        override fun applyChoice(
            surface: AndroidSubtitlePreferenceChoiceSession.Surface,
            choice: AndroidSubtitlePreferenceChoiceSession.Choice,
            expected: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot
        ): AndroidSubtitlePreferenceChoiceTransaction.Transition? {
            applyCalls += 1
            if (throwOnApply) throw IllegalStateException("application refused")
            if (current != expected) return null
            val enabled = choice == ENABLED
            var after = expected.copy(
                sessionPreference = expected.sessionPreference?.copy(enabled = enabled)
            )
            if (mutateBinding) after = after.copy(globalLanguage = "French")
            current = after
            if (driftAfterApply) current = after.copy(globalLanguage = "French")
            return AndroidSubtitlePreferenceChoiceTransaction.Transition(
                surface = if (mismatchSurface) SUBTITLE_SETTINGS else surface,
                choice = choice,
                before = expected,
                after = after
            )
        }

        override fun rollbackChoice(
            transition: AndroidSubtitlePreferenceChoiceTransaction.Transition
        ): Boolean {
            rollbackCalls += 1
            if (refuseRollback || current != transition.after) return false
            current = transition.before
            return true
        }
    }

    private fun enabledSnapshot() = snapshot(true)
    private fun offSnapshot() = snapshot(false)

    private fun snapshot(enabled: Boolean) =
        AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot(
            sessionPreference = NativeSubtitlePreferenceProvenance.SessionPreference(
                enabled = enabled,
                language = "English",
                source = "embedded"
            ),
            globalLanguage = "English"
        )

    private companion object {
        val FIRST_RUN = AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        val SUBTITLE_SETTINGS = AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS
        val ENABLED = AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        val OFF = AndroidSubtitlePreferenceChoiceSession.Choice.OFF
        const val DIGEST_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.installation_digest.v1"
        const val RECEIPT_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.receipt.v1"
    }
}
