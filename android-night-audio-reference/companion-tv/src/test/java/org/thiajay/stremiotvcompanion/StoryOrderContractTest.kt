package org.thiajay.stremiotvcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
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
        assertSame(canonical, StoryOrderContract.presentation(canonical, broken, true))
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

    @Test fun canonicalModeReturnsOriginalListWithoutHintInterpretation() {
        val invalid = StoryOrderHints(99, listOf("unknown"))
        assertSame(canonical, StoryOrderContract.presentation(canonical, invalid, false))
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
}
