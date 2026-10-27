package org.pss.stremio.tv

/**
 * Android mobile title action projected from the already guarded Android TV/Core
 * title-action delegate. A host receives only a framework-level semantic action;
 * it must not infer consent from touch position, focus, hydration or preference.
 */
class NativeMobileTitleWatchedAction internal constructor(
    val actionId: String,
    val label: String,
    val confirmation: String,
    val scope: WatchedScope,
    val watched: Boolean,
    private val delegateAction: NativeTvTitleWatchedAction,
    private val provenanceSeal: Any
) {
    internal fun delegateFor(seal: Any): NativeTvTitleWatchedAction? =
        if (provenanceSeal === seal) delegateAction else null
}

enum class NativeMobileTitleWatchedOutcome {
    APPLIED,
    REFUSED
}

/**
 * Fail-closed Android mobile presentation boundary.
 *
 * The host must map View.performClick/accessibility click/keyboard activation to the
 * single semantic event ACTIVATE. Pointer down, long press and account hydration are
 * never consent. Presentation performs no write. Activation consumes the exact
 * issued object before delegating to the existing fresh-read, one-shot Core path.
 */
class NativeMobileTitleWatchedMenu(
    coordinator: TvWatchedCoordinator,
    account: StremioAccountPort
) {
    companion object {
        const val SEMANTIC_ACTIVATION = "ACTIVATE"
        const val MAX_PRESENTED_ACTIONS = NativeTvTitleWatchedMenu.MAX_PRESENTED_ACTIONS
    }

    private data class IssuedAction(
        val action: NativeMobileTitleWatchedAction,
        val delegateAction: NativeTvTitleWatchedAction
    )

    private val ownerThread = Thread.currentThread()
    private val mobileSeal = Any()
    private val delegate = NativeTvTitleWatchedMenu(coordinator, account)
    private val issued = LinkedHashMap<String, IssuedAction>()
    private var closed = false

    fun present(seasonNumber: Int?): List<NativeMobileTitleWatchedAction> {
        if (!onOwnerThread() || closed) return emptyList()
        issued.clear()
        val delegated = delegate.present(seasonNumber)
        if (delegated.isEmpty() || delegated.size > MAX_PRESENTED_ACTIONS) return emptyList()

        val actions = ArrayList<NativeMobileTitleWatchedAction>(delegated.size)
        for (source in delegated) {
            val action = NativeMobileTitleWatchedAction(
                actionId = "mobile-${source.actionId}",
                label = source.label,
                confirmation = source.confirmation,
                scope = source.scope,
                watched = source.watched,
                delegateAction = source,
                provenanceSeal = mobileSeal
            )
            if (issued.put(action.actionId, IssuedAction(action, source)) != null) {
                issued.clear()
                return emptyList()
            }
            actions.add(action)
        }
        return actions.toList()
    }

    fun activate(
        action: NativeMobileTitleWatchedAction,
        selectedActionId: String?,
        semanticEvent: String,
        unwatchAcknowledged: Boolean = false
    ): NativeMobileTitleWatchedOutcome {
        if (!onOwnerThread() || closed) return NativeMobileTitleWatchedOutcome.REFUSED
        val record = issued.remove(action.actionId) ?: return NativeMobileTitleWatchedOutcome.REFUSED
        val exactDelegate = action.delegateFor(mobileSeal)
        if (record.action !== action || exactDelegate !== record.delegateAction ||
            selectedActionId != action.actionId || semanticEvent != SEMANTIC_ACTIVATION) {
            return NativeMobileTitleWatchedOutcome.REFUSED
        }
        return when (
            delegate.activate(
                action = record.delegateAction,
                focusedActionId = record.delegateAction.actionId,
                remoteKey = "ENTER",
                unwatchAcknowledged = unwatchAcknowledged
            )
        ) {
            NativeTvTitleWatchedOutcome.APPLIED -> NativeMobileTitleWatchedOutcome.APPLIED
            NativeTvTitleWatchedOutcome.REFUSED -> NativeMobileTitleWatchedOutcome.REFUSED
        }
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        issued.clear()
        return delegate.close()
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
