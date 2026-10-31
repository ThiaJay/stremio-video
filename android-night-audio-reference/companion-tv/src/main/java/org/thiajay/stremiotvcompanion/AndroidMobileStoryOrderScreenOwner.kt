package org.thiajay.stremiotvcompanion

import android.os.Looper
import android.view.ViewGroup

/**
 * Android main-thread composition root for mobile Story Order presentation.
 *
 * The selected canonical episode is held in a one-slot mailbox for the private
 * client to consume through its already reviewed player boundary. This owner
 * performs no playback, account, network or persistence operation itself.
 */
class AndroidMobileStoryOrderScreenOwner private constructor(
    container: ViewGroup,
    canonical: List<CanonicalEpisode>,
    hints: StoryOrderHints?
) {
    companion object {
        fun create(
            container: ViewGroup,
            canonical: List<CanonicalEpisode>,
            hints: StoryOrderHints?
        ): AndroidMobileStoryOrderScreenOwner? {
            if (Looper.myLooper() !== Looper.getMainLooper()) return null
            val owner = try {
                AndroidMobileStoryOrderScreenOwner(container, canonical, hints)
            } catch (_: Exception) {
                null
            } catch (_: LinkageError) {
                null
            }
            return owner?.takeIf { it.ready }
        }
    }

    private val ownerThread = Thread.currentThread()
    private var pendingSelection: CanonicalEpisode? = null
    private var closed = false
    private var ready = false
    private val port = AndroidMobileStoryOrderWidgetPort(
        container = container,
        onModeRequested = ::onModeRequested,
        onActivation = ::onActivation
    )
    private val host = MobileStoryOrderHostBinding(canonical, hints, port)

    init {
        ready = host.isOperational()
        if (!ready) {
            host.close()
            closed = true
        }
    }

    fun render(mode: MobileStoryOrderMode): Boolean {
        if (!onMainOwnerThread() || closed || !ready || pendingSelection != null) {
            return false
        }
        if (!host.render(mode)) return poison()
        return true
    }

    fun consumeSelection(): CanonicalEpisode? {
        if (!onMainOwnerThread() || closed || !ready) return null
        val selected = pendingSelection ?: return null
        pendingSelection = null
        return selected
    }

    fun next(currentVideoId: String): CanonicalEpisode? {
        if (!onMainOwnerThread() || closed || !ready || pendingSelection != null) {
            return null
        }
        return host.next(currentVideoId)
    }

    fun close(): Boolean {
        if (!onMainOwnerThread() || closed) return false
        closed = true
        ready = false
        pendingSelection = null
        return host.close()
    }

    private fun onModeRequested(mode: MobileStoryOrderMode) {
        if (!onMainOwnerThread() || closed || !ready || pendingSelection != null) {
            poison()
            return
        }
        if (!host.render(mode)) poison()
    }

    private fun onActivation(
        action: MobileStoryOrderViewAction,
        selectedVideoId: String,
        semanticEvent: String
    ) {
        if (!onMainOwnerThread() || closed || !ready || pendingSelection != null) {
            poison()
            return
        }
        val selected = host.activate(action, selectedVideoId, semanticEvent)
        if (selected == null) {
            poison()
            return
        }
        pendingSelection = selected
    }

    private fun poison(): Boolean {
        if (!closed) {
            closed = true
            ready = false
            pendingSelection = null
            host.close()
        }
        return false
    }

    private fun onMainOwnerThread(): Boolean =
        Thread.currentThread() === ownerThread &&
            Looper.myLooper() === Looper.getMainLooper()
}
