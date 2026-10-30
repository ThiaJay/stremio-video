package org.thiajay.stremiotvcompanion

import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button

/**
 * Android mobile Button implementation for Story Order mode and episode rows.
 *
 * Framework click, accessibility click and keyboard activation all use the
 * semantic click callback. Raw touch coordinates are never admitted and long
 * press is consumed without selecting an episode.
 */
class AndroidMobileStoryOrderWidgetPort(
    private val container: ViewGroup,
    private val onModeRequested: (MobileStoryOrderMode) -> Unit,
    private val onActivation: (
        action: MobileStoryOrderViewAction,
        selectedVideoId: String,
        semanticEvent: String
    ) -> Unit
) : MobileStoryOrderViewPort {
    private val ownerThread = Thread.currentThread()
    private val ownedViews = ArrayList<View>()
    private var closed = false

    override fun replaceStoryRows(
        mode: MobileStoryOrderMode,
        actions: List<MobileStoryOrderViewAction>
    ): Boolean {
        if (!onMainOwnerThread() || closed) return false
        if (actions.isEmpty() ||
            actions.size > MobileStoryOrderHostBinding.MAX_PRESENTED_ROWS ||
            actions.any { it.stableVideoId.isBlank() } ||
            actions.map { it.stableVideoId }.toSet().size != actions.size
        ) return false

        val candidateViews = try {
            val views = ArrayList<View>(actions.size + 1)
            views += createModeButton(mode)
            for (action in actions) views += createEpisodeButton(action)
            views
        } catch (_: Exception) {
            return false
        } catch (_: LinkageError) {
            return false
        }

        if (!removeOwnedViews()) return poison()
        return try {
            for (view in candidateViews) {
                container.addView(view)
                ownedViews += view
            }
            true
        } catch (_: Exception) {
            poison()
        } catch (_: LinkageError) {
            poison()
        }
    }

    override fun clearStoryRows(): Boolean {
        if (!onMainOwnerThread() || closed) return false
        if (!removeOwnedViews()) return poison()
        return true
    }

    private fun createModeButton(current: MobileStoryOrderMode): Button {
        val requested = when (current) {
            MobileStoryOrderMode.CANONICAL -> MobileStoryOrderMode.STORY
            MobileStoryOrderMode.STORY -> MobileStoryOrderMode.CANONICAL
        }
        val label = when (requested) {
            MobileStoryOrderMode.CANONICAL -> "Show season order"
            MobileStoryOrderMode.STORY -> "Show story order"
        }
        return Button(container.context).apply {
            id = View.generateViewId()
            text = label
            contentDescription = label
            isAllCaps = false
            isFocusable = true
            isClickable = true
            setOnLongClickListener { true }
            setOnClickListener {
                if (!onMainOwnerThread() || closed) return@setOnClickListener
                deliverMode(requested)
            }
        }
    }

    private fun createEpisodeButton(action: MobileStoryOrderViewAction): Button {
        val coordinates = if (action.episodeNumber == null) {
            "Season " + action.season
        } else {
            "S" + action.season + " E" + action.episodeNumber
        }
        val label = coordinates + "  " + action.title
        return Button(container.context).apply {
            id = View.generateViewId()
            text = label
            contentDescription = label
            tag = action.stableVideoId
            isAllCaps = false
            isFocusable = true
            isClickable = true
            setOnLongClickListener { true }
            setOnClickListener {
                if (!onMainOwnerThread() || closed) return@setOnClickListener
                if (!removeOwnedViews()) {
                    poison()
                    return@setOnClickListener
                }
                deliverActivation(action)
            }
        }
    }

    private fun deliverMode(mode: MobileStoryOrderMode) {
        try {
            onModeRequested(mode)
        } catch (_: Exception) {
            poison()
        } catch (_: LinkageError) {
            poison()
        }
    }

    private fun deliverActivation(action: MobileStoryOrderViewAction) {
        try {
            onActivation(
                action,
                action.stableVideoId,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        } catch (_: Exception) {
            poison()
        } catch (_: LinkageError) {
            poison()
        }
    }

    private fun removeOwnedViews(): Boolean = try {
        for (view in ownedViews) container.removeView(view)
        ownedViews.clear()
        true
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun poison(): Boolean {
        closed = true
        removeOwnedViews()
        return false
    }

    private fun onMainOwnerThread(): Boolean =
        Thread.currentThread() === ownerThread &&
            Looper.myLooper() === Looper.getMainLooper()
}
