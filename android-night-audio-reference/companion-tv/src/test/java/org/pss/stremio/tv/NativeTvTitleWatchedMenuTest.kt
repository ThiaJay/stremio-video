package org.pss.stremio.tv

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class NativeTvTitleWatchedMenuTest {
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
        now = Instant.parse("2026-10-27T12:00:00Z")
    )

    private class Account(var value: SeriesAccountSnapshot?) : StremioAccountPort {
        val sent = mutableListOf<Map<String, Any>>()
        var throwOnRead = false
        var throwOnDispatch = false

        override fun currentSnapshot(): SeriesAccountSnapshot? {
            if (throwOnRead) throw IllegalStateException("bounded test")
            return value
        }

        override fun dispatch(message: Map<String, Any>): Boolean {
            if (throwOnDispatch) throw IllegalStateException("bounded test")
            sent.add(message)
            return true
        }
    }

    private fun action(
        actions: List<NativeTvTitleWatchedAction>,
        scope: WatchedScope,
        watched: Boolean
    ) = actions.single { it.scope == scope && it.watched == watched }

    @Test fun readyCoreSnapshotPresentsFourTitleActionsWithoutWriting() {
        val account = Account(snapshot())
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val actions = menu.present(1)

        assertEquals(4, actions.size)
        assertEquals(4, actions.map { it.actionId }.toSet().size)
        assertTrue(actions.all { it.actionId.startsWith("title-") })
        assertTrue(actions.all { it.confirmation.contains("2 released episodes") })
        assertEquals(0, account.sent.size)
    }

    @Test fun futureEpisodeSuppressesUnsafeSeasonActions() {
        val account = Account(snapshot(listOf(
            CanonicalEpisode("s1e1", 1, 1, released),
            CanonicalEpisode("s1e2", 1, 2, future)
        )))
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val actions = menu.present(1)

        assertEquals(2, actions.size)
        assertTrue(actions.all { it.scope == WatchedScope.Series })
        assertTrue(actions.all { it.confirmation.contains("1 released episodes") })
        assertEquals(0, account.sent.size)
    }

    @Test fun focusAndRemoteConfirmationDispatchExactSeriesAction() {
        val account = Account(snapshot())
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)

        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            menu.activate(selected, "another-action", "ENTER")
        )
        assertEquals(0, account.sent.size)

        val refreshed = action(menu.present(1), WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            menu.activate(refreshed, refreshed.actionId, "DPAD_CENTER")
        )
        assertEquals(1, account.sent.size)
        assertEquals(
            mapOf<String, Any>("action" to "MetaDetails", "args" to mapOf("action" to "MarkAsWatched", "args" to true)),
            account.sent.single()
        )
    }

    @Test fun unwatchRequiresAcknowledgementAndPresentationIsOneShot() {
        val account = Account(snapshot())
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, false)

        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            menu.activate(selected, selected.actionId, "ENTER")
        )
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            menu.activate(selected, selected.actionId, "ENTER", unwatchAcknowledged = true)
        )
        assertEquals(0, account.sent.size)

        val refreshed = action(menu.present(1), WatchedScope.Series, false)
        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            menu.activate(refreshed, refreshed.actionId, "ENTER", unwatchAcknowledged = true)
        )
        assertEquals(1, account.sent.size)
    }

    @Test fun accountDriftRefusesPresentedAction() {
        val account = Account(snapshot())
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Season(1), true)
        account.value = account.value!!.copy(libraryItemFingerprint = "c".repeat(64))

        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            menu.activate(selected, selected.actionId, "ENTER")
        )
        assertEquals(0, account.sent.size)
    }

    @Test fun staleAndForgedActionsCannotExecute() {
        val account = Account(snapshot())
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val stale = action(menu.present(1), WatchedScope.Series, true)
        val current = action(menu.present(1), WatchedScope.Series, true)
        val forged = NativeTvTitleWatchedAction(
            actionId = current.actionId,
            label = current.label,
            confirmation = current.confirmation,
            scope = current.scope,
            watched = current.watched,
            provenanceSeal = Any()
        )

        assertEquals(NativeTvTitleWatchedOutcome.REFUSED, menu.activate(stale, stale.actionId, "ENTER"))
        assertEquals(NativeTvTitleWatchedOutcome.REFUSED, menu.activate(forged, forged.actionId, "ENTER"))
        assertEquals(0, account.sent.size)
    }

    @Test fun ownerThreadAndCloseFailClosed() {
        val account = Account(snapshot())
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)
        val result = AtomicReference<NativeTvTitleWatchedOutcome>()
        val foreignPresentation = AtomicReference<List<NativeTvTitleWatchedAction>>()
        val thread = Thread {
            foreignPresentation.set(menu.present(1))
            result.set(menu.activate(selected, selected.actionId, "ENTER"))
        }
        thread.start()
        thread.join()

        assertTrue(foreignPresentation.get().isEmpty())
        assertEquals(NativeTvTitleWatchedOutcome.REFUSED, result.get())
        assertEquals(0, account.sent.size)
        assertTrue(menu.close())
        assertTrue(menu.present(1).isEmpty())
        assertFalse(menu.close())
    }

    @Test fun snapshotAndDispatchExceptionsFailClosed() {
        val account = Account(snapshot())
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        account.throwOnRead = true
        assertTrue(menu.present(1).isEmpty())

        account.throwOnRead = false
        val selected = action(menu.present(1), WatchedScope.Series, true)
        account.throwOnDispatch = true
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            menu.activate(selected, selected.actionId, "ENTER")
        )
        assertEquals(0, account.sent.size)
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            menu.activate(selected, selected.actionId, "ENTER")
        )
    }
}
