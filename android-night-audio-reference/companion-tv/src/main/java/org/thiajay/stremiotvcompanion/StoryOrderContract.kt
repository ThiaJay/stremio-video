package org.thiajay.stremiotvcompanion

/**
 * Pure Story Order presentation adapter for the isolated Android TV companion.
 *
 * It accepts canonical episode objects, never reconstructs their identity and has
 * no player, account, network or persistence effects. Invalid hints return the
 * ordinary canonical presentation. A next-item result is only an identity-safe
 * override candidate; the host player must still apply its existing readiness,
 * source and continuation gates.
 */
data class CanonicalEpisode(
    val id: String,
    val season: Int,
    val episode: Int?,
    val title: String
)

data class StoryOrderHints(
    val storyOrderVersion: Int?,
    val storyOrder: List<String>?
)

enum class StoryOrderFallbackReason {
    EMPTY_CANONICAL,
    INVALID_CANONICAL_ID,
    DUPLICATE_CANONICAL_ID,
    UNSUPPORTED_VERSION,
    MISSING_ORDER,
    INVALID_ORDER_ID,
    DUPLICATE_ORDER_ID,
    UNKNOWN_ORDER_ID,
    MISSING_REGULAR_EPISODE
}

sealed interface StoryOrderResolution {
    data class Valid(val episodes: List<CanonicalEpisode>) : StoryOrderResolution
    data class CanonicalFallback(val reason: StoryOrderFallbackReason) : StoryOrderResolution
}

enum class NoStoryOverrideReason {
    INVALID_HINT,
    CURRENT_ID_NOT_IN_STORY,
    END_OF_STORY
}

sealed interface NextStoryDecision {
    data class UseCanonicalEpisode(val episode: CanonicalEpisode) : NextStoryDecision
    data class UseExistingContinuation(val reason: NoStoryOverrideReason) : NextStoryDecision
}

object StoryOrderContract {
    fun resolve(
        canonical: List<CanonicalEpisode>,
        hints: StoryOrderHints?
    ): StoryOrderResolution {
        if (canonical.isEmpty()) {
            return StoryOrderResolution.CanonicalFallback(StoryOrderFallbackReason.EMPTY_CANONICAL)
        }
        if (canonical.any { it.id.isBlank() }) {
            return StoryOrderResolution.CanonicalFallback(StoryOrderFallbackReason.INVALID_CANONICAL_ID)
        }
        if (canonical.map { it.id }.toSet().size != canonical.size) {
            return StoryOrderResolution.CanonicalFallback(StoryOrderFallbackReason.DUPLICATE_CANONICAL_ID)
        }
        if (hints?.storyOrderVersion != 1) {
            return StoryOrderResolution.CanonicalFallback(StoryOrderFallbackReason.UNSUPPORTED_VERSION)
        }
        val requested = hints.storyOrder
        if (requested.isNullOrEmpty()) {
            return StoryOrderResolution.CanonicalFallback(StoryOrderFallbackReason.MISSING_ORDER)
        }

        val byId = canonical.associateBy { it.id }
        val seen = LinkedHashSet<String>()
        val ordered = ArrayList<CanonicalEpisode>(requested.size)
        for (id in requested) {
            if (id.isBlank()) {
                return StoryOrderResolution.CanonicalFallback(StoryOrderFallbackReason.INVALID_ORDER_ID)
            }
            if (!seen.add(id)) {
                return StoryOrderResolution.CanonicalFallback(StoryOrderFallbackReason.DUPLICATE_ORDER_ID)
            }
            val original = byId[id]
                ?: return StoryOrderResolution.CanonicalFallback(StoryOrderFallbackReason.UNKNOWN_ORDER_ID)
            ordered += original
        }

        if (canonical.any { it.season > 0 && it.id !in seen }) {
            return StoryOrderResolution.CanonicalFallback(StoryOrderFallbackReason.MISSING_REGULAR_EPISODE)
        }
        return StoryOrderResolution.Valid(ordered)
    }

    fun presentation(
        canonical: List<CanonicalEpisode>,
        hints: StoryOrderHints?,
        storyOrderSelected: Boolean
    ): List<CanonicalEpisode> {
        if (!storyOrderSelected) return canonical
        return when (val resolved = resolve(canonical, hints)) {
            is StoryOrderResolution.Valid -> resolved.episodes
            is StoryOrderResolution.CanonicalFallback -> canonical
        }
    }

    fun next(
        canonical: List<CanonicalEpisode>,
        hints: StoryOrderHints?,
        currentVideoId: String
    ): NextStoryDecision {
        val resolved = resolve(canonical, hints)
        if (resolved !is StoryOrderResolution.Valid) {
            return NextStoryDecision.UseExistingContinuation(NoStoryOverrideReason.INVALID_HINT)
        }
        val index = resolved.episodes.indexOfFirst { it.id == currentVideoId }
        if (index < 0) {
            return NextStoryDecision.UseExistingContinuation(
                NoStoryOverrideReason.CURRENT_ID_NOT_IN_STORY
            )
        }
        if (index + 1 >= resolved.episodes.size) {
            return NextStoryDecision.UseExistingContinuation(NoStoryOverrideReason.END_OF_STORY)
        }
        return NextStoryDecision.UseCanonicalEpisode(resolved.episodes[index + 1])
    }
}
