package org.thiajay.stremiotvcompanion

/**
 * Exact mobile view action issued by [MobileStoryOrderHostBinding].
 *
 * A stable ID, label or list position is not activation authority. The view
 * must return this exact object through the semantic activation path.
 */
class MobileStoryOrderViewAction internal constructor(
    val stableVideoId: String,
    val title: String,
    val season: Int,
    val episodeNumber: Int?,
    private val row: MobileStoryOrderRow,
    private val provenanceSeal: Any
) {
    internal fun rowFor(seal: Any): MobileStoryOrderRow? =
        if (provenanceSeal === seal) row else null
}

interface MobileStoryOrderViewPort {
    fun replaceStoryRows(
        mode: MobileStoryOrderMode,
        actions: List<MobileStoryOrderViewAction>
    ): Boolean

    fun clearStoryRows(): Boolean
}

/**
 * Pure host boundary between the accepted Story Order controller and an
 * Android mobile view port.
 *
 * It never owns a player, account, network or persistence operation. Every
 * rendered generation is exact-object and stable-ID bound. A malformed view
 * callback revokes the whole generation before it can reach the controller.
 */
class MobileStoryOrderHostBinding(
    canonical: List<CanonicalEpisode>,
    hints: StoryOrderHints?,
    private val view: MobileStoryOrderViewPort
) {
    companion object {
        const val MAX_PRESENTED_ROWS = 500
    }

    private data class Issued(
        val action: MobileStoryOrderViewAction,
        val row: MobileStoryOrderRow
    )

    private val ownerThread = Thread.currentThread()
    private val bindingSeal = Any()
    private val controller = MobileStoryOrderController(canonical, hints)
    private val issued = LinkedHashMap<String, Issued>()
    private var rendering = false
    private var closed = !controller.isOperational()

    fun render(mode: MobileStoryOrderMode): Boolean {
        if (!onOwnerThread() || closed || rendering) return false
        if (issued.isNotEmpty() && !revokePresentation()) return false

        val rows = controller.present(mode) ?: return poison()
        if (rows.isEmpty() || rows.size > MAX_PRESENTED_ROWS) return poison()

        val actions = ArrayList<MobileStoryOrderViewAction>(rows.size)
        for (row in rows) {
            val action = MobileStoryOrderViewAction(
                stableVideoId = row.stableVideoId,
                title = row.title,
                season = row.season,
                episodeNumber = row.episodeNumber,
                row = row,
                provenanceSeal = bindingSeal
            )
            if (issued.put(row.stableVideoId, Issued(action, row)) != null) {
                return poison()
            }
            actions += action
        }

        rendering = true
        val replaced = replaceOrFalse(mode, actions)
        rendering = false
        if (!replaced) return poison()
        return true
    }

    fun activate(
        action: MobileStoryOrderViewAction,
        selectedVideoId: String?,
        semanticEvent: String
    ): CanonicalEpisode? {
        if (!onOwnerThread() || closed || rendering) return null

        val record = issued.remove(action.stableVideoId)
        issued.clear()
        val exactRow = action.rowFor(bindingSeal)
        val valid = record != null &&
            record.action === action &&
            exactRow === record.row &&
            selectedVideoId == action.stableVideoId &&
            semanticEvent == MobileStoryOrderController.SEMANTIC_SELECTION

        if (!clearOrFalse()) {
            poison()
            return null
        }
        if (!valid) return null

        val selected = controller.select(
            row = record!!.row,
            selectedVideoId = selectedVideoId,
            semanticEvent = semanticEvent
        )
        if (selected == null) poison()
        return selected
    }

    fun next(currentVideoId: String): CanonicalEpisode? {
        if (!onOwnerThread() || closed || rendering) return null
        return controller.next(currentVideoId)
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed || rendering) return false
        closed = true
        issued.clear()
        val viewCleared = clearOrFalse()
        val controllerClosed = controller.close()
        return viewCleared && controllerClosed
    }

    fun isOperational(): Boolean =
        onOwnerThread() && !closed && !rendering && controller.isOperational()

    private fun revokePresentation(): Boolean {
        issued.clear()
        if (!clearOrFalse()) return poison()
        return true
    }

    private fun replaceOrFalse(
        mode: MobileStoryOrderMode,
        actions: List<MobileStoryOrderViewAction>
    ): Boolean = try {
        view.replaceStoryRows(mode, actions.toList())
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun clearOrFalse(): Boolean = try {
        view.clearStoryRows()
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun poison(): Boolean {
        if (!closed) {
            closed = true
            issued.clear()
            clearOrFalse()
            controller.close()
        }
        rendering = false
        return false
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
