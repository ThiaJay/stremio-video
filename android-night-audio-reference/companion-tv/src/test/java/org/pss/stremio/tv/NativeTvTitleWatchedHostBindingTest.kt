package org.pss.stremio.tv

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class NativeTvTitleWatchedHostBindingTest {
    private val released = "2025-01-01T00:00:00Z"
    private val future = "2027-01-01T00:00:00Z"

    private fun snapshot(videos: List<CanonicalEpisode> = listOf(
        CanonicalEpisode("s1e1", 1, 1, released),
        CanonicalEpisode("s1e2", 1, 2, released)
    )) = SeriesAccountSnapshot(
        accountFingerprint = "a".repeat(64),
        libraryItemFingerprint = "b".repeat(64),
        seriesId = "series",
        readyMetaId = "series",
        metaReady = true,
        libraryReady = true,
        videos = videos,
        storyOrder = videos.map { it.id },
        storyOrderVersion = 1,
        now = Instant.parse("2026-10-27T20:00:00Z")
    )

    private class Account(var value: SeriesAccountSnapshot?) : CanonicalWatchedStatePort {
        val sent = mutableListOf<Map<String, Any>>()
        val watchedIds = linkedSetOf<String>()
        var rejectDispatch = false
        var applyDispatchToReadback = true

        override fun currentSnapshot(): SeriesAccountSnapshot? = value

        override fun dispatch(message: Map<String, Any>): Boolean {
            if (rejectDispatch) return false
            sent.add(message)
            if (applyDispatchToReadback) {
                val inner = message["args"] as Map<*, *>
                val watched = when (inner["action"]) {
                    "MarkAsWatched" -> inner["args"] as Boolean
                    "MarkSeasonAsWatched" -> (inner["args"] as List<*>)[1] as Boolean
                    else -> return false
                }
                val ids = value?.videos?.map { it.id }.orEmpty()
                if (watched) watchedIds.addAll(ids) else watchedIds.removeAll(ids.toSet())
            }
            return true
        }

        override fun currentWatchedState(): CanonicalWatchedStateReadback? = value?.let {
            CanonicalWatchedStateReadback(
                accountFingerprint = it.accountFingerprint,
                libraryItemFingerprint = it.libraryItemFingerprint,
                seriesId = it.seriesId,
                watchedVideoIds = watchedIds.toList()
            )
        }
    }

    private class View : NativeTvTitleWatchedViewPort {
        var actions = emptyList<NativeTvTitleWatchedViewAction>()
        var replaceCalls = 0
        var clearCalls = 0
        var rejectReplace = false
        var rejectClear = false
        var throwReplace = false

        override fun replaceTitleActions(
            actions: List<NativeTvTitleWatchedViewAction>
        ): Boolean {
            replaceCalls += 1
            if (throwReplace) throw IllegalStateException("TV view unavailable")
            if (rejectReplace) return false
            this.actions = actions
            return true
        }

        override fun clearTitleActions(): Boolean {
            clearCalls += 1
            if (rejectClear) return false
            actions = emptyList()
            return true
        }
    }

    private fun action(
        view: View,
        scope: WatchedScope,
        watched: Boolean
    ) = view.actions.single { it.scope == scope && it.watched == watched }

    @Test fun tvHostRenderPublishesFourFocusableModelsWithoutWriting() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )

        assertTrue(binding.render(1))
        assertEquals(4, view.actions.size)
        assertTrue(view.actions.all { it.focusable })
        assertEquals(2, view.actions.count { it.requiresAcknowledgement })
        assertEquals(1, view.replaceCalls)
        assertEquals(0, account.sent.size)
    }

    @Test fun enterOnExactFocusedTvActionDispatchesAndClearsGeneration() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val selected = action(view, WatchedScope.Series, true)

        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            binding.activate(selected, selected.actionId, "ENTER")
        )
        assertTrue(view.actions.isEmpty())
        assertEquals(
            mapOf<String, Any>(
                "action" to "MetaDetails",
                "args" to mapOf("action" to "MarkAsWatched", "args" to true)
            ),
            account.sent.single()
        )
    }

    @Test fun dpadCenterIsTheOnlyAlternateAcceptedTvConfirmation() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val selected = action(view, WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            binding.activate(selected, selected.actionId, "DPAD_CENTER")
        )
        assertEquals(1, account.sent.size)
    }

    @Test fun focusMismatchAndOtherRemoteKeysFailClosedAndRevoke() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val wrongFocus = action(view, WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            binding.activate(wrongFocus, "different-action", "ENTER")
        )
        assertTrue(view.actions.isEmpty())

        assertTrue(binding.render(1))
        val back = action(view, WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            binding.activate(back, back.actionId, "BACK")
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun staleOrForgedTvViewObjectsRevokeCurrentPresentation() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val stale = action(view, WatchedScope.Series, true)
        assertTrue(binding.render(1))
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            binding.activate(stale, stale.actionId, "ENTER")
        )

        assertTrue(binding.render(1))
        val current = action(view, WatchedScope.Series, true)
        val forged = NativeTvTitleWatchedViewAction(
            current.actionId,
            current.label,
            current.confirmation,
            current.scope,
            current.watched,
            menuAction = NativeTvTitleWatchedAction(
                current.actionId,
                current.label,
                current.confirmation,
                current.scope,
                current.watched,
                Any()
            ),
            provenanceSeal = Any()
        )
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            binding.activate(forged, forged.actionId, "ENTER")
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun tvUnwatchRequiresAcknowledgementOnExactIssuedAction() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val unwatch = action(view, WatchedScope.Series, false)
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            binding.activate(unwatch, unwatch.actionId, "ENTER")
        )

        assertTrue(binding.render(1))
        val acknowledged = action(view, WatchedScope.Series, false)
        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            binding.activate(
                acknowledged,
                acknowledged.actionId,
                "ENTER",
                unwatchAcknowledged = true
            )
        )
        assertEquals(1, account.sent.size)
    }

    @Test fun tvAccountDriftAndRejectedDispatchClearPresentation() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val drifted = action(view, WatchedScope.Series, true)
        account.value = account.value!!.copy(accountFingerprint = "c".repeat(64))
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            binding.activate(drifted, drifted.actionId, "ENTER")
        )
        assertTrue(view.actions.isEmpty())

        account.value = snapshot()
        account.rejectDispatch = true
        assertTrue(binding.render(1))
        val rejected = action(view, WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            binding.activate(rejected, rejected.actionId, "ENTER")
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun futureEpisodeFilteringSurvivesTvHostBoundary() {
        val account = Account(snapshot(listOf(
            CanonicalEpisode("s1e1", 1, 1, released),
            CanonicalEpisode("s1e2", 1, 2, future)
        )))
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )

        assertTrue(binding.render(1))
        assertEquals(2, view.actions.size)
        assertTrue(view.actions.all { it.scope == WatchedScope.Series })
        assertEquals(0, account.sent.size)
    }

    @Test fun rejectedOrThrowingTvViewPortPermanentlyPoisonsBinding() {
        val account = Account(snapshot())
        val rejectedView = View().apply { rejectReplace = true }
        val rejected = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            rejectedView
        )
        assertFalse(rejected.render(1))
        rejectedView.rejectReplace = false
        assertFalse(rejected.render(1))
        assertFalse(rejected.close())

        val throwingView = View().apply { throwReplace = true }
        val throwing = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            throwingView
        )
        assertFalse(throwing.render(1))
        throwingView.throwReplace = false
        assertFalse(throwing.render(1))
        assertEquals(0, account.sent.size)
    }

    @Test fun clearFailureAfterAppliedTvActionClosesWithoutReplay() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val selected = action(view, WatchedScope.Series, true)
        view.rejectClear = true

        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            binding.activate(selected, selected.actionId, "ENTER")
        )
        assertEquals(1, account.sent.size)
        view.rejectClear = false
        assertFalse(binding.render(1))
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            binding.activate(selected, selected.actionId, "ENTER")
        )
        assertEquals(1, account.sent.size)
    }

    @Test fun tvHostOwnerThreadAndCloseRevokeOutstandingCallbacks() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val selected = action(view, WatchedScope.Series, true)
        val foreign = AtomicReference<NativeTvTitleWatchedOutcome>()
        val thread = Thread {
            foreign.set(binding.activate(selected, selected.actionId, "ENTER"))
        }
        thread.start()
        thread.join()

        assertEquals(NativeTvTitleWatchedOutcome.REFUSED, foreign.get())
        assertTrue(binding.close())
        assertTrue(view.actions.isEmpty())
        assertFalse(binding.render(1))
        assertFalse(binding.close())
        assertEquals(0, account.sent.size)
    }

    @Test fun tvHostCannotRenderAgainWhileAcceptedWriteLacksReadback() {
        val account = Account(snapshot()).apply { applyDispatchToReadback = false }
        val view = View()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val selected = action(view, WatchedScope.Series, true)

        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            binding.activate(selected, selected.actionId, "ENTER")
        )
        assertTrue(view.actions.isEmpty())
        assertFalse(binding.render(1))

        account.watchedIds.addAll(account.value!!.videos.map { it.id })
        assertTrue(binding.render(1))
        assertEquals(4, view.actions.size)
        assertEquals(1, account.sent.size)
    }
    @Test fun tvHostOperationalStateDistinguishesFatalFailureFromPendingReadback() {
        val fatalView = View().apply { rejectReplace = true }
        val fatal = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            Account(snapshot()),
            fatalView
        )
        assertFalse(fatal.render(1))
        assertFalse(fatal.isOperational())

        val pendingAccount = Account(snapshot()).apply {
            applyDispatchToReadback = false
        }
        val pendingView = View()
        val pending = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            pendingAccount,
            pendingView
        )
        assertTrue(pending.render(1))
        val selected = action(pendingView, WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            pending.activate(selected, selected.actionId, "ENTER")
        )
        assertFalse(pending.render(1))
        assertTrue(pending.isOperational())
    }

}
