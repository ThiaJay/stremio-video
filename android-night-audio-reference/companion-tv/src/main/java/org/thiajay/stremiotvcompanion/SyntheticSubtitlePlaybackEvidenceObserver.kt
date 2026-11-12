package org.thiajay.stremiotvcompanion

/**
 * Correlates Media3's selected synthetic text track with deterministic cue callbacks.
 * Correlated callbacks are not evidence that subtitles were visibly rendered.
 */
internal class SyntheticSubtitlePlaybackEvidenceObserver(
    private val ownerThread: Thread = Thread.currentThread()
) {
    data class Context(
        val playerGeneration: Long,
        val loadGeneration: Long,
        val videoId: String,
        val sourceId: String
    )

    enum class Status {
        NOT_REPORTED,
        CUES_WITHOUT_TRACK_REPORT,
        TRACK_ABSENT,
        TRACK_UNSELECTED,
        SELECTED_NO_CUES,
        SELECTED_PARTIAL_CUES,
        SELECTED_ALL_CUES
    }

    data class Snapshot(
        val context: Context,
        val status: Status,
        val deliveredCueCount: Int,
        val expectedCueCount: Int,
        val selectedTrackAndAllCuesObserved: Boolean
    )

    private var activeContext: Context? = null
    private var highestLoadGeneration = 0L
    private var trackState: SyntheticSubtitleTrackStateObserver.State? = null
    private var deliveredCueCount = 0
    private var closed = false

    fun begin(context: Context): Boolean {
        if (!ownsThread() || closed || !valid(context) ||
            context.loadGeneration <= highestLoadGeneration) {
            return false
        }
        highestLoadGeneration = context.loadGeneration
        activeContext = context
        trackState = null
        deliveredCueCount = 0
        return true
    }

    fun observeTrack(
        context: Context,
        state: SyntheticSubtitleTrackStateObserver.State
    ): Boolean {
        if (!ownsThread() || closed || activeContext != context) return false
        if (state == SyntheticSubtitleTrackStateObserver.State.NOT_REPORTED) {
            poisonActiveGeneration()
            return false
        }
        trackState = state
        return true
    }

    fun observeCues(
        context: Context,
        delivered: Int,
        expected: Int,
        allExpectedObserved: Boolean
    ): Boolean {
        if (!ownsThread() || closed || activeContext != context) return false
        if (expected != EXPECTED_CUE_COUNT || delivered !in 0..expected ||
            allExpectedObserved != (delivered == expected) ||
            delivered < deliveredCueCount) {
            poisonActiveGeneration()
            return false
        }
        deliveredCueCount = delivered
        return true
    }

    fun invalidate(context: Context): Boolean {
        if (!ownsThread() || closed || activeContext != context) return false
        poisonActiveGeneration()
        return true
    }

    fun snapshot(context: Context): Snapshot? {
        if (!ownsThread() || closed || activeContext != context) return null
        val status = when (trackState) {
            null -> if (deliveredCueCount == 0) Status.NOT_REPORTED else Status.CUES_WITHOUT_TRACK_REPORT
            SyntheticSubtitleTrackStateObserver.State.ABSENT -> Status.TRACK_ABSENT
            SyntheticSubtitleTrackStateObserver.State.PRESENT_UNSELECTED -> Status.TRACK_UNSELECTED
            SyntheticSubtitleTrackStateObserver.State.PRESENT_SELECTED -> when (deliveredCueCount) {
                0 -> Status.SELECTED_NO_CUES
                EXPECTED_CUE_COUNT -> Status.SELECTED_ALL_CUES
                else -> Status.SELECTED_PARTIAL_CUES
            }
            SyntheticSubtitleTrackStateObserver.State.NOT_REPORTED -> return null
        }
        return Snapshot(
            context = context,
            status = status,
            deliveredCueCount = deliveredCueCount,
            expectedCueCount = EXPECTED_CUE_COUNT,
            selectedTrackAndAllCuesObserved = status == Status.SELECTED_ALL_CUES
        )
    }

    fun abandon(context: Context): Boolean {
        if (!ownsThread() || closed || activeContext != context) return false
        activeContext = null
        trackState = null
        deliveredCueCount = 0
        return true
    }

    fun close(): Boolean {
        if (!ownsThread() || closed) return false
        closed = true
        activeContext = null
        trackState = null
        deliveredCueCount = 0
        return true
    }

    private fun poisonActiveGeneration() {
        activeContext = null
        trackState = null
        deliveredCueCount = 0
    }

    private fun ownsThread(): Boolean = Thread.currentThread() === ownerThread

    private fun valid(context: Context): Boolean =
        context.playerGeneration > 0L && context.loadGeneration > 0L &&
            context.videoId.isNotBlank() && context.sourceId.isNotBlank()

    companion object {
        private const val EXPECTED_CUE_COUNT = 3
    }
}
