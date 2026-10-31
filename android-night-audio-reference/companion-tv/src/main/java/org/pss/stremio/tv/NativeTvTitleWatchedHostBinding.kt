package org.pss.stremio.tv

/**
 * Exact immutable model handed to a real Android TV focusable action view.
 *
 * The UI must capture this object in its ENTER/DPAD_CENTER callback. An action
 * ID or reconstructed display model is not sufficient authority.
 */
class NativeTvTitleWatchedViewAction internal constructor(
    val actionId: String,
    val label: String,
    val confirmation: String,
    val scope: WatchedScope,
    val watched: Boolean,
    val focusable: Boolean = true,
    val requiresAcknowledgement: Boolean = !watched,
    private val menuAction: NativeTvTitleWatchedAction,
    private val provenanceSeal: Any
) {
    internal fun menuFor(seal: Any): NativeTvTitleWatchedAction? =
        if (provenanceSeal === seal) menuAction else null
}

/**
 * Display-only Android TV port. It cannot read an account or dispatch Core.
 */
interface NativeTvTitleWatchedViewPort {
    fun replaceTitleActions(actions: List<NativeTvTitleWatchedViewAction>): Boolean
    fun clearTitleActions(): Boolean
}

/**
 * Lifecycle-safe Android TV UI host binding for title-level watched actions.
 *
 * Rendering is read-only. Activation requires the exact view-issued object,
 * matching focused ID and an accepted remote confirmation key. Every attempt
 * revokes the full rendered generation. The existing NativeTvTitleWatchedMenu
 * remains the exclusive fresh-read, confirmation and Core-dispatch authority.
 */
class NativeTvTitleWatchedHostBinding(
    coordinator: TvWatchedCoordinator,
    account: CanonicalWatchedStatePort,
    private val view: NativeTvTitleWatchedViewPort
) {
    private data class IssuedViewAction(
        val viewAction: NativeTvTitleWatchedViewAction,
        val menuAction: NativeTvTitleWatchedAction
    )

    private val ownerThread = Thread.currentThread()
    private val bindingSeal = Any()
    private val menu = NativeTvTitleWatchedMenu(coordinator, account)
    private val issued = LinkedHashMap<String, IssuedViewAction>()
    private var lastSeasonNumber: Int? = null
    private var visible = false
    private var closed = false

    fun render(seasonNumber: Int?): Boolean {
        if (!onOwnerThread() || closed) return false
        issued.clear()
        visible = false
        lastSeasonNumber = seasonNumber

        val menuActions = menu.present(seasonNumber)
        if (menuActions.isEmpty() ||
            menuActions.size > NativeTvTitleWatchedMenu.MAX_PRESENTED_ACTIONS) {
            clearViewOrPoison()
            return false
        }

        val viewActions = ArrayList<NativeTvTitleWatchedViewAction>(menuActions.size)
        for (menuAction in menuActions) {
            val viewAction = NativeTvTitleWatchedViewAction(
                actionId = menuAction.actionId,
                label = menuAction.label,
                confirmation = menuAction.confirmation,
                scope = menuAction.scope,
                watched = menuAction.watched,
                menuAction = menuAction,
                provenanceSeal = bindingSeal
            )
            if (issued.put(
                    viewAction.actionId,
                    IssuedViewAction(viewAction, menuAction)
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
        action: NativeTvTitleWatchedViewAction,
        focusedActionId: String?,
        remoteKey: String,
        unwatchAcknowledged: Boolean = false
    ): NativeTvTitleWatchedOutcome {
        if (!onOwnerThread() || closed || !visible) {
            return NativeTvTitleWatchedOutcome.REFUSED
        }

        val record = issued.remove(action.actionId)
        val exactMenuAction = action.menuFor(bindingSeal)
        val exact = record != null &&
            record.viewAction === action &&
            exactMenuAction === record.menuAction &&
            focusedActionId == action.actionId

        val outcome = if (exact) {
            menu.activate(
                action = record!!.menuAction,
                focusedActionId = focusedActionId,
                remoteKey = remoteKey,
                unwatchAcknowledged = unwatchAcknowledged
            )
        } else {
            NativeTvTitleWatchedOutcome.REFUSED
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
        // present() clears existing menu issuance before snapshot work. The
        // replacement is intentionally discarded and cannot be activated.
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
