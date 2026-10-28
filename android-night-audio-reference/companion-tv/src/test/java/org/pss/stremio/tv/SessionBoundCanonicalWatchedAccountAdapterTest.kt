package org.pss.stremio.tv

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class SessionBoundCanonicalWatchedAccountAdapterTest {
    private val videos = mutableListOf(
        CanonicalEpisode("s1e1", 1, 1, "2025-01-01T00:00:00Z"),
        CanonicalEpisode("s1e2", 1, 2, "2025-02-01T00:00:00Z")
    )
    private val storyOrder = mutableListOf("s1e1", "s1e2")
    private val watchedIds = mutableListOf("s1e1")

    private fun snapshot() = SeriesAccountSnapshot(
        accountFingerprint = "a".repeat(64),
        libraryItemFingerprint = "b".repeat(64),
        seriesId = "series",
        readyMetaId = "series",
        metaReady = true,
        libraryReady = true,
        videos = videos,
        storyOrder = storyOrder,
        storyOrderVersion = 1,
        now = Instant.parse("2026-10-28T03:00:00Z")
    )

    private inner class Host : CanonicalWatchedAccountHostPort {
        var epoch = 7L
        var observedEpoch = 7L
        var value = snapshot()
        var rejectDispatch = false
        var throwObserve = false
        var throwDispatch = false
        var incrementEpochOnDispatch = false
        var epochSequence = ArrayDeque<Long>()
        var epochReads = 0
        var observeCalls = 0
        val sent = mutableListOf<Map<String, Any>>()
        val dispatchedEpochs = mutableListOf<Long>()

        override fun sessionEpoch(): Long {
            epochReads += 1
            return if (epochSequence.isEmpty()) epoch else epochSequence.removeFirst()
        }

        override fun observe(): CanonicalWatchedAccountObservation? {
            observeCalls += 1
            if (throwObserve) throw IllegalStateException("bounded test")
            return CanonicalWatchedAccountObservation(observedEpoch, value, watchedIds)
        }

        override fun dispatchTitleWatched(
            expectedSessionEpoch: Long,
            message: Map<String, Any>
        ): Boolean {
            dispatchedEpochs.add(expectedSessionEpoch)
            if (expectedSessionEpoch != epoch) return false
            if (throwDispatch) throw IllegalStateException("bounded test")
            sent.add(message)
            if (incrementEpochOnDispatch) epoch += 1
            return !rejectDispatch
        }
    }

    private fun seriesCommand(watched: Boolean = true): Map<String, Any> =
        mapOf("action" to "MetaDetails", "args" to
            mapOf("action" to "MarkAsWatched", "args" to watched))

    private fun seasonCommand(season: Int, watched: Boolean): Map<String, Any> =
        mapOf("action" to "MetaDetails", "args" to
            mapOf("action" to "MarkSeasonAsWatched", "args" to listOf(season, watched)))

    @Test fun detachesSnapshotAndWatchedIdsFromHostCollections() {
        val host = Host()
        val adapter = SessionBoundCanonicalWatchedAccountAdapter(host)

        val detachedSnapshot = adapter.currentSnapshot()!!
        val detachedReadback = adapter.currentWatchedState()!!
        videos.add(CanonicalEpisode("s1e3", 1, 3, "2025-03-01T00:00:00Z"))
        storyOrder.add("s1e3")
        watchedIds.add("s1e2")

        assertEquals(listOf("s1e1", "s1e2"), detachedSnapshot.videos.map { it.id })
        assertEquals(listOf("s1e1", "s1e2"), detachedSnapshot.storyOrder)
        assertEquals(listOf("s1e1"), detachedReadback.watchedVideoIds)
    }

    @Test fun watchedReadbackRequiresMatchingObservedSnapshot() {
        val host = Host()
        val adapter = SessionBoundCanonicalWatchedAccountAdapter(host)
        assertNull(adapter.currentWatchedState())
        assertNotNull(adapter.currentSnapshot())

        host.value = host.value.copy(libraryItemFingerprint = "c".repeat(64))
        assertNull(adapter.currentWatchedState())

        assertNotNull(adapter.currentSnapshot())
        assertNotNull(adapter.currentWatchedState())
    }

    @Test fun dispatchAcceptsOnlyExactTitleWatchedCommands() {
        val host = Host()
        val adapter = SessionBoundCanonicalWatchedAccountAdapter(host)
        assertNotNull(adapter.currentSnapshot())

        assertFalse(adapter.dispatch(mapOf("action" to "Player", "args" to true)))
        assertEquals(0, host.sent.size)

        assertTrue(adapter.dispatch(seriesCommand()))
        assertEquals(seriesCommand(), host.sent.single())

        assertNotNull(adapter.currentSnapshot())
        assertTrue(adapter.dispatch(seasonCommand(1, false)))
        assertEquals(seasonCommand(1, false), host.sent.last())

        assertNotNull(adapter.currentSnapshot())
        assertFalse(adapter.dispatch(seasonCommand(0, true)))
        assertEquals(2, host.sent.size)
    }

    @Test fun dispatchBindsHostCallToObservedSessionEpoch() {
        val host = Host()
        val adapter = SessionBoundCanonicalWatchedAccountAdapter(host)
        assertNotNull(adapter.currentSnapshot())

        host.epoch = 8L
        host.epochSequence.addAll(listOf(7L, 7L))
        assertFalse(adapter.dispatch(seriesCommand()))
        assertEquals(listOf(7L), host.dispatchedEpochs)
        assertEquals(0, host.sent.size)
        assertFalse(adapter.dispatch(seriesCommand()))
    }

    @Test fun dispatchConsumesSnapshotLeaseBeforeRejectedHostCall() {
        val host = Host().apply { rejectDispatch = true }
        val adapter = SessionBoundCanonicalWatchedAccountAdapter(host)
        assertNotNull(adapter.currentSnapshot())

        assertFalse(adapter.dispatch(seriesCommand()))
        assertFalse(adapter.dispatch(seriesCommand()))
        assertEquals(1, host.sent.size)

        assertNotNull(adapter.currentSnapshot())
        assertFalse(adapter.dispatch(seriesCommand()))
        assertEquals(2, host.sent.size)
    }

    @Test fun sessionEpochDriftFailsClosed() {
        val host = Host()
        val adapter = SessionBoundCanonicalWatchedAccountAdapter(host)
        host.epochSequence.addAll(listOf(7L, 8L))
        assertNull(adapter.currentSnapshot())

        host.epoch = 7L
        host.observedEpoch = 8L
        assertNull(adapter.currentSnapshot())

        host.observedEpoch = 7L
        assertNotNull(adapter.currentSnapshot())
        host.incrementEpochOnDispatch = true
        assertFalse(adapter.dispatch(seriesCommand()))
        assertEquals(1, host.sent.size)
    }

    @Test fun closedAdapterNeverTouchesHostCallbacks() {
        val host = Host()
        val adapter = SessionBoundCanonicalWatchedAccountAdapter(host)
        assertTrue(adapter.close())

        assertNull(adapter.currentSnapshot())
        assertNull(adapter.currentWatchedState())
        assertFalse(adapter.dispatch(seriesCommand()))
        assertEquals(0, host.epochReads)
        assertEquals(0, host.observeCalls)
        assertEquals(0, host.dispatchedEpochs.size)
    }

    @Test fun ownedScreenConstructionExceptionRevokesAdapterWithoutHostAccess() {
        val host = Host()
        var captured: SessionBoundCanonicalWatchedAccountAdapter? = null

        val result = CanonicalWatchedScreenConstructionBoundary.owned<String>(host) { account ->
            captured = account
            throw IllegalStateException("bounded construction failure")
        }

        assertNull(result)
        assertNotNull(captured)
        assertNull(captured!!.currentSnapshot())
        assertEquals(0, host.epochReads)
        assertEquals(0, host.observeCalls)
        assertEquals(0, host.dispatchedEpochs.size)
    }

    @Test fun ownedScreenNullRevokesButSuccessfulConstructionTransfersLifecycle() {
        val rejectedHost = Host()
        var rejected: SessionBoundCanonicalWatchedAccountAdapter? = null
        assertNull(CanonicalWatchedScreenConstructionBoundary.owned<Any>(rejectedHost) {
            rejected = it
            null
        })
        assertNull(rejected!!.currentSnapshot())
        assertEquals(0, rejectedHost.epochReads)

        val acceptedHost = Host()
        var accepted: SessionBoundCanonicalWatchedAccountAdapter? = null
        val screen = Any()
        assertSame(screen, CanonicalWatchedScreenConstructionBoundary.owned(acceptedHost) {
            accepted = it
            screen
        })
        assertNotNull(accepted!!.currentSnapshot())
        assertTrue(accepted!!.close())
    }

    @Test fun externalScreenConstructionFailuresAreContainedWithoutOwnership() {
        assertNull(CanonicalWatchedScreenConstructionBoundary.external<String> {
            throw IllegalStateException("bounded construction failure")
        })
        assertNull(CanonicalWatchedScreenConstructionBoundary.external<String> {
            throw LinkageError("bounded compatibility failure")
        })
    }

    @Test fun foreignThreadAndCloseRejectAllOperations() {
        val host = Host()
        val adapter = SessionBoundCanonicalWatchedAccountAdapter(host)
        val snapshot = AtomicReference<SeriesAccountSnapshot?>()
        val dispatched = AtomicReference<Boolean>()
        val closed = AtomicReference<Boolean>()
        val thread = Thread {
            snapshot.set(adapter.currentSnapshot())
            dispatched.set(adapter.dispatch(seriesCommand()))
            closed.set(adapter.close())
        }
        thread.start()
        thread.join()

        assertNull(snapshot.get())
        assertFalse(dispatched.get())
        assertFalse(closed.get())
        assertTrue(adapter.close())
        assertNull(adapter.currentSnapshot())
        assertNull(adapter.currentWatchedState())
        assertFalse(adapter.dispatch(seriesCommand()))
    }

    @Test fun throwingHostCollectionsAndCallbacksFailClosed() {
        val host = Host()
        val adapter = SessionBoundCanonicalWatchedAccountAdapter(host)
        host.value = host.value.copy(videos = object : AbstractList<CanonicalEpisode>() {
            override val size: Int = 1
            override fun get(index: Int): CanonicalEpisode =
                throw IllegalStateException("mutable host wrapper changed")
        })
        assertNull(adapter.currentSnapshot())

        host.value = snapshot()
        host.throwObserve = true
        assertNull(adapter.currentSnapshot())

        host.throwObserve = false
        assertNotNull(adapter.currentSnapshot())
        host.throwDispatch = true
        assertFalse(adapter.dispatch(seriesCommand()))
        assertEquals(0, host.sent.size)
    }
}
