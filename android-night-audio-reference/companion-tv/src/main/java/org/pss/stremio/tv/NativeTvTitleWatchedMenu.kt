package org.pss.stremio.tv

/**
 * One focusable Android TV title action issued from one fresh Core account snapshot.
 * The object cannot be copied or reconstructed by a caller; activation accepts only
 * the exact instance issued by its owning menu.
 */
class NativeTvTitleWatchedAction internal constructor(
    val actionId: String,
    val label: String,
    val confirmation: String,
    val scope: WatchedScope,
    val watched: Boolean,
    private val provenanceSeal: Any
) {
    internal fun wasIssuedBy(seal: Any): Boolean = provenanceSeal === seal
}

enum class NativeTvTitleWatchedOutcome {
    APPLIED,
    DISPATCHED_PENDING_READBACK,
    REFUSED
}

/**
 * Canonical watched state from the same ready Core account/library item as
 * [SeriesAccountSnapshot]. A dispatch acknowledgement is not enough: a real
 * client must expose this readback before another title-action generation can
 * be rendered.
 */
data class CanonicalWatchedStateReadback(
    val accountFingerprint: String,
    val libraryItemFingerprint: String,
    val seriesId: String,
    val watchedVideoIds: List<String>
)

/** Optional stronger account boundary required only after an accepted write. */
interface CanonicalWatchedStatePort : StremioAccountPort {
    fun currentWatchedState(): CanonicalWatchedStateReadback?
}

/**
 * Fail-closed presenter/actuator for Android TV title-level watched actions.
 *
 * It is deliberately not connected to the synthetic companion Activity. A real host
 * must inject its authenticated Core account port and retain this menu on one UI
 * thread. Presentation performs no write. Activation consumes the issued action
 * before a fresh Core read and before dispatch, so stale, rejected or throwing paths
 * cannot replay the same confirmation.
 */
class NativeTvTitleWatchedMenu(
    private val coordinator: TvWatchedCoordinator,
    private val account: StremioAccountPort
) {
    companion object {
        const val MAX_PRESENTED_ACTIONS = 4
    }

    private data class IssuedAction(
        val action: NativeTvTitleWatchedAction,
        val plan: WatchedPlan
    )

    private data class PendingReadback(val plan: WatchedPlan)

    private val ownerThread = Thread.currentThread()
    private val menuSeal = Any()
    private val issued = LinkedHashMap<String, IssuedAction>()
    private var pendingReadback: PendingReadback? = null
    private var closed = false

    fun present(seasonNumber: Int?): List<NativeTvTitleWatchedAction> {
        if (!onOwnerThread() || closed) return emptyList()
        issued.clear()
        val snapshot = snapshotOrNull() ?: return emptyList()
        if (!confirmPendingReadback(snapshot)) return emptyList()
        val requests = buildList {
            add(WatchedScope.Series to true)
            add(WatchedScope.Series to false)
            if (seasonNumber != null && seasonNumber > 0) {
                add(WatchedScope.Season(seasonNumber) to true)
                add(WatchedScope.Season(seasonNumber) to false)
            }
        }
        val prepared = try {
            requests.mapNotNull { (scope, watched) ->
                coordinator.prepare(snapshot, scope, watched)?.let { Triple(scope, watched, it) }
            }
        } catch (_: Exception) {
            return emptyList()
        } catch (_: LinkageError) {
            return emptyList()
        }
        if (prepared.isEmpty() || prepared.size > MAX_PRESENTED_ACTIONS) return emptyList()

        val actions = ArrayList<NativeTvTitleWatchedAction>(prepared.size)
        for ((scope, watched, plan) in prepared) {
            val scopeId = when (scope) {
                WatchedScope.Series -> "series"
                is WatchedScope.Season -> "season-${scope.number}"
            }
            val stateId = if (watched) "watched" else "unwatched"
            val actionId = "title-$scopeId-$stateId-${plan.fingerprint}"
            val label = when (scope) {
                WatchedScope.Series -> if (watched)
                    "Mark all released episodes watched" else
                    "Mark all released episodes unwatched"
                is WatchedScope.Season -> if (watched)
                    "Mark released season ${scope.number} episodes watched" else
                    "Mark released season ${scope.number} episodes unwatched"
            }
            val action = NativeTvTitleWatchedAction(
                actionId = actionId,
                label = label,
                confirmation = plan.confirmation,
                scope = scope,
                watched = watched,
                provenanceSeal = menuSeal
            )
            if (issued.put(actionId, IssuedAction(action, plan)) != null) {
                issued.clear()
                return emptyList()
            }
            actions.add(action)
        }
        return actions.toList()
    }

    fun activate(
        action: NativeTvTitleWatchedAction,
        focusedActionId: String?,
        remoteKey: String,
        unwatchAcknowledged: Boolean = false
    ): NativeTvTitleWatchedOutcome {
        if (!onOwnerThread() || closed) return NativeTvTitleWatchedOutcome.REFUSED
        val record = issued.remove(action.actionId) ?: return NativeTvTitleWatchedOutcome.REFUSED
        if (record.action !== action || !action.wasIssuedBy(menuSeal) ||
            focusedActionId != action.actionId ||
            (remoteKey != "ENTER" && remoteKey != "DPAD_CENTER")) {
            return NativeTvTitleWatchedOutcome.REFUSED
        }
        val fresh = snapshotOrNull() ?: return NativeTvTitleWatchedOutcome.REFUSED
        val command = try {
            coordinator.confirm(
                record.plan,
                fresh,
                TvWatchedConfirmation(
                    focusOnConfirmation = true,
                    remoteKey = remoteKey,
                    fingerprint = record.plan.fingerprint,
                    unwatchAcknowledged = unwatchAcknowledged
                )
            )
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        } ?: return NativeTvTitleWatchedOutcome.REFUSED

        if (!dispatchOrFalse(command.coreAction)) return NativeTvTitleWatchedOutcome.REFUSED
        pendingReadback = PendingReadback(record.plan)
        val postDispatch = snapshotOrNull()
        return if (postDispatch != null && confirmPendingReadback(postDispatch)) {
            NativeTvTitleWatchedOutcome.APPLIED
        } else NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        issued.clear()
        pendingReadback = null
        return true
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    private fun snapshotOrNull(): SeriesAccountSnapshot? = try {
        account.currentSnapshot()
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun dispatchOrFalse(message: Map<String, Any>): Boolean = try {
        account.dispatch(message)
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun confirmPendingReadback(snapshot: SeriesAccountSnapshot): Boolean {
        val pending = pendingReadback ?: return true
        val currentPlan = try {
            coordinator.prepare(snapshot, pending.plan.scope, pending.plan.watched)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        } ?: return false
        if (currentPlan.selectedIds != pending.plan.selectedIds) return false

        val state = watchedStateOrNull() ?: return false
        if (state.accountFingerprint != snapshot.accountFingerprint ||
            state.libraryItemFingerprint != snapshot.libraryItemFingerprint ||
            state.seriesId != snapshot.seriesId ||
            state.watchedVideoIds.any { it.isBlank() } ||
            state.watchedVideoIds.size != state.watchedVideoIds.toSet().size
        ) return false

        val watchedIds = state.watchedVideoIds.toSet()
        val confirmed = if (pending.plan.watched) {
            pending.plan.selectedIds.all { it in watchedIds }
        } else {
            pending.plan.selectedIds.none { it in watchedIds }
        }
        if (!confirmed) return false
        pendingReadback = null
        return true
    }

    private fun watchedStateOrNull(): CanonicalWatchedStateReadback? = try {
        (account as? CanonicalWatchedStatePort)?.currentWatchedState()
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}
