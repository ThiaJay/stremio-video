package org.pss.stremio.tv

import java.security.MessageDigest
import java.time.Instant

/**
 * Native Android TV integration boundary for the existing fork Stremio Core contracts.
 * This code has no Android or network dependencies and never contacts a Stremio account.
 * The private client owns binding it to its player and action dispatcher.
 */
enum class SkipKind { Intro, Recap, Outro, Preview }
enum class SkipMode { Ask, Always, Never }

data class CoreSkipSegment(
    val kind: SkipKind,
    val generation: Long,
    val videoId: String,
    val fromMs: Long,
    val toMs: Long,
    val durationMs: Long,
    val mode: SkipMode,
    val active: Boolean,
    val dismissed: Boolean,
    val seekToMs: Long?
)

data class TvPlaybackState(
    val generation: Long,
    val videoId: String,
    val positionMs: Long,
    val durationMs: Long,
    val loaded: Boolean,
    val paused: Boolean = false,
    val buffering: Boolean = false,
    val seeking: Boolean = false,
    val switchingSource: Boolean = false,
    val menuOpen: Boolean = false,
    val nextVideoPopupOpen: Boolean = false
)

data class SegmentIdentity(val generation: Long, val videoId: String, val kind: SkipKind, val fromMs: Long, val toMs: Long)

data class SkipPresentation(val label: String, val focusedIdentity: SegmentIdentity)

/** Core's DismissSkipSegment action must be processed before the current-media seek. */
data class NativeSkipCommand(val dismissCoreAction: Map<String, Any>, val seekWithinCurrentMediaMs: Long)

class TvSkipCoordinator(private val maxConsumption: Int = 64, private val creditsSafetyTailMs: Long = 1500) {
    init { require(maxConsumption in 1..64 && creditsSafetyTailMs >= 1000) }
    private val consumed = LinkedHashSet<SegmentIdentity>()
    private var generation: Long? = null

    private fun identity(s: CoreSkipSegment) = SegmentIdentity(s.generation, s.videoId, s.kind, s.fromMs, s.toMs)

    private fun syncGeneration(p: TvPlaybackState) {
        if (generation == null || p.generation > generation!!) {
            generation = p.generation
            consumed.clear()
        }
    }

    private fun safe(s: CoreSkipSegment, p: TvPlaybackState): Boolean {
        if (p.generation != generation) return false
        if (s.mode == SkipMode.Never || s.kind == SkipKind.Preview || !s.active || s.dismissed) return false
        if (!p.loaded || p.menuOpen || p.nextVideoPopupOpen || p.buffering || p.seeking || p.switchingSource) return false
        if (s.videoId.isBlank() || p.videoId != s.videoId || s.generation < 0 || s.generation != p.generation) return false
        if (s.durationMs <= 0 || p.durationMs != s.durationMs || s.fromMs < 0) return false
        if (s.toMs <= s.fromMs || s.toMs > s.durationMs || s.seekToMs != s.toMs) return false
        if (p.positionMs < s.fromMs || p.positionMs >= s.toMs) return false
        // Seeking right to media end may synthesise an Ended event, or start the next episode.
        if (s.kind == SkipKind.Outro && s.toMs >= s.durationMs - creditsSafetyTailMs) return false
        return true
    }

    fun presentation(s: CoreSkipSegment?, p: TvPlaybackState): SkipPresentation? {
        syncGeneration(p)
        if (s == null || s.mode != SkipMode.Ask || !safe(s, p)) return null
        val id = identity(s)
        if (consumed.contains(id) || consumed.size >= maxConsumption) return null
        return SkipPresentation(
            when (s.kind) {
                SkipKind.Intro -> "Skip Intro"
                SkipKind.Recap -> "Skip Recap"
                SkipKind.Outro -> "Skip Credits"
                SkipKind.Preview -> return null
            },
            id
        )
    }

    fun manual(s: CoreSkipSegment?, p: TvPlaybackState, focused: SegmentIdentity?, remoteKey: String): NativeSkipCommand? {
        syncGeneration(p)
        if (s == null || s.mode != SkipMode.Ask || focused != identity(s)) return null
        if (remoteKey != "DPAD_CENTER" && remoteKey != "ENTER") return null
        return consume(s, p)
    }

    fun automatic(s: CoreSkipSegment?, p: TvPlaybackState): NativeSkipCommand? {
        syncGeneration(p)
        if (s == null || s.mode != SkipMode.Always || p.paused) return null
        return consume(s, p)
    }

    private fun consume(s: CoreSkipSegment, p: TvPlaybackState): NativeSkipCommand? {
        if (!safe(s, p) || consumed.size >= maxConsumption) return null
        val id = identity(s)
        if (!consumed.add(id)) return null
        val dismiss = mapOf<String, Any>(
            "action" to "Player",
            "args" to mapOf(
                "action" to "DismissSkipSegment",
                "args" to mapOf(
                    "generation" to s.generation,
                    "kind" to s.kind.name,
                    "from" to s.fromMs,
                    "to" to s.toMs
                )
            )
        )
        return NativeSkipCommand(dismiss, s.toMs)
    }
}

data class CanonicalEpisode(
    val id: String,
    val season: Int,
    val episode: Int,
    val releasedAt: String?
)

data class SeriesAccountSnapshot(
    val accountFingerprint: String,
    val libraryItemFingerprint: String,
    val seriesId: String,
    val readyMetaId: String,
    val metaReady: Boolean,
    val libraryReady: Boolean,
    val videos: List<CanonicalEpisode>,
    val storyOrder: List<String>?,
    val storyOrderVersion: Int?,
    val now: Instant,
    val mediaType: String = "series"
)

sealed interface WatchedScope {
    data object Series : WatchedScope
    data class Season(val number: Int) : WatchedScope
}

data class WatchedPlan(
    val scope: WatchedScope,
    val watched: Boolean,
    val selectedIds: List<String>,
    val confirmation: String,
    val fingerprint: String
)

data class TvWatchedConfirmation(
    val focusOnConfirmation: Boolean,
    val remoteKey: String,
    val fingerprint: String,
    val unwatchAcknowledged: Boolean = false
)

data class CoreWatchedAction(val coreAction: Map<String, Any>)

class TvWatchedCoordinator {
    private val dispatched = HashSet<String>()
    private val hexSha = Regex("^[a-f0-9]{64}$")

    private fun released(ep: CanonicalEpisode, now: Instant): Boolean {
        if (ep.releasedAt.isNullOrBlank() || !ep.releasedAt.endsWith("Z")) return false
        val instant = try { Instant.parse(ep.releasedAt) } catch (_: Exception) { return false }
        return !instant.isAfter(now)
    }

    private fun storyMembership(s: SeriesAccountSnapshot): List<CanonicalEpisode> {
        val regular = s.videos.filter { it.season > 0 }
        val order = s.storyOrder ?: return regular
        if (s.storyOrderVersion != 1 || order.isEmpty() || order.any { it.isBlank() }) return regular
        if (order.size != order.toSet().size) return regular
        if (!order.containsAll(regular.map { it.id })) return regular
        val byId = s.videos.associateBy { it.id }
        if (order.any { it !in byId }) return regular
        // Story Order may place narrative or ancillary Season 0 entries among
        // regular episodes for presentation. Series watched actions must still
        // exclude every Season 0 item; only canonical regular episodes are writable.
        return order.mapNotNull { byId[it] }.filter { it.season > 0 }
    }

    fun prepare(s: SeriesAccountSnapshot, scope: WatchedScope, watched: Boolean): WatchedPlan? {
        if (!s.metaReady || !s.libraryReady || s.mediaType != "series" || s.seriesId.isBlank() || s.readyMetaId != s.seriesId) return null
        if (!hexSha.matches(s.accountFingerprint) || !hexSha.matches(s.libraryItemFingerprint)) return null
        if (s.videos.isEmpty() || s.videos.any { it.id.isBlank() || it.season < 0 || it.episode < 0 }) return null
        if (s.videos.map { it.id }.toSet().size != s.videos.size) return null
        val selected: List<CanonicalEpisode> = when (scope) {
            WatchedScope.Series -> storyMembership(s).filter { released(it, s.now) }
            is WatchedScope.Season -> {
                if (scope.number <= 0) return null
                val seasonVideos = s.videos.filter { it.season == scope.number }
                // Current Core MarkSeasonAsWatched changes ALL listed season videos.
                // Refuse both operations rather than modifying future or unknown entries.
                if (seasonVideos.isEmpty() || seasonVideos.any { !released(it, s.now) }) return null
                seasonVideos
            }
        }
        if (selected.isEmpty()) return null
        val ids = selected.map { it.id }.sorted()
        val catalogue = s.videos.sortedBy { it.id }.joinToString("|") { ep ->
            listOf(ep.id, ep.season, ep.episode, ep.releasedAt ?: "unknown").joinToString("/")
        }
        val signature = listOf(s.accountFingerprint, s.libraryItemFingerprint, s.seriesId, s.mediaType,
            scope.toString(), watched.toString(), ids.joinToString(","), catalogue,
            (s.storyOrder ?: emptyList()).joinToString(","), s.storyOrderVersion?.toString() ?: "unknown")
            .joinToString("|").toByteArray(Charsets.UTF_8)
        val fingerprint = MessageDigest.getInstance("SHA-256")
            .digest(signature).joinToString("") { "%02x".format(it) }
        val text = if (watched) "Mark" else "Remove watched status from"
        val scopeLabel = when (scope) {
            WatchedScope.Series -> "this series"
            is WatchedScope.Season -> "season ${scope.number}"
        }
        return WatchedPlan(scope, watched, ids, "$text ${ids.size} released episodes in $scopeLabel", fingerprint)
    }

    fun confirm(plan: WatchedPlan, fresh: SeriesAccountSnapshot, c: TvWatchedConfirmation): CoreWatchedAction? {
        if (!c.focusOnConfirmation || (c.remoteKey != "ENTER" && c.remoteKey != "DPAD_CENTER")) return null
        if (c.fingerprint != plan.fingerprint || (!plan.watched && !c.unwatchAcknowledged)) return null
        val current = prepare(fresh, plan.scope, plan.watched) ?: return null
        if (current != plan || dispatched.contains(plan.fingerprint)) return null
        val action = when (val scope = plan.scope) {
            WatchedScope.Series -> mapOf<String, Any>("action" to "MarkAsWatched", "args" to plan.watched)
            is WatchedScope.Season -> mapOf<String, Any>("action" to "MarkSeasonAsWatched", "args" to listOf(scope.number, plan.watched))
        }
        dispatched.add(plan.fingerprint)
        return CoreWatchedAction(mapOf("action" to "MetaDetails", "args" to action))
    }
}
