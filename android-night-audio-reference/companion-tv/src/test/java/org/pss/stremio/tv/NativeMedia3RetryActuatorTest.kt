package org.pss.stremio.tv

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NativeMedia3RetryActuatorTest {
    private class Port(
        var snapshot: NativeMedia3RetrySnapshot = snapshot(),
        var result: Boolean = true
    ) : NativeMedia3RetryPort {
        val actions = mutableListOf<String>()
        var throwSnapshot = false
        var throwExecution = false

        override fun currentRetrySnapshot(): NativeMedia3RetrySnapshot {
            if (throwSnapshot) error("snapshot unavailable")
            return snapshot
        }

        override fun executeRetry(action: String): Boolean {
            actions += action
            if (throwExecution) error("retry unavailable")
            return result
        }
    }

    private fun actuator(
        port: Port,
        budget: NativeMedia3RetryBudget = episodeBudget(port.snapshot)
    ) =
        NativeMedia3RetryActuator(port, budget)

    private fun episodeBudget(value: NativeMedia3RetrySnapshot = snapshot()) =
        NativeMedia3RetryBudget.open(value.episodeSessionId, value.videoId)!!

    @Test fun firstTransportFailureExecutesOnceAndReservesEpisodeBudget() {
        val port = Port()
        val actuator = actuator(port)
        val prepared = actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertNotNull(prepared)
        assertEquals("stream_classifier", prepared!!.plan.authority)
        assertEquals("retry_same_transport_route", prepared.plan.action)
        assertEquals(NativeMedia3RetryOutcome.APPLIED, actuator.execute(prepared))
        assertEquals(listOf("retry_same_transport_route"), port.actions)
        assertEquals(NativeMedia3RetryOutcome.ALREADY_CONSUMED, actuator.execute(prepared))
    }

    @Test fun repeatedPreparationCannotMintAnotherAttemptBeforeOrAfterExecution() {
        val port = Port()
        val actuator = actuator(port)
        val prepared = actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertNotNull(prepared)
        assertNull(actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(NativeMedia3RetryOutcome.APPLIED, actuator.execute(prepared!!))
        assertNull(actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    @Test fun rejectedPortExecutionStillConsumesSameEpisodeBudget() {
        val port = Port(result = false)
        val actuator = actuator(port)
        val prepared = actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)!!
        assertEquals(NativeMedia3RetryOutcome.EXECUTION_REJECTED, actuator.execute(prepared))
        assertEquals(NativeMedia3RetryOutcome.ALREADY_CONSUMED, actuator.execute(prepared))
        assertNull(actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertEquals(1, port.actions.size)
    }

    @Test fun finalSnapshotDriftRefusesAndBudgetStaysReserved() {
        val port = Port()
        val actuator = actuator(port)
        val prepared = actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)!!
        port.snapshot = port.snapshot.copy(loadGeneration = 2)
        assertEquals(NativeMedia3RetryOutcome.REFUSED, actuator.execute(prepared))
        assertTrue(port.actions.isEmpty())
        assertNull(actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    @Test fun episodeBudgetCannotResetThroughGenerationOrSourceChange() {
        val port = Port()
        val actuator = actuator(port)
        assertNotNull(actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        port.snapshot = port.snapshot.copy(sourceId = "source-b", loadGeneration = 9)
        assertNull(actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    @Test fun terminalAndUnknownMedia3CodesNeverPrepare() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED,
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            PlaybackException.ERROR_CODE_UNSPECIFIED
        )) {
            assertNull(actuator(Port()).prepare(code))
        }
    }

    @Test fun episodeAdvanceAndInvalidStateFailClosed() {
        val code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        assertNull(actuator(Port(snapshot = snapshot().copy(episodeAdvanced = true))).prepare(code))
        assertNull(actuator(Port(snapshot = snapshot().copy(userRequestedNextEpisode = true))).prepare(code))
        assertNull(actuator(Port(snapshot = snapshot().copy(backendSwitchCount = 2))).prepare(code))
        assertNull(actuator(Port(snapshot = snapshot().copy(switchingSource = true))).prepare(code))
        assertNull(actuator(Port(snapshot = snapshot().copy(sourceId = "bad source"))).prepare(code))
    }

    @Test fun preparedLeaseCannotCrossActuatorInstance() {
        val port = Port()
        val first = actuator(port)
        val prepared = first.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)!!
        val second = actuator(port)
        assertEquals(NativeMedia3RetryOutcome.REFUSED, second.execute(prepared))
        assertTrue(port.actions.isEmpty())
    }

    @Test fun ownerThreadAndCloseRevokeOutstandingLeaseAndFutureUse() {
        val port = Port()
        val actuator = actuator(port)
        val prepared = actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)!!
        val foreign = AtomicReference<NativeMedia3RetryOutcome>()
        val foreignClose = AtomicReference<Boolean>()
        Thread {
            foreign.set(actuator.execute(prepared))
            foreignClose.set(actuator.close())
        }.apply { start(); join() }
        assertEquals(NativeMedia3RetryOutcome.REFUSED, foreign.get())
        assertFalse(foreignClose.get())
        assertTrue(actuator.close())
        assertEquals(NativeMedia3RetryOutcome.REFUSED, actuator.execute(prepared))
        assertNull(actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
    }

    @Test fun snapshotAndExecutionExceptionsFailClosedWithOneShotConsumption() {
        val snapshotFailure = Port().apply { throwSnapshot = true }
        assertNull(
            actuator(snapshotFailure)
                .prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        )

        val executionFailure = Port().apply { throwExecution = true }
        val actuator = actuator(executionFailure)
        val prepared = actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)!!
        assertEquals(NativeMedia3RetryOutcome.EXECUTION_REJECTED, actuator.execute(prepared))
        assertEquals(NativeMedia3RetryOutcome.ALREADY_CONSUMED, actuator.execute(prepared))
        assertNull(actuator.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
    }

    @Test fun sharedBudgetSurvivesActuatorCloseAndReplacement() {
        val port = Port()
        val shared = episodeBudget()
        val first = actuator(port, shared)
        assertNotNull(first.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT))
        assertTrue(first.close())

        val replacement = actuator(port, shared)
        assertNull(replacement.prepare(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED))
        assertTrue(port.actions.isEmpty())
    }

    @Test fun retrySnapshotExposesNoCallerControlledAttemptCounter() {
        val forbidden = NativeMedia3RetrySnapshot::class.java.declaredMethods
            .map { it.name }
            .filter { it.contains("RetryCount", ignoreCase = true) }
        assertTrue(forbidden.toString(), forbidden.isEmpty())

        val port = Port()
        val shared = episodeBudget(port.snapshot)
        assertNotNull(actuator(port, shared).prepare(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        ))
        assertNull(actuator(port, shared).prepare(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED
        ))
    }

    @Test fun budgetIdentityThreadAndLifecycleFailClosed() {
        val code = PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        val port = Port()
        val wrongEpisode = NativeMedia3RetryBudget.open("episode-session-2", port.snapshot.videoId)!!
        assertNull(actuator(port, wrongEpisode).prepare(code))
        assertNull(NativeMedia3RetryBudget.open("bad episode", port.snapshot.videoId))

        val closed = episodeBudget()
        assertTrue(closed.close())
        assertNull(actuator(port, closed).prepare(code))

        val foreign = episodeBudget()
        val foreignClose = AtomicReference<Boolean>()
        Thread { foreignClose.set(foreign.close()) }.apply { start(); join() }
        assertFalse(foreignClose.get())
        assertNotNull(actuator(port, foreign).prepare(code))

        val lifecycle = episodeBudget()
        val active = actuator(port, lifecycle)
        val prepared = active.prepare(code)!!
        assertTrue(lifecycle.close())
        assertEquals(NativeMedia3RetryOutcome.REFUSED, active.execute(prepared))
        assertTrue(port.actions.isEmpty())
    }

    companion object {
        private fun snapshot() = NativeMedia3RetrySnapshot(
            episodeSessionId = "episode-session-1",
            videoId = "tt4803766:13:1",
            sourceId = "source-a",
            loadGeneration = 1,
            loaded = true
        )
    }
}
