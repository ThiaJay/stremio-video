package org.thiajay.stremiotvcompanion

/**
 * Owns the single asynchronous window-pixel capture allowed for one cue revision.
 * A newer load may supersede an outstanding lease. Its late callback is then
 * stale and cannot clear or fail the newer load's capture.
 */
internal class SyntheticSubtitleWindowPixelCaptureLease(
    private val ownerThread: Thread = Thread.currentThread()
) {
    data class Context(
        val playerGeneration: Long,
        val loadGeneration: Long,
        val videoId: String,
        val sourceId: String,
        val cueRevision: Long
    )

    @ConsistentCopyVisibility
    data class Lease internal constructor(
        val attemptId: Long,
        val context: Context
    )

    enum class Resolution {
        CURRENT_SUCCESS,
        CURRENT_FAILURE,
        STALE_IGNORED
    }

    private var active: Lease? = null
    private var lastStartedContext: Context? = null
    private var highestLoadGeneration = 0L
    private var nextAttemptId = 0L
    private var closed = false

    fun begin(context: Context): Lease? {
        if (!ownsThread() || closed || !valid(context) ||
            context.loadGeneration < highestLoadGeneration ||
            context == lastStartedContext || nextAttemptId == Long.MAX_VALUE) {
            return null
        }
        val current = active
        if (current != null && context.loadGeneration <= current.context.loadGeneration) {
            return null
        }
        nextAttemptId++
        highestLoadGeneration = maxOf(highestLoadGeneration, context.loadGeneration)
        return Lease(nextAttemptId, context).also {
            active = it
            lastStartedContext = context
        }
    }

    fun resolve(
        lease: Lease,
        currentContext: Context?,
        copySucceeded: Boolean
    ): Resolution {
        if (!ownsThread() || closed || active !== lease) return Resolution.STALE_IGNORED
        active = null
        return if (copySucceeded && currentContext == lease.context &&
            valid(currentContext)) {
            Resolution.CURRENT_SUCCESS
        } else {
            Resolution.CURRENT_FAILURE
        }
    }

    fun abandon(context: Context): Boolean {
        if (!ownsThread() || closed || active?.context != context) return false
        active = null
        return true
    }

    fun pending(context: Context): Boolean =
        ownsThread() && !closed && active?.context == context

    fun pending(lease: Lease, currentContext: Context?): Boolean =
        ownsThread() && !closed && currentContext != null &&
            active === lease && currentContext == lease.context && valid(currentContext)

    fun close(): Boolean {
        if (!ownsThread() || closed) return false
        closed = true
        active = null
        lastStartedContext = null
        return true
    }

    private fun valid(context: Context): Boolean =
        context.playerGeneration > 0L && context.loadGeneration > 0L &&
            context.videoId.isNotBlank() && context.sourceId.isNotBlank() &&
            context.cueRevision > 0L

    private fun ownsThread(): Boolean = Thread.currentThread() === ownerThread
}
