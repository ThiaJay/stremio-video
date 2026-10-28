package org.pss.stremio.tv

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class NativeMobileTitleWatchedHostBindingTest {
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
        now = Instant.parse("2026-10-27T19:00:00Z")
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

    private class View : NativeMobileTitleWatchedViewPort {
        var actions = emptyList<NativeMobileTitleWatchedViewAction>()
        var replaceCalls = 0
        var clearCalls = 0
        var rejectReplace = false
        var rejectClear = false
        var throwReplace = false

        override fun replaceTitleActions(
            actions: List<NativeMobileTitleWatchedViewAction>
        ): Boolean {
            replaceCalls += 1
            if (throwReplace) throw IllegalStateException("view unavailable")
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

    @Test fun hostRenderPublishesFourDisplayModelsWithoutWriting() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )

        assertTrue(binding.render(1))
        assertEquals(4, view.actions.size)
        assertEquals(4, view.actions.map { it.actionId }.toSet().size)
        assertEquals(1, view.replaceCalls)
        assertEquals(0, account.sent.size)
    }

    @Test fun exactSemanticViewActionDispatchesAndClearsWholeGeneration() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val selected = action(view, WatchedScope.Series, true)

        assertEquals(
            NativeMobileTitleWatchedOutcome.APPLIED,
            binding.activate(
                selected,
                selected.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )
        assertTrue(view.actions.isEmpty())
        assertEquals(
            mapOf<String, Any>(
                "action" to "MetaDetails",
                "args" to mapOf("action" to "MarkAsWatched", "args" to true)
            ),
            account.sent.single()
        )
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            binding.activate(
                selected,
                selected.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )
    }

    @Test fun rawPointerAndLongPressCallbacksNeverImplyHostConsent() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val pointer = action(view, WatchedScope.Series, true)
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            binding.activate(pointer, pointer.actionId, "POINTER_UP")
        )
        assertTrue(view.actions.isEmpty())

        assertTrue(binding.render(1))
        val longPress = action(view, WatchedScope.Series, true)
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            binding.activate(longPress, longPress.actionId, "LONG_PRESS")
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun staleOrForgedViewObjectsRevokeCurrentPresentation() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val stale = action(view, WatchedScope.Series, true)
        assertTrue(binding.render(1))
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            binding.activate(
                stale,
                stale.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )
        assertTrue(view.actions.isEmpty())

        assertTrue(binding.render(1))
        val current = action(view, WatchedScope.Series, true)
        val forged = NativeMobileTitleWatchedViewAction(
            current.actionId,
            current.label,
            current.confirmation,
            current.scope,
            current.watched,
            NativeMobileTitleWatchedAction(
                current.actionId,
                current.label,
                current.confirmation,
                current.scope,
                current.watched,
                NativeTvTitleWatchedAction(
                    current.actionId,
                    current.label,
                    current.confirmation,
                    current.scope,
                    current.watched,
                    Any()
                ),
                Any()
            ),
            Any()
        )
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            binding.activate(
                forged,
                forged.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun unwatchAcknowledgementPassesOnlyThroughExactIssuedViewAction() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val unwatch = action(view, WatchedScope.Series, false)
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            binding.activate(
                unwatch,
                unwatch.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )

        assertTrue(binding.render(1))
        val acknowledged = action(view, WatchedScope.Series, false)
        assertEquals(
            NativeMobileTitleWatchedOutcome.APPLIED,
            binding.activate(
                acknowledged,
                acknowledged.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION,
                unwatchAcknowledged = true
            )
        )
        assertEquals(1, account.sent.size)
    }

    @Test fun accountDriftAndRejectedDispatchClearHostPresentation() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val drifted = action(view, WatchedScope.Series, true)
        account.value = account.value!!.copy(accountFingerprint = "c".repeat(64))
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            binding.activate(
                drifted,
                drifted.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )
        assertTrue(view.actions.isEmpty())

        account.value = snapshot()
        account.rejectDispatch = true
        assertTrue(binding.render(1))
        val rejected = action(view, WatchedScope.Series, true)
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            binding.activate(
                rejected,
                rejected.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun futureEpisodeFilteringSurvivesRealHostBoundary() {
        val account = Account(snapshot(listOf(
            CanonicalEpisode("s1e1", 1, 1, released),
            CanonicalEpisode("s1e2", 1, 2, future)
        )))
        val view = View()
        val binding = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )

        assertTrue(binding.render(1))
        assertEquals(2, view.actions.size)
        assertTrue(view.actions.all { it.scope == WatchedScope.Series })
        assertEquals(0, account.sent.size)
    }

    @Test fun rejectedOrThrowingViewPortPermanentlyPoisonsBinding() {
        val account = Account(snapshot())
        val rejectedView = View().apply { rejectReplace = true }
        val rejected = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            rejectedView
        )
        assertFalse(rejected.render(1))
        rejectedView.rejectReplace = false
        assertFalse(rejected.render(1))
        assertFalse(rejected.close())

        val throwingView = View().apply { throwReplace = true }
        val throwing = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            throwingView
        )
        assertFalse(throwing.render(1))
        throwingView.throwReplace = false
        assertFalse(throwing.render(1))
        assertEquals(0, account.sent.size)
    }

    @Test fun hostOwnerThreadAndCloseRevokeOutstandingCallbacks() {
        val account = Account(snapshot())
        val view = View()
        val binding = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val selected = action(view, WatchedScope.Series, true)
        val foreign = AtomicReference<NativeMobileTitleWatchedOutcome>()
        val thread = Thread {
            foreign.set(
                binding.activate(
                    selected,
                    selected.actionId,
                    NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
                )
            )
        }
        thread.start()
        thread.join()

        assertEquals(NativeMobileTitleWatchedOutcome.REFUSED, foreign.get())
        assertTrue(binding.close())
        assertTrue(view.actions.isEmpty())
        assertFalse(binding.render(1))
        assertFalse(binding.close())
        assertEquals(0, account.sent.size)
    }

    @Test fun mobileHostCannotRenderAgainWhileAcceptedWriteLacksReadback() {
        val account = Account(snapshot()).apply { applyDispatchToReadback = false }
        val view = View()
        val binding = NativeMobileTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )
        assertTrue(binding.render(1))
        val selected = action(view, WatchedScope.Series, true)

        assertEquals(
            NativeMobileTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            binding.activate(
                selected,
                selected.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )
        assertTrue(view.actions.isEmpty())
        assertFalse(binding.render(1))

        account.watchedIds.addAll(account.value!!.videos.map { it.id })
        assertTrue(binding.render(1))
        assertEquals(4, view.actions.size)
        assertEquals(1, account.sent.size)
    }
}
