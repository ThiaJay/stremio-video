package org.pss.stremio.tv

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class NativeMobileTitleWatchedMenuTest {
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
        now = Instant.parse("2026-10-27T18:00:00Z")
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

    private fun action(
        actions: List<NativeMobileTitleWatchedAction>,
        scope: WatchedScope,
        watched: Boolean
    ) = actions.single { it.scope == scope && it.watched == watched }

    @Test fun mobilePresentationMirrorsFourGuardedTitleActionsWithoutWriting() {
        val account = Account(snapshot())
        val menu = NativeMobileTitleWatchedMenu(TvWatchedCoordinator(), account)
        val actions = menu.present(1)

        assertEquals(4, actions.size)
        assertEquals(4, actions.map { it.actionId }.toSet().size)
        assertTrue(actions.all { it.actionId.startsWith("mobile-title-") })
        assertEquals(0, account.sent.size)
    }

    @Test fun semanticAccessibilityActivationDispatchesExactSeriesAction() {
        val account = Account(snapshot())
        val menu = NativeMobileTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)

        assertEquals(
            NativeMobileTitleWatchedOutcome.APPLIED,
            menu.activate(
                selected,
                selected.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )
        assertEquals(
            mapOf<String, Any>(
                "action" to "MetaDetails",
                "args" to mapOf("action" to "MarkAsWatched", "args" to true)
            ),
            account.sent.single()
        )
    }

    @Test fun rawPointerAndLongPressEventsNeverImplyConsent() {
        val account = Account(snapshot())
        val menu = NativeMobileTitleWatchedMenu(TvWatchedCoordinator(), account)
        val tap = action(menu.present(1), WatchedScope.Series, true)
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            menu.activate(tap, tap.actionId, "POINTER_UP")
        )

        val longPress = action(menu.present(1), WatchedScope.Series, true)
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            menu.activate(longPress, longPress.actionId, "LONG_PRESS")
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun mobileUnwatchRequiresAcknowledgementAndConsumesIssuance() {
        val account = Account(snapshot())
        val menu = NativeMobileTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, false)

        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            menu.activate(
                selected,
                selected.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            menu.activate(
                selected,
                selected.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION,
                unwatchAcknowledged = true
            )
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun futureEpisodeStillSuppressesUnsafeMobileSeasonActions() {
        val account = Account(snapshot(listOf(
            CanonicalEpisode("s1e1", 1, 1, released),
            CanonicalEpisode("s1e2", 1, 2, future)
        )))
        val menu = NativeMobileTitleWatchedMenu(TvWatchedCoordinator(), account)
        val actions = menu.present(1)

        assertEquals(2, actions.size)
        assertTrue(actions.all { it.scope == WatchedScope.Series })
        assertEquals(0, account.sent.size)
    }

    @Test fun staleSelectedAndForgedMobileActionsFailClosed() {
        val account = Account(snapshot())
        val menu = NativeMobileTitleWatchedMenu(TvWatchedCoordinator(), account)
        val stale = action(menu.present(1), WatchedScope.Series, true)
        assertEquals(4, menu.present(1).size)
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            menu.activate(stale, stale.actionId, NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION)
        )

        val refreshed = action(menu.present(1), WatchedScope.Series, true)
        val forged = NativeMobileTitleWatchedAction(
            actionId = refreshed.actionId,
            label = refreshed.label,
            confirmation = refreshed.confirmation,
            scope = refreshed.scope,
            watched = refreshed.watched,
            delegateAction = NativeTvTitleWatchedAction(
                refreshed.actionId,
                refreshed.label,
                refreshed.confirmation,
                refreshed.scope,
                refreshed.watched,
                Any()
            ),
            provenanceSeal = Any()
        )

        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            menu.activate(forged, forged.actionId, NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION)
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun accountDriftAndRejectedDispatchFailClosedOnMobile() {
        val account = Account(snapshot())
        val menu = NativeMobileTitleWatchedMenu(TvWatchedCoordinator(), account)
        val drifted = action(menu.present(1), WatchedScope.Series, true)
        account.value = account.value!!.copy(accountFingerprint = "c".repeat(64))
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            menu.activate(drifted, drifted.actionId, NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION)
        )

        account.value = snapshot()
        account.rejectDispatch = true
        val rejected = action(menu.present(1), WatchedScope.Series, true)
        assertEquals(
            NativeMobileTitleWatchedOutcome.REFUSED,
            menu.activate(rejected, rejected.actionId, NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION)
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun mobileOwnerThreadAndCloseRevokeOutstandingActions() {
        val account = Account(snapshot())
        val menu = NativeMobileTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)
        val foreign = AtomicReference<NativeMobileTitleWatchedOutcome>()
        val thread = Thread {
            foreign.set(
                menu.activate(
                    selected,
                    selected.actionId,
                    NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
                )
            )
        }
        thread.start()
        thread.join()

        assertEquals(NativeMobileTitleWatchedOutcome.REFUSED, foreign.get())
        assertTrue(menu.close())
        assertTrue(menu.present(1).isEmpty())
        assertFalse(menu.close())
        assertEquals(0, account.sent.size)
    }

    @Test fun mobileAcceptedDispatchCannotReissueBeforeCanonicalReadback() {
        val account = Account(snapshot()).apply { applyDispatchToReadback = false }
        val menu = NativeMobileTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)

        assertEquals(
            NativeMobileTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            menu.activate(
                selected,
                selected.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
            )
        )
        assertTrue(menu.present(1).isEmpty())

        account.watchedIds.addAll(account.value!!.videos.map { it.id })
        assertEquals(4, menu.present(1).size)
        assertEquals(1, account.sent.size)
    }
}
