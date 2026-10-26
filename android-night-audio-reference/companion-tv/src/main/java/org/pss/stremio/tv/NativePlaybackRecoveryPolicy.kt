package org.pss.stremio.tv

/**
 * Native Kotlin implementation of the Foundation PR #75 -> PR #77
 * classification-first playback recovery contract.
 *
 * This planner has no Android, player, network, account or provider side effects.
 * A host must still bind a returned action to reviewed native capabilities and
 * perform a fresh player-state lease immediately before actuation.
 */
data class ClassifiedStreamFailure(
    val contractVersion: Int,
    val category: String,
    val action: String,
    val preserveEpisode: Boolean,
    val preserveWatchedState: Boolean,
    val preserveProviderConfiguration: Boolean,
    val preserveStreamIdentity: Boolean,
    val rawLogExportAllowed: Boolean,
    val retryAllowed: Boolean,
    val maxAdditionalAttempts: Int
)

data class PlaybackHealthObservation(
    val audioExpected: Boolean = false,
    val audioPresent: Boolean = false,
    val videoStable: Boolean = true,
    val videoFramePresent: Boolean = true,
    val controlsResponsive: Boolean = true,
    val transient: Boolean = false,
    val startupTimedOut: Boolean = false,
    val dolbyVisionProfile: Int? = null,
    val hdr10BaseLayer: Boolean = false,
    val profile7ConversionAttempted: Boolean = false,
    val hdr10FallbackAttempted: Boolean = false,
    val canConvertDolbyVisionProfile7: Boolean = false,
    val canFallbackHdr10: Boolean = false,
    val canTranscodeAudio: Boolean = false,
    val canSwitchEngine: Boolean = false,
    val canRestoreStableVideo: Boolean = false,
    val canSwitchStream: Boolean = false,
    val attemptedRecoveries: List<String>? = null,
    val recoveryContextId: String? = null,
    val currentContextId: String? = null,
    val sourceIdentityMismatch: Boolean = false,
    val videoIdentityMismatch: Boolean = false,
    val mediaGenerationChanged: Boolean = false
)

data class NativeRecoveryPlan(
    val authority: String,
    val action: String,
    val category: String? = null,
    val classifierAttemptsGranted: Int = 0,
    val playbackRecovery: String? = null,
    val preserveEpisode: Boolean = true,
    val preserveWatchedState: Boolean = true,
    val preserveProviderConfiguration: Boolean = true,
    val preserveStreamIdentity: Boolean = true
)

object NativePlaybackRecoveryPolicy {
    private const val MAX_ATTEMPTS = 16
    private const val MAX_CONTEXT_ID_LENGTH = 160

    private val RETRYABLE = setOf(
        "provider_readiness:wait_for_provider_readiness",
        "source_authorisation:reresolve_source_without_credential_change",
        "expired_or_missing_source:reresolve_same_source",
        "provider_rate_limit:bounded_backoff",
        "upstream_http:retry_same_source",
        "http_transport:retry_same_transport_route"
    )

    private val TERMINAL = setOf(
        "next_episode_navigation:stop_unrequested_episode",
        "backend_switch_loop:stop_backend_cycling",
        "backend_resource_exhaustion:release_player_and_stop",
        "matroska_parser:stop_with_actionable_container_error",
        "unsupported_container:stop_with_actionable_container_error",
        "codec_or_audio_decoder:stop_with_actionable_decoder_error",
        "drm:stop_with_actionable_decoder_error",
        "unclassified:stop_and_report_sanitised_failure"
    )

    private val RECOVERY_ACTIONS = setOf(
        "convertDolbyVisionProfile7To8_1",
        "fallbackToHdr10BaseLayer",
        "restoreStableVideoAndTranscodeAudio",
        "transcodeAudio",
        "switchPlaybackEngine",
        "switchStream"
    )

    fun plan(
        classification: ClassifiedStreamFailure?,
        observation: PlaybackHealthObservation?
    ): NativeRecoveryPlan {
        if (!trusted(classification)) {
            return stop("stop_invalid_classification_contract")
        }
        classification!!
        val key = "${classification.category}:${classification.action}"

        if (key in TERMINAL) {
            if (classification.retryAllowed || classification.maxAdditionalAttempts != 0) {
                return stop("stop_invalid_classification_contract")
            }
            return stop(classification.action, classification.category)
        }

        if (key in RETRYABLE) {
            if (classification.retryAllowed && classification.maxAdditionalAttempts == 1) {
                return NativeRecoveryPlan(
                    authority = "stream_classifier",
                    action = classification.action,
                    category = classification.category,
                    classifierAttemptsGranted = 1
                )
            }
            if (!classification.retryAllowed && classification.maxAdditionalAttempts == 0) {
                return stop("stop_classifier_budget_exhausted", classification.category)
            }
            return stop("stop_invalid_classification_contract")
        }

        if (key != "playback_health:delegate_to_bounded_playback_recovery" ||
            classification.retryAllowed ||
            classification.maxAdditionalAttempts != 0
        ) {
            return stop("stop_invalid_classification_contract")
        }

        if (observation?.attemptedRecoveries == null) {
            return stop("stop_unbound_playback_recovery")
        }
        val recovery = selectPlaybackRecovery(observation)
            ?: return stop("stop_no_safe_playback_recovery")
        return NativeRecoveryPlan(
            authority = "playback_health",
            action = recovery,
            category = classification.category,
            playbackRecovery = recovery
        )
    }

    private fun trusted(value: ClassifiedStreamFailure?): Boolean =
        value != null &&
            value.contractVersion == 1 &&
            value.category.isNotBlank() &&
            value.action.isNotBlank() &&
            value.preserveEpisode &&
            value.preserveWatchedState &&
            value.preserveProviderConfiguration &&
            value.preserveStreamIdentity &&
            !value.rawLogExportAllowed &&
            value.maxAdditionalAttempts >= 0

    private fun stop(action: String, category: String? = null) = NativeRecoveryPlan(
        authority = "terminal_stop",
        action = action,
        category = category
    )

    private fun validContextId(value: String?): Boolean =
        value != null &&
            value.length in 1..MAX_CONTEXT_ID_LENGTH &&
            CONTEXT_ID.matches(value)

    private fun selectPlaybackRecovery(value: PlaybackHealthObservation): String? {
        if (value.sourceIdentityMismatch ||
            value.videoIdentityMismatch ||
            value.mediaGenerationChanged
        ) return null

        val history = value.attemptedRecoveries ?: return null
        if (history.size > MAX_ATTEMPTS ||
            history.any { it !in RECOVERY_ACTIONS } ||
            history.toSet().size != history.size
        ) return null
        if (!validContextId(value.recoveryContextId) ||
            !validContextId(value.currentContextId) ||
            value.recoveryContextId != value.currentContextId
        ) return null

        val audioHealthy = !value.audioExpected || value.audioPresent
        val videoHealthy =
            value.videoStable && value.videoFramePresent && value.controlsResponsive
        if (value.transient || (audioHealthy && videoHealthy)) return null

        val strategies = ArrayList<String>()
        if (!videoHealthy && value.dolbyVisionProfile == 7) {
            if (!value.startupTimedOut) return null
            if (value.canConvertDolbyVisionProfile7 && !value.profile7ConversionAttempted) {
                strategies += "convertDolbyVisionProfile7To8_1"
            }
            if (value.hdr10BaseLayer &&
                value.canFallbackHdr10 &&
                !value.hdr10FallbackAttempted
            ) {
                strategies += "fallbackToHdr10BaseLayer"
            }
        }

        if (!audioHealthy && videoHealthy) {
            if (value.canTranscodeAudio) strategies += "transcodeAudio"
            if (value.canSwitchEngine) strategies += "switchPlaybackEngine"
            if (value.canSwitchStream) strategies += "switchStream"
        } else if (audioHealthy && !videoHealthy) {
            if (value.canRestoreStableVideo && value.canTranscodeAudio) {
                strategies += "restoreStableVideoAndTranscodeAudio"
            }
            if (value.canSwitchStream) strategies += "switchStream"
            if (value.canSwitchEngine) strategies += "switchPlaybackEngine"
        } else {
            if (value.canSwitchEngine) strategies += "switchPlaybackEngine"
            if (value.canSwitchStream) strategies += "switchStream"
        }

        val attempted = history.toSet()
        return strategies.firstOrNull { it in RECOVERY_ACTIONS && it !in attempted }
    }

    private val CONTEXT_ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")
}
