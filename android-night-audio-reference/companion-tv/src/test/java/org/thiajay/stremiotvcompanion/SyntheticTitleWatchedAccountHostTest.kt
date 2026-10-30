package org.thiajay.stremiotvcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class SyntheticTitleWatchedAccountHostTest {
    @Test fun seriesActionMutatesOnlyReleasedRegularSyntheticEpisodes() {
        val host = SyntheticTitleWatchedAccountHost()

        assertTrue(host.dispatchTitleWatched(1L, command("MarkAsWatched", true)))

        assertEquals(
            listOf(
                "local:mobile-watched:1:1",
                "local:mobile-watched:1:2"
            ),
            host.watchedIds()
        )
        assertFalse(host.watchedIds().contains("local:mobile-watched:0:2"))
        assertEquals(1, host.acceptedDispatchCount())
    }

    @Test fun seasonActionIsBoundedToRequestedSyntheticSeason() {
        val host = SyntheticTitleWatchedAccountHost()
        assertTrue(
            host.dispatchTitleWatched(
                1L,
                command("MarkSeasonAsWatched", listOf(1, true))
            )
        )
        assertEquals(2, host.watchedIds().size)
        assertTrue(
            host.dispatchTitleWatched(
                1L,
                command("MarkSeasonAsWatched", listOf(1, false))
            )
        )
        assertTrue(host.watchedIds().isEmpty())
        assertEquals(2, host.acceptedDispatchCount())
    }

    @Test fun malformedOrStaleDispatchNeverMutatesSyntheticState() {
        val host = SyntheticTitleWatchedAccountHost()

        assertFalse(host.dispatchTitleWatched(2L, command("MarkAsWatched", true)))
        assertFalse(
            host.dispatchTitleWatched(
                1L,
                mapOf("action" to "MetaDetails", "args" to emptyMap<String, Any>())
            )
        )
        assertFalse(
            host.dispatchTitleWatched(
                1L,
                command("MarkSeasonAsWatched", listOf(0, true))
            )
        )
        assertTrue(host.watchedIds().isEmpty())
        assertEquals(0, host.acceptedDispatchCount())
    }

    @Test fun observationsAndReadbackListsAreDetached() {
        val host = SyntheticTitleWatchedAccountHost()
        val before = host.observe()
        val callerCopy = before.watchedVideoIds.toMutableList()
        callerCopy.add("forged")

        assertTrue(before.snapshot.videos !== host.observe().snapshot.videos)
        assertTrue(host.observe().watchedVideoIds.isEmpty())
        assertTrue(host.watchedIds().isEmpty())
    }

    private fun command(action: String, args: Any): Map<String, Any> =
        mapOf(
            "action" to "MetaDetails",
            "args" to mapOf("action" to action, "args" to args)
        )
}
