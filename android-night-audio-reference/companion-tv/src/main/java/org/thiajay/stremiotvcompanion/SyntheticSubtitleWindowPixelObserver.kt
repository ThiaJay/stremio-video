package org.thiajay.stremiotvcompanion

/**
 * Reduces one synthetic PlayerView window-buffer result to bounded counts.
 * The pixel array is never retained. Accepted evidence proves only that the
 * app window buffer contained the expected synthetic output; it is not proof
 * of physical display visibility, legibility or device acceptance.
 */
internal class SyntheticSubtitleWindowPixelObserver(
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
        RENDER_PIPELINE_PENDING,
        ENABLED_WINDOW_PIXELS_OBSERVED,
        OFF_DARK_WINDOW_OBSERVED,
        WINDOW_PIXELS_REFUSED
    }

    data class Metrics(
        val width: Int,
        val height: Int,
        val totalPixels: Int,
        val lowerNeutralBrightPixels: Int,
        val upperNeutralBrightPixels: Int,
        val nonOpaquePixels: Int
    )

    data class Snapshot(
        val context: Context,
        val status: Status,
        val cueRevision: Long,
        val metrics: Metrics?,
        val windowPixelEvidenceComplete: Boolean,
        val visibleRenderingAccepted: Boolean = false,
        val physicalDisplayAccepted: Boolean = false,
        val deviceAcceptance: Boolean = false
    )

    private var activeContext: Context? = null
    private var activePreference: NativeSubtitlePlaybackPreferenceActuator.Preference? = null
    private var highestLoadGeneration = 0L
    private var cueRevision = 0L
    private var metrics: Metrics? = null
    private var status = Status.NOT_REPORTED
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
        cueRevision = 0L
        metrics = null
        status = Status.RENDER_PIPELINE_PENDING
        completed = false
        return true
    }

    fun observe(
        context: Context,
        renderEvidence: SyntheticSubtitleRenderPipelineObserver.Snapshot,
        revision: Long,
        width: Int,
        height: Int,
        argbPixels: IntArray
    ): Boolean {
        if (!ownsThread() || closed) return false
        if (activeContext != context || completed ||
            renderEvidence.context != context.toRenderContext() ||
            renderEvidence.preference != activePreference ||
            !validRenderEvidence(renderEvidence, revision)) {
            poisonActiveGeneration()
            return false
        }
        val observed = reduce(width, height, argbPixels) ?: run {
            poisonActiveGeneration()
            return false
        }
        cueRevision = revision
        metrics = observed
        val preference = activePreference ?: run {
            poisonActiveGeneration()
            return false
        }
        status = when {
            preference.enabled && enabledPixels(observed) ->
                Status.ENABLED_WINDOW_PIXELS_OBSERVED
            !preference.enabled && offPixels(observed) ->
                Status.OFF_DARK_WINDOW_OBSERVED
            else -> Status.WINDOW_PIXELS_REFUSED
        }
        completed = status == Status.ENABLED_WINDOW_PIXELS_OBSERVED ||
            status == Status.OFF_DARK_WINDOW_OBSERVED
        if (!completed) {
            poisonActiveGeneration()
            return false
        }
        return true
    }

    fun snapshot(context: Context): Snapshot? {
        if (!ownsThread() || closed || activeContext != context) return null
        return Snapshot(
            context,
            status,
            cueRevision,
            metrics,
            completed
        )
    }

    fun invalidate(context: Context): Boolean {
        if (!ownsThread() || closed || activeContext != context) return false
        poisonActiveGeneration()
        return true
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

    private fun reduce(width: Int, height: Int, pixels: IntArray): Metrics? {
        if (width !in MIN_DIMENSION..MAX_DIMENSION ||
            height !in MIN_DIMENSION..MAX_DIMENSION) return null
        val total = width.toLong() * height.toLong()
        if (total !in 1L..MAX_PIXELS.toLong() || total != pixels.size.toLong()) return null
        val lowerStart = height * LOWER_BAND_PERCENT / 100
        var lowerBright = 0
        var upperBright = 0
        var nonOpaque = 0
        pixels.forEachIndexed { index, pixel ->
            val alpha = pixel ushr 24 and 0xff
            if (alpha != 0xff) nonOpaque++
            if (neutralBright(pixel)) {
                if (index / width >= lowerStart) lowerBright++ else upperBright++
            }
        }
        return Metrics(width, height, pixels.size, lowerBright, upperBright, nonOpaque)
    }

    private fun neutralBright(pixel: Int): Boolean {
        val alpha = pixel ushr 24 and 0xff
        val red = pixel ushr 16 and 0xff
        val green = pixel ushr 8 and 0xff
        val blue = pixel and 0xff
        val high = maxOf(red, green, blue)
        val low = minOf(red, green, blue)
        return alpha >= MIN_ALPHA && low >= MIN_BRIGHT && high - low <= MAX_NEUTRAL_SPREAD
    }

    private fun enabledPixels(observed: Metrics): Boolean =
        observed.nonOpaquePixels == 0 &&
            observed.lowerNeutralBrightPixels >= MIN_ENABLED_BRIGHT_PIXELS &&
            observed.lowerNeutralBrightPixels <= observed.totalPixels / MAX_BRIGHT_FRACTION &&
            observed.lowerNeutralBrightPixels > observed.upperNeutralBrightPixels * 2

    private fun offPixels(observed: Metrics): Boolean =
        observed.nonOpaquePixels == 0 &&
            observed.lowerNeutralBrightPixels + observed.upperNeutralBrightPixels <=
            MAX_OFF_BRIGHT_PIXELS

    private fun validRenderEvidence(
        evidence: SyntheticSubtitleRenderPipelineObserver.Snapshot,
        revision: Long
    ): Boolean = evidence.renderPipelineEvidenceComplete &&
        !evidence.visibleRenderingAccepted && !evidence.pixelOutputAccepted &&
        !evidence.deviceAcceptance && revision > 0L &&
        evidence.cueRevision == revision && evidence.drawRevision == revision &&
        evidence.status == if (activePreference?.enabled == true) {
            SyntheticSubtitleRenderPipelineObserver.Status.ENABLED_DRAW_PIPELINE_OBSERVED
        } else {
            SyntheticSubtitleRenderPipelineObserver.Status.OFF_EMPTY_DRAW_PIPELINE_OBSERVED
        }

    private fun valid(context: Context): Boolean =
        context.playerGeneration > 0L && context.loadGeneration > 0L &&
            context.videoId.isNotBlank() && context.sourceId.isNotBlank()

    private fun valid(
        preference: NativeSubtitlePlaybackPreferenceActuator.Preference
    ): Boolean = preference.enabled || !preference.sameInstallationReceipt

    private fun Context.toRenderContext() =
        SyntheticSubtitleRenderPipelineObserver.Context(
            playerGeneration,
            loadGeneration,
            videoId,
            sourceId
        )

    private fun poisonActiveGeneration() = clearActive()

    private fun clearActive() {
        activeContext = null
        activePreference = null
        cueRevision = 0L
        metrics = null
        status = Status.NOT_REPORTED
        completed = false
    }

    private fun ownsThread(): Boolean = Thread.currentThread() === ownerThread

    private companion object {
        const val MIN_DIMENSION = 16
        const val MAX_DIMENSION = 4096
        const val MAX_PIXELS = 4_194_304
        const val LOWER_BAND_PERCENT = 45
        const val MIN_ALPHA = 240
        const val MIN_BRIGHT = 200
        const val MAX_NEUTRAL_SPREAD = 24
        const val MIN_ENABLED_BRIGHT_PIXELS = 16
        const val MAX_BRIGHT_FRACTION = 10
        const val MAX_OFF_BRIGHT_PIXELS = 4
    }
}
