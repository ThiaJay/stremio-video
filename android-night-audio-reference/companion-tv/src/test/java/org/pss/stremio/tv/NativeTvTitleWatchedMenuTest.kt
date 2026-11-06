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
        var afterWatchedStateRead: (() -> Unit)? = null
        var resumeVideoId: String? = "s1e2"
        var resumeTimeOffset = 42_000L

        override fun currentSnapshot(): SeriesAccountSnapshot? {
            if (throwOnRead) throw IllegalStateException("bounded test")
            return value
        }

        override fun dispatch(message: Map<String, Any>): Boolean {
            if (throwOnDispatch) throw IllegalStateException("bounded test")
            sent.add(message)
            if (applyDispatchToReadback) {
                val inner = message["args"] as Map<*, *>
                val watched: Boolean
                val scope = when (inner["action"]) {
                    "MarkAsWatched" -> {
                        watched = inner["args"] as Boolean
                        WatchedScope.Series
                    }
                    "MarkSeasonAsWatched" -> {
                        val args = inner["args"] as List<*>
                        watched = args[1] as Boolean
                        WatchedScope.Season(args[0] as Int)
                    }
                    else -> return false
                }
                val ids = value?.let { TvWatchedCoordinator().prepare(it, scope, watched)?.selectedIds }
                    ?: return false
                if (watched) watchedIds.addAll(ids) else watchedIds.removeAll(ids.toSet())
                if (scope == WatchedScope.Series && watched) resumeTimeOffset = 0L
            }
            return true
        }

        override fun currentWatchedState(): CanonicalWatchedStateReadback? {
            val state = readbackOverride ?: value?.let {
                CanonicalWatchedStateReadback(
                accountFingerprint = it.accountFingerprint,
                libraryItemFingerprint = it.libraryItemFingerprint,
                seriesId = it.seriesId,
                watchedVideoIds = watchedIds.toList(),
                resumeVideoId = resumeVideoId,
                resumeTimeOffset = resumeTimeOffset
                )
            }
            afterWatchedStateRead?.invoke()
            return state
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

    @Test fun hostSnapshotCannotAdvanceReleaseClock() {
        val account = Account(snapshot(listOf(
            CanonicalEpisode("s1e1", 1, 1, released),
            CanonicalEpisode("s1e2", 1, 2, "2035-01-01T00:00:00Z")
        )).copy(now = Instant.now().plusSeconds(3_600)))
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)

        assertTrue(menu.present(1).isEmpty())
        assertEquals(0, account.sent.size)
    }

    @Test fun delimiterBearingCatalogueFieldsCannotAuthorizeStalePlan() {
        val shared = CanonicalEpisode("s1e1", 1, 1, released)
        val collisionA = snapshot(listOf(
            shared,
            CanonicalEpisode("x", 1, 2, "3/4")
        )).copy(storyOrder = null, storyOrderVersion = null)
        val collisionB = snapshot(listOf(
            shared,
            CanonicalEpisode("x/1", 2, 3, "4")
        )).copy(storyOrder = null, storyOrderVersion = null)
        val coordinator = TvWatchedCoordinator()
        val issued = coordinator.prepare(collisionA, WatchedScope.Series, true)!!
        val distinct = coordinator.prepare(collisionB, WatchedScope.Series, true)!!

        assertEquals(issued.selectedIds, distinct.selectedIds)
        assertNotEquals(issued.fingerprint, distinct.fingerprint)
        assertNull(coordinator.confirm(
            issued,
            collisionB,
            TvWatchedConfirmation(true, "ENTER", issued.fingerprint)
        ))
    }

    @Test fun externalCanonicalPortCannotBypassAdapterMetadataBounds() {
        val tooLong = "x".repeat(CanonicalWatchedBounds.MAX_STABLE_ID_LENGTH + 1)
        val coordinator = TvWatchedCoordinator()
        val valid = snapshot()

        assertNull(coordinator.prepare(
            valid.copy(seriesId = tooLong, readyMetaId = tooLong),
            WatchedScope.Series,
            true
        ))
        assertNull(coordinator.prepare(
            snapshot(listOf(CanonicalEpisode(tooLong, 1, 1, released))),
            WatchedScope.Series,
            true
        ))
        assertNull(coordinator.prepare(
            valid.copy(storyOrder = listOf(tooLong)),
            WatchedScope.Series,
            true
        ))
        assertNull(coordinator.prepare(
            snapshot(listOf(CanonicalEpisode(
                "s1e1",
                1,
                1,
                released + "x".repeat(CanonicalWatchedBounds.MAX_RELEASED_AT_LENGTH)
            ))),
            WatchedScope.Series,
            true
        ))
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

    @Test fun finalSnapshotAfterWatchedReadbackDriftFailsBeforeDispatch() {
        val original = snapshot()
        val account = Account(original)
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)
        account.afterWatchedStateRead = {
            account.afterWatchedStateRead = null
            account.value = original.copy(
                libraryItemFingerprint = "c".repeat(64),
                seriesId = "other-series",
                readyMetaId = "other-series"
            )
        }

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

    @Test fun emptyHistoryCannotCarryPositiveResumeOffset() {
        val account = Account(snapshot()).apply {
            resumeVideoId = null
            resumeTimeOffset = 42_000L
        }
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val invalid = action(menu.present(1), WatchedScope.Series, true)

        assertEquals(
            NativeTvTitleWatchedOutcome.REFUSED,
            menu.activate(invalid, invalid.actionId, "ENTER")
        )
        assertEquals(0, account.sent.size)

        account.resumeTimeOffset = 0L
        val freshMenu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val valid = action(freshMenu.present(1), WatchedScope.Series, true)
        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            freshMenu.activate(valid, valid.actionId, "ENTER")
        )
        assertEquals(1, account.sent.size)
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
        account.resumeTimeOffset = 0L
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
        account.resumeTimeOffset = 0L

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
            watchedVideoIds = ids,
            resumeVideoId = account.resumeVideoId,
            resumeTimeOffset = 0L
        )
        assertTrue(menu.present(1).isEmpty())

        account.readbackOverride = CanonicalWatchedStateReadback(
            accountFingerprint = account.value!!.accountFingerprint,
            libraryItemFingerprint = account.value!!.libraryItemFingerprint,
            seriesId = account.value!!.seriesId,
            watchedVideoIds = ids + ids.first(),
            resumeVideoId = account.resumeVideoId,
            resumeTimeOffset = 0L
        )
        assertTrue(menu.present(1).isEmpty())

        account.readbackOverride = null
        account.watchedIds.addAll(ids)
        account.resumeTimeOffset = 0L
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
            watchedVideoIds = ids + "stale-unknown-episode",
            resumeVideoId = account.resumeVideoId,
            resumeTimeOffset = 0L
        )
        assertTrue(menu.present(1).isEmpty())

        account.readbackOverride = CanonicalWatchedStateReadback(
            accountFingerprint = account.value!!.accountFingerprint,
            libraryItemFingerprint = account.value!!.libraryItemFingerprint,
            seriesId = account.value!!.seriesId,
            watchedVideoIds = ids,
            resumeVideoId = account.resumeVideoId,
            resumeTimeOffset = 0L
        )
        assertEquals(4, menu.present(1).size)
        assertEquals(1, account.sent.size)
    }

    @Test fun throwingCanonicalWatchedCollectionFailsClosedWithoutEscaping() {
        val account = Account(snapshot()).apply { applyDispatchToReadback = false }
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)

        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            menu.activate(selected, selected.actionId, "ENTER")
        )
        account.readbackOverride = CanonicalWatchedStateReadback(
            accountFingerprint = account.value!!.accountFingerprint,
            libraryItemFingerprint = account.value!!.libraryItemFingerprint,
            seriesId = account.value!!.seriesId,
            watchedVideoIds = object : AbstractList<String>() {
                override val size: Int = 1
                override fun get(index: Int): String =
                    throw IllegalStateException("mutable Core wrapper changed")
            },
            resumeVideoId = account.resumeVideoId,
            resumeTimeOffset = 0L
        )
        assertTrue(menu.present(1).isEmpty())

        account.readbackOverride = null
        account.watchedIds.addAll(account.value!!.videos.map { it.id })
        account.resumeTimeOffset = 0L
        assertEquals(4, menu.present(1).size)
        assertEquals(1, account.sent.size)
    }

    @Test fun seriesWatchedRequiresExactMembershipAndClearedResumeReadback() {
        val account = Account(snapshot()).apply { applyDispatchToReadback = false }
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Series, true)

        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            menu.activate(selected, selected.actionId, "ENTER")
        )
        account.watchedIds.addAll(account.value!!.videos.map { it.id })
        assertTrue(menu.present(1).isEmpty())

        account.resumeTimeOffset = 0L
        account.resumeVideoId = "s1e1"
        assertTrue(menu.present(1).isEmpty())

        account.resumeVideoId = "s1e2"
        assertEquals(4, menu.present(1).size)
        assertEquals(1, account.sent.size)
    }

    @Test fun seriesUnwatchedPreservesResumeAndRejectsUnselectedMutation() {
        val account = Account(snapshot(listOf(
            CanonicalEpisode("s1e1", 1, 1, released),
            CanonicalEpisode("s1e2", 1, 2, released),
            CanonicalEpisode("s1e3", 1, 3, future)
        ))).apply {
            watchedIds.addAll(listOf("s1e1", "s1e2", "s1e3"))
            applyDispatchToReadback = false
        }
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(null), WatchedScope.Series, false)

        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            menu.activate(selected, selected.actionId, "ENTER", unwatchAcknowledged = true)
        )
        account.watchedIds.removeAll(listOf("s1e1", "s1e2"))
        account.resumeTimeOffset = 0L
        assertTrue(menu.present(null).isEmpty())

        account.resumeTimeOffset = 42_000L
        account.watchedIds.remove("s1e3")
        assertTrue(menu.present(null).isEmpty())

        account.watchedIds.add("s1e3")
        assertEquals(2, menu.present(null).size)
        assertEquals(1, account.sent.size)
    }

    @Test fun seasonWatchedPreservesResumeIdentityAndOffset() {
        val account = Account(snapshot()).apply { applyDispatchToReadback = false }
        val menu = NativeTvTitleWatchedMenu(TvWatchedCoordinator(), account)
        val selected = action(menu.present(1), WatchedScope.Season(1), true)

        assertEquals(
            NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK,
            menu.activate(selected, selected.actionId, "ENTER")
        )
        account.watchedIds.addAll(account.value!!.videos.map { it.id })
        account.resumeVideoId = "s1e1"
        account.resumeTimeOffset = 0L
        assertTrue(menu.present(1).isEmpty())

        account.resumeVideoId = "s1e2"
        account.resumeTimeOffset = 42_000L
        assertEquals(4, menu.present(1).size)
        assertEquals(1, account.sent.size)
    }
}
