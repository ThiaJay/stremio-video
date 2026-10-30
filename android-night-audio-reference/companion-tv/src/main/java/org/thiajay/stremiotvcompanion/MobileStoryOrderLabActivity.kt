package org.thiajay.stremiotvcompanion

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import org.pss.stremio.tv.NativeMobileTitleWatchedOutcome
import org.pss.stremio.tv.TvWatchedCoordinator

/**
 * Exported launcher surface for the side-by-side Android mobile Story Order and
 * title-action lab.
 *
 * Every row and watched value is synthetic local state. This activity reads no
 * intent extras, catalogue, account, network, player or provider surface.
 */
class MobileStoryOrderLabActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var storyOwner: AndroidMobileStoryOrderScreenOwner? = null
    private var watchedOwner: AndroidMobileTitleWatchedScreenOwner? = null
    private val watchedHost = SyntheticTitleWatchedAccountHost()
    private lateinit var storyStatus: TextView
    private lateinit var watchedStatus: TextView

    private val outcomePump = object : Runnable {
        override fun run() {
            var active = false
            storyOwner?.let { current ->
                active = true
                val selected = current.consumeSelection()
                if (selected != null) {
                    storyStatus.text =
                        "Selected exact synthetic episode: " + selected.id +
                            "\nNo playback, account or production watched-state action was performed."
                    if (!current.render(MobileStoryOrderMode.STORY)) {
                        storyStatus.text =
                            "Story Order lab closed after a rejected re-render. " +
                                "No external action was attempted."
                        current.close()
                        storyOwner = null
                    }
                }
            }
            watchedOwner?.let { current ->
                active = true
                val outcome = current.consumeOutcome()
                if (outcome != null) {
                    val result = when (outcome) {
                        NativeMobileTitleWatchedOutcome.APPLIED ->
                            "Applied to local synthetic watched state."
                        NativeMobileTitleWatchedOutcome.DISPATCHED_PENDING_READBACK ->
                            "Local readback was not available; no further action is enabled."
                        NativeMobileTitleWatchedOutcome.REFUSED ->
                            "Action refused by the bounded title-action contract."
                    }
                    watchedStatus.text =
                        result + "\nSynthetic watched IDs: " +
                            watchedHost.watchedIds().ifEmpty { listOf("none") }.joinToString()
                    if (!current.render(1)) {
                        watchedStatus.text =
                            watchedStatus.text.toString() +
                                "\nTitle-action lab closed after a rejected re-render."
                        current.close()
                        watchedOwner = null
                    }
                }
            }
            if (active) ui.postDelayed(this, OUTCOME_POLL_MS)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val screen = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(24.dp, 24.dp, 24.dp, 24.dp)
            setBackgroundColor(Color.rgb(16, 23, 34))
        }
        val scroll = ScrollView(this).apply {
            setFillViewport(true)
            addView(screen)
        }
        screen.addView(label("Mobile Story Order and Title Action Lab", 24f, Color.WHITE))
        screen.addView(
            label(
                "Synthetic local metadata and watched state only. This screen cannot open a " +
                    "stream, read a Stremio account or change production watched history.",
                16f,
                Color.rgb(210, 223, 237)
            )
        )

        storyStatus = label(
            "Preparing bounded Story Order rows.",
            16f,
            Color.rgb(132, 225, 190)
        )
        screen.addView(storyStatus)
        val storyRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        screen.addView(storyRows)

        screen.addView(label("Synthetic title watched actions", 20f, Color.WHITE))
        watchedStatus = label(
            "Rendering the real Android mobile widget against local in-memory state.",
            16f,
            Color.rgb(132, 225, 190)
        )
        screen.addView(watchedStatus)
        val watchedActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        screen.addView(watchedActions)
        setContentView(scroll)

        val createdStory = AndroidMobileStoryOrderScreenOwner.create(
            storyRows,
            SYNTHETIC_CANONICAL,
            SYNTHETIC_HINTS
        )
        if (createdStory == null || !createdStory.render(MobileStoryOrderMode.STORY)) {
            createdStory?.close()
            storyStatus.text =
                "Story Order presentation unavailable. No external action was attempted."
        } else {
            storyOwner = createdStory
            storyStatus.text =
                "Story Order rows ready. Selection resolves the original synthetic " +
                    "canonical object only."
        }

        val createdWatched = AndroidMobileTitleWatchedScreenOwner.create(
            watchedActions,
            TvWatchedCoordinator(),
            watchedHost
        )
        if (createdWatched == null || !createdWatched.render(1)) {
            createdWatched?.close()
            watchedStatus.text =
                "Synthetic title actions unavailable. No account action was attempted."
        } else {
            watchedOwner = createdWatched
            watchedStatus.text =
                "Title actions ready. Any accepted action changes this process's local " +
                    "synthetic watched set only."
        }

        if (storyOwner != null || watchedOwner != null) ui.post(outcomePump)
    }

    override fun onDestroy() {
        ui.removeCallbacks(outcomePump)
        storyOwner?.close()
        storyOwner = null
        watchedOwner?.close()
        watchedOwner = null
        super.onDestroy()
    }

    private fun label(text: String, size: Float, color: Int): TextView =
        TextView(this).apply {
            this.text = text
            textSize = size
            setTextColor(color)
            setPadding(0, 4.dp, 0, 12.dp)
        }

    private val Int.dp: Int
        get() = (this * resources.displayMetrics.density).toInt()

    companion object {
        private const val OUTCOME_POLL_MS = 100L
        private val SYNTHETIC_CANONICAL = listOf(
            CanonicalEpisode("local:mobile-story:1:1", 1, 1, "Episode one"),
            CanonicalEpisode("local:mobile-story:0:2", 0, 2, "Narrative special"),
            CanonicalEpisode("local:mobile-story:1:2", 1, 2, "Episode two"),
            CanonicalEpisode("local:mobile-story:0:99", 0, 99, "Ancillary extra")
        )
        private val SYNTHETIC_HINTS = StoryOrderHints(
            storyOrderVersion = 1,
            storyOrder = listOf(
                "local:mobile-story:1:1",
                "local:mobile-story:0:2",
                "local:mobile-story:1:2"
            )
        )
    }
}
