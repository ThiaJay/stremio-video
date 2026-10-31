package org.pss.stremio.tv

import androidx.media3.common.PlaybackException

/**
 * Converts only Media3's stable numeric error code and explicit local counters
 * into the existing sanitised classifier contract. Raw exception messages,
 * causes, URLs, headers, source hashes, credentials and account state are never
 * accepted by this boundary.
 *
 * Generic Media3 HTTP failures do not expose a trustworthy response status here,
 * so this adapter deliberately leaves httpStatus null rather than inventing a
 * 401, 403, 404 or 5xx classification.
 */
object NativeMedia3FailureIntake {
    fun sanitise(
        errorCode: Int,
        sameEpisodeRetryCount: Int = 0,
        backendSwitchCount: Int = 0,
        episodeAdvanced: Boolean = false,
        userRequestedNextEpisode: Boolean = false
    ): SanitisedStreamFailureFacts {
        val transportError = when (errorCode) {
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED -> "connection"
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "timeout"
            else -> "unknown"
        }
        val containerError = when (errorCode) {
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED,
            PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED -> "unsupported"
            else -> "unknown"
        }
        val decoderError = when (errorCode) {
            PlaybackException.ERROR_CODE_DECODER_INIT_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FAILED,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES,
            PlaybackException.ERROR_CODE_DECODING_FORMAT_UNSUPPORTED -> "codec"

            PlaybackException.ERROR_CODE_AUDIO_TRACK_INIT_FAILED,
            PlaybackException.ERROR_CODE_AUDIO_TRACK_WRITE_FAILED -> "audio"

            PlaybackException.ERROR_CODE_DRM_UNSPECIFIED,
            PlaybackException.ERROR_CODE_DRM_SCHEME_UNSUPPORTED,
            PlaybackException.ERROR_CODE_DRM_PROVISIONING_FAILED,
            PlaybackException.ERROR_CODE_DRM_CONTENT_ERROR,
            PlaybackException.ERROR_CODE_DRM_LICENSE_ACQUISITION_FAILED,
            PlaybackException.ERROR_CODE_DRM_DISALLOWED_OPERATION,
            PlaybackException.ERROR_CODE_DRM_SYSTEM_ERROR,
            PlaybackException.ERROR_CODE_DRM_DEVICE_REVOKED,
            PlaybackException.ERROR_CODE_DRM_LICENSE_EXPIRED -> "drm"
            else -> "unknown"
        }
        return SanitisedStreamFailureFacts(
            providerStatus = "unknown",
            httpStatus = null,
            transportError = transportError,
            containerError = containerError,
            decoderError = decoderError,
            backend = "exoplayer",
            backendSwitchCount = backendSwitchCount,
            sameEpisodeRetryCount = sameEpisodeRetryCount,
            episodeAdvanced = episodeAdvanced,
            userRequestedNextEpisode = userRequestedNextEpisode,
            playbackHealthEligible = false
        )
    }

    fun classify(
        errorCode: Int,
        sameEpisodeRetryCount: Int = 0,
        backendSwitchCount: Int = 0,
        episodeAdvanced: Boolean = false,
        userRequestedNextEpisode: Boolean = false
    ): ClassifiedStreamFailure = NativeStreamFailureClassifier.classify(
        sanitise(
            errorCode = errorCode,
            sameEpisodeRetryCount = sameEpisodeRetryCount,
            backendSwitchCount = backendSwitchCount,
            episodeAdvanced = episodeAdvanced,
            userRequestedNextEpisode = userRequestedNextEpisode
        )
    )
}
