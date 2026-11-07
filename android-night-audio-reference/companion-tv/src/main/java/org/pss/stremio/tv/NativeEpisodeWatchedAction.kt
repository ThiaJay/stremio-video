package org.pss.stremio.tv

import java.security.MessageDigest
import java.time.Instant
import java.util.Collections

/**
 * Loaded Core canonical video object, not a fabricated metadata preview or Story Order position.
 * A real client supplies this only from the same ready MetaDetails session as currentSnapshot().
 */
interface CoreEpisodeAccountPort : StremioAccountPort {
    fun canonicalVideoFromReadyMetaDetails(id: String): Map<String, Any?>?
}

data class TvEpisodeWatchedPlan(
    val stableVideoId: String,
    val markWatched: Boolean,
    val fingerprint: String,
    val videoFingerprint: String,
    val confirmation: String
)

/** Explicit individual-episode Core action. No account, network or watched writes until a host is bound. */
class NativeTvEpisodeWatchedActuator(private val port: CoreEpisodeAccountPort) {
    companion object {
        private const val MAX_CANONICAL_VIDEO_DEPTH = 64
        private const val MAX_CANONICAL_VIDEO_NODES = 10_000
        private const val MAX_CANONICAL_VIDEO_COLLECTION_ENTRIES = 10_000
        private const val MAX_CANONICAL_VIDEO_STRING_LENGTH = 262_144
        private const val MAX_CANONICAL_VIDEO_TOTAL_STRING_LENGTH = 1_000_000
        private const val MAX_ISSUED_PLANS = 16
    }

    private val ownerThread = Thread.currentThread()
    private val prepared = LinkedHashMap<String, TvEpisodeWatchedPlan>()
    private val consumed = HashSet<String>()
    private var closed = false
    private val sha = Regex("^[0-9a-f]{64}$")

    private fun hash(parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    private fun framed(values: List<String>): String = buildString {
        append(values.size).append('[')
        values.forEach { value ->
            append(value.length).append(':').append(value)
        }
        append(']')
    }

    private inline fun <T> failClosedPortCall(block: () -> T): T? = try {
        block()
    } catch (_: Exception) {
        null
    } catch (_: LinkageError) {
        null
    }

    private fun <T> snapshotList(source: List<T>, maximum: Int): List<T>? {
        return try {
            val expectedSize = source.size
            if (expectedSize < 0 || expectedSize > maximum) return null
            val iterator = source.iterator()
            val detached = ArrayList<T>(expectedSize)
            repeat(expectedSize) {
                if (!iterator.hasNext()) return null
                detached += iterator.next()
            }
            if (iterator.hasNext()) return null
            Collections.unmodifiableList(detached)
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun snapshotAccountState(raw: SeriesAccountSnapshot): SeriesAccountSnapshot? {
        if (raw.seriesId.isBlank() ||
            raw.seriesId.length > CanonicalWatchedBounds.MAX_STABLE_ID_LENGTH ||
            raw.readyMetaId.isBlank() ||
            raw.readyMetaId.length > CanonicalWatchedBounds.MAX_STABLE_ID_LENGTH
        ) return null
        val rawVideos = snapshotList(
            raw.videos,
            CanonicalWatchedBounds.MAX_CANONICAL_EPISODES
        ) ?: return null
        val videos = Collections.unmodifiableList(rawVideos.map { episode ->
            if (episode.id.isBlank() ||
                episode.id.length > CanonicalWatchedBounds.MAX_STABLE_ID_LENGTH ||
                episode.season < 0 ||
                episode.episode < 0 ||
                (episode.releasedAt?.length ?: 0) > CanonicalWatchedBounds.MAX_RELEASED_AT_LENGTH
            ) return null
            episode.copy()
        })
        if (videos.map { it.id }.toSet().size != videos.size) return null
        val storyOrder = raw.storyOrder?.let { source ->
            snapshotList(source, CanonicalWatchedBounds.MAX_CANONICAL_EPISODES)
                ?: return null
        }
        if (storyOrder?.any {
                it.isBlank() || it.length > CanonicalWatchedBounds.MAX_STABLE_ID_LENGTH
            } == true
        ) return null
        return raw.copy(videos = videos, storyOrder = storyOrder)
    }

    /**
     * Detach nested metadata from a potentially mutable Core data wrapper before checking or
     * dispatching it. A shared map reference is never used as a watched-action payload.
     * Unsupported leaves are rejected by canonical() below, not serialised by a guess.
     */
    private class SnapshotBudget {
        var nodes = 0
        var stringUnits = 0
        val active: MutableSet<Any> = java.util.Collections.newSetFromMap(
            java.util.IdentityHashMap<Any, Boolean>()
        )

        fun enter(value: Any?, depth: Int) {
            require(depth <= MAX_CANONICAL_VIDEO_DEPTH) { "Core Video metadata is too deep" }
            nodes = Math.addExact(nodes, 1)
            require(nodes <= MAX_CANONICAL_VIDEO_NODES) { "Core Video metadata is too large" }
            if (value is String) consumeString(value)
        }

        fun consumeString(value: String) {
            require(value.length <= MAX_CANONICAL_VIDEO_STRING_LENGTH) {
                "Core Video string is too large"
            }
            stringUnits = Math.addExact(stringUnits, value.length)
            require(stringUnits <= MAX_CANONICAL_VIDEO_TOTAL_STRING_LENGTH) {
                "Core Video strings are too large"
            }
        }
    }

    private fun detached(
        value: Any?,
        budget: SnapshotBudget = SnapshotBudget(),
        depth: Int = 0
    ): Any? {
        budget.enter(value, depth)
        return when (value) {
            is Map<*, *> -> {
                val expectedSize = value.size
                require(expectedSize <= MAX_CANONICAL_VIDEO_COLLECTION_ENTRIES) {
                    "Core Video map is too large"
                }
                require(budget.active.add(value)) { "Cyclic Core Video metadata" }
                try {
                    val copy = LinkedHashMap<String, Any?>(expectedSize)
                    var entries = 0
                    for (entry in value.entries) {
                        entries = Math.addExact(entries, 1)
                        require(entries <= expectedSize) { "Core Video map changed during snapshot" }
                        val key = entry.key
                        require(key is String) { "Non-string Core Video field" }
                        budget.consumeString(key)
                        copy[key] = detached(entry.value, budget, depth + 1)
                    }
                    require(entries == expectedSize && copy.size == expectedSize) {
                        "Core Video map changed during snapshot"
                    }
                    copy
                } finally {
                    budget.active.remove(value)
                }
            }
            is List<*> -> {
                val expectedSize = value.size
                require(expectedSize <= MAX_CANONICAL_VIDEO_COLLECTION_ENTRIES) {
                    "Core Video list is too large"
                }
                require(budget.active.add(value)) { "Cyclic Core Video metadata" }
                try {
                    val copy = ArrayList<Any?>(expectedSize)
                    for (entry in value) {
                        require(copy.size < expectedSize) { "Core Video list changed during snapshot" }
                        copy.add(detached(entry, budget, depth + 1))
                    }
                    require(copy.size == expectedSize) { "Core Video list changed during snapshot" }
                    copy
                } finally {
                    budget.active.remove(value)
                }
            }
            else -> value
        }
    }

    private fun snapshotCoreVideo(source: Map<String, Any?>): Map<String, Any?>? = try {
        @Suppress("UNCHECKED_CAST")
        detached(source) as? Map<String, Any?>
    } catch (_: RuntimeException) {
        null
    } catch (_: LinkageError) {
        null
    } catch (_: StackOverflowError) {
        null
    }

    /** Canonical byte-equivalent fingerprint over the original Video object; no positional rewriting. */
    private fun canonical(value: Any?): String? {
        return when (value) {
        null -> "n"
        is Boolean -> if (value) "b1" else "b0"
        is String -> "s${value.length}:$value"
        is Int -> "i32:$value"
        is Long -> "i64:$value"
        is Float -> if (value.isFinite()) {
            "f32:${java.lang.Integer.toUnsignedString(value.toRawBits(), 16)}"
        } else null
        is Double -> if (value.isFinite()) {
            "f64:${java.lang.Long.toUnsignedString(value.toRawBits(), 16)}"
        } else null
        is List<*> -> {
            val parts = value.map { canonical(it) ?: return null }
            "l${parts.size}[${parts.joinToString("")}]"
        }
        is Map<*, *> -> {
            if (value.keys.any { it !is String }) return null
            val pairs = value.entries.sortedBy { it.key as String }.map { (k, v) ->
                val vk = canonical(k) ?: return null
                val vv = canonical(v) ?: return null
                vk + vv
            }
            "m${pairs.size}{${pairs.joinToString("")}}"
        }
        else -> null
        }
    }

    private fun buildPlan(stableVideoId: String, markWatched: Boolean): TvEpisodeWatchedPlan? {
        val rawState = failClosedPortCall { port.currentSnapshot() } ?: return null
        val state = snapshotAccountState(rawState) ?: return null
        val videoSource = failClosedPortCall {
            port.canonicalVideoFromReadyMetaDetails(stableVideoId)
        } ?: return null
        val video = snapshotCoreVideo(videoSource) ?: return null
        if (!state.metaReady || !state.libraryReady || state.mediaType != "series" ||
            state.seriesId.isBlank() || state.seriesId != state.readyMetaId ||
            !sha.matches(state.accountFingerprint) || !sha.matches(state.libraryItemFingerprint)) return null
        if (stableVideoId.isBlank() || state.videos.map { it.id }.distinct().size != state.videos.size) return null
        val epMeta = state.videos.singleOrNull { it.id == stableVideoId } ?: return null
        if (epMeta.season < 0 || epMeta.episode < 0 || epMeta.releasedAt.isNullOrBlank()) return null
        // Match the title-level coordinator's release-clock authority: a host
        // snapshot may conservatively lag this process, but it cannot move the
        // release boundary forward and thereby admit a genuinely future episode.
        val runtimeNow = try { Instant.now() } catch (_: Exception) { return null }
        if (state.now.isAfter(runtimeNow)) return null
        val released = try { Instant.parse(epMeta.releasedAt) } catch (_: Exception) { return null }
        if (released.isAfter(state.now)) return null
        if (video["id"] != stableVideoId) return null
        if (video["season"] != null && video["season"] != epMeta.season) return null
        if (video["episode"] != null && video["episode"] != epMeta.episode) return null
        val objectHash = canonical(video) ?: return null
        val videoFingerprint = hash(objectHash)
        val fullCatalogue = framed(state.videos.sortedBy { it.id }.map { ep ->
            framed(listOf(
                ep.id,
                ep.season.toString(),
                ep.episode.toString(),
                ep.releasedAt ?: "unknown"
            ))
        })
        val fingerprint = hash(framed(listOf(
            state.accountFingerprint,
            state.libraryItemFingerprint,
            state.seriesId,
            stableVideoId,
            markWatched.toString(),
            fullCatalogue,
            videoFingerprint
        )))
        return TvEpisodeWatchedPlan(stableVideoId, markWatched, fingerprint, videoFingerprint,
            (if (markWatched) "Mark episode watched" else "Mark episode unwatched") + ": $stableVideoId")
    }

    fun prepare(stableVideoId: String, markWatched: Boolean): TvEpisodeWatchedPlan? {
        if (!onOwnerThread() || closed || prepared.size + consumed.size >= MAX_ISSUED_PLANS) return null
        val plan = buildPlan(stableVideoId, markWatched) ?: return null
        if (plan.fingerprint in prepared || plan.fingerprint in consumed) return null
        prepared[plan.fingerprint] = plan
        return plan
    }

    fun confirm(plan: TvEpisodeWatchedPlan, request: TvWatchedConfirmation): Boolean {
        if (!onOwnerThread() || closed || !request.focusOnConfirmation ||
            request.fingerprint != plan.fingerprint ||
            (request.remoteKey != "DPAD_CENTER" && request.remoteKey != "ENTER") ||
            (!plan.markWatched && !request.unwatchAcknowledged)) return false
        val exact = prepared[plan.fingerprint] ?: return false
        if (exact !== plan) return false
        prepared.remove(plan.fingerprint)
        consumed.add(plan.fingerprint)
        val fresh = buildPlan(plan.stableVideoId, plan.markWatched) ?: return false
        if (fresh != plan) return false
        // The first refreshed metadata read can pass and then change again before dispatch.
        // Freeze and verify a SECOND canonical object, including nested fields, not just its ID.
        val secondSource = failClosedPortCall {
            port.canonicalVideoFromReadyMetaDetails(plan.stableVideoId)
        } ?: return false
        val unchangedCoreVideo = snapshotCoreVideo(secondSource) ?: return false
        if (unchangedCoreVideo["id"] != plan.stableVideoId ||
            hash(canonical(unchangedCoreVideo) ?: return false) != plan.videoFingerprint) return false
        // The final canonical object read can itself synchronously expose an account,
        // catalogue or release-boundary change. Recompute the complete plan once more
        // after freezing that exact dispatch payload; a video-only recheck must not
        // authorise a command against an account snapshot which has already drifted.
        val finalPlan = buildPlan(plan.stableVideoId, plan.markWatched) ?: return false
        if (finalPlan != plan) return false
        val command = mapOf<String, Any>(
            "action" to "MetaDetails",
            "args" to mapOf(
                "action" to "MarkVideoAsWatched",
                "args" to listOf(unchangedCoreVideo, plan.markWatched)
            )
        )
        return failClosedPortCall { port.dispatch(command) } ?: false
    }

    /** Revokes every prepared individual-episode action when its UI owner is released. */
    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        prepared.clear()
        consumed.clear()
        return true
    }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
