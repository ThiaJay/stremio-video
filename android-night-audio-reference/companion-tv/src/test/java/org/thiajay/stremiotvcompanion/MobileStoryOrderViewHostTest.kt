package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class MobileStoryOrderViewHostTest {
    private val first = CanonicalEpisode("series:1:1", 1, 1, "Episode one")
    private val special = CanonicalEpisode("series:0:2", 0, 2, "Narrative special")
    private val second = CanonicalEpisode("series:1:2", 1, 2, "Episode two")
    private val ancillary = CanonicalEpisode("series:0:99", 0, 99, "Behind the scenes")
    private val canonical = listOf(first, special, second, ancillary)
    private val hints = StoryOrderHints(1, listOf(first.id, special.id, second.id))

    private class RecordingView : MobileStoryOrderViewPort {
        var mode: MobileStoryOrderMode? = null
        var actions: List<MobileStoryOrderViewAction> = emptyList()
        var replaceCalls = 0
        var clearCalls = 0
        var rejectReplace = false
        var rejectClear = false
        var throwReplace = false

        override fun replaceStoryRows(
            mode: MobileStoryOrderMode,
            actions: List<MobileStoryOrderViewAction>
        ): Boolean {
            replaceCalls += 1
            if (throwReplace) throw IllegalStateException("view failed")
            if (rejectReplace) return false
            this.mode = mode
            this.actions = actions
            return true
        }

        override fun clearStoryRows(): Boolean {
            clearCalls += 1
            actions = emptyList()
            return !rejectClear
        }
    }

    @Test fun hostRendersStoryRowsWithoutSelecting() {
        val view = RecordingView()
        val host = MobileStoryOrderHostBinding(canonical, hints, view)

        assertTrue(host.render(MobileStoryOrderMode.STORY))
        assertEquals(MobileStoryOrderMode.STORY, view.mode)
        assertEquals(listOf(first.id, special.id, second.id), view.actions.map { it.stableVideoId })
        assertTrue(host.isOperational())
    }

    @Test fun exactSemanticActionReturnsOriginalCanonicalAndRevokesGeneration() {
        val view = RecordingView()
        val host = MobileStoryOrderHostBinding(canonical, hints, view)
        assertTrue(host.render(MobileStoryOrderMode.STORY))
        val action = view.actions[1]

        assertSame(
            special,
            host.activate(
                action,
                special.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )
        assertTrue(view.actions.isEmpty())
        assertNull(
            host.activate(
                action,
                special.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )
    }

    @Test fun canonicalModeAndMalformedHintsPreserveOrdinaryRows() {
        val malformed = StoryOrderHints(1, listOf(first.id, first.id, second.id))
        val view = RecordingView()
        val host = MobileStoryOrderHostBinding(canonical, malformed, view)

        assertTrue(host.render(MobileStoryOrderMode.STORY))
        assertEquals(canonical.map { it.id }, view.actions.map { it.stableVideoId })
        assertNull(host.next(first.id))

        assertTrue(host.render(MobileStoryOrderMode.CANONICAL))
        assertEquals(canonical.map { it.id }, view.actions.map { it.stableVideoId })
    }

    @Test fun rawStaleAndForgedActionsFailClosed() {
        val view = RecordingView()
        val host = MobileStoryOrderHostBinding(canonical, hints, view)
        assertTrue(host.render(MobileStoryOrderMode.STORY))
        val stale = view.actions.first()

        assertTrue(host.render(MobileStoryOrderMode.STORY))
        assertNull(
            host.activate(
                stale,
                first.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )
        assertTrue(view.actions.isEmpty())

        assertTrue(host.render(MobileStoryOrderMode.STORY))
        val current = view.actions.first()
        val forgedRow = MobileStoryOrderRow(
            stableVideoId = first.id,
            title = first.title,
            season = first.season,
            episodeNumber = first.episode,
            canonicalEpisode = first,
            provenanceSeal = Any()
        )
        val forged = MobileStoryOrderViewAction(
            stableVideoId = current.stableVideoId,
            title = current.title,
            season = current.season,
            episodeNumber = current.episodeNumber,
            row = forgedRow,
            provenanceSeal = Any()
        )
        assertNull(
            host.activate(
                forged,
                first.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )

        assertTrue(host.render(MobileStoryOrderMode.STORY))
        val raw = view.actions.first()
        assertNull(host.activate(raw, first.id, "raw-pointer"))
        assertTrue(host.isOperational())
    }

    @Test fun viewReplacementAndClearFailuresPoisonHost() {
        val rejected = RecordingView().apply { rejectReplace = true }
        val rejectedHost = MobileStoryOrderHostBinding(canonical, hints, rejected)
        assertFalse(rejectedHost.render(MobileStoryOrderMode.STORY))
        assertFalse(rejectedHost.isOperational())

        val throwing = RecordingView().apply { throwReplace = true }
        val throwingHost = MobileStoryOrderHostBinding(canonical, hints, throwing)
        assertFalse(throwingHost.render(MobileStoryOrderMode.STORY))
        assertFalse(throwingHost.isOperational())

        val clearFailure = RecordingView()
        val clearHost = MobileStoryOrderHostBinding(canonical, hints, clearFailure)
        assertTrue(clearHost.render(MobileStoryOrderMode.STORY))
        val action = clearFailure.actions.first()
        clearFailure.rejectClear = true
        assertNull(
            clearHost.activate(
                action,
                first.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )
        assertFalse(clearHost.isOperational())
    }

    @Test fun hostNextUsesPresentedStoryIdentityOnly() {
        val view = RecordingView()
        val host = MobileStoryOrderHostBinding(canonical, hints, view)

        assertTrue(host.render(MobileStoryOrderMode.STORY))
        assertSame(special, host.next(first.id))
        assertSame(second, host.next(special.id))
        assertNull(host.next(second.id))

        assertTrue(host.render(MobileStoryOrderMode.CANONICAL))
        assertNull(host.next(first.id))
    }

    @Test fun oversizedMobilePresentationFailsClosedBeforeView() {
        val oversized = List(MobileStoryOrderHostBinding.MAX_PRESENTED_ROWS + 1) { index ->
            CanonicalEpisode("series:1:" + index, 1, index + 1, "Episode " + index)
        }
        val order = StoryOrderHints(1, oversized.map { it.id })
        val view = RecordingView()
        val host = MobileStoryOrderHostBinding(oversized, order, view)

        assertFalse(host.render(MobileStoryOrderMode.STORY))
        assertEquals(0, view.replaceCalls)
        assertFalse(host.isOperational())
    }

    @Test fun hostOwnerThreadAndCloseRevokeOutstandingActions() {
        val view = RecordingView()
        val host = MobileStoryOrderHostBinding(canonical, hints, view)
        assertTrue(host.render(MobileStoryOrderMode.STORY))
        val action = view.actions.first()
        val foreignRender = AtomicReference<Boolean>()
        val foreignSelection = AtomicReference<CanonicalEpisode?>()
        val thread = Thread {
            foreignRender.set(host.render(MobileStoryOrderMode.CANONICAL))
            foreignSelection.set(
                host.activate(
                    action,
                    first.id,
                    MobileStoryOrderController.SEMANTIC_SELECTION
                )
            )
        }
        thread.start()
        thread.join()

        assertFalse(foreignRender.get())
        assertNull(foreignSelection.get())
        assertTrue(host.close())
        assertFalse(host.isOperational())
        assertFalse(host.render(MobileStoryOrderMode.STORY))
        assertFalse(host.close())
    }
}
