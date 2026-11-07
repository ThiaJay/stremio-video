package org.pss.stremio.tv

/**
 * Exact immutable object handed to a real Android mobile title-action view.
 *
 * A host must capture this object in its semantic click callback. The binding
 * accepts neither a reconstructed model nor an action ID by itself.
 */
class NativeMobileTitleWatchedViewAction internal constructor(
    val actionId: String,
    val label: String,
    val confirmation: String,
    val scope: WatchedScope,
    val watched: Boolean,
    private val mobileAction: NativeMobileTitleWatchedAction,
    private val provenanceSeal: Any
) {
    internal fun mobileFor(seal: Any): NativeMobileTitleWatchedAction? =
        if (provenanceSeal === seal) mobileAction else null
}

/**
 * Narrow UI port for an Android host. It receives display-only models and
 * cannot dispatch Core messages, access credentials or infer activation.
 */
interface NativeMobileTitleWatchedViewPort {
    fun replaceTitleActions(actions: List<NativeMobileTitleWatchedViewAction>): Boolean
    fun clearTitleActions(): Boolean
}

/**
 * Lifecycle-safe Android mobile host binding for title-level watched actions.
 *
 * Rendering performs no account write. Activation requires the exact model
 * object issued to the view, the selected ID and the single semantic ACTIVATE
 * event. Every activation attempt revokes the complete rendered generation.
 * Core reads, confirmation and dispatch remain exclusively owned by the
 * existing NativeMobileTitleWatchedMenu -> NativeTvTitleWatchedMenu path.
 */
class NativeMobileTitleWatchedHostBinding(
    coordinator: TvWatchedCoordinator,
    account: CanonicalWatchedStatePort,
    private val view: NativeMobileTitleWatchedViewPort
) {
    private data class IssuedViewAction(
        val viewAction: NativeMobileTitleWatchedViewAction,
        val mobileAction: NativeMobileTitleWatchedAction
    )

    private val ownerThread = Thread.currentThread()
    private val bindingSeal = Any()
    private val menu = NativeMobileTitleWatchedMenu(coordinator, account)
    private val issued = LinkedHashMap<String, IssuedViewAction>()
    private var lastSeasonNumber: Int? = null
    private var visible = false
    private var closed = false

    fun render(seasonNumber: Int?): Boolean {
        if (!onOwnerThread() || closed) return false
        issued.clear()
        visible = false
        lastSeasonNumber = seasonNumber

        val mobileActions = menu.present(seasonNumber)
        if (mobileActions.isEmpty() ||
            mobileActions.size > NativeMobileTitleWatchedMenu.MAX_PRESENTED_ACTIONS) {
            clearViewOrPoison()
            return false
        }

        val viewActions = ArrayList<NativeMobileTitleWatchedViewAction>(mobileActions.size)
        for (mobileAction in mobileActions) {
            val viewAction = NativeMobileTitleWatchedViewAction(
                actionId = mobileAction.actionId,
                label = mobileAction.label,
                confirmation = mobileAction.confirmation,
                scope = mobileAction.scope,
                watched = mobileAction.watched,
                mobileAction = mobileAction,
                provenanceSeal = bindingSeal
            )
            if (issued.put(
                    viewAction.actionId,
                    IssuedViewAction(viewAction, mobileAction)
                ) != null) {
                poison()
                return false
            }
            viewActions.add(viewAction)
        }

        val accepted = try {
            view.replaceTitleActions(viewActions.toList())
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!accepted) {
            poison()
            return false
        }
        visible = true
        return true
    }

    fun activate(
        action: NativeMobileTitleWatchedViewAction,
        selectedActionId: String?,
        semanticEvent: String,
        unwatchAcknowledged: Boolean = false
    ): NativeMobileTitleWatchedOutcome {
        if (!onOwnerThread() || closed || !visible) {
            return NativeMobileTitleWatchedOutcome.REFUSED
        }

        val record = issued.remove(action.actionId)
        val exactMobileAction = action.mobileFor(bindingSeal)
        val exact = record != null &&
            record.viewAction === action &&
            exactMobileAction === record.mobileAction &&
            selectedActionId == action.actionId

        val outcome = if (exact) {
            menu.activate(
                action = record!!.mobileAction,
                selectedActionId = selectedActionId,
                semanticEvent = semanticEvent,
                unwatchAcknowledged = unwatchAcknowledged
            )
        } else {
            NativeMobileTitleWatchedOutcome.REFUSED
        }

        revokeRenderedGeneration()
        return outcome
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        issued.clear()
        visible = false
        val menuClosed = menu.close()
        val viewCleared = clearView()
        return menuClosed && viewCleared
    }

    fun isOperational(): Boolean = onOwnerThread() && !closed

    private fun revokeRenderedGeneration() {
        issued.clear()
        visible = false
        // present() clears both mobile and delegated TV issuance before any
        // snapshot work. The returned replacement generation is intentionally
        // discarded and cannot be activated without its exact object.
        menu.present(lastSeasonNumber)
        if (!clearView()) {
            closed = true
            menu.close()
        }
    }

    private fun clearViewOrPoison(): Boolean {
        val cleared = clearView()
        if (!cleared) poison()
        return cleared
    }

    private fun poison() {
        if (!closed) {
            closed = true
            issued.clear()
            visible = false
            menu.close()
        }
        clearView()
    }

    private fun clearView(): Boolean = try {
        view.clearTitleActions()
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
