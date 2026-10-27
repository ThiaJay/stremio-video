package org.pss.stremio.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeStreamFailureClassifierTest {
    private fun classify(
        facts: SanitisedStreamFailureFacts = SanitisedStreamFailureFacts()
    ) = NativeStreamFailureClassifier.classify(facts)

    @Test fun classifierAlwaysEmitsExactPreservationEnvelope() {
        val result = classify()
        assertEquals(1, result.contractVersion)
        assertTrue(result.preserveEpisode)
        assertTrue(result.preserveWatchedState)
        assertTrue(result.preserveProviderConfiguration)
        assertTrue(result.preserveStreamIdentity)
        assertFalse(result.rawLogExportAllowed)
    }

    @Test fun invalidSanitisedFactsFailClosed() {
        val invalid = listOf(
            SanitisedStreamFailureFacts(providerStatus = "ready "),
            SanitisedStreamFailureFacts(httpStatus = 99),
            SanitisedStreamFailureFacts(transportError = "socket_dump"),
            SanitisedStreamFailureFacts(containerError = "mkv_url"),
            SanitisedStreamFailureFacts(decoderError = "raw_log"),
            SanitisedStreamFailureFacts(backend = "custom"),
            SanitisedStreamFailureFacts(backendSwitchCount = -1),
            SanitisedStreamFailureFacts(sameEpisodeRetryCount = -1)
        )
        for (facts in invalid) {
            val result = classify(facts)
            assertEquals("unclassified", result.category)
            assertEquals("stop_and_report_sanitised_failure", result.action)
            assertFalse(result.retryAllowed)
            assertEquals(0, result.maxAdditionalAttempts)
        }
    }

    @Test fun terminalEpisodeResourceContainerAndDecoderStopsWinBeforeDelegation() {
        val inputs = listOf(
            SanitisedStreamFailureFacts(episodeAdvanced = true, playbackHealthEligible = true),
            SanitisedStreamFailureFacts(backendSwitchCount = 2, playbackHealthEligible = true),
            SanitisedStreamFailureFacts(decoderError = "egl_bad_alloc", playbackHealthEligible = true),
            SanitisedStreamFailureFacts(containerError = "matroska_ebml", playbackHealthEligible = true),
            SanitisedStreamFailureFacts(containerError = "unsupported", playbackHealthEligible = true),
            SanitisedStreamFailureFacts(decoderError = "codec", playbackHealthEligible = true),
            SanitisedStreamFailureFacts(decoderError = "audio", playbackHealthEligible = true),
            SanitisedStreamFailureFacts(decoderError = "drm", playbackHealthEligible = true)
        )
        for (facts in inputs) {
            val result = classify(facts)
            assertFalse(result.category == "playback_health")
            assertFalse(result.action == "delegate_to_bounded_playback_recovery")
            assertFalse(result.retryAllowed)
        }
    }

    @Test fun providerSourceAndTransportOwnExactlyOneSameEpisodeRetry() {
        val first = listOf(
            SanitisedStreamFailureFacts(providerStatus = "not_ready", playbackHealthEligible = true),
            SanitisedStreamFailureFacts(httpStatus = 403, playbackHealthEligible = true),
            SanitisedStreamFailureFacts(httpStatus = 410, playbackHealthEligible = true),
            SanitisedStreamFailureFacts(httpStatus = 429, playbackHealthEligible = true),
            SanitisedStreamFailureFacts(httpStatus = 503, playbackHealthEligible = true),
            SanitisedStreamFailureFacts(transportError = "timeout", playbackHealthEligible = true)
        )
        for (facts in first) {
            val result = classify(facts)
            assertTrue(result.retryAllowed)
            assertEquals(1, result.maxAdditionalAttempts)
            assertFalse(result.category == "playback_health")
            val exhausted = classify(facts.copy(sameEpisodeRetryCount = 1))
            assertFalse(exhausted.retryAllowed)
            assertEquals(0, exhausted.maxAdditionalAttempts)
            assertEquals(result.category, exhausted.category)
            assertEquals(result.action, exhausted.action)
        }
    }

    @Test fun exactPlaybackDelegationOccursOnlyAfterClassificationClears() {
        val result = classify(
            SanitisedStreamFailureFacts(
                providerStatus = "ready",
                transportError = "none",
                containerError = "none",
                decoderError = "none",
                playbackHealthEligible = true
            )
        )
        assertEquals("playback_health", result.category)
        assertEquals("delegate_to_bounded_playback_recovery", result.action)
        assertFalse(result.retryAllowed)
        assertEquals(0, result.maxAdditionalAttempts)
    }

    @Test fun classifierOutputComposesWithNativeBudgetWithoutParallelRetry() {
        val retry = classify(
            SanitisedStreamFailureFacts(httpStatus = 503, playbackHealthEligible = true)
        )
        val retryPlan = NativePlaybackRecoveryPolicy.plan(retry, playbackObservation())
        assertEquals("stream_classifier", retryPlan.authority)
        assertEquals(1, retryPlan.classifierAttemptsGranted)

        val delegated = classify(
            SanitisedStreamFailureFacts(
                providerStatus = "ready",
                transportError = "none",
                containerError = "none",
                decoderError = "none",
                playbackHealthEligible = true
            )
        )
        val recoveryPlan = NativePlaybackRecoveryPolicy.plan(delegated, playbackObservation())
        assertEquals("playback_health", recoveryPlan.authority)
        assertEquals("transcodeAudio", recoveryPlan.action)
        assertEquals(0, recoveryPlan.classifierAttemptsGranted)
    }

    private fun playbackObservation() = PlaybackHealthObservation(
        audioExpected = true,
        audioPresent = false,
        videoStable = true,
        canTranscodeAudio = true,
        attemptedRecoveries = emptyList(),
        recoveryContextId = "video-1:source-1:generation-1",
        currentContextId = "video-1:source-1:generation-1"
    )
}
