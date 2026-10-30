package org.thiajay.stremiotvcompanion

import android.app.Activity
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

/**
 * Exported launcher surface for the side-by-side Android mobile Story Order lab.
 *
 * Every row is synthetic local metadata. This activity reads no intent extras,
 * catalogue, account, network, player, provider or watched-state surface.
 */
class MobileStoryOrderLabActivity : Activity() {
    private val ui = Handler(Looper.getMainLooper())
    private var owner: AndroidMobileStoryOrderScreenOwner? = null
    private lateinit var status: TextView

    private val selectionPump = object : Runnable {
        override fun run() {
            val current = owner ?: return
            val selected = current.consumeSelection()
            if (selected != null) {
                status.text =
                    "Selected exact synthetic episode: " + selected.id +
                        "\nNo playback, account or watched-state action was performed."
                if (!current.render(MobileStoryOrderMode.STORY)) {
                    status.text =
                        "Story Order lab closed after a rejected re-render. " +
                            "No external action was attempted."
                    current.close()
                    owner = null
                    return
                }
            }
            ui.postDelayed(this, SELECTION_POLL_MS)
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
        screen.addView(label("Mobile Story Order Lab", 24f, Color.WHITE))
        screen.addView(
            label(
                "Synthetic local metadata only. This screen cannot open a stream, " +
                    "read a Stremio account or change watched state.",
                16f,
                Color.rgb(210, 223, 237)
            )
        )
        status = label(
            "Preparing bounded Story Order rows.",
            16f,
            Color.rgb(132, 225, 190)
        )
        screen.addView(status)
        val storyRows = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        screen.addView(storyRows)
        setContentView(scroll)

        val created = AndroidMobileStoryOrderScreenOwner.create(
            storyRows,
            SYNTHETIC_CANONICAL,
            SYNTHETIC_HINTS
        )
        if (created == null || !created.render(MobileStoryOrderMode.STORY)) {
            created?.close()
            status.text =
                "Story Order presentation unavailable. No external action was attempted."
            return
        }
        owner = created
        status.text =
            "Story Order rows ready. Selection resolves the original synthetic " +
                "canonical object only."
        ui.post(selectionPump)
    }

    override fun onDestroy() {
        ui.removeCallbacks(selectionPump)
        owner?.close()
        owner = null
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
        private const val SELECTION_POLL_MS = 100L
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
