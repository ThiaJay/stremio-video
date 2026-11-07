package org.thiajay.stremiotvcompanion

/**
 * Correlates the already bounded preference/callback evidence with a draw from
 * Media3's subtitle view. A draw callback establishes only that the Android
 * view pipeline ran. It never accepts visible pixels, a display or a device.
 */
internal class SyntheticSubtitleRenderPipelineObserver(
    private val ownerThread: Thread = Thread.currentThread()
) {
    data class Context(
        val playerGeneration: Long,
        val loadGeneration: Long,
        val videoId: String,
        val sourceId: String
    )

    data class ViewState(
        val attached: Boolean,
        val shown: Boolean,
        val width: Int,
        val height: Int,
        val alphaPermille: Int
    )

    enum class Status {
        NOT_REPORTED,
        PREFERENCE_HELD,
        CALLBACKS_PENDING,
        VIEW_NOT_READY,
        ENABLED_WAITING_DRAW,
        ENABLED_DRAW_PIPELINE_OBSERVED,
        OFF_WAITING_EMPTY_DRAW,
        OFF_EMPTY_DRAW_PIPELINE_OBSERVED
    }

    data class Snapshot(
        val context: Context,
        val preference: NativeSubtitlePlaybackPreferenceActuator.Preference,
        val status: Status,
        val cueRevision: Long,
        val drawRevision: Long,
        val renderPipelineEvidenceComplete: Boolean,
        val visibleRenderingAccepted: Boolean = false,
        val pixelOutputAccepted: Boolean = false,
        val deviceAcceptance: Boolean = false
    )

    private var activeContext: Context? = null
    private var activePreference: NativeSubtitlePlaybackPreferenceActuator.Preference? = null
    private var preferenceEvidence: SyntheticSubtitlePreferenceEvidenceObserver.Snapshot? = null
    private var viewState: ViewState? = null
    private var cueRevision = 0L
    private var activeCueCount = 0
    private var drawRevision = 0L
    private var status = Status.NOT_REPORTED
    private var highestLoadGeneration = 0L
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
        preferenceEvidence = null
        viewState = null
        cueRevision = 0L
        activeCueCount = 0
        drawRevision = 0L
        completed = false
        status = if (preference.enabled && !preference.sameInstallationReceipt) {
            Status.PREFERENCE_HELD
        } else {
            Status.NOT_REPORTED
        }
        return true
    }

    fun observePreferenceEvidence(
        context: Context,
        preference: NativeSubtitlePlaybackPreferenceActuator.Preference,
        evidence: SyntheticSubtitlePreferenceEvidenceObserver.Snapshot
    ): Boolean {
        if (!ownsThread() || closed || activeContext != context ||
            activePreference != preference || evidence.context != context.toPreferenceContext() ||
            evidence.preference != preference || !validEvidence(evidence)) {
            poisonActiveGeneration()
            return false
        }
        if (completed && preferenceEvidence != evidence) {
            poisonActiveGeneration()
            return false
        }
        preferenceEvidence = evidence
        if (!completed) classifyWaitingState()
        return true
    }

    fun observeCueGroup(context: Context, revision: Long, activeCueCount: Int): Boolean {
        if (!ownsThread() || closed || activeContext != context || revision <= cueRevision ||
            activeCueCount !in 0..1) {
            poisonActiveGeneration()
            return false
        }
        cueRevision = revision
        this.activeCueCount = activeCueCount
        if (!completed) classifyWaitingState()
        return true
    }

    fun observeView(context: Context, state: ViewState): Boolean {
        if (!ownsThread() || closed || activeContext != context || !valid(state)) {
            poisonActiveGeneration()
            return false
        }
        viewState = state
        if (!completed) classifyWaitingState()
        return true
    }

    fun observeDraw(context: Context, revision: Long): Boolean {
        if (!ownsThread() || closed || activeContext != context) return false
        if (revision != cueRevision || revision <= 0L || revision < drawRevision) return false
        if (completed) {
            drawRevision = revision
            return true
        }
        drawRevision = revision
        val preference = activePreference ?: return false
        val evidence = preferenceEvidence ?: run {
            status = Status.CALLBACKS_PENDING
            return true
        }
        if (!ready(viewState)) {
            status = Status.VIEW_NOT_READY
            return true
        }
        status = when {
            evidence.status ==
                SyntheticSubtitlePreferenceEvidenceObserver.Status.UNADMITTED_ENABLED_HELD ||
                evidence.status ==
                SyntheticSubtitlePreferenceEvidenceObserver.Status.MANUAL_SELECTION_HELD ->
                Status.PREFERENCE_HELD
            preference.enabled && evidence.callbackEvidenceComplete && activeCueCount == 1 ->
                Status.ENABLED_DRAW_PIPELINE_OBSERVED
            !preference.enabled && evidence.callbackEvidenceComplete && activeCueCount == 0 ->
                Status.OFF_EMPTY_DRAW_PIPELINE_OBSERVED
            preference.enabled -> Status.ENABLED_WAITING_DRAW
            else -> Status.OFF_WAITING_EMPTY_DRAW
        }
        completed = status == Status.ENABLED_DRAW_PIPELINE_OBSERVED ||
            status == Status.OFF_EMPTY_DRAW_PIPELINE_OBSERVED
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
            context,
            preference,
            status,
            cueRevision,
            drawRevision,
            completed
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

    private fun classifyWaitingState() {
        val preference = activePreference ?: return
        val evidence = preferenceEvidence
        status = when {
            preference.enabled && !preference.sameInstallationReceipt -> Status.PREFERENCE_HELD
            evidence == null -> Status.CALLBACKS_PENDING
            evidence.status ==
                SyntheticSubtitlePreferenceEvidenceObserver.Status.UNADMITTED_ENABLED_HELD ||
                evidence.status ==
                SyntheticSubtitlePreferenceEvidenceObserver.Status.MANUAL_SELECTION_HELD ->
                Status.PREFERENCE_HELD
            !ready(viewState) -> Status.VIEW_NOT_READY
            preference.enabled -> Status.ENABLED_WAITING_DRAW
            else -> Status.OFF_WAITING_EMPTY_DRAW
        }
    }

    private fun validEvidence(
        evidence: SyntheticSubtitlePreferenceEvidenceObserver.Snapshot
    ): Boolean = !evidence.visibleRenderingAccepted &&
        evidence.callbackEvidenceComplete == (
            evidence.status ==
                SyntheticSubtitlePreferenceEvidenceObserver.Status.ENABLED_CALLBACKS_COMPLETE ||
                evidence.status ==
                SyntheticSubtitlePreferenceEvidenceObserver.Status.OFF_CALLBACKS_COMPLETE
            )

    private fun valid(context: Context): Boolean =
        context.playerGeneration > 0L && context.loadGeneration > 0L &&
            context.videoId.isNotBlank() && context.sourceId.isNotBlank()

    private fun valid(
        preference: NativeSubtitlePlaybackPreferenceActuator.Preference
    ): Boolean = preference.enabled || !preference.sameInstallationReceipt

    private fun valid(state: ViewState): Boolean =
        state.width >= 0 && state.height >= 0 && state.alphaPermille in 0..1000

    private fun ready(state: ViewState?): Boolean = state != null &&
        state.attached && state.shown && state.width > 0 && state.height > 0 &&
        state.alphaPermille == 1000

    private fun Context.toPreferenceContext() =
        SyntheticSubtitlePreferenceEvidenceObserver.Context(
            playerGeneration,
            loadGeneration,
            videoId,
            sourceId
        )

    private fun poisonActiveGeneration() = clearActive()

    private fun clearActive() {
        activeContext = null
        activePreference = null
        preferenceEvidence = null
        viewState = null
        cueRevision = 0L
        activeCueCount = 0
        drawRevision = 0L
        status = Status.NOT_REPORTED
        completed = false
    }

    private fun ownsThread(): Boolean = Thread.currentThread() === ownerThread
}
