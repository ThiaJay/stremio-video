package org.pss.stremio.tv

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeMedia3FailureIntakeTest {
    @Test fun networkSignalsMapToOneBoundedTransportRetry() {
        val first = NativeMedia3FailureIntake.classify(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        )
        assertEquals("http_transport", first.category)
        assertEquals("retry_same_transport_route", first.action)
        assertTrue(first.retryAllowed)
        assertEquals(1, first.maxAdditionalAttempts)

        val exhausted = NativeMedia3FailureIntake.classify(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            sameEpisodeRetryCount = 1
        )
        assertEquals("http_transport", exhausted.category)
        assertFalse(exhausted.retryAllowed)
        assertEquals(0, exhausted.maxAdditionalAttempts)
    }

    @Test fun genericHttpErrorNeverInventsResponseStatusOrCredentialFailure() {
        val facts = NativeMedia3FailureIntake.sanitise(
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS
        )
        assertNull(facts.httpStatus)
        assertEquals("unknown", facts.transportError)

        val decision = NativeStreamFailureClassifier.classify(facts)
        assertEquals("unclassified", decision.category)
        assertEquals("stop_and_report_sanitised_failure", decision.action)
        assertFalse(decision.retryAllowed)
    }

    @Test fun parserSignalsStopWithoutInventingMatroskaEvidence() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED
        )) {
            val facts = NativeMedia3FailureIntake.sanitise(code)
            assertEquals("unsupported", facts.containerError)
            assertFalse(facts.playbackHealthEligible)
            val decision = NativeStreamFailureClassifier.classify(facts)
            assertEquals("unsupported_container", decision.category)
            assertEquals("stop_with_actionable_container_error", decision.action)
            assertFalse(decision.retryAllowed)
        }
    }

    @Test fun decoderAudioAndDrmSignalsRemainTerminal() {
        val cases = mapOf(
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED to "codec_or_audio_decoder",
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED to "codec_or_audio_decoder",
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED to "drm"
        )
        for ((code, category) in cases) {
            val facts = NativeMedia3FailureIntake.sanitise(code)
            assertFalse(facts.playbackHealthEligible)
            val decision = NativeStreamFailureClassifier.classify(facts)
            assertEquals(category, decision.category)
            assertEquals("stop_with_actionable_decoder_error", decision.action)
            assertFalse(decision.retryAllowed)
        }
    }

    @Test fun unknownAndOperationalCodesFailClosedWithoutPlaybackRecovery() {
        for (code in listOf(
            PlaybackException.ERROR_CODE_UNSPECIFIED,
            PlaybackException.ERROR_CODE_TIMEOUT,
            Int.MAX_VALUE
        )) {
            val facts = NativeMedia3FailureIntake.sanitise(code)
            assertFalse(facts.playbackHealthEligible)
            val decision = NativeStreamFailureClassifier.classify(facts)
            assertEquals("unclassified", decision.category)
            assertEquals("stop_and_report_sanitised_failure", decision.action)
        }
    }

    @Test fun unrequestedEpisodeAdvanceWinsBeforeRetryableTransport() {
        val decision = NativeMedia3FailureIntake.classify(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            episodeAdvanced = true,
            userRequestedNextEpisode = false
        )
        assertEquals("next_episode_navigation", decision.category)
        assertEquals("stop_unrequested_episode", decision.action)
        assertFalse(decision.retryAllowed)
    }

    @Test fun invalidExplicitCountersRemainFailClosed() {
        val decision = NativeMedia3FailureIntake.classify(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            sameEpisodeRetryCount = -1,
            backendSwitchCount = -1
        )
        assertEquals("unclassified", decision.category)
        assertEquals("stop_and_report_sanitised_failure", decision.action)
        assertFalse(decision.retryAllowed)
    }
}
