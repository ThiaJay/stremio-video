package org.thiajay.stremiotvcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.pss.stremio.tv.NativeTvTitleWatchedHostBinding
import org.pss.stremio.tv.NativeTvTitleWatchedOutcome
import org.pss.stremio.tv.NativeTvTitleWatchedViewAction
import org.pss.stremio.tv.NativeTvTitleWatchedViewPort
import org.pss.stremio.tv.SessionBoundCanonicalWatchedAccountAdapter
import org.pss.stremio.tv.TvWatchedCoordinator

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

    @Test fun tvHostBindingAppliesOnlyLocalSyntheticSeriesState() {
        val host = SyntheticTitleWatchedAccountHost()
        val account = SessionBoundCanonicalWatchedAccountAdapter(host)
        val view = SyntheticTvView()
        val binding = NativeTvTitleWatchedHostBinding(
            TvWatchedCoordinator(),
            account,
            view
        )

        assertTrue(binding.render(1))
        val selected = view.actions.single {
            it.label == "Mark all released episodes watched"
        }
        assertEquals(
            NativeTvTitleWatchedOutcome.APPLIED,
            binding.activate(selected, selected.actionId, "ENTER")
        )
        assertEquals(
            listOf(
                "local:mobile-watched:1:1",
                "local:mobile-watched:1:2"
            ),
            host.watchedIds()
        )
        assertFalse(host.watchedIds().contains("local:mobile-watched:0:2"))
        assertTrue(binding.close())
        assertTrue(account.close())
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

    private class SyntheticTvView : NativeTvTitleWatchedViewPort {
        var actions = emptyList<NativeTvTitleWatchedViewAction>()

        override fun replaceTitleActions(
            actions: List<NativeTvTitleWatchedViewAction>
        ): Boolean {
            this.actions = actions
            return true
        }

        override fun clearTitleActions(): Boolean {
            actions = emptyList()
            return true
        }
    }

    private fun command(action: String, args: Any): Map<String, Any> =
        mapOf(
            "action" to "MetaDetails",
            "args" to mapOf("action" to action, "args" to args)
        )
}
