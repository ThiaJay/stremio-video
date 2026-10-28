package org.pss.stremio.tv

/**
 * One Android mobile widget model derived from an exact host-binding action.
 *
 * Selection by ID alone is never authority. Consumption accepts only the same
 * widget instance and returns the original action object.
 */
class NativeMobileTitleWatchedWidget internal constructor(
    val actionId: String,
    val label: String,
    val confirmation: String,
    val watched: Boolean,
    val requiresAcknowledgement: Boolean,
    private val action: NativeMobileTitleWatchedViewAction,
    private val provenanceSeal: Any
) {
    internal fun actionFor(seal: Any): NativeMobileTitleWatchedViewAction? =
        if (provenanceSeal === seal) action else null
}

class NativeMobileTitleWatchedWidgetIntent internal constructor(
    val action: NativeMobileTitleWatchedViewAction,
    val selectedActionId: String,
    val semanticEvent: String,
    private val provenanceSeal: Any
) {
    internal fun issuedBy(seal: Any): Boolean = provenanceSeal === seal
}

class NativeMobileTitleWatchedConfirmation internal constructor(
    internal val intent: NativeMobileTitleWatchedWidgetIntent,
    private val provenanceSeal: Any
) {
    internal fun issuedBy(seal: Any): Boolean = provenanceSeal === seal
}

data class NativeMobileTitleWatchedConfirmationResult(
    val intent: NativeMobileTitleWatchedWidgetIntent,
    val acknowledged: Boolean
)

/**
 * One-thread, one-generation gate for a real Android mobile title-action view.
 *
 * Every semantic activation attempt consumes the complete widget generation
 * before provenance, selection and event validation.
 */
class NativeMobileTitleWatchedWidgetController {
    private data class Issued(
        val widget: NativeMobileTitleWatchedWidget,
        val action: NativeMobileTitleWatchedViewAction
    )

    private val ownerThread = Thread.currentThread()
    private val controllerSeal = Any()
    private val confirmationSeal = Any()
    private val issued = LinkedHashMap<String, Issued>()
    private var confirmableIntent: NativeMobileTitleWatchedWidgetIntent? = null
    private var pendingConfirmation: NativeMobileTitleWatchedConfirmation? = null
    private var closed = false

    fun replace(
        actions: List<NativeMobileTitleWatchedViewAction>
    ): List<NativeMobileTitleWatchedWidget>? {
        if (!onOwnerThread() || closed) return null
        issued.clear()
        confirmableIntent = null
        pendingConfirmation = null
        if (actions.isEmpty() ||
            actions.size > NativeMobileTitleWatchedMenu.MAX_PRESENTED_ACTIONS ||
            actions.any {
                it.actionId.isBlank() || it.label.isBlank() || it.confirmation.isBlank()
            } ||
            actions.map { it.actionId }.toSet().size != actions.size
        ) return null

        val widgets = ArrayList<NativeMobileTitleWatchedWidget>(actions.size)
        for (action in actions) {
            val widget = NativeMobileTitleWatchedWidget(
                actionId = action.actionId,
                label = action.label,
                confirmation = action.confirmation,
                watched = action.watched,
                requiresAcknowledgement = !action.watched,
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
        widget: NativeMobileTitleWatchedWidget,
        selectedActionId: String?,
        semanticEvent: String
    ): NativeMobileTitleWatchedWidgetIntent? {
        if (!onOwnerThread() || closed) return null
        val record = issued.remove(widget.actionId)
        issued.clear()
        val exactAction = widget.actionFor(controllerSeal)
        if (record == null ||
            record.widget !== widget ||
            exactAction !== record.action ||
            selectedActionId != widget.actionId ||
            semanticEvent != NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
        ) return null

        val intent = NativeMobileTitleWatchedWidgetIntent(
            action = record.action,
            selectedActionId = widget.actionId,
            semanticEvent = semanticEvent,
            provenanceSeal = controllerSeal
        )
        confirmableIntent = if (widget.requiresAcknowledgement) intent else null
        return intent
    }

    fun beginConfirmation(
        intent: NativeMobileTitleWatchedWidgetIntent
    ): NativeMobileTitleWatchedConfirmation? {
        if (!onOwnerThread() || closed || pendingConfirmation != null ||
            confirmableIntent !== intent || !intent.issuedBy(controllerSeal) ||
            intent.action.watched
        ) return null

        confirmableIntent = null
        return NativeMobileTitleWatchedConfirmation(intent, confirmationSeal).also {
            pendingConfirmation = it
        }
    }

    fun resolveConfirmation(
        confirmation: NativeMobileTitleWatchedConfirmation,
        acknowledged: Boolean
    ): NativeMobileTitleWatchedConfirmationResult? {
        if (!onOwnerThread() || closed ||
            pendingConfirmation !== confirmation ||
            !confirmation.issuedBy(confirmationSeal)
        ) return null

        pendingConfirmation = null
        return NativeMobileTitleWatchedConfirmationResult(
            intent = confirmation.intent,
            acknowledged = acknowledged
        )
    }

    fun invalidateConfirmation(): Boolean {
        if (!onOwnerThread() || closed) return false
        confirmableIntent = null
        pendingConfirmation = null
        return true
    }

    fun clear(): Boolean {
        if (!onOwnerThread() || closed) return false
        issued.clear()
        confirmableIntent = null
        pendingConfirmation = null
        return true
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        issued.clear()
        confirmableIntent = null
        pendingConfirmation = null
        return true
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
