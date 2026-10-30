package org.thiajay.stremiotvcompanion

import java.util.AbstractList
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

class StoryOrderContractTest {
    private val regularOne = CanonicalEpisode("series:1:1", 1, 1, "Episode one")
    private val narrativeSpecial = CanonicalEpisode("series:0:2", 0, 2, "Narrative special")
    private val regularTwo = CanonicalEpisode("series:1:2", 1, 2, "Episode two")
    private val ancillarySpecial = CanonicalEpisode("series:0:99", 0, 99, "Behind the scenes")
    private val canonical = listOf(regularOne, narrativeSpecial, regularTwo, ancillarySpecial)
    private val hints = StoryOrderHints(
        storyOrderVersion = 1,
        storyOrder = listOf(regularOne.id, narrativeSpecial.id, regularTwo.id)
    )

    @Test fun narrativeSeasonZeroCanBeInsertedWithoutIdentityRewrite() {
        val resolved = StoryOrderContract.resolve(canonical, hints)
        assertTrue(resolved is StoryOrderResolution.Valid)
        val ordered = (resolved as StoryOrderResolution.Valid).episodes
        assertEquals(listOf(regularOne.id, narrativeSpecial.id, regularTwo.id), ordered.map { it.id })
        assertSame(narrativeSpecial, ordered[1])
        assertEquals(0, ordered[1].season)
    }

    @Test fun ancillarySeasonZeroCanRemainOutsideStoryPresentation() {
        val ordered = (StoryOrderContract.resolve(canonical, hints) as StoryOrderResolution.Valid).episodes
        assertTrue(ancillarySpecial.id !in ordered.map { it.id })
        assertTrue(ancillarySpecial in canonical)
    }

    @Test fun duplicateOrderIdsFallBackToCanonicalPresentation() {
        val broken = hints.copy(storyOrder = listOf(regularOne.id, regularOne.id, regularTwo.id))
        val resolved = StoryOrderContract.resolve(canonical, broken)
        assertEquals(
            StoryOrderFallbackReason.DUPLICATE_ORDER_ID,
            (resolved as StoryOrderResolution.CanonicalFallback).reason
        )
        val fallback = StoryOrderContract.presentation(canonical, broken, true)
        assertEquals(canonical, fallback)
        assertTrue(fallback !== canonical)
    }

    @Test fun unknownOrderIdFallsBackToCanonicalPresentation() {
        val broken = hints.copy(storyOrder = listOf(regularOne.id, "series:9:9", regularTwo.id))
        val resolved = StoryOrderContract.resolve(canonical, broken)
        assertEquals(
            StoryOrderFallbackReason.UNKNOWN_ORDER_ID,
            (resolved as StoryOrderResolution.CanonicalFallback).reason
        )
    }

    @Test fun missingRegularEpisodeFailsClosed() {
        val broken = hints.copy(storyOrder = listOf(regularOne.id, narrativeSpecial.id))
        val resolved = StoryOrderContract.resolve(canonical, broken)
        assertEquals(
            StoryOrderFallbackReason.MISSING_REGULAR_EPISODE,
            (resolved as StoryOrderResolution.CanonicalFallback).reason
        )
    }

    @Test fun unsupportedVersionAndMissingOrderFailClosed() {
        assertTrue(
            StoryOrderContract.resolve(canonical, hints.copy(storyOrderVersion = 2))
                is StoryOrderResolution.CanonicalFallback
        )
        assertTrue(
            StoryOrderContract.resolve(canonical, hints.copy(storyOrder = emptyList()))
                is StoryOrderResolution.CanonicalFallback
        )
    }

    @Test fun duplicateCanonicalIdentityFailsClosed() {
        val duplicated = canonical + regularOne
        val resolved = StoryOrderContract.resolve(duplicated, hints)
        assertEquals(
            StoryOrderFallbackReason.DUPLICATE_CANONICAL_ID,
            (resolved as StoryOrderResolution.CanonicalFallback).reason
        )
    }

    @Test fun canonicalModeReturnsDetachedListWithoutHintInterpretation() {
        var hintRead = false
        val hostileOrder = object : AbstractList<String>() {
            override val size: Int
                get() {
                    hintRead = true
                    throw IllegalStateException("must not inspect disabled Story Order")
                }

            override fun get(index: Int): String {
                hintRead = true
                throw IllegalStateException("must not inspect disabled Story Order")
            }
        }
        val result = StoryOrderContract.presentation(
            canonical,
            StoryOrderHints(99, hostileOrder),
            false
        )
        assertEquals(canonical, result)
        assertTrue(result !== canonical)
        assertTrue(!hintRead)
    }

    @Test fun nextStoryUsesStableIdAndReturnsOriginalCanonicalObject() {
        val decision = StoryOrderContract.next(canonical, hints, regularOne.id)
        assertTrue(decision is NextStoryDecision.UseCanonicalEpisode)
        assertSame(narrativeSpecial, (decision as NextStoryDecision.UseCanonicalEpisode).episode)
    }

    @Test fun storyContinuationNeverOverridesInvalidUnknownOrTerminalState() {
        val invalid = StoryOrderContract.next(canonical, hints.copy(storyOrderVersion = 2), regularOne.id)
        assertEquals(
            NoStoryOverrideReason.INVALID_HINT,
            (invalid as NextStoryDecision.UseExistingContinuation).reason
        )
        val unknown = StoryOrderContract.next(canonical, hints, ancillarySpecial.id)
        assertEquals(
            NoStoryOverrideReason.CURRENT_ID_NOT_IN_STORY,
            (unknown as NextStoryDecision.UseExistingContinuation).reason
        )
        val terminal = StoryOrderContract.next(canonical, hints, regularTwo.id)
        assertEquals(
            NoStoryOverrideReason.END_OF_STORY,
            (terminal as NextStoryDecision.UseExistingContinuation).reason
        )
    }

    @Test fun callerMutationCannotChangeDetachedStoryPresentation() {
        val mutableCanonical = canonical.toMutableList()
        val mutableOrder = hints.storyOrder!!.toMutableList()
        val result = StoryOrderContract.presentation(
            mutableCanonical,
            StoryOrderHints(1, mutableOrder),
            true
        )
        mutableCanonical.clear()
        mutableOrder.clear()

        assertEquals(
            listOf(regularOne.id, narrativeSpecial.id, regularTwo.id),
            result.map { it.id }
        )
        assertSame(narrativeSpecial, result[1])
        try {
            @Suppress("UNCHECKED_CAST")
            (result as MutableList<CanonicalEpisode>).add(ancillarySpecial)
            fail("detached result must be immutable")
        } catch (_: UnsupportedOperationException) {
            // Expected: the host cannot mutate the accepted presentation.
        }
    }

    @Test fun changingCardinalityFailsClosedWithoutEscaping() {
        val shortIterator = object : AbstractList<String>() {
            override val size: Int = 2
            override fun get(index: Int): String = regularOne.id
            override fun iterator(): MutableIterator<String> =
                mutableListOf(regularOne.id).iterator()
        }
        val hostileHints = StoryOrderHints(1, shortIterator)

        val resolved = StoryOrderContract.resolve(canonical, hostileHints)
        assertEquals(
            StoryOrderFallbackReason.INPUT_SNAPSHOT_FAILED,
            (resolved as StoryOrderResolution.CanonicalFallback).reason
        )
        assertTrue(StoryOrderContract.presentation(canonical, hostileHints, true).isEmpty())
        val next = StoryOrderContract.next(canonical, hostileHints, regularOne.id)
        assertEquals(
            NoStoryOverrideReason.INVALID_HINT,
            (next as NextStoryDecision.UseExistingContinuation).reason
        )
    }

    @Test fun throwingCanonicalCollectionFailsClosedWithoutEscaping() {
        val throwingCanonical = object : AbstractList<CanonicalEpisode>() {
            override val size: Int = 1
            override fun get(index: Int): CanonicalEpisode =
                throw IllegalStateException("host collection failed")
        }

        val resolved = StoryOrderContract.resolve(throwingCanonical, hints)
        assertEquals(
            StoryOrderFallbackReason.INPUT_SNAPSHOT_FAILED,
            (resolved as StoryOrderResolution.CanonicalFallback).reason
        )
        assertTrue(StoryOrderContract.presentation(throwingCanonical, hints, true).isEmpty())
        val next = StoryOrderContract.next(throwingCanonical, hints, regularOne.id)
        assertEquals(
            NoStoryOverrideReason.INVALID_HINT,
            (next as NextStoryDecision.UseExistingContinuation).reason
        )
    }
}
