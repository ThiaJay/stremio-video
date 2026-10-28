package org.pss.stremio.tv

import java.util.concurrent.atomic.AtomicBoolean

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
 * Fail-closed construction boundary for the real Android mobile/TV screen
 * owners.
 *
 * A host-owned adapter is revoked when construction returns no screen or
 * raises an ordinary compatibility failure. A successfully returned owner
 * receives the still-live adapter and becomes solely responsible for closing
 * it. The external path contains the same construction failures without ever
 * assuming ownership of the caller-supplied canonical port.
 */
internal object CanonicalWatchedScreenConstructionBoundary {
    fun <T : Any> owned(
        host: CanonicalWatchedAccountHostPort,
        build: (SessionBoundCanonicalWatchedAccountAdapter) -> T?
    ): T? {
        val account = SessionBoundCanonicalWatchedAccountAdapter(host)
        val owner = contained { build(account) }
        if (owner == null) account.close()
        return owner
    }

    fun <T : Any> external(build: () -> T?): T? = contained(build)

    private fun <T : Any> contained(build: () -> T?): T? = try {
        build()
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }
}

/**
 * Idempotent account-only revocation held by an Android screen owner.
 *
 * A foreign host teardown may use this lease to revoke only the adapter the
 * screen created itself. Android view, relay and menu cleanup remains on the
 * screen's main owner thread. A caller-supplied canonical port is represented
 * by a null adapter and is never closed by this lease.
 */
internal class CanonicalWatchedScreenOwnedAccountLease(
    private val account: SessionBoundCanonicalWatchedAccountAdapter?
) {
    private val revoked = AtomicBoolean(false)

    fun revoke(): Boolean {
        if (!revoked.compareAndSet(false, true)) return true
        return try {
            account?.close()
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }
}

/**
 * Concrete fail-closed [CanonicalWatchedStatePort] for Android title actions.
 *
 * Every observation is detached between two equal session-epoch reads. Dispatch
 * requires the complete detached snapshot last returned by [currentSnapshot],
 * consumes that lease before the host call, accepts only exact series/season
 * watched commands and verifies that the host epoch did not change.
 *
 * Account operations remain owner-thread confined. [close] is a thread-safe
 * revocation boundary so an asynchronous host teardown can wait for any
 * in-flight owner operation and guarantee that no later host callback starts.
 * The adapter carries no authentication material.
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
    private val lifecycleLock = Any()
    private val hexSha = Regex("^[a-f0-9]{64}$")
    private var latestIdentity: ObservationIdentity? = null
    @Volatile
    private var closed = false

    override fun currentSnapshot(): SeriesAccountSnapshot? = synchronized(lifecycleLock) {
        if (!onOwnerThread() || closed) return@synchronized null
        val observation = observeOrNull() ?: return@synchronized null
        latestIdentity = observation.identity
        observation.snapshot
    }

    override fun currentWatchedState(): CanonicalWatchedStateReadback? =
        synchronized(lifecycleLock) {
            if (!onOwnerThread() || closed) return@synchronized null
            val expected = latestIdentity ?: return@synchronized null
            val observation = observeOrNull() ?: return@synchronized null
            if (observation.identity != expected) return@synchronized null
            observation.readback
        }

    override fun dispatch(message: Map<String, Any>): Boolean = synchronized(lifecycleLock) {
        if (!onOwnerThread() || closed) return@synchronized false
        val expected = latestIdentity ?: return@synchronized false
        val observation = observeOrNull() ?: return@synchronized false
        if (observation.identity != expected) return@synchronized false
        val command = exactTitleWatchedCommand(message) ?: return@synchronized false

        latestIdentity = null
        val accepted = try {
            host.dispatchTitleWatched(expected.sessionEpoch, command)
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
        if (!accepted) return@synchronized false
        val after = try {
            host.sessionEpoch()
        } catch (_: Exception) {
            return@synchronized false
        } catch (_: LinkageError) {
            return@synchronized false
        }
        after == expected.sessionEpoch
    }

    fun close(): Boolean = synchronized(lifecycleLock) {
        if (closed) return@synchronized false
        closed = true
        latestIdentity = null
        true
    }

    private fun observeOrNull(): DetachedObservation? {
        return try {
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

    private fun exactTitleWatchedCommand(message: Map<String, Any>): Map<String, Any>? {
        return try {
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
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
