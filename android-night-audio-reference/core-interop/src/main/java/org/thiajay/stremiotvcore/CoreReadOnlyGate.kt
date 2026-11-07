package org.thiajay.stremiotvcore

/** Deliberately excludes account IDs, keys, addon URLs and stream URLs. */
data class SafeCoreObservation(
    val sessionEpoch: Long,
    val authenticated: Boolean,
    val metaId: String?,
    val videoId: String?,
    val streamListCount: Int
)

interface CoreReadOnlySignals {
    fun observe(): SafeCoreObservation?
}

enum class CoreReadOnlyFailure {
    SOURCE_UNAVAILABLE, NOT_AUTHENTICATED, STALE_OR_SWITCHED_SESSION,
    WRONG_VIDEO_OR_METADATA, NO_SAFE_SELECTION
}

sealed class CoreReadOnlyResult {
    data class Available(val selectedMetaId: String, val selectedVideoId: String?, val streamListsObserved: Int) : CoreReadOnlyResult()
    data class Held(val reason: CoreReadOnlyFailure) : CoreReadOnlyResult()
}

/**
 * Takes two independent snapshots and refuses stale account, metadata, or
 * selected-video identity, even if the provider reports a changing but valid
 * Core context during a command. Returns no credentials or private stream URLs.
 * This is only a read gate; it has no dispatch, login, mutation or playback API.
 */
class CoreReadOnlyGate(private val source: CoreReadOnlySignals) {
    fun observe(expectedEpoch: Long, expectedMetaId: String, expectedVideoId: String?): CoreReadOnlyResult {
        if (expectedEpoch < 0 || expectedMetaId.isBlank() || expectedMetaId.length > 240 ||
            (expectedVideoId != null && (expectedVideoId.isBlank() || expectedVideoId.length > 240)))
            return CoreReadOnlyResult.Held(CoreReadOnlyFailure.NO_SAFE_SELECTION)
        val first = source.observe() ?: return CoreReadOnlyResult.Held(CoreReadOnlyFailure.SOURCE_UNAVAILABLE)
        val second = source.observe() ?: return CoreReadOnlyResult.Held(CoreReadOnlyFailure.SOURCE_UNAVAILABLE)
        if (first.sessionEpoch != expectedEpoch || second.sessionEpoch != expectedEpoch ||
            first.sessionEpoch != second.sessionEpoch || first.authenticated != second.authenticated)
            return CoreReadOnlyResult.Held(CoreReadOnlyFailure.STALE_OR_SWITCHED_SESSION)
        if (!first.authenticated) return CoreReadOnlyResult.Held(CoreReadOnlyFailure.NOT_AUTHENTICATED)
        if (first.metaId != expectedMetaId || second.metaId != expectedMetaId ||
            first.videoId != expectedVideoId || second.videoId != expectedVideoId ||
            first.streamListCount < 0 || first.streamListCount > 1000 ||
            second.streamListCount < 0 || second.streamListCount > 1000 ||
            first.streamListCount != second.streamListCount)
            return CoreReadOnlyResult.Held(CoreReadOnlyFailure.WRONG_VIDEO_OR_METADATA)
        // 'streamListsObserved' counts provider response lists, NOT playable URLs.
        return CoreReadOnlyResult.Available(expectedMetaId, expectedVideoId, second.streamListCount)
    }
}
