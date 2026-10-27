package org.pss.stremio.tv

/**
 * Sanitised, side-effect-free native port of the Foundation PR #75
 * classification contract. This type deliberately cannot carry raw logs,
 * URLs, credentials, source hashes or account state.
 */
data class SanitisedStreamFailureFacts(
    val providerStatus: String = "unknown",
    val httpStatus: Int? = null,
    val transportError: String = "unknown",
    val containerError: String = "unknown",
    val decoderError: String = "unknown",
    val backend: String = "unknown",
    val backendSwitchCount: Int = 0,
    val sameEpisodeRetryCount: Int = 0,
    val episodeAdvanced: Boolean = false,
    val userRequestedNextEpisode: Boolean = false,
    val playbackHealthEligible: Boolean = false
)

object NativeStreamFailureClassifier {
    private val PROVIDERS = setOf("ready", "not_ready", "unknown")
    private val TRANSPORTS = setOf("none", "timeout", "tls", "connection", "reset", "unknown")
    private val CONTAINERS = setOf("none", "matroska_ebml", "unsupported", "unknown")
    private val DECODERS = setOf("none", "codec", "audio", "drm", "egl_bad_alloc", "unknown")
    private val BACKENDS = setOf("exoplayer", "vlc", "mpv", "unknown")

    fun classify(facts: SanitisedStreamFailureFacts? = null): ClassifiedStreamFailure {
        val value = facts ?: SanitisedStreamFailureFacts()
        if (value.providerStatus !in PROVIDERS ||
            value.httpStatus?.let { it !in 100..599 } == true ||
            value.transportError !in TRANSPORTS ||
            value.containerError !in CONTAINERS ||
            value.decoderError !in DECODERS ||
            value.backend !in BACKENDS ||
            value.backendSwitchCount < 0 ||
            value.sameEpisodeRetryCount < 0
        ) {
            return stop("unclassified", "stop_and_report_sanitised_failure")
        }

        if (value.episodeAdvanced && !value.userRequestedNextEpisode) {
            return stop("next_episode_navigation", "stop_unrequested_episode")
        }
        if (value.backendSwitchCount >= 2) {
            return stop("backend_switch_loop", "stop_backend_cycling")
        }
        if (value.decoderError == "egl_bad_alloc") {
            return stop("backend_resource_exhaustion", "release_player_and_stop")
        }
        if (value.providerStatus == "not_ready") {
            return retry("provider_readiness", "wait_for_provider_readiness", value.sameEpisodeRetryCount)
        }
        when (value.httpStatus) {
            401, 403 -> return retry(
                "source_authorisation",
                "reresolve_source_without_credential_change",
                value.sameEpisodeRetryCount
            )
            404, 410 -> return retry(
                "expired_or_missing_source",
                "reresolve_same_source",
                value.sameEpisodeRetryCount
            )
            429 -> return retry("provider_rate_limit", "bounded_backoff", value.sameEpisodeRetryCount)
        }
        if (value.httpStatus != null && value.httpStatus >= 500) {
            return retry("upstream_http", "retry_same_source", value.sameEpisodeRetryCount)
        }
        if (value.transportError !in setOf("none", "unknown")) {
            return retry("http_transport", "retry_same_transport_route", value.sameEpisodeRetryCount)
        }
        if (value.containerError == "matroska_ebml") {
            return stop("matroska_parser", "stop_with_actionable_container_error")
        }
        if (value.containerError == "unsupported") {
            return stop("unsupported_container", "stop_with_actionable_container_error")
        }
        if (value.decoderError !in setOf("none", "unknown")) {
            return stop(
                if (value.decoderError == "drm") "drm" else "codec_or_audio_decoder",
                "stop_with_actionable_decoder_error"
            )
        }
        if (value.playbackHealthEligible) {
            return result("playback_health", "delegate_to_bounded_playback_recovery")
        }
        return stop("unclassified", "stop_and_report_sanitised_failure")
    }

    private fun retry(category: String, action: String, attempts: Int): ClassifiedStreamFailure =
        result(
            category,
            action,
            retryAllowed = attempts == 0,
            maxAdditionalAttempts = if (attempts == 0) 1 else 0
        )

    private fun stop(category: String, action: String): ClassifiedStreamFailure =
        result(category, action)

    private fun result(
        category: String,
        action: String,
        retryAllowed: Boolean = false,
        maxAdditionalAttempts: Int = 0
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
        maxAdditionalAttempts = maxAdditionalAttempts
    )
}
