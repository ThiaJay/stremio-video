package org.pss.stremio.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativePlaybackRecoveryPolicyTest {
    private fun classification(
        category: String = "playback_health",
        action: String = "delegate_to_bounded_playback_recovery",
        retryAllowed: Boolean = false,
        maxAdditionalAttempts: Int = 0,
        contractVersion: Int = 1,
        preserveWatchedState: Boolean = true,
        rawLogExportAllowed: Boolean = false
    ): ClassifiedStreamFailure {
        val retryCount = if (retryAllowed && maxAdditionalAttempts == 1) 0 else 1
        val facts = when ("$category:$action") {
            "playback_health:delegate_to_bounded_playback_recovery" ->
                SanitisedStreamFailureFacts(
                    providerStatus = "ready",
                    transportError = "none",
                    containerError = "none",
                    decoderError = "none",
                    playbackHealthEligible = true
                )
            "matroska_parser:stop_with_actionable_container_error" ->
                SanitisedStreamFailureFacts(transportError = "none", containerError = "matroska_ebml")
            "backend_resource_exhaustion:release_player_and_stop" ->
                SanitisedStreamFailureFacts(decoderError = "egl_bad_alloc")
            "codec_or_audio_decoder:stop_with_actionable_decoder_error" ->
                SanitisedStreamFailureFacts(decoderError = "codec")
            "next_episode_navigation:stop_unrequested_episode" ->
                SanitisedStreamFailureFacts(episodeAdvanced = true)
            "upstream_http:retry_same_source" ->
                SanitisedStreamFailureFacts(httpStatus = 503, sameEpisodeRetryCount = retryCount)
            "http_transport:retry_same_transport_route" ->
                SanitisedStreamFailureFacts(transportError = "timeout", sameEpisodeRetryCount = retryCount)
            "provider_rate_limit:bounded_backoff" ->
                SanitisedStreamFailureFacts(httpStatus = 429, sameEpisodeRetryCount = retryCount)
            "drm:stop_with_actionable_decoder_error" ->
                SanitisedStreamFailureFacts(decoderError = "drm")
            else -> null
        }
        val generated = facts?.let(NativeStreamFailureClassifier::classify)
        if (generated != null &&
            contractVersion == 1 &&
            preserveWatchedState &&
            !rawLogExportAllowed &&
            generated.category == category &&
            generated.action == action &&
            generated.retryAllowed == retryAllowed &&
            generated.maxAdditionalAttempts == maxAdditionalAttempts
        ) return generated
        return ClassifiedStreamFailure(
            contractVersion = contractVersion,
            category = category,
            action = action,
            preserveEpisode = true,
            preserveWatchedState = preserveWatchedState,
            preserveProviderConfiguration = true,
            preserveStreamIdentity = true,
            rawLogExportAllowed = rawLogExportAllowed,
            retryAllowed = retryAllowed,
            maxAdditionalAttempts = maxAdditionalAttempts,
            provenanceSeal = Any()
        )
    }

    private fun observation(
        history: List<String>? = emptyList(),
        recoveryContextId: String = "video-1:source-1:generation-1",
        currentContextId: String = recoveryContextId
    ) = PlaybackHealthObservation(
        audioExpected = true,
        audioPresent = false,
        videoStable = true,
        canTranscodeAudio = true,
        canSwitchEngine = true,
        canSwitchStream = true,
        attemptedRecoveries = history,
        recoveryContextId = recoveryContextId,
        currentContextId = currentContextId
    )

    @Test fun exactPlaybackDelegationUsesFirstBoundedNativeRecovery() {
        val plan = NativePlaybackRecoveryPolicy.plan(classification(), observation())
        assertEquals("playback_health", plan.authority)
        assertEquals("transcodeAudio", plan.action)
        assertEquals(0, plan.classifierAttemptsGranted)
    }

    @Test fun terminalClassificationsCannotBeBypassedByPlayerCapabilities() {
        val terminal = listOf(
            "matroska_parser" to "stop_with_actionable_container_error",
            "backend_resource_exhaustion" to "release_player_and_stop",
            "codec_or_audio_decoder" to "stop_with_actionable_decoder_error",
            "next_episode_navigation" to "stop_unrequested_episode"
        )
        for ((category, action) in terminal) {
            val plan = NativePlaybackRecoveryPolicy.plan(
                classification(category, action),
                observation()
            )
            assertEquals("terminal_stop", plan.authority)
            assertEquals(action, plan.action)
            assertNull(plan.playbackRecovery)
        }
    }

    @Test fun retryableSourceClassificationExclusivelyOwnsItsSingleAttempt() {
        val plan = NativePlaybackRecoveryPolicy.plan(
            classification(
                category = "upstream_http",
                action = "retry_same_source",
                retryAllowed = true,
                maxAdditionalAttempts = 1
            ),
            observation()
        )
        assertEquals("stream_classifier", plan.authority)
        assertEquals("retry_same_source", plan.action)
        assertEquals(1, plan.classifierAttemptsGranted)
        assertNull(plan.playbackRecovery)
    }

    @Test fun exhaustedClassifierBudgetCannotSpillIntoPlaybackRecovery() {
        val plan = NativePlaybackRecoveryPolicy.plan(
            classification("http_transport", "retry_same_transport_route"),
            observation()
        )
        assertEquals("terminal_stop", plan.authority)
        assertEquals("stop_classifier_budget_exhausted", plan.action)
    }

    @Test fun delegatedRecoveryRequiresExplicitLedgerAndMatchingContext() {
        assertEquals(
            "stop_unbound_playback_recovery",
            NativePlaybackRecoveryPolicy.plan(
                classification(),
                observation(history = null)
            ).action
        )
        assertEquals(
            "stop_no_safe_playback_recovery",
            NativePlaybackRecoveryPolicy.plan(
                classification(),
                observation(currentContextId = "video-2:source-1:generation-1")
            ).action
        )
    }

    @Test fun sourceVideoOrGenerationDriftStopsBeforeNativeRecovery() {
        val base = observation()
        for (drifted in listOf(
            base.copy(sourceIdentityMismatch = true),
            base.copy(videoIdentityMismatch = true),
            base.copy(mediaGenerationChanged = true)
        )) {
            assertEquals(
                "stop_no_safe_playback_recovery",
                NativePlaybackRecoveryPolicy.plan(classification(), drifted).action
            )
        }
    }

    @Test fun completedActionAdvancesWithinTheSameBoundedLedger() {
        val plan = NativePlaybackRecoveryPolicy.plan(
            classification(),
            observation(history = listOf("transcodeAudio"))
        )
        assertEquals("playback_health", plan.authority)
        assertEquals("switchPlaybackEngine", plan.action)
    }

    @Test fun malformedDuplicateUnknownOrOversizedHistoryFailsClosed() {
        val invalid = listOf(
            listOf("transcodeAudio", "transcodeAudio"),
            listOf("resetAccount"),
            List(17) { "switchStream" }
        )
        for (history in invalid) {
            assertEquals(
                "stop_no_safe_playback_recovery",
                NativePlaybackRecoveryPolicy.plan(
                    classification(),
                    observation(history = history)
                ).action
            )
        }
    }

    @Test fun callerConstructedClassificationCannotBypassClassifier() {
        val forged = ClassifiedStreamFailure(
            contractVersion = 1,
            category = "playback_health",
            action = "delegate_to_bounded_playback_recovery",
            preserveEpisode = true,
            preserveWatchedState = true,
            preserveProviderConfiguration = true,
            preserveStreamIdentity = true,
            rawLogExportAllowed = false,
            retryAllowed = false,
            maxAdditionalAttempts = 0,
            provenanceSeal = Any()
        )
        assertEquals(
            "stop_invalid_classification_contract",
            NativePlaybackRecoveryPolicy.plan(forged, observation()).action
        )
    }

    @Test fun wrongVersionArbitraryActionOrWeakenedPreservationFailsClosed() {
        val invalid = listOf(
            classification(contractVersion = 2),
            classification(action = "resetAccount"),
            classification(preserveWatchedState = false),
            classification(rawLogExportAllowed = true)
        )
        for (value in invalid) {
            assertEquals(
                "stop_invalid_classification_contract",
                NativePlaybackRecoveryPolicy.plan(value, observation()).action
            )
        }
    }

    @Test fun dolbyVisionProfileSevenRecoveryIsOrderedAndNeverLoops() {
        val base = PlaybackHealthObservation(
            audioExpected = true,
            audioPresent = true,
            videoStable = false,
            videoFramePresent = false,
            startupTimedOut = true,
            dolbyVisionProfile = 7,
            hdr10BaseLayer = true,
            canConvertDolbyVisionProfile7 = true,
            canFallbackHdr10 = true,
            attemptedRecoveries = emptyList(),
            recoveryContextId = "video-1:source-1:generation-1",
            currentContextId = "video-1:source-1:generation-1"
        )
        assertEquals(
            "convertDolbyVisionProfile7To8_1",
            NativePlaybackRecoveryPolicy.plan(classification(), base).action
        )
        assertEquals(
            "fallbackToHdr10BaseLayer",
            NativePlaybackRecoveryPolicy.plan(
                classification(),
                base.copy(attemptedRecoveries = listOf("convertDolbyVisionProfile7To8_1"))
            ).action
        )
        assertEquals(
            "stop_no_safe_playback_recovery",
            NativePlaybackRecoveryPolicy.plan(
                classification(),
                base.copy(
                    attemptedRecoveries = listOf(
                        "convertDolbyVisionProfile7To8_1",
                        "fallbackToHdr10BaseLayer"
                    )
                )
            ).action
        )
    }

    @Test fun healthyTransientOrPreTimeoutPlaybackNeverStartsRecovery() {
        val healthy = observation().copy(audioPresent = true)
        val transient = observation().copy(transient = true)
        val profileSevenBeforeTimeout = observation().copy(
            audioPresent = true,
            videoStable = false,
            videoFramePresent = false,
            dolbyVisionProfile = 7,
            startupTimedOut = false,
            canConvertDolbyVisionProfile7 = true
        )
        for (value in listOf(healthy, transient, profileSevenBeforeTimeout)) {
            assertEquals(
                "stop_no_safe_playback_recovery",
                NativePlaybackRecoveryPolicy.plan(classification(), value).action
            )
        }
    }

    @Test fun everyPlanPreservesEpisodeWatchedProviderAndStreamIdentity() {
        val plans = listOf(
            NativePlaybackRecoveryPolicy.plan(classification(), observation()),
            NativePlaybackRecoveryPolicy.plan(
                classification("drm", "stop_with_actionable_decoder_error"),
                observation()
            ),
            NativePlaybackRecoveryPolicy.plan(
                classification(
                    "provider_rate_limit",
                    "bounded_backoff",
                    retryAllowed = true,
                    maxAdditionalAttempts = 1
                ),
                observation()
            )
        )
        for (plan in plans) {
            assertTrue(plan.preserveEpisode)
            assertTrue(plan.preserveWatchedState)
            assertTrue(plan.preserveProviderConfiguration)
            assertTrue(plan.preserveStreamIdentity)
        }
    }
}
