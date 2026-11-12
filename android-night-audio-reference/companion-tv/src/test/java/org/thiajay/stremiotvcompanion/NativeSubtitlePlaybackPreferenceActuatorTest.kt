package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeSubtitlePlaybackPreferenceActuatorTest {
    @Test fun explicitOffClearsOnlyAutomaticSelection() {
        val fixture = Fixture(preference = preference(false), snapshot = snapshot(AUTOMATIC))
        val actuator = fixture.actuator()
        val lease = actuator.prepare(INITIAL_LOAD)

        assertNotNull(lease)
        assertEquals(CLEAR_AUTOMATIC, lease!!.action)
        assertTrue(actuator.execute(lease))
        assertEquals(listOf(Call(CLEAR_AUTOMATIC, INITIAL_LOAD, snapshot(AUTOMATIC))), fixture.calls)
        assertTrue(actuator.close())
    }

    @Test fun explicitOffPreservesManualSelectionAcrossEpisodeTransition() {
        val fixture = Fixture(preference = preference(false), snapshot = snapshot(MANUAL))
        val actuator = fixture.actuator()

        assertNull(actuator.prepare(EPISODE_TRANSITION))
        assertTrue(fixture.calls.isEmpty())
        assertTrue(actuator.close())
    }

    @Test fun explicitOffWithNoSelectedTrackNeedsNoPlayerMutation() {
        val fixture = Fixture(preference = preference(false), snapshot = snapshot(NONE))
        val actuator = fixture.actuator()

        assertNull(actuator.prepare(INITIAL_LOAD))
        assertTrue(fixture.calls.isEmpty())
        assertTrue(actuator.close())
    }

    @Test fun admittedEnabledAppliesAutomaticOnlyWhenNothingIsSelected() {
        val fixture = Fixture(preference = preference(true, admitted = true), snapshot = snapshot(NONE))
        val actuator = fixture.actuator()
        val lease = actuator.prepare(RECOVERY)

        assertNotNull(lease)
        assertEquals(APPLY_AUTOMATIC, lease!!.action)
        assertTrue(actuator.execute(lease))
        assertEquals(listOf(Call(APPLY_AUTOMATIC, RECOVERY, snapshot(NONE))), fixture.calls)
        assertTrue(actuator.close())
    }

    @Test fun admittedEnabledNeverReplacesManualSelection() {
        val fixture = Fixture(
            preference = preference(true, admitted = true),
            snapshot = snapshot(MANUAL)
        )
        val actuator = fixture.actuator()

        assertNull(actuator.prepare(RECOVERY))
        assertTrue(fixture.calls.isEmpty())
        assertTrue(actuator.close())
    }

    @Test fun unadmittedEnabledCannotApplyAutomaticSelection() {
        val none = Fixture(preference = preference(true), snapshot = snapshot(NONE))
        val noneActuator = none.actuator()
        assertNull(noneActuator.prepare(INITIAL_LOAD))
        assertTrue(noneActuator.close())

        val automatic = Fixture(preference = preference(true), snapshot = snapshot(AUTOMATIC))
        val automaticActuator = automatic.actuator()
        val lease = automaticActuator.prepare(EPISODE_TRANSITION)!!
        assertEquals(CLEAR_AUTOMATIC, lease.action)
        assertTrue(automaticActuator.execute(lease))
        assertTrue(automaticActuator.close())
    }

    @Test fun playerIdentityDriftConsumesLeaseBeforePortCall() {
        val fixture = Fixture(preference = preference(false), snapshot = snapshot(AUTOMATIC))
        val actuator = fixture.actuator()
        val lease = actuator.prepare(RECOVERY)!!

        fixture.snapshot = snapshot(AUTOMATIC).copy(loadGeneration = 8L)
        assertFalse(actuator.execute(lease))
        fixture.snapshot = snapshot(AUTOMATIC)
        assertFalse(actuator.execute(lease))
        assertTrue(fixture.calls.isEmpty())
        assertTrue(actuator.close())
    }

    @Test fun preferenceOrReceiptDriftConsumesLeaseBeforePortCall() {
        val fixture = Fixture(
            preference = preference(true, admitted = true),
            snapshot = snapshot(NONE)
        )
        val actuator = fixture.actuator()
        val lease = actuator.prepare(INITIAL_LOAD)!!

        fixture.preference = preference(true, admitted = false)
        assertFalse(actuator.execute(lease))
        fixture.preference = preference(true, admitted = true)
        assertFalse(actuator.execute(lease))
        assertTrue(fixture.calls.isEmpty())
        assertTrue(actuator.close())
    }

    @Test fun forgedCrossInstanceAndDuplicateLeasesFailClosed() {
        val first = Fixture(preference = preference(false), snapshot = snapshot(AUTOMATIC))
        val second = Fixture(preference = preference(false), snapshot = snapshot(AUTOMATIC))
        val firstActuator = first.actuator()
        val secondActuator = second.actuator()
        val firstLease = firstActuator.prepare(INITIAL_LOAD)!!
        val secondLease = secondActuator.prepare(INITIAL_LOAD)!!

        assertFalse(firstActuator.execute(firstLease.copy()))
        assertFalse(firstActuator.execute(firstLease))
        assertFalse(secondActuator.execute(firstLease))
        assertFalse(secondActuator.execute(secondLease))
        assertTrue(first.calls.isEmpty())
        assertTrue(second.calls.isEmpty())
        assertTrue(firstActuator.close())
        assertTrue(secondActuator.close())
    }

    @Test fun transitionAndUnsafePlayerStatesCannotPrepare() {
        for (unsafe in listOf(
            snapshot(AUTOMATIC).copy(ready = false),
            snapshot(AUTOMATIC).copy(released = true),
            snapshot(AUTOMATIC).copy(transitioning = true),
            snapshot(AUTOMATIC).copy(nextEpisodeRequested = true),
            snapshot(AUTOMATIC).copy(videoId = ""),
            snapshot(AUTOMATIC).copy(sourceId = "bad\u0000source")
        )) {
            val fixture = Fixture(preference = preference(false), snapshot = unsafe)
            val actuator = fixture.actuator()
            assertNull(actuator.prepare(EPISODE_TRANSITION))
            assertTrue(actuator.close())
        }
    }

    @Test fun unstableThrowingAndRejectedPortsFailClosed() {
        val fixture = Fixture(preference = preference(false), snapshot = snapshot(AUTOMATIC))
        val actuator = fixture.actuator()
        fixture.unstableSnapshot = true
        assertNull(actuator.prepare(INITIAL_LOAD))
        fixture.unstableSnapshot = false
        fixture.throwPreference = true
        assertNull(actuator.prepare(INITIAL_LOAD))
        fixture.throwPreference = false
        fixture.portResult = false
        val lease = actuator.prepare(INITIAL_LOAD)!!
        assertFalse(actuator.execute(lease))
        assertFalse(actuator.execute(lease))
        assertEquals(1, fixture.calls.size)
        assertTrue(actuator.close())
    }

    @Test fun foreignThreadCannotPrepareExecuteOrClose() {
        val fixture = Fixture(preference = preference(false), snapshot = snapshot(AUTOMATIC))
        val actuator = fixture.actuator()
        val lease = actuator.prepare(INITIAL_LOAD)!!
        val prepared = AtomicReference<NativeSubtitlePlaybackPreferenceActuator.Lease?>()
        val executed = AtomicBoolean(true)
        val closed = AtomicBoolean(true)

        Thread {
            prepared.set(actuator.prepare(RECOVERY))
            executed.set(actuator.execute(lease))
            closed.set(actuator.close())
        }.apply { start(); join() }

        assertNull(prepared.get())
        assertFalse(executed.get())
        assertFalse(closed.get())
        assertTrue(actuator.execute(lease))
        assertTrue(actuator.close())
        assertFalse(actuator.close())
    }

    private data class Call(
        val action: NativeSubtitlePlaybackPreferenceActuator.Action,
        val trigger: NativeSubtitlePlaybackPreferenceActuator.Trigger,
        val snapshot: NativeSubtitlePlaybackPreferenceActuator.Snapshot
    )

    private class Fixture(
        var preference: NativeSubtitlePlaybackPreferenceActuator.Preference,
        var snapshot: NativeSubtitlePlaybackPreferenceActuator.Snapshot
    ) {
        val calls = mutableListOf<Call>()
        var unstableSnapshot = false
        var throwPreference = false
        var portResult = true
        private var snapshotReads = 0

        fun actuator() = NativeSubtitlePlaybackPreferenceActuator(
            snapshotPort = NativeSubtitlePlaybackPreferenceActuator.SnapshotPort {
                snapshotReads += 1
                if (unstableSnapshot && snapshotReads % 2 == 0) {
                    snapshot.copy(loadGeneration = snapshot.loadGeneration + 1L)
                } else {
                    snapshot
                }
            },
            preferencePort = NativeSubtitlePlaybackPreferenceActuator.PreferencePort {
                if (throwPreference) throw IllegalStateException("refuse preference")
                preference
            },
            actionPort = NativeSubtitlePlaybackPreferenceActuator.ActionPort { action, trigger, state ->
                calls += Call(action, trigger, state)
                portResult
            }
        )
    }

    private companion object {
        val INITIAL_LOAD = NativeSubtitlePlaybackPreferenceActuator.Trigger.INITIAL_LOAD
        val EPISODE_TRANSITION =
            NativeSubtitlePlaybackPreferenceActuator.Trigger.EPISODE_TRANSITION
        val RECOVERY = NativeSubtitlePlaybackPreferenceActuator.Trigger.RECOVERY
        val NONE = NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.NONE
        val AUTOMATIC = NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.AUTOMATIC
        val MANUAL = NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.MANUAL
        val APPLY_AUTOMATIC = NativeSubtitlePlaybackPreferenceActuator.Action.APPLY_AUTOMATIC
        val CLEAR_AUTOMATIC = NativeSubtitlePlaybackPreferenceActuator.Action.CLEAR_AUTOMATIC

        fun preference(enabled: Boolean, admitted: Boolean = false) =
            NativeSubtitlePlaybackPreferenceActuator.Preference(
                enabled = enabled,
                sameInstallationReceipt = admitted
            )

        fun snapshot(origin: NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin) =
            NativeSubtitlePlaybackPreferenceActuator.Snapshot(
                playerGeneration = 3L,
                loadGeneration = 7L,
                videoId = "tt4803766:13:1",
                sourceId = "synthetic-source",
                ready = true,
                released = false,
                transitioning = false,
                nextEpisodeRequested = false,
                selectionOrigin = origin
            )
    }
}
