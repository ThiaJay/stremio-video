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

    private class Account(var value: SeriesAccountSnapshot?) : CanonicalWatchedStatePort {
        val sent = mutableListOf<Map<String, Any>>()
        val watchedIds = linkedSetOf<String>()
        var throwOnRead = false
        var throwOnDispatch = false
        var applyDispatchToReadback = true
        var readbackOverride: CanonicalWatchedStateReadback? = null

        override fun currentSnapshot(): SeriesAccountSnapshot? {
            if (throwOnRead) throw IllegalStateException("bounded test")
            return value
        }

        override fun dispatch(message: Map<String, Any>): Boolean {
            if (throwOnDispatch) throw IllegalStateException("bounded test")
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

        override fun currentWatchedState(): CanonicalWatchedStateReadback? =
            readbackOverride ?: value?.let {
            CanonicalWatchedStateReadback(
                accountFingerprint = it.accountFingerprint,
                libraryItemFingerprint = it.libraryItemFingerprint,
                seriesId = it.seriesId,
                watchedVideoIds = watchedIds.toList()
            )
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

    @Test fun activatingOneActionRevokesEverySiblingFromThatGeneration() {
        val account = Account(snapshot())
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val actions = menu.present(1)
        val selected = action(actions, WatchedScope.Series, true)
        val sibling = action(actions, WatchedScope.Season(1), true)

        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            menu.activate(selected, selected.actionId, "ENTER")
        )
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            menu.activate(sibling, sibling.actionId, "ENTER")
        )
        assertEquals(1, account.sent.size)
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

    @Test fun canonicalReadbackRearmsOnlyOppositeWatchedDirection() {
        val account = Account(snapshot())
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)

        val firstWatched = action(menu.present(1), WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            menu.activate(firstWatched, firstWatched.actionId, "ENTER")
        )

        val duplicateWatched = action(menu.present(1), WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            menu.activate(duplicateWatched, duplicateWatched.actionId, "ENTER")
        )

        val unwatched = action(menu.present(1), WatchedScope.Series, false)
        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            menu.activate(
                unwatched,
                unwatched.actionId,
                "ENTER",
                unwatchAcknowledged = true
            )
        )

        val watchedAgain = action(menu.present(1), WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            menu.activate(watchedAgain, watchedAgain.actionId, "ENTER")
        )
        assertEquals(3, account.sent.size)
    }

    @Test fun acceptedDispatchBlocksNewGenerationUntilCanonicalReadbackMatches() {
        val account = Account(snapshot()).apply { applyDispatchToReadback = false }
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)

        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            menu.activate(selected, selected.actionId, "ENTER")
        )
        assertEquals(1, account.sent.size)
        assertTrue(menu.present(1).isEmpty())

        account.watchedIds.addAll(account.value!!.videos.map { it.id })
        assertEquals(4, menu.present(1).size)
        assertEquals(1, account.sent.size)
    }

    @Test fun pendingReadbackCannotCrossAccountOrLibraryIdentity() {
        val original = snapshot()
        val account = Account(original).apply { applyDispatchToReadback = false }
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)

        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            menu.activate(selected, selected.actionId, "ENTER")
        )
        account.watchedIds.addAll(original.videos.map { it.id })

        account.value = original.copy(accountFingerprint = "c".repeat(64))
        assertTrue(menu.present(1).isEmpty())

        account.value = original.copy(libraryItemFingerprint = "d".repeat(64))
        assertTrue(menu.present(1).isEmpty())

        account.value = original
        assertEquals(4, menu.present(1).size)
        assertEquals(1, account.sent.size)
    }

    @Test fun mismatchedOrDuplicateCanonicalReadbackNeverReopensGeneration() {
        val account = Account(snapshot()).apply { applyDispatchToReadback = false }
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            menu.activate(selected, selected.actionId, "ENTER")
        )

        val ids = account.value!!.videos.map { it.id }
        account.readbackOverride = CanonicalWatchedStateReadback(
            accountFingerprint = "c".repeat(64),
            libraryItemFingerprint = account.value!!.libraryItemFingerprint,
            seriesId = account.value!!.seriesId,
            watchedVideoIds = ids
        )
        assertTrue(menu.present(1).isEmpty())

        account.readbackOverride = CanonicalWatchedStateReadback(
            accountFingerprint = account.value!!.accountFingerprint,
            libraryItemFingerprint = account.value!!.libraryItemFingerprint,
            seriesId = account.value!!.seriesId,
            watchedVideoIds = ids + ids.first()
        )
        assertTrue(menu.present(1).isEmpty())

        account.readbackOverride = null
        account.watchedIds.addAll(ids)
        assertEquals(4, menu.present(1).size)
        assertEquals(1, account.sent.size)
    }

    @Test fun unknownCanonicalWatchedIdNeverReopensGeneration() {
        val account = Account(snapshot()).apply { applyDispatchToReadback = false }
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            menu.activate(selected, selected.actionId, "ENTER")
        )

        val ids = account.value!!.videos.map { it.id }
        account.readbackOverride = CanonicalWatchedStateReadback(
            accountFingerprint = account.value!!.accountFingerprint,
            libraryItemFingerprint = account.value!!.libraryItemFingerprint,
            seriesId = account.value!!.seriesId,
            watchedVideoIds = ids + "stale-unknown-episode"
        )
        assertTrue(menu.present(1).isEmpty())

        account.readbackOverride = CanonicalWatchedStateReadback(
            accountFingerprint = account.value!!.accountFingerprint,
            libraryItemFingerprint = account.value!!.libraryItemFingerprint,
            seriesId = account.value!!.seriesId,
            watchedVideoIds = ids
        )
        assertEquals(4, menu.present(1).size)
        assertEquals(1, account.sent.size)
    }

    @Test fun throwingCanonicalWatchedCollectionFailsClosedWithoutEscaping() {
        val account = Account(snapshot()).apply {
            applyDispatchToReadback = false
            readbackOverride = CanonicalWatchedStateReadback(
                accountFingerprint = value!!.accountFingerprint,
                libraryItemFingerprint = value!!.libraryItemFingerprint,
                seriesId = value!!.seriesId,
                watchedVideoIds = object : AbstractList<String>() {
                    override val size: Int = 1
                    override fun get(index: Int): String =
                        throw IllegalStateException("mutable Core wrapper changed")
                }
            )
        }
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)

        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            menu.activate(selected, selected.actionId, "ENTER")
        )
        assertTrue(menu.present(1).isEmpty())

        account.readbackOverride = null
        account.watchedIds.addAll(account.value!!.videos.map { it.id })
        assertEquals(4, menu.present(1).size)
        assertEquals(1, account.sent.size)
    }
}
