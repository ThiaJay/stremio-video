package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class MobileStoryOrderControllerTest {
    private val regularOne = CanonicalEpisode("series:1:1", 1, 1, "Episode one")
    private val narrativeSpecial = CanonicalEpisode("series:0:2", 0, 2, "Narrative special")
    private val regularTwo = CanonicalEpisode("series:1:2", 1, 2, "Episode two")
    private val ancillarySpecial = CanonicalEpisode("series:0:99", 0, 99, "Behind the scenes")
    private val canonical = listOf(regularOne, narrativeSpecial, regularTwo, ancillarySpecial)
    private val hints = StoryOrderHints(
        1,
        listOf(regularOne.id, narrativeSpecial.id, regularTwo.id)
    )

    @Test fun mobileStoryModePresentsNarrativeSpecialWithStableIdentity() {
        val controller = MobileStoryOrderController(canonical, hints)
        val rows = controller.present(MobileStoryOrderMode.STORY)!!
        assertEquals(
            listOf(regularOne.id, narrativeSpecial.id, regularTwo.id),
            rows.map { it.stableVideoId }
        )
        assertEquals(0, rows[1].season)
        val selected = controller.select(
            rows[1],
            narrativeSpecial.id,
            MobileStoryOrderController.SEMANTIC_SELECTION
        )
        assertSame(narrativeSpecial, selected)
    }

    @Test fun mobileCanonicalModePreservesOrdinarySequenceAndAncillaryEntry() {
        val controller = MobileStoryOrderController(canonical, hints)
        val rows = controller.present(MobileStoryOrderMode.CANONICAL)!!
        assertEquals(canonical.map { it.id }, rows.map { it.stableVideoId })
        assertEquals(ancillarySpecial.title, rows.last().title)
        assertNull(controller.next(regularOne.id))
        try {
            @Suppress("UNCHECKED_CAST")
            (rows as MutableList<MobileStoryOrderRow>).clear()
            fail("mobile presentation must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected.
        }
    }

    @Test fun malformedHintsFallBackToCanonicalAndDisableStoryNext() {
        val malformed = StoryOrderHints(
            1,
            listOf(regularOne.id, regularOne.id, regularTwo.id)
        )
        val controller = MobileStoryOrderController(canonical, malformed)
        val rows = controller.present(MobileStoryOrderMode.STORY)!!
        assertEquals(canonical.map { it.id }, rows.map { it.stableVideoId })
        assertNull(controller.next(regularOne.id))
        assertSame(
            regularOne,
            controller.select(
                rows.first(),
                regularOne.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )
    }

    @Test fun semanticSelectionRequiresExactIssuedRowAndStableId() {
        val controller = MobileStoryOrderController(canonical, hints)
        val first = controller.present(MobileStoryOrderMode.STORY)!!.first()
        assertNull(
            controller.select(
                first,
                regularTwo.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )
        assertNull(
            controller.select(
                first,
                regularOne.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )

        val reissued = controller.present(MobileStoryOrderMode.STORY)!!.first()
        assertSame(
            regularOne,
            controller.select(
                reissued,
                regularOne.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )
    }

    @Test fun rawPointerAndForgedRowsNeverAuthorizeSelection() {
        val controller = MobileStoryOrderController(canonical, hints)
        val issued = controller.present(MobileStoryOrderMode.STORY)!!.first()
        assertNull(controller.select(issued, regularOne.id, "raw-pointer"))

        val current = controller.present(MobileStoryOrderMode.STORY)!!.first()
        val forged = MobileStoryOrderRow(
            stableVideoId = current.stableVideoId,
            title = current.title,
            season = current.season,
            episodeNumber = current.episodeNumber,
            watched = current.watched,
            canonicalEpisode = regularOne,
            provenanceSeal = Any()
        )
        assertNull(
            controller.select(
                forged,
                regularOne.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )
    }

    @Test fun watchedMarkersStayBoundToStableIdsAcrossPresentationOrder() {
        val controller = MobileStoryOrderController(
            canonical,
            hints,
            setOf(narrativeSpecial.id, regularTwo.id)
        )

        val story = controller.present(MobileStoryOrderMode.STORY)!!
        assertEquals(
            mapOf(
                regularOne.id to false,
                narrativeSpecial.id to true,
                regularTwo.id to true
            ),
            story.associate { it.stableVideoId to it.watched }
        )

        val ordinary = controller.present(MobileStoryOrderMode.CANONICAL)!!
        assertEquals(
            mapOf(
                regularOne.id to false,
                narrativeSpecial.id to true,
                regularTwo.id to true,
                ancillarySpecial.id to false
            ),
            ordinary.associate { it.stableVideoId to it.watched }
        )

        val invalid = MobileStoryOrderController(
            canonical,
            hints,
            setOf("series:unknown")
        )
        assertNull(invalid.present(MobileStoryOrderMode.STORY))
        assertTrue(!invalid.isOperational())
    }

    @Test fun mobileNextUsesPresentedStableIdentityAndSurvivesCallerMutation() {
        val mutableCanonical = canonical.toMutableList()
        val mutableOrder = hints.storyOrder!!.toMutableList()
        val controller = MobileStoryOrderController(
            mutableCanonical,
            StoryOrderHints(1, mutableOrder)
        )
        val rows = controller.present(MobileStoryOrderMode.STORY)!!
        mutableCanonical.clear()
        mutableOrder.clear()

        assertEquals(3, rows.size)
        assertSame(narrativeSpecial, controller.next(regularOne.id))
        assertSame(regularTwo, controller.next(narrativeSpecial.id))
        assertNull(controller.next(regularTwo.id))
        assertNull(controller.next("unknown"))
    }

    @Test fun callerHintMutationCannotChangeBoundScreenGeneration() {
        val mutableOrder = hints.storyOrder!!.toMutableList()
        val controller = MobileStoryOrderController(
            canonical,
            StoryOrderHints(1, mutableOrder)
        )

        mutableOrder.clear()
        mutableOrder += ancillarySpecial.id
        val first = controller.present(MobileStoryOrderMode.STORY)!!
        assertEquals(
            listOf(regularOne.id, narrativeSpecial.id, regularTwo.id),
            first.map { it.stableVideoId }
        )
        assertSame(narrativeSpecial, controller.next(regularOne.id))

        mutableOrder.clear()
        mutableOrder += listOf(regularTwo.id, regularOne.id)
        val second = controller.present(MobileStoryOrderMode.STORY)!!
        assertEquals(
            listOf(regularOne.id, narrativeSpecial.id, regularTwo.id),
            second.map { it.stableVideoId }
        )
        assertSame(regularTwo, controller.next(narrativeSpecial.id))
    }

    @Test fun watchedRefreshRevokesRowsAndRebindsMarkersByStableId() {
        val controller = MobileStoryOrderController(canonical, hints)
        val stale = controller.present(MobileStoryOrderMode.STORY)!!.first()

        assertTrue(controller.refreshWatchedStableIds(setOf(narrativeSpecial.id, regularTwo.id)))
        assertNull(
            controller.select(
                stale,
                regularOne.id,
                MobileStoryOrderController.SEMANTIC_SELECTION
            )
        )
        val refreshed = controller.present(MobileStoryOrderMode.STORY)!!
        assertEquals(
            mapOf(regularOne.id to false, narrativeSpecial.id to true, regularTwo.id to true),
            refreshed.associate { it.stableVideoId to it.watched }
        )

        assertTrue(!controller.refreshWatchedStableIds(setOf("series:unknown")))
        assertTrue(!controller.isOperational())
        assertNull(controller.present(MobileStoryOrderMode.STORY))
    }

    @Test fun ownerThreadAndCloseFailClosed() {
        val controller = MobileStoryOrderController(canonical, hints)
        val foreignPresent = AtomicReference<List<MobileStoryOrderRow>?>()
        val foreignClose = AtomicReference<Boolean>()
        val thread = Thread {
            foreignPresent.set(controller.present(MobileStoryOrderMode.STORY))
            foreignClose.set(controller.close())
        }
        thread.start()
        thread.join()

        assertNull(foreignPresent.get())
        assertEquals(false, foreignClose.get())
        assertTrue(controller.isOperational())
        assertTrue(controller.close())
        assertTrue(!controller.isOperational())
        assertNull(controller.present(MobileStoryOrderMode.CANONICAL))
    }
}
