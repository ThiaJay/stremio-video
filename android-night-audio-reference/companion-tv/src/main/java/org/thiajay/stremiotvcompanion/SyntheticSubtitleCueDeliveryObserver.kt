package org.thiajay.stremiotvcompanion

/**
 * Records only the three deterministic subtitle cues emitted by the local lab fixture.
 * This is a Media3 callback boundary, not evidence that any cue was visibly rendered.
 */
internal class SyntheticSubtitleCueDeliveryObserver(
    private val ownerThread: Thread = Thread.currentThread()
) {
    data class Context(
        val playerGeneration: Long,
        val loadGeneration: Long,
        val videoId: String,
        val sourceId: String
    )

    data class Snapshot(
        val context: Context,
        val deliveredCueCount: Int,
        val expectedCueCount: Int,
        val allExpectedCuesObserved: Boolean
    )

    enum class Observation { ACCEPTED, NO_CUES, REFUSED }

    private var activeContext: Context? = null
    private var highestLoadGeneration = 0L
    private val delivered = LinkedHashSet<String>()
    private var closed = false

    fun begin(context: Context): Boolean {
        if (!ownsThread() || closed || !valid(context) ||
            context.loadGeneration <= highestLoadGeneration) {
            return false
        }
        highestLoadGeneration = context.loadGeneration
        activeContext = context
        delivered.clear()
        return true
    }

    fun observe(context: Context, cueTexts: List<String>): Observation {
        if (!ownsThread() || closed || activeContext != context) {
            return Observation.REFUSED
        }
        if (cueTexts.isEmpty()) return Observation.NO_CUES
        if (cueTexts.size > EXPECTED_CUES.size ||
            cueTexts.any { it.isBlank() || it !in EXPECTED_CUES }) {
            poisonActiveGeneration()
            return Observation.REFUSED
        }
        delivered.addAll(cueTexts)
        return Observation.ACCEPTED
    }

    fun snapshot(context: Context): Snapshot? {
        if (!ownsThread() || closed || activeContext != context) return null
        return Snapshot(
            context = context,
            deliveredCueCount = delivered.size,
            expectedCueCount = EXPECTED_CUES.size,
            allExpectedCuesObserved = delivered.containsAll(EXPECTED_CUES)
        )
    }

    fun abandon(context: Context): Boolean {
        if (!ownsThread() || closed || activeContext != context) return false
        activeContext = null
        delivered.clear()
        return true
    }

    fun close(): Boolean {
        if (!ownsThread() || closed) return false
        closed = true
        activeContext = null
        delivered.clear()
        return true
    }

    private fun poisonActiveGeneration() {
        activeContext = null
        delivered.clear()
    }

    private fun ownsThread(): Boolean = Thread.currentThread() === ownerThread

    private fun valid(context: Context): Boolean =
        context.playerGeneration > 0L && context.loadGeneration > 0L &&
            context.videoId.isNotBlank() && context.sourceId.isNotBlank()

    companion object {
        internal val EXPECTED_CUES = listOf(
            "Synthetic subtitle cue one.",
            "Synthetic subtitle cue two.",
            "Synthetic subtitle cue three."
        )
    }
}
