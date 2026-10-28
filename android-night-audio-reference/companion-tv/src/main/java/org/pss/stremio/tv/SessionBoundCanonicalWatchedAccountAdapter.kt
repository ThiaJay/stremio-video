package org.pss.stremio.tv

/**
 * One host observation from an already authenticated private client session.
 *
 * It contains no credentials, addon URLs, stream URLs or playback history.
 * The host epoch must change whenever the account, selected library item or
 * metadata generation changes.
 */
data class CanonicalWatchedAccountObservation(
    val sessionEpoch: Long,
    val snapshot: SeriesAccountSnapshot,
    val watchedVideoIds: List<String>
)

/**
 * Narrow private-client injection surface. Only title watched commands accepted
 * by [SessionBoundCanonicalWatchedAccountAdapter] can reach dispatch.
 */
interface CanonicalWatchedAccountHostPort {
    fun sessionEpoch(): Long
    fun observe(): CanonicalWatchedAccountObservation?

    /**
     * The host must atomically reject when [expectedSessionEpoch] is no longer
     * its active authenticated session epoch.
     */
    fun dispatchTitleWatched(
        expectedSessionEpoch: Long,
        message: Map<String, Any>
    ): Boolean
}

/**
 * Concrete fail-closed [CanonicalWatchedStatePort] for Android title actions.
 *
 * Every observation is detached between two equal session-epoch reads. Dispatch
 * requires the complete detached snapshot last returned by [currentSnapshot],
 * consumes that lease before the host call, accepts only exact series/season
 * watched commands and verifies that the host epoch did not change.
 *
 * This adapter is owner-thread confined and carries no authentication material.
 */
class SessionBoundCanonicalWatchedAccountAdapter(
    private val host: CanonicalWatchedAccountHostPort
) : CanonicalWatchedStatePort {
    companion object {
        const val MAX_CANONICAL_EPISODES = 10_000
        const val MAX_WATCHED_VIDEO_IDS = 10_000
        const val MAX_STABLE_ID_LENGTH = 240
        const val MAX_RELEASED_AT_LENGTH = 64
    }

    private data class ObservationIdentity(
        val sessionEpoch: Long,
        val snapshot: SeriesAccountSnapshot
    )

    private data class DetachedObservation(
        val identity: ObservationIdentity,
        val snapshot: SeriesAccountSnapshot,
        val readback: CanonicalWatchedStateReadback
    )

    private val ownerThread = Thread.currentThread()
    private val hexSha = Regex("^[a-f0-9]{64}$")
    private var latestIdentity: ObservationIdentity? = null
    private var closed = false

    override fun currentSnapshot(): SeriesAccountSnapshot? {
        if (!onOwnerThread() || closed) return null
        val observation = observeOrNull() ?: return null
        latestIdentity = observation.identity
        return observation.snapshot
    }

    override fun currentWatchedState(): CanonicalWatchedStateReadback? {
        if (!onOwnerThread() || closed) return null
        val expected = latestIdentity ?: return null
        val observation = observeOrNull() ?: return null
        if (observation.identity != expected) return null
        return observation.readback
    }

    override fun dispatch(message: Map<String, Any>): Boolean {
        if (!onOwnerThread() || closed) return false
        val expected = latestIdentity ?: return false
        val observation = observeOrNull() ?: return false
        if (observation.identity != expected) return false
        val command = exactTitleWatchedCommand(message) ?: return false

        latestIdentity = null
        val accepted = try {
            host.dispatchTitleWatched(expected.sessionEpoch, command)
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!accepted) return false
        val after = try {
            host.sessionEpoch()
        } catch (_: Exception) {
            return false
        } catch (_: LinkageError) {
            return false
        }
        return after == expected.sessionEpoch
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        latestIdentity = null
        return true
    }

    private fun observeOrNull(): DetachedObservation? = try {
        val before = host.sessionEpoch()
        if (before < 0L) return null
        val raw = host.observe() ?: return null
        if (raw.sessionEpoch != before) return null

        val snapshot = detachSnapshot(raw.snapshot) ?: return null
        if (raw.watchedVideoIds.size > MAX_WATCHED_VIDEO_IDS) return null
        val watchedIds = raw.watchedVideoIds.toList()
        if (watchedIds.any { it.isBlank() || it.length > MAX_STABLE_ID_LENGTH } ||
            watchedIds.size != watchedIds.toSet().size
        ) return null

        val after = host.sessionEpoch()
        if (after != before) return null
        val identity = ObservationIdentity(after, snapshot)
        DetachedObservation(
            identity = identity,
            snapshot = snapshot,
            readback = CanonicalWatchedStateReadback(
                accountFingerprint = snapshot.accountFingerprint,
                libraryItemFingerprint = snapshot.libraryItemFingerprint,
                seriesId = snapshot.seriesId,
                watchedVideoIds = watchedIds
            )
        )
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun detachSnapshot(raw: SeriesAccountSnapshot): SeriesAccountSnapshot? {
        if (!hexSha.matches(raw.accountFingerprint) ||
            !hexSha.matches(raw.libraryItemFingerprint) ||
            raw.seriesId.isBlank() || raw.seriesId.length > MAX_STABLE_ID_LENGTH ||
            raw.readyMetaId.isBlank() || raw.readyMetaId.length > MAX_STABLE_ID_LENGTH ||
            raw.videos.size > MAX_CANONICAL_EPISODES ||
            (raw.storyOrder?.size ?: 0) > MAX_CANONICAL_EPISODES
        ) return null

        val videos = raw.videos.map {
            if (it.id.isBlank() || it.id.length > MAX_STABLE_ID_LENGTH ||
                (it.releasedAt?.length ?: 0) > MAX_RELEASED_AT_LENGTH
            ) return null
            it.copy()
        }
        val storyOrder = raw.storyOrder?.toList()
        if (storyOrder?.any { it.isBlank() || it.length > MAX_STABLE_ID_LENGTH } == true) return null
        return raw.copy(videos = videos, storyOrder = storyOrder)
    }

    private fun exactTitleWatchedCommand(message: Map<String, Any>): Map<String, Any>? = try {
        if (message.keys != setOf("action", "args") || message["action"] != "MetaDetails") {
            return null
        }
        val inner = message["args"] as? Map<*, *> ?: return null
        if (inner.keys != setOf("action", "args")) return null
        val action = inner["action"] as? String ?: return null
        val detachedArgs: Any = when (action) {
            "MarkAsWatched" -> inner["args"] as? Boolean ?: return null
            "MarkSeasonAsWatched" -> {
                val args = inner["args"] as? List<*> ?: return null
                if (args.size != 2) return null
                val season = args[0] as? Int ?: return null
                val watched = args[1] as? Boolean ?: return null
                if (season <= 0) return null
                listOf(season, watched)
            }
            else -> return null
        }
        mapOf<String, Any>(
            "action" to "MetaDetails",
            "args" to mapOf<String, Any>("action" to action, "args" to detachedArgs)
        )
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
