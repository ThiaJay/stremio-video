package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentSyntheticSubtitlePreferenceHostTest {
    @Test fun absentStateStartsOffOnFirstRunWithoutWriting() {
        val preferences = FakeSharedPreferences().apply { seed("unrelated", "preserved") }
        val host = open(preferences, MOBILE)!!

        assertEquals(FIRST_RUN, host.presentationSurface())
        assertEquals(snapshot(false), host.readPreferenceSnapshot())
        assertEquals(mapOf("unrelated" to "preserved"), preferences.snapshot())
        assertTrue(host.close())
    }

    @Test fun enabledChoicePersistsAcrossFreshOwner() {
        val preferences = FakeSharedPreferences()
        val first = open(preferences, TV)!!
        val transition = first.applyChoice(FIRST_RUN, ENABLED, snapshot(false))

        assertNotNull(transition)
        assertEquals(true, preferences.value(TV_KEY))
        assertTrue(first.close())

        val reopened = open(preferences, TV)!!
        assertEquals(SUBTITLE_SETTINGS, reopened.presentationSurface())
        assertEquals(snapshot(true), reopened.readPreferenceSnapshot())
        assertTrue(reopened.close())
    }

    @Test fun explicitOffPersistsAcrossFreshOwner() {
        val preferences = FakeSharedPreferences().apply { seed(MOBILE_KEY, true) }
        val first = open(preferences, MOBILE)!!
        val transition = first.applyChoice(SUBTITLE_SETTINGS, OFF, snapshot(true))

        assertNotNull(transition)
        assertEquals(false, preferences.value(MOBILE_KEY))
        assertTrue(first.close())

        val reopened = open(preferences, MOBILE)!!
        assertEquals(SUBTITLE_SETTINGS, reopened.presentationSurface())
        assertEquals(snapshot(false), reopened.readPreferenceSnapshot())
        assertTrue(reopened.close())
    }

    @Test fun legacyEnabledMigratesAtomicallyToCurrentKey() {
        val preferences = FakeSharedPreferences().apply {
            seed(LEGACY_MOBILE_KEY, true)
            seed("unrelated", "preserved")
        }

        val host = open(preferences, MOBILE)!!

        assertNull(preferences.value(LEGACY_MOBILE_KEY))
        assertEquals(true, preferences.value(MOBILE_KEY))
        assertEquals("preserved", preferences.value("unrelated"))
        assertEquals(SUBTITLE_SETTINGS, host.presentationSurface())
        assertEquals(snapshot(true), host.readPreferenceSnapshot())
        assertTrue(host.close())
    }

    @Test fun legacyExplicitOffMigratesWithoutBecomingFirstRun() {
        val preferences = FakeSharedPreferences().apply {
            seed(LEGACY_TV_KEY, false)
        }

        val host = open(preferences, TV)!!

        assertNull(preferences.value(LEGACY_TV_KEY))
        assertEquals(false, preferences.value(TV_KEY))
        assertEquals(SUBTITLE_SETTINGS, host.presentationSurface())
        assertEquals(snapshot(false), host.readPreferenceSnapshot())
        assertTrue(host.close())
    }

    @Test fun currentAndLegacyCollisionFailsClosedWithoutMutation() {
        val preferences = FakeSharedPreferences().apply {
            seed(LEGACY_MOBILE_KEY, false)
            seed(MOBILE_KEY, true)
            seed("unrelated", 8)
        }
        val before = preferences.snapshot()

        assertNull(open(preferences, MOBILE))
        assertEquals(before, preferences.snapshot())
    }

    @Test fun corruptLegacyTypeFailsClosedWithoutMutation() {
        val preferences = FakeSharedPreferences().apply {
            seed(LEGACY_TV_KEY, "corrupt")
        }

        assertNull(open(preferences, TV))
        assertEquals(mapOf(LEGACY_TV_KEY to "corrupt"), preferences.snapshot())
    }

    @Test fun migrationCommitRefusalPreservesLegacyAndUnrelatedState() {
        val preferences = FakeSharedPreferences().apply {
            seed(LEGACY_MOBILE_KEY, true)
            seed("unrelated", 9)
            setFailCommit(true)
        }

        assertNull(open(preferences, MOBILE))
        assertEquals(
            mapOf(LEGACY_MOBILE_KEY to true, "unrelated" to 9),
            preferences.snapshot()
        )
    }

    @Test fun postMigrationDriftIsPreservedWithoutAcceptance() {
        val preferences = FakeSharedPreferences().apply {
            seed(LEGACY_TV_KEY, true)
            setMutateAfterCommitKey(TV_KEY)
        }

        assertNull(open(preferences, TV))
        assertEquals("__drift__", preferences.value(TV_KEY))
    }

    @Test fun mobileAndTvNamespacesRemainIndependent() {
        val preferences = FakeSharedPreferences()
        val mobile = open(preferences, MOBILE)!!
        val tv = open(preferences, TV)!!

        assertNotNull(mobile.applyChoice(FIRST_RUN, ENABLED, snapshot(false)))
        assertEquals(snapshot(true), mobile.readPreferenceSnapshot())
        assertEquals(snapshot(false), tv.readPreferenceSnapshot())
        assertEquals(setOf(MOBILE_KEY), preferences.snapshot().keys)
        assertTrue(mobile.close())
        assertTrue(tv.close())
    }

    @Test fun exactRollbackRestoresAbsentStorageShape() {
        val preferences = FakeSharedPreferences().apply { seed("unrelated", 7) }
        val host = open(preferences, TV)!!
        val transition = host.applyChoice(FIRST_RUN, ENABLED, snapshot(false))!!

        assertTrue(host.rollbackChoice(transition))
        assertEquals(mapOf("unrelated" to 7), preferences.snapshot())
        assertEquals(snapshot(false), host.readPreferenceSnapshot())
        assertTrue(host.close())
    }

    @Test fun exactRollbackRestoresStoredEnabledValue() {
        val preferences = FakeSharedPreferences().apply { seed(MOBILE_KEY, true) }
        val host = open(preferences, MOBILE)!!
        val transition = host.applyChoice(SUBTITLE_SETTINGS, OFF, snapshot(true))!!

        assertTrue(host.rollbackChoice(transition))
        assertEquals(true, preferences.value(MOBILE_KEY))
        assertEquals(snapshot(true), host.readPreferenceSnapshot())
        assertTrue(host.close())
    }

    @Test fun staleExpectedSnapshotCannotWrite() {
        val preferences = FakeSharedPreferences().apply { seed(TV_KEY, true) }
        val host = open(preferences, TV)!!

        assertNull(host.applyChoice(SUBTITLE_SETTINGS, OFF, snapshot(false)))
        assertEquals(true, preferences.value(TV_KEY))
        assertTrue(host.close())
    }

    @Test fun forgedAndCrossNamespaceTransitionsCannotRollback() {
        val preferences = FakeSharedPreferences()
        val mobile = open(preferences, MOBILE)!!
        val tv = open(preferences, TV)!!
        val mobileTransition = mobile.applyChoice(FIRST_RUN, ENABLED, snapshot(false))!!
        val tvTransition = tv.applyChoice(FIRST_RUN, ENABLED, snapshot(false))!!

        assertFalse(mobile.rollbackChoice(mobileTransition.copy()))
        assertFalse(mobile.rollbackChoice(tvTransition))
        assertEquals(true, preferences.value(MOBILE_KEY))
        assertTrue(mobile.rollbackChoice(mobileTransition))
        assertTrue(tv.rollbackChoice(tvTransition))
        assertTrue(mobile.close())
        assertTrue(tv.close())
    }

    @Test fun commitRefusalPreservesPriorStateAndUnrelatedKeys() {
        val preferences = FakeSharedPreferences().apply {
            seed("unrelated", "preserved")
            setFailCommit(true)
        }
        val host = open(preferences, MOBILE)!!

        assertNull(host.applyChoice(FIRST_RUN, ENABLED, snapshot(false)))
        assertEquals(mapOf("unrelated" to "preserved"), preferences.snapshot())
        assertEquals(snapshot(false), host.readPreferenceSnapshot())
        assertTrue(host.close())
    }

    @Test fun corruptTypeFailsClosedWithoutOverwrite() {
        val preferences = FakeSharedPreferences().apply { seed(TV_KEY, "corrupt") }

        assertNull(open(preferences, TV))
        assertEquals("corrupt", preferences.value(TV_KEY))
    }

    @Test fun postCommitDriftIsPreservedAndCannotBeRolledBack() {
        val preferences = FakeSharedPreferences().apply { setMutateAfterCommitKey(TV_KEY) }
        val host = open(preferences, TV)!!

        assertNull(host.applyChoice(FIRST_RUN, ENABLED, snapshot(false)))
        assertEquals("__drift__", preferences.value(TV_KEY))
        assertNull(host.readPreferenceSnapshot())
        assertTrue(host.close())
    }

    @Test fun acceptedTransactionPersistsStateAndInstallationReceipt() {
        val statePreferences = FakeSharedPreferences()
        val receiptPreferences = FakeSharedPreferences()
        val host = open(statePreferences, TV)!!
        val session = AndroidSubtitlePreferenceChoiceSession.openForTest(
            receiptPreferences,
            host
        ) { target ->
            target.fill(0x55)
            true
        }!!
        val transaction = AndroidSubtitlePreferenceChoiceTransaction(session, host, host)

        val result = transaction.applyAndCommitChoice(FIRST_RUN, ENABLED)

        assertTrue(result.ok)
        assertEquals(true, statePreferences.value(TV_KEY))
        assertNotNull(receiptPreferences.value(RECEIPT_KEY))
        assertEquals(result.provenance, session.loadStoredPreference())
        assertTrue(transaction.close())
        assertTrue(host.close())
    }

    @Test fun receiptCommitFailureRestoresExactAbsentState() {
        val statePreferences = FakeSharedPreferences().apply { seed("unrelated", 9) }
        val receiptPreferences = FakeSharedPreferences().apply { setFailCommit(true) }
        val host = open(statePreferences, MOBILE)!!
        val session = AndroidSubtitlePreferenceChoiceSession.openForTest(
            receiptPreferences,
            host
        ) { target ->
            target.fill(0x56)
            true
        }!!
        val transaction = AndroidSubtitlePreferenceChoiceTransaction(session, host, host)

        val result = transaction.applyAndCommitChoice(FIRST_RUN, ENABLED)

        assertFalse(result.ok)
        assertTrue(result.rollbackVerified)
        assertEquals(mapOf("unrelated" to 9), statePreferences.snapshot())
        assertEquals(snapshot(false), host.readPreferenceSnapshot())
        assertTrue(receiptPreferences.snapshot().isEmpty())
        assertTrue(transaction.close())
        assertTrue(host.close())
    }

    @Test fun ownerThreadCloseAndNamespaceLeaseFailClosed() {
        val preferences = FakeSharedPreferences()
        val host = open(preferences, MOBILE)!!
        assertNull(open(preferences, MOBILE))
        val observed = AtomicReference<Any?>()
        val closed = AtomicReference<Boolean>()

        Thread {
            observed.set(host.readPreferenceSnapshot())
            closed.set(host.close())
        }.apply { start(); join() }

        assertNull(observed.get())
        assertFalse(closed.get())
        assertNull(open(preferences, MOBILE))
        assertTrue(host.close())
        val reopened = open(preferences, MOBILE)
        assertNotNull(reopened)
        assertTrue(reopened!!.close())
    }

    private fun open(
        preferences: FakeSharedPreferences,
        namespace: PersistentSyntheticSubtitlePreferenceHost.Namespace
    ) = PersistentSyntheticSubtitlePreferenceHost.open(preferences, namespace)

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
        val MOBILE = PersistentSyntheticSubtitlePreferenceHost.Namespace.MOBILE
        val TV = PersistentSyntheticSubtitlePreferenceHost.Namespace.TV
        val FIRST_RUN = AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        val SUBTITLE_SETTINGS = AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS
        val ENABLED = AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        val OFF = AndroidSubtitlePreferenceChoiceSession.Choice.OFF
        const val LEGACY_MOBILE_KEY =
            "org.thiajay.stremiotvcompanion.synthetic.mobile.subtitle.enabled.v1"
        const val MOBILE_KEY =
            "org.thiajay.stremiotvcompanion.synthetic.mobile.subtitle.enabled.v2"
        const val LEGACY_TV_KEY =
            "org.thiajay.stremiotvcompanion.synthetic.tv.subtitle.enabled.v1"
        const val TV_KEY =
            "org.thiajay.stremiotvcompanion.synthetic.tv.subtitle.enabled.v2"
        const val RECEIPT_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.receipt.v1"
    }
}
