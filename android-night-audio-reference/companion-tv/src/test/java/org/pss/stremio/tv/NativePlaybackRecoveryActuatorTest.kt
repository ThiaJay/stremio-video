package org.pss.stremio.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NativePlaybackRecoveryActuatorTest {
    private class FakePort(
        var snapshot: NativeRecoverySnapshot,
        var accept: Boolean = true
    ) : NativePlaybackRecoveryPort {
        val actions = ArrayList<String>()

        override fun currentSnapshot(): NativeRecoverySnapshot = snapshot

        override fun executeRecovery(action: String): Boolean {
            actions += action
            return accept
        }
    }

    private fun snapshot(
        context: String = "video-1:source-1:generation-1",
        videoId: String = "video-1",
        sourceId: String = "source-1",
        generation: Long = 1,
        watched: String = "a".repeat(64),
        resume: String = "b".repeat(64)
    ) = NativeRecoverySnapshot(
        recoveryContextId = context,
        videoId = videoId,
        sourceId = sourceId,
        loadGeneration = generation,
        watchedStateFingerprint = watched,
        resumeStateFingerprint = resume,
        loaded = true
    )

    private fun classification(
        category: String = "playback_health",
        action: String = "delegate_to_bounded_playback_recovery",
        retryAllowed: Boolean = false,
        attempts: Int = 0
    ) = ClassifiedStreamFailure(
        contractVersion = 1,
        category = category,
        action = action,
        preserveEpisode = true,
        preserveWatchedState = true,
        preserveProviderConfiguration = true,
        preserveStreamIdentity = true,
        rawLogExportAllowed = false,
        retryAllowed = retryAllowed,
        maxAdditionalAttempts = attempts
    )

    private fun observation(
        history: List<String>? = emptyList(),
        context: String = "video-1:source-1:generation-1"
    ) = PlaybackHealthObservation(
        audioExpected = true,
        audioPresent = false,
        videoStable = true,
        canTranscodeAudio = true,
        canSwitchEngine = true,
        attemptedRecoveries = history,
        recoveryContextId = context,
        currentContextId = context
    )

    @Test fun exactPlaybackPlanExecutesOnlyOnce() {
        val port = FakePort(snapshot())
        val actuator = NativePlaybackRecoveryActuator(port)
        val prepared = actuator.prepare(classification(), observation())
        assertNotNull(prepared)
        assertEquals(NativeRecoveryExecutionOutcome.APPLIED, actuator.execute(prepared!!))
        assertEquals(NativeRecoveryExecutionOutcome.ALREADY_CONSUMED, actuator.execute(prepared))
        assertEquals(listOf("transcodeAudio"), port.actions)
    }

    @Test fun terminalAndClassifierPlansNeverPrepare() {
        val actuator = NativePlaybackRecoveryActuator(FakePort(snapshot()))
        assertNull(actuator.prepare(
            classification("matroska_parser", "stop_with_actionable_container_error"),
            observation()
        ))
        assertNull(actuator.prepare(
            classification("upstream_http", "retry_same_source", true, 1),
            observation()
        ))
    }

    @Test fun finalLeaseRejectsVideoSourceOrGenerationDrift() {
        val port = FakePort(snapshot())
        val actuator = NativePlaybackRecoveryActuator(port)
        val prepared = actuator.prepare(classification(), observation())!!
        for (drifted in listOf(
            snapshot(videoId = "video-2"),
            snapshot(sourceId = "source-2"),
            snapshot(generation = 2)
        )) {
            port.snapshot = drifted
            assertEquals(NativeRecoveryExecutionOutcome.REFUSED, actuator.execute(prepared))
        }
        assertEquals(emptyList<String>(), port.actions)
    }

    @Test fun finalLeaseRejectsWatchedOrResumeFingerprintDrift() {
        val port = FakePort(snapshot())
        val actuator = NativePlaybackRecoveryActuator(port)
        val prepared = actuator.prepare(classification(), observation())!!
        port.snapshot = snapshot(watched = "c".repeat(64))
        assertEquals(NativeRecoveryExecutionOutcome.REFUSED, actuator.execute(prepared))
        port.snapshot = snapshot(resume = "d".repeat(64))
        assertEquals(NativeRecoveryExecutionOutcome.REFUSED, actuator.execute(prepared))
        assertEquals(emptyList<String>(), port.actions)
    }

    @Test fun transitionReleaseOrNextEpisodeStateRejects() {
        val states = listOf(
            snapshot().copy(switchingSource = true),
            snapshot().copy(playerReleased = true),
            snapshot().copy(nextEpisodePopupOpen = true),
            snapshot().copy(loaded = false)
        )
        for (state in states) {
            val port = FakePort(snapshot())
            val actuator = NativePlaybackRecoveryActuator(port)
            val prepared = actuator.prepare(classification(), observation())!!
            port.snapshot = state
            assertEquals(NativeRecoveryExecutionOutcome.REFUSED, actuator.execute(prepared))
            assertEquals(emptyList<String>(), port.actions)
        }
    }

    @Test fun rejectedPortExecutionStillConsumesLease() {
        val port = FakePort(snapshot(), accept = false)
        val actuator = NativePlaybackRecoveryActuator(port)
        val prepared = actuator.prepare(classification(), observation())!!
        assertEquals(
            NativeRecoveryExecutionOutcome.EXECUTION_REJECTED,
            actuator.execute(prepared)
        )
        port.accept = true
        assertEquals(
            NativeRecoveryExecutionOutcome.ALREADY_CONSUMED,
            actuator.execute(prepared)
        )
        assertEquals(listOf("transcodeAudio"), port.actions)
    }

    @Test fun malformedSnapshotAndContextMismatchFailPreparation() {
        val invalid = listOf(
            snapshot(watched = "A".repeat(64)),
            snapshot(resume = "short"),
            snapshot(videoId = "video 1"),
            snapshot(generation = -1),
            snapshot(context = "video-2:source-1:generation-1")
        )
        for (state in invalid) {
            val actuator = NativePlaybackRecoveryActuator(FakePort(state))
            assertNull(actuator.prepare(classification(), observation()))
        }
    }

    @Test fun forgedOrWeakenedPlanCannotExecute() {
        val port = FakePort(snapshot())
        val actuator = NativePlaybackRecoveryActuator(port)
        val forged = PreparedNativeRecovery(
            plan = NativeRecoveryPlan(
                authority = "playback_health",
                action = "transcodeAudio",
                playbackRecovery = "transcodeAudio",
                preserveWatchedState = false
            ),
            lease = snapshot(),
            token = "forged"
        )
        assertEquals(NativeRecoveryExecutionOutcome.REFUSED, actuator.execute(forged))
        assertEquals(emptyList<String>(), port.actions)
    }

    @Test fun preparedLeaseCannotCrossActuatorInstanceOrBeReissued() {
        val firstPort = FakePort(snapshot())
        val first = NativePlaybackRecoveryActuator(firstPort)
        val prepared = first.prepare(classification(), observation())!!
        assertNull(first.prepare(classification(), observation()))

        val secondPort = FakePort(snapshot())
        val second = NativePlaybackRecoveryActuator(secondPort)
        assertEquals(NativeRecoveryExecutionOutcome.REFUSED, second.execute(prepared))
        assertEquals(emptyList<String>(), secondPort.actions)

        assertEquals(NativeRecoveryExecutionOutcome.APPLIED, first.execute(prepared))
        assertEquals(listOf("transcodeAudio"), firstPort.actions)
    }

    @Test fun preparationAndExecutionStayOnConstructionThread() {
        val port = FakePort(snapshot())
        val actuator = NativePlaybackRecoveryActuator(port)
        val foreignPreparation = AtomicReference<PreparedNativeRecovery?>()
        Thread {
            foreignPreparation.set(actuator.prepare(classification(), observation()))
        }.apply {
            start()
            join()
        }
        assertNull(foreignPreparation.get())

        val prepared = actuator.prepare(classification(), observation())!!
        val foreignOutcome = AtomicReference<NativeRecoveryExecutionOutcome?>()
        Thread {
            foreignOutcome.set(actuator.execute(prepared))
        }.apply {
            start()
            join()
        }
        assertEquals(NativeRecoveryExecutionOutcome.REFUSED, foreignOutcome.get())
        assertEquals(emptyList<String>(), port.actions)

        assertEquals(NativeRecoveryExecutionOutcome.APPLIED, actuator.execute(prepared))
        assertEquals(listOf("transcodeAudio"), port.actions)
    }

    @Test fun profileSevenConversionUsesSameOneShotLease() {
        val port = FakePort(snapshot())
        val actuator = NativePlaybackRecoveryActuator(port)
        val profileSeven = PlaybackHealthObservation(
            audioExpected = true,
            audioPresent = true,
            videoStable = false,
            videoFramePresent = false,
            startupTimedOut = true,
            dolbyVisionProfile = 7,
            canConvertDolbyVisionProfile7 = true,
            attemptedRecoveries = emptyList(),
            recoveryContextId = snapshot().recoveryContextId,
            currentContextId = snapshot().recoveryContextId
        )
        val prepared = actuator.prepare(classification(), profileSeven)!!
        assertEquals(
            NativeRecoveryExecutionOutcome.APPLIED,
            actuator.execute(prepared)
        )
        assertEquals(listOf("convertDolbyVisionProfile7To8_1"), port.actions)
    }

    @Test fun prepareRequiresExactDelegationAndExplicitLedger() {
        val actuator = NativePlaybackRecoveryActuator(FakePort(snapshot()))
        assertNull(actuator.prepare(classification(), observation(history = null)))
        assertNull(actuator.prepare(
            classification(action = "delegate_to_unbounded_recovery"),
            observation()
        ))
    }
}
