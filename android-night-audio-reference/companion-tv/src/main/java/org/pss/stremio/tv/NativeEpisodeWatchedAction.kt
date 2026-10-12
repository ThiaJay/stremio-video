package org.pss.stremio.tv

import java.security.MessageDigest
import java.time.Instant

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
    val confirmation: String
)

/** Explicit individual-episode Core action. No account, network or watched writes until a host is bound. */
class NativeTvEpisodeWatchedActuator(private val port: CoreEpisodeAccountPort) {
    private val issued = HashSet<String>()
    private val sha = Regex("^[0-9a-f]{64}$")

    private fun hash(parts: String): String = MessageDigest.getInstance("SHA-256")
        .digest(parts.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

    /** Canonical byte-equivalent fingerprint over the original Video object; no positional rewriting. */
    private fun canonical(value: Any?): String? {
        return when (value) {
        null -> "n"
        is Boolean -> if (value) "b1" else "b0"
        is String -> "s${value.length}:$value"
        is Int, is Long -> "i$value"
        is Float, is Double -> "d$value"
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

    fun prepare(stableVideoId: String, markWatched: Boolean): TvEpisodeWatchedPlan? {
        val state = port.currentSnapshot() ?: return null
        val video = port.canonicalVideoFromReadyMetaDetails(stableVideoId) ?: return null
        if (!state.metaReady || !state.libraryReady || state.mediaType != "series" ||
            state.seriesId.isBlank() || state.seriesId != state.readyMetaId ||
            !sha.matches(state.accountFingerprint) || !sha.matches(state.libraryItemFingerprint)) return null
        if (stableVideoId.isBlank() || state.videos.map { it.id }.distinct().size != state.videos.size) return null
        val epMeta = state.videos.singleOrNull { it.id == stableVideoId } ?: return null
        if (epMeta.season < 0 || epMeta.episode < 0 || epMeta.releasedAt.isNullOrBlank()) return null
        val released = try { Instant.parse(epMeta.releasedAt) } catch (_: Exception) { return null }
        if (released.isAfter(state.now)) return null
        if (video["id"] != stableVideoId) return null
        if (video["season"] != null && video["season"] != epMeta.season) return null
        if (video["episode"] != null && video["episode"] != epMeta.episode) return null
        val objectHash = canonical(video) ?: return null
        val fullCatalogue = state.videos.sortedBy { it.id }.joinToString("|") { ep ->
            "${ep.id}/${ep.season}/${ep.episode}/${ep.releasedAt ?: "unknown"}"
        }
        val fingerprint = hash(listOf(state.accountFingerprint, state.libraryItemFingerprint,
            state.seriesId, stableVideoId, markWatched.toString(), fullCatalogue, objectHash).joinToString("|"))
        return TvEpisodeWatchedPlan(stableVideoId, markWatched, fingerprint,
            (if (markWatched) "Mark episode watched" else "Mark episode unwatched") + ": $stableVideoId")
    }

    fun confirm(plan: TvEpisodeWatchedPlan, request: TvWatchedConfirmation): Boolean {
        if (!request.focusOnConfirmation || request.fingerprint != plan.fingerprint ||
            (request.remoteKey != "DPAD_CENTER" && request.remoteKey != "ENTER") ||
            (!plan.markWatched && !request.unwatchAcknowledged) || issued.contains(plan.fingerprint)) return false
        val fresh = prepare(plan.stableVideoId, plan.markWatched) ?: return false
        if (fresh != plan) return false
        val unchangedCoreVideo = port.canonicalVideoFromReadyMetaDetails(plan.stableVideoId) ?: return false
        if (unchangedCoreVideo["id"] != plan.stableVideoId) return false
        val command = mapOf<String, Any>(
            "action" to "MetaDetails",
            "args" to mapOf(
                "action" to "MarkVideoAsWatched",
                "args" to listOf(unchangedCoreVideo, plan.markWatched)
            )
        )
        issued.add(plan.fingerprint)
        return port.dispatch(command)
    }
}
