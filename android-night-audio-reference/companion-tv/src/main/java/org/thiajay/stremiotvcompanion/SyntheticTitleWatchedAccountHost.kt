package org.thiajay.stremiotvcompanion

import java.time.Instant
import org.pss.stremio.tv.CanonicalEpisode
import org.pss.stremio.tv.CanonicalWatchedAccountHostPort
import org.pss.stremio.tv.CanonicalWatchedAccountObservation
import org.pss.stremio.tv.CanonicalWatchedDispatchExpectation
import org.pss.stremio.tv.CanonicalWatchedStateReadback
import org.pss.stremio.tv.SeriesAccountSnapshot
import org.pss.stremio.tv.TvWatchedCoordinator
import org.pss.stremio.tv.WatchedScope

/**
 * Fixture-only watched-state host for the side-by-side mobile and TV labs.
 *
 * It has no credentials, network, persistence, Stremio account or provider
 * surface. Exact accepted title commands update only this in-memory instance.
 */
internal class SyntheticTitleWatchedAccountHost : CanonicalWatchedAccountHostPort {
    private val epoch = 1L
    private val watched = linkedSetOf<String>()
    private var resumeVideoId: String? = "local:mobile-watched:0:99"
    private var resumeTimeOffset = 42_000L
    private var acceptedDispatches = 0

    private val snapshot = SeriesAccountSnapshot(
        accountFingerprint = "a".repeat(64),
        libraryItemFingerprint = "b".repeat(64),
        seriesId = "local:mobile-watched:series",
        readyMetaId = "local:mobile-watched:series",
        metaReady = true,
        libraryReady = true,
        videos = SYNTHETIC_VIDEOS,
        storyOrder = listOf(
            "local:mobile-watched:1:1",
            "local:mobile-watched:0:2",
            "local:mobile-watched:1:2",
            "local:mobile-watched:1:3"
        ),
        storyOrderVersion = 1,
        now = Instant.parse("2026-10-30T00:00:00Z")
    )

    @Synchronized
    override fun sessionEpoch(): Long = epoch

    @Synchronized
    override fun observe(): CanonicalWatchedAccountObservation =
        CanonicalWatchedAccountObservation(
            sessionEpoch = epoch,
            snapshot = snapshot.copy(
                videos = snapshot.videos.map { it.copy() },
                storyOrder = snapshot.storyOrder?.toList()
            ),
            watchedVideoIds = watched.toList(),
            resumeVideoId = resumeVideoId,
            resumeTimeOffset = resumeTimeOffset
        )

    @Synchronized
    override fun dispatchTitleWatched(
        expected: CanonicalWatchedDispatchExpectation,
        message: Map<String, Any>
    ): Boolean {
        if (expected != currentDispatchExpectation() ||
            message.keys != setOf("action", "args") ||
            message["action"] != "MetaDetails"
        ) return false
        val inner = message["args"] as? Map<*, *> ?: return false
        if (inner.keys != setOf("action", "args")) return false

        val selected: List<String>
        val markWatched: Boolean
        when (inner["action"]) {
            "MarkAsWatched" -> {
                markWatched = inner["args"] as? Boolean ?: return false
                selected = TvWatchedCoordinator()
                    .prepare(snapshot, WatchedScope.Series, markWatched)
                    ?.selectedIds
                    ?: return false
            }
            "MarkSeasonAsWatched" -> {
                val args = inner["args"] as? List<*> ?: return false
                if (args.size != 2) return false
                val season = args[0] as? Int ?: return false
                markWatched = args[1] as? Boolean ?: return false
                if (season <= 0) return false
                selected = snapshot.videos
                    .filter { it.season == season && released(it) }
                    .map { it.id }
            }
            else -> return false
        }
        if (selected.isEmpty()) return false
        if (markWatched) watched.addAll(selected) else watched.removeAll(selected.toSet())
        if (inner["action"] == "MarkAsWatched" && markWatched) {
            // Mirror merged Core PR #38 only inside this synthetic fixture: title
            // completion clears time_offset while preserving historical video_id.
            resumeTimeOffset = 0L
        }
        acceptedDispatches += 1
        return true
    }

    fun watchedIds(): List<String> = watched.toList()

    fun resumeState(): Pair<String?, Long> = resumeVideoId to resumeTimeOffset

    fun acceptedDispatchCount(): Int = acceptedDispatches

    private fun currentDispatchExpectation(): CanonicalWatchedDispatchExpectation =
        CanonicalWatchedDispatchExpectation(
            sessionEpoch = epoch,
            snapshot = snapshot.copy(
                videos = snapshot.videos.map { it.copy() },
                storyOrder = snapshot.storyOrder?.toList()
            ),
            readback = CanonicalWatchedStateReadback(
                accountFingerprint = snapshot.accountFingerprint,
                libraryItemFingerprint = snapshot.libraryItemFingerprint,
                seriesId = snapshot.seriesId,
                watchedVideoIds = watched.toList(),
                resumeVideoId = resumeVideoId,
                resumeTimeOffset = resumeTimeOffset
            )
        )

    private fun released(episode: CanonicalEpisode): Boolean {
        val value = episode.releasedAt ?: return false
        val releasedAt = try {
            Instant.parse(value)
        } catch (_: Exception) {
            return false
        }
        return !releasedAt.isAfter(snapshot.now)
    }

    companion object {
        private val SYNTHETIC_VIDEOS = listOf(
            CanonicalEpisode(
                "local:mobile-watched:1:1",
                1,
                1,
                "2025-01-01T00:00:00Z"
            ),
            CanonicalEpisode(
                "local:mobile-watched:0:2",
                0,
                2,
                "2025-01-02T00:00:00Z"
            ),
            CanonicalEpisode(
                "local:mobile-watched:1:2",
                1,
                2,
                "2025-01-03T00:00:00Z"
            ),
            CanonicalEpisode(
                "local:mobile-watched:1:3",
                1,
                3,
                "2035-01-03T00:00:00Z"
            ),
            CanonicalEpisode(
                "local:mobile-watched:0:99",
                0,
                99,
                "2025-01-04T00:00:00Z"
            )
        )
    }
}
