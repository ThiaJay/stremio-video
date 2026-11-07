package org.thiajay.stremiotvcompanion

/**
 * Records Media3's runtime view of the one deterministic local text track.
 * Selection state is not evidence that the track was visibly rendered.
 */
internal class SyntheticSubtitleTrackStateObserver(
    private val ownerThread: Thread = Thread.currentThread()
) {
    data class Context(
        val playerGeneration: Long,
        val loadGeneration: Long,
        val videoId: String,
        val sourceId: String
    )

    data class TextTrack(
        val sampleMimeType: String?,
        val codecs: String?,
        val language: String?,
        val label: String?,
        val selected: Boolean
    )

    enum class State { NOT_REPORTED, ABSENT, PRESENT_UNSELECTED, PRESENT_SELECTED }

    data class Snapshot(val context: Context, val state: State)

    private var activeContext: Context? = null
    private var highestLoadGeneration = 0L
    private var state = State.NOT_REPORTED
    private var closed = false

    fun begin(context: Context): Boolean {
        if (!ownsThread() || closed || !valid(context) ||
            context.loadGeneration <= highestLoadGeneration) {
            return false
        }
        highestLoadGeneration = context.loadGeneration
        activeContext = context
        state = State.NOT_REPORTED
        return true
    }

    fun observe(context: Context, textTracks: List<TextTrack>): Boolean {
        if (!ownsThread() || closed || activeContext != context) {
            return false
        }
        if (textTracks.size > MAX_TEXT_TRACKS) {
            poisonActiveGeneration()
            return false
        }
        if (textTracks.isEmpty()) {
            state = State.ABSENT
            return true
        }
        val track = textTracks.singleOrNull()
        if (track == null || !isExpectedSyntheticWebVttTrack(track)) {
            poisonActiveGeneration()
            return false
        }
        state = if (track.selected) {
            State.PRESENT_SELECTED
        } else {
            State.PRESENT_UNSELECTED
        }
        return true
    }

    fun snapshot(context: Context): Snapshot? {
        if (!ownsThread() || closed || activeContext != context) return null
        return Snapshot(context, state)
    }

    fun abandon(context: Context): Boolean {
        if (!ownsThread() || closed || activeContext != context) return false
        activeContext = null
        state = State.NOT_REPORTED
        return true
    }

    fun close(): Boolean {
        if (!ownsThread() || closed) return false
        closed = true
        activeContext = null
        state = State.NOT_REPORTED
        return true
    }

    private fun isExpectedSyntheticWebVttTrack(track: TextTrack): Boolean {
        val payloadIdentityAccepted = when (track.sampleMimeType) {
            WEBVTT_MIME_TYPE -> track.codecs == null
            MEDIA3_CUES_MIME_TYPE -> track.codecs == WEBVTT_MIME_TYPE
            else -> false
        }
        return payloadIdentityAccepted && track.language == EXPECTED_LANGUAGE &&
            track.label == EXPECTED_LABEL
    }

    private fun poisonActiveGeneration() {
        activeContext = null
        state = State.NOT_REPORTED
    }

    private fun ownsThread(): Boolean = Thread.currentThread() === ownerThread

    private fun valid(context: Context): Boolean =
        context.playerGeneration > 0L && context.loadGeneration > 0L &&
            context.videoId.isNotBlank() && context.sourceId.isNotBlank()

    companion object {
        private const val MAX_TEXT_TRACKS = 4
        private const val WEBVTT_MIME_TYPE = "text/vtt"
        private const val MEDIA3_CUES_MIME_TYPE = "application/x-media3-cues"
        private const val EXPECTED_LANGUAGE = "en"
        private const val EXPECTED_LABEL = "English (synthetic lab)"
    }
}
