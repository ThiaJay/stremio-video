package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicBoolean
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Media3SubtitlePlaybackPreferenceAdapterTest {
    @Test fun explicitOffDisablesOnlyAutomaticTextSelection() {
        val fixture = Fixture(preference(false), media(textDisabled = false))
        val adapter = fixture.adapter()

        assertTrue(adapter.reconcile(PREFERENCE_CHANGE) == true)
        assertEquals(listOf(true), fixture.setCalls)
        assertTrue(fixture.media.textDisabled)
        assertTrue(adapter.close())
    }

    @Test fun admittedEnabledReenablesAutomaticTextSelection() {
        val fixture = Fixture(preference(true, admitted = true), media(textDisabled = true))
        val adapter = fixture.adapter()

        assertTrue(adapter.reconcile(INITIAL_LOAD) == true)
        assertEquals(listOf(false), fixture.setCalls)
        assertFalse(fixture.media.textDisabled)
        assertTrue(adapter.close())
    }

    @Test fun unadmittedEnabledCannotLeaveAutomaticSelectionEnabled() {
        val fixture = Fixture(preference(true), media(textDisabled = false))
        val adapter = fixture.adapter()

        assertTrue(adapter.reconcile(EPISODE_TRANSITION) == true)
        assertEquals(listOf(true), fixture.setCalls)
        assertTrue(adapter.close())
    }

    @Test fun manualOverrideAlwaysBlocksPlayerMutation() {
        val off = Fixture(preference(false), media(manual = true))
        val offAdapter = off.adapter()
        assertNull(offAdapter.reconcile(RECOVERY))
        assertTrue(off.setCalls.isEmpty())
        assertTrue(offAdapter.close())

        val enabled = Fixture(
            preference(true, admitted = true),
            media(textDisabled = true, manual = true)
        )
        val enabledAdapter = enabled.adapter()
        assertNull(enabledAdapter.reconcile(RECOVERY))
        assertTrue(enabled.setCalls.isEmpty())
        assertTrue(enabledAdapter.close())
    }

    @Test fun unchangedPolicyRequiresNoMedia3Mutation() {
        val off = Fixture(preference(false), media(textDisabled = true))
        val offAdapter = off.adapter()
        assertNull(offAdapter.reconcile(INITIAL_LOAD))
        assertTrue(off.setCalls.isEmpty())
        assertTrue(offAdapter.close())

        val enabled = Fixture(preference(true, admitted = true), media(textDisabled = false))
        val enabledAdapter = enabled.adapter()
        assertNull(enabledAdapter.reconcile(INITIAL_LOAD))
        assertTrue(enabled.setCalls.isEmpty())
        assertTrue(enabledAdapter.close())
    }

    @Test fun contextAndMediaDriftFailClosedBeforeMutation() {
        val fixture = Fixture(preference(false), media(textDisabled = false))
        fixture.unstableContext = true
        val adapter = fixture.adapter()
        assertNull(adapter.reconcile(INITIAL_LOAD))
        fixture.unstableContext = false
        fixture.unstableMedia = true
        assertNull(adapter.reconcile(INITIAL_LOAD))
        assertTrue(fixture.setCalls.isEmpty())
        assertTrue(adapter.close())
    }

    @Test fun rejectedAndConcurrentMedia3WritesAreNotAccepted() {
        val refused = Fixture(preference(false), media(textDisabled = false))
        refused.acceptWrite = false
        val refusedAdapter = refused.adapter()
        assertFalse(refusedAdapter.reconcile(PREFERENCE_CHANGE)!!)
        assertFalse(refused.media.textDisabled)
        assertTrue(refusedAdapter.close())

        val drift = Fixture(preference(false), media(textDisabled = false))
        drift.driftBeforeWrite = true
        val driftAdapter = drift.adapter()
        assertFalse(driftAdapter.reconcile(PREFERENCE_CHANGE)!!)
        assertTrue(drift.setCalls.isEmpty())
        assertTrue(driftAdapter.close())
    }

    @Test fun ownerThreadAndCloseRevokeAdapter() {
        val fixture = Fixture(preference(false), media(textDisabled = false))
        val adapter = fixture.adapter()
        val reconciled = AtomicBoolean(true)
        val closed = AtomicBoolean(true)

        Thread {
            reconciled.set(adapter.reconcile(INITIAL_LOAD) != false)
            closed.set(adapter.close())
        }.apply { start(); join() }

        assertFalse(reconciled.get())
        assertFalse(closed.get())
        assertTrue(adapter.reconcile(INITIAL_LOAD) == true)
        assertTrue(adapter.close())
        assertFalse(adapter.close())
        assertFalse(adapter.reconcile(INITIAL_LOAD)!!)
    }

    private class Fixture(
        var preference: NativeSubtitlePlaybackPreferenceActuator.Preference,
        var media: Media3SubtitlePlaybackPreferenceAdapter.MediaState
    ) {
        var context = context()
        var unstableContext = false
        var unstableMedia = false
        var acceptWrite = true
        var driftBeforeWrite = false
        val setCalls = mutableListOf<Boolean>()
        private var contextReads = 0
        private var mediaReads = 0

        fun adapter() = Media3SubtitlePlaybackPreferenceAdapter.openForTest(
            contextPort = Media3SubtitlePlaybackPreferenceAdapter.ContextPort {
                contextReads += 1
                if (unstableContext && contextReads % 2 == 0) {
                    context.copy(loadGeneration = context.loadGeneration + 1L)
                } else {
                    context
                }
            },
            preferencePort = NativeSubtitlePlaybackPreferenceActuator.PreferencePort {
                preference
            },
            mediaPort = object : Media3SubtitlePlaybackPreferenceAdapter.MediaPort {
                override fun readState(): Media3SubtitlePlaybackPreferenceAdapter.MediaState {
                    mediaReads += 1
                    return if (unstableMedia && mediaReads % 2 == 0) {
                        media.copy(ready = !media.ready)
                    } else {
                        media
                    }
                }

                override fun setTextDisabled(
                    expected: Media3SubtitlePlaybackPreferenceAdapter.MediaState,
                    disabled: Boolean
                ): Boolean {
                    if (driftBeforeWrite) {
                        media = media.copy(ready = false)
                        driftBeforeWrite = false
                    }
                    if (media != expected) return false
                    setCalls += disabled
                    if (!acceptWrite) return false
                    media = media.copy(textDisabled = disabled)
                    return true
                }
            }
        )
    }

    private companion object {
        val PREFERENCE_CHANGE = NativeSubtitlePlaybackPreferenceActuator.Trigger.PREFERENCE_CHANGE
        val INITIAL_LOAD = NativeSubtitlePlaybackPreferenceActuator.Trigger.INITIAL_LOAD
        val EPISODE_TRANSITION =
            NativeSubtitlePlaybackPreferenceActuator.Trigger.EPISODE_TRANSITION
        val RECOVERY = NativeSubtitlePlaybackPreferenceActuator.Trigger.RECOVERY

        fun preference(enabled: Boolean, admitted: Boolean = false) =
            NativeSubtitlePlaybackPreferenceActuator.Preference(enabled, admitted)

        fun media(textDisabled: Boolean = false, manual: Boolean = false) =
            Media3SubtitlePlaybackPreferenceAdapter.MediaState(
                ready = true,
                released = false,
                textDisabled = textDisabled,
                manualTextOverride = manual
            )

        fun context() = Media3SubtitlePlaybackPreferenceAdapter.Context(
            playerGeneration = 1L,
            loadGeneration = 3L,
            videoId = "local:synthetic-media:12s",
            sourceId = "local:synthetic-wav",
            transitioning = false,
            nextEpisodeRequested = false
        )
    }
}
