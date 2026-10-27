package org.pss.stremio.tv

/**
 * One focusable Android TV widget derived from an exact host-binding action.
 *
 * A widget ID is never sufficient authority. Consumption accepts only the same
 * widget instance returned by this controller and returns the original action
 * object for the existing host binding.
 */
class NativeTvTitleWatchedWidget internal constructor(
    val actionId: String,
    val label: String,
    val confirmation: String,
    val watched: Boolean,
    val requiresAcknowledgement: Boolean,
    private val action: NativeTvTitleWatchedViewAction,
    private val provenanceSeal: Any
) {
    internal fun actionFor(seal: Any): NativeTvTitleWatchedViewAction? =
        if (provenanceSeal === seal) action else null
}

data class NativeTvTitleWatchedWidgetIntent(
    val action: NativeTvTitleWatchedViewAction,
    val focusedActionId: String,
    val remoteKey: String
)

/**
 * Pure one-thread generation controller used by the real Android widget port.
 *
 * Key-down is ignored so the corresponding key-up can activate. Any key-up
 * attempt consumes the entire widget generation before provenance, focus and
 * key checks, preventing replay through a stale Android View callback.
 */
class NativeTvTitleWatchedWidgetController {
    companion object {
        const val KEY_ACTION_UP = "UP"
        const val KEY_ENTER = "ENTER"
        const val KEY_DPAD_CENTER = "DPAD_CENTER"
    }

    private data class Issued(
        val widget: NativeTvTitleWatchedWidget,
        val action: NativeTvTitleWatchedViewAction
    )

    private val ownerThread = Thread.currentThread()
    private val controllerSeal = Any()
    private val issued = LinkedHashMap<String, Issued>()
    private var closed = false

    fun replace(
        actions: List<NativeTvTitleWatchedViewAction>
    ): List<NativeTvTitleWatchedWidget>? {
        if (!onOwnerThread() || closed) return null
        issued.clear()
        if (actions.isEmpty() ||
            actions.size > NativeTvTitleWatchedMenu.MAX_PRESENTED_ACTIONS ||
            actions.any {
                it.actionId.isBlank() || it.label.isBlank() ||
                    it.confirmation.isBlank() || !it.focusable
            } ||
            actions.map { it.actionId }.toSet().size != actions.size
        ) return null

        val widgets = ArrayList<NativeTvTitleWatchedWidget>(actions.size)
        for (action in actions) {
            val widget = NativeTvTitleWatchedWidget(
                actionId = action.actionId,
                label = action.label,
                confirmation = action.confirmation,
                watched = action.watched,
                requiresAcknowledgement = action.requiresAcknowledgement,
                action = action,
                provenanceSeal = controllerSeal
            )
            if (issued.put(action.actionId, Issued(widget, action)) != null) {
                issued.clear()
                return null
            }
            widgets.add(widget)
        }
        return widgets.toList()
    }

    fun consume(
        widget: NativeTvTitleWatchedWidget,
        focusedActionId: String?,
        remoteKey: String,
        keyAction: String
    ): NativeTvTitleWatchedWidgetIntent? {
        if (!onOwnerThread() || closed) return null
        if (keyAction != KEY_ACTION_UP) return null

        val record = issued.remove(widget.actionId)
        issued.clear()
        val exactAction = widget.actionFor(controllerSeal)
        if (record == null ||
            record.widget !== widget ||
            exactAction !== record.action ||
            focusedActionId != widget.actionId ||
            (remoteKey != KEY_ENTER && remoteKey != KEY_DPAD_CENTER)
        ) return null

        return NativeTvTitleWatchedWidgetIntent(
            action = record.action,
            focusedActionId = widget.actionId,
            remoteKey = remoteKey
        )
    }

    fun clear(): Boolean {
        if (!onOwnerThread() || closed) return false
        issued.clear()
        return true
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        issued.clear()
        return true
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
