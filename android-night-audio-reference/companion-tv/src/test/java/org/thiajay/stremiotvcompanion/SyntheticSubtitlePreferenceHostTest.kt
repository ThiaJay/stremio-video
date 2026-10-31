package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticSubtitlePreferenceHostTest {
    @Test
    fun startsExplicitlyOffWithFixedSyntheticBinding() {
        val host = SyntheticSubtitlePreferenceHost()
        assertEquals(snapshot(false), host.readPreferenceSnapshot())
    }

    @Test
    fun enabledChoiceChangesOnlyEnabledValue() {
        val host = SyntheticSubtitlePreferenceHost()
        val transition = host.applyChoice(FIRST_RUN, ENABLED, snapshot(false))
        assertNotNull(transition)
        assertEquals(snapshot(false), transition!!.before)
        assertEquals(snapshot(true), transition.after)
        assertEquals(snapshot(true), host.readPreferenceSnapshot())
    }

    @Test
    fun explicitOffChangesOnlyEnabledValue() {
        val host = SyntheticSubtitlePreferenceHost()
        host.applyChoice(FIRST_RUN, ENABLED, snapshot(false))
        val transition = host.applyChoice(SUBTITLE_SETTINGS, OFF, snapshot(true))
        assertNotNull(transition)
        assertEquals(snapshot(true), transition!!.before)
        assertEquals(snapshot(false), transition.after)
        assertEquals(snapshot(false), host.readPreferenceSnapshot())
    }

    @Test
    fun staleExpectedSnapshotCannotApply() {
        val host = SyntheticSubtitlePreferenceHost()
        host.applyChoice(FIRST_RUN, ENABLED, snapshot(false))
        assertNull(host.applyChoice(SUBTITLE_SETTINGS, OFF, snapshot(false)))
        assertEquals(snapshot(true), host.readPreferenceSnapshot())
    }

    @Test
    fun exactIssuedRollbackRestoresBeforeSnapshot() {
        val host = SyntheticSubtitlePreferenceHost()
        val transition = host.applyChoice(FIRST_RUN, ENABLED, snapshot(false))!!
        assertTrue(host.rollbackChoice(transition))
        assertEquals(snapshot(false), host.readPreferenceSnapshot())
        assertFalse(host.rollbackChoice(transition))
    }

    @Test
    fun forgedAndCrossHostTransitionsCannotRollback() {
        val host = SyntheticSubtitlePreferenceHost()
        val transition = host.applyChoice(FIRST_RUN, ENABLED, snapshot(false))!!
        val forged = transition.copy()
        val other = SyntheticSubtitlePreferenceHost()
        val foreign = other.applyChoice(FIRST_RUN, ENABLED, snapshot(false))!!
        assertFalse(host.rollbackChoice(forged))
        assertFalse(host.rollbackChoice(foreign))
        assertEquals(snapshot(true), host.readPreferenceSnapshot())
        assertTrue(host.rollbackChoice(transition))
    }

    @Test
    fun newerChoiceRevokesOlderRollbackObject() {
        val host = SyntheticSubtitlePreferenceHost()
        val older = host.applyChoice(FIRST_RUN, ENABLED, snapshot(false))!!
        val newer = host.applyChoice(SUBTITLE_SETTINGS, OFF, snapshot(true))!!
        assertFalse(host.rollbackChoice(older))
        assertTrue(host.rollbackChoice(newer))
        assertEquals(snapshot(true), host.readPreferenceSnapshot())
    }

    @Test
    fun foreignThreadCannotReadApplyRollbackOrClose() {
        val host = SyntheticSubtitlePreferenceHost()
        val transition = host.applyChoice(FIRST_RUN, ENABLED, snapshot(false))!!
        val observed = AtomicReference<Any?>()
        val applied = AtomicReference<Any?>()
        val rolledBack = AtomicReference<Boolean>()
        val closed = AtomicReference<Boolean>()
        Thread {
            observed.set(host.readPreferenceSnapshot())
            applied.set(host.applyChoice(SUBTITLE_SETTINGS, OFF, snapshot(true)))
            rolledBack.set(host.rollbackChoice(transition))
            closed.set(host.close())
        }.apply { start(); join() }
        assertNull(observed.get())
        assertNull(applied.get())
        assertFalse(rolledBack.get())
        assertFalse(closed.get())
        assertEquals(snapshot(true), host.readPreferenceSnapshot())
    }

    @Test
    fun closeRevokesEveryFutureOperation() {
        val host = SyntheticSubtitlePreferenceHost()
        val transition = host.applyChoice(FIRST_RUN, ENABLED, snapshot(false))!!
        assertTrue(host.close())
        assertFalse(host.close())
        assertNull(host.readPreferenceSnapshot())
        assertNull(host.applyChoice(SUBTITLE_SETTINGS, OFF, snapshot(true)))
        assertFalse(host.rollbackChoice(transition))
    }

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
    }
}
