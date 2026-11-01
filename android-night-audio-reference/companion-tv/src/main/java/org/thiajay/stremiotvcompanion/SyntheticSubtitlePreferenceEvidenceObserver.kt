package org.thiajay.stremiotvcompanion

/**
 * Binds synthetic Media3 callback evidence to the exact subtitle preference
 * admitted for one load generation. This observes callbacks only; it never
 * accepts visible subtitle rendering.
 */
internal class SyntheticSubtitlePreferenceEvidenceObserver(
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
        UNADMITTED_ENABLED_HELD,
        MANUAL_SELECTION_HELD,
        ENABLED_WAITING_AUTOMATIC_SELECTION,
        ENABLED_CALLBACKS_COMPLETE,
        OFF_WAITING_AUTOMATIC_CLEAR,
        OFF_WAITING_COMPLETION,
        OFF_CALLBACKS_COMPLETE
    }

    data class Snapshot(
        val context: Context,
        val preference: NativeSubtitlePlaybackPreferenceActuator.Preference,
        val status: Status,
        val callbackEvidenceComplete: Boolean,
        val visibleRenderingAccepted: Boolean = false
    )

    private var activeContext: Context? = null
    private var activePreference: NativeSubtitlePlaybackPreferenceActuator.Preference? = null
    private var status = Status.NOT_REPORTED
    private var highestLoadGeneration = 0L
    private var playbackEnded = false
    private var manualSelectionObserved = false
    private var completed = false
    private var closed = false

    fun begin(
        context: Context,
        preference: NativeSubtitlePlaybackPreferenceActuator.Preference
    ): Boolean {
        if (!ownsThread() || closed || !valid(context) || !valid(preference) ||
            context.loadGeneration <= highestLoadGeneration) {
            return false
        }
        highestLoadGeneration = context.loadGeneration
        activeContext = context
        activePreference = preference
        status = if (preference.enabled && !preference.sameInstallationReceipt) {
            Status.UNADMITTED_ENABLED_HELD
        } else {
            Status.NOT_REPORTED
        }
        playbackEnded = false
        manualSelectionObserved = false
        completed = false
        return true
    }

    fun observe(
        context: Context,
        preference: NativeSubtitlePlaybackPreferenceActuator.Preference,
        origin: NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin,
        evidence: SyntheticSubtitlePlaybackEvidenceObserver.Snapshot,
        ended: Boolean
    ): Boolean {
        if (!ownsThread() || closed || activeContext != context ||
            activePreference != preference || evidence.context != context.toPlaybackContext() ||
            !validEvidence(evidence) || (playbackEnded && !ended)) {
            poisonActiveGeneration()
            return false
        }
        playbackEnded = playbackEnded || ended
        manualSelectionObserved = manualSelectionObserved ||
            origin == NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.MANUAL

        val next = classify(preference, origin, evidence)
        if (completed && next != status) {
            poisonActiveGeneration()
            return false
        }
        if (status == Status.MANUAL_SELECTION_HELD) {
            status = Status.MANUAL_SELECTION_HELD
        } else {
            status = next
        }
        completed = status == Status.ENABLED_CALLBACKS_COMPLETE ||
            status == Status.OFF_CALLBACKS_COMPLETE
        return true
    }

    fun invalidate(context: Context): Boolean {
        if (!ownsThread() || closed || activeContext != context) return false
        poisonActiveGeneration()
        return true
    }

    fun snapshot(context: Context): Snapshot? {
        if (!ownsThread() || closed || activeContext != context) return null
        val preference = activePreference ?: return null
        return Snapshot(
            context = context,
            preference = preference,
            status = status,
            callbackEvidenceComplete = completed
        )
    }

    fun abandon(context: Context): Boolean {
        if (!ownsThread() || closed || activeContext != context) return false
        clearActive()
        return true
    }

    fun close(): Boolean {
        if (!ownsThread() || closed) return false
        closed = true
        clearActive()
        return true
    }

    private fun classify(
        preference: NativeSubtitlePlaybackPreferenceActuator.Preference,
        origin: NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin,
        evidence: SyntheticSubtitlePlaybackEvidenceObserver.Snapshot
    ): Status {
        if (preference.enabled && !preference.sameInstallationReceipt) {
            return Status.UNADMITTED_ENABLED_HELD
        }
        if (manualSelectionObserved) return Status.MANUAL_SELECTION_HELD
        if (preference.enabled) {
            return if (
                origin == NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.AUTOMATIC &&
                evidence.status == SyntheticSubtitlePlaybackEvidenceObserver.Status.SELECTED_ALL_CUES
            ) {
                Status.ENABLED_CALLBACKS_COMPLETE
            } else {
                Status.ENABLED_WAITING_AUTOMATIC_SELECTION
            }
        }
        return when {
            origin == NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.AUTOMATIC ->
                Status.OFF_WAITING_AUTOMATIC_CLEAR
            playbackEnded &&
                origin == NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.NONE &&
                evidence.status == SyntheticSubtitlePlaybackEvidenceObserver.Status.TRACK_UNSELECTED &&
                evidence.deliveredCueCount == 0 -> Status.OFF_CALLBACKS_COMPLETE
            else -> Status.OFF_WAITING_COMPLETION
        }
    }

    private fun validEvidence(
        evidence: SyntheticSubtitlePlaybackEvidenceObserver.Snapshot
    ): Boolean = evidence.expectedCueCount == EXPECTED_CUE_COUNT &&
        evidence.deliveredCueCount in 0..EXPECTED_CUE_COUNT &&
        evidence.selectedTrackAndAllCuesObserved ==
            (evidence.status == SyntheticSubtitlePlaybackEvidenceObserver.Status.SELECTED_ALL_CUES)

    private fun valid(context: Context): Boolean =
        context.playerGeneration > 0L && context.loadGeneration > 0L &&
            context.videoId.isNotBlank() && context.sourceId.isNotBlank()

    private fun valid(
        preference: NativeSubtitlePlaybackPreferenceActuator.Preference
    ): Boolean = preference.enabled || !preference.sameInstallationReceipt

    private fun Context.toPlaybackContext() =
        SyntheticSubtitlePlaybackEvidenceObserver.Context(
            playerGeneration,
            loadGeneration,
            videoId,
            sourceId
        )

    private fun poisonActiveGeneration() = clearActive()

    private fun clearActive() {
        activeContext = null
        activePreference = null
        status = Status.NOT_REPORTED
        playbackEnded = false
        manualSelectionObserved = false
        completed = false
    }

    private fun ownsThread(): Boolean = Thread.currentThread() === ownerThread

    companion object {
        private const val EXPECTED_CUE_COUNT = 3
    }
}
