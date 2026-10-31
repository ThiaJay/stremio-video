package org.thiajay.stremiotvcompanion

import java.util.Collections

enum class MobileStoryOrderMode {
    CANONICAL,
    STORY
}

/**
 * Exact immutable row issued to an Android mobile Story Order view.
 *
 * A stable video ID or list position by itself is never activation authority.
 * Selection must return this exact row to the issuing controller.
 */
class MobileStoryOrderRow internal constructor(
    val stableVideoId: String,
    val title: String,
    val season: Int,
    val episodeNumber: Int?,
    private val canonicalEpisode: CanonicalEpisode,
    private val provenanceSeal: Any
) {
    internal fun episodeFor(seal: Any): CanonicalEpisode? =
        if (provenanceSeal === seal) canonicalEpisode else null
}

/**
 * Source-only Android mobile Story Order presentation and selection boundary.
 *
 * The controller snapshots canonical episodes on construction, delegates hint
 * validation to StoryOrderContract and issues one exact row generation. It has
 * no Android view, player, network, account or persistence effects.
 */
class MobileStoryOrderController(
    canonical: List<CanonicalEpisode>,
    private val hints: StoryOrderHints?
) {
    companion object {
        const val SEMANTIC_SELECTION = "semantic-select"
    }

    private data class Issued(
        val row: MobileStoryOrderRow,
        val episode: CanonicalEpisode
    )

    private val ownerThread = Thread.currentThread()
    private val controllerSeal = Any()
    private val canonicalSnapshot =
        StoryOrderContract.presentation(canonical, null, storyOrderSelected = false)
    private val issued = LinkedHashMap<String, Issued>()
    private var activeEpisodes: List<CanonicalEpisode> = emptyList()
    private var effectiveStoryOrder = false
    private var closed = canonicalSnapshot.isEmpty()

    fun present(mode: MobileStoryOrderMode): List<MobileStoryOrderRow>? {
        if (!onOwnerThread() || closed) return null
        issued.clear()
        activeEpisodes = emptyList()
        effectiveStoryOrder = false

        val episodes = when (mode) {
            MobileStoryOrderMode.CANONICAL -> canonicalSnapshot
            MobileStoryOrderMode.STORY -> when (
                val resolved = StoryOrderContract.resolve(canonicalSnapshot, hints)
            ) {
                is StoryOrderResolution.Valid -> {
                    effectiveStoryOrder = true
                    resolved.episodes
                }
                is StoryOrderResolution.CanonicalFallback -> canonicalSnapshot
            }
        }
        if (episodes.isEmpty()) return null

        val rows = ArrayList<MobileStoryOrderRow>(episodes.size)
        for (episode in episodes) {
            val row = MobileStoryOrderRow(
                stableVideoId = episode.id,
                title = episode.title,
                season = episode.season,
                episodeNumber = episode.episode,
                canonicalEpisode = episode,
                provenanceSeal = controllerSeal
            )
            if (issued.put(episode.id, Issued(row, episode)) != null) {
                issued.clear()
                activeEpisodes = emptyList()
                effectiveStoryOrder = false
                return null
            }
            rows += row
        }
        activeEpisodes = episodes
        return Collections.unmodifiableList(rows)
    }

    fun select(
        row: MobileStoryOrderRow,
        selectedVideoId: String?,
        semanticEvent: String
    ): CanonicalEpisode? {
        if (!onOwnerThread() || closed) return null
        val record = issued.remove(row.stableVideoId)
        issued.clear()
        val exactEpisode = row.episodeFor(controllerSeal)
        if (record == null ||
            record.row !== row ||
            exactEpisode !== record.episode ||
            selectedVideoId != row.stableVideoId ||
            semanticEvent != SEMANTIC_SELECTION
        ) return null
        return record.episode
    }

    fun next(currentVideoId: String): CanonicalEpisode? {
        if (!onOwnerThread() || closed || !effectiveStoryOrder) return null
        val index = activeEpisodes.indexOfFirst { it.id == currentVideoId }
        if (index < 0 || index + 1 >= activeEpisodes.size) return null
        return activeEpisodes[index + 1]
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        issued.clear()
        activeEpisodes = emptyList()
        effectiveStoryOrder = false
        return true
    }

    fun isOperational(): Boolean = onOwnerThread() && !closed

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread
}
