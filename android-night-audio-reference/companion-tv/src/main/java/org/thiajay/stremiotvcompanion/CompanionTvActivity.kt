package org.thiajay.stremiotvcompanion

import android.app.Activity
import android.content.Context
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.audio.AudioSink
import androidx.media3.exoplayer.audio.DefaultAudioSink
import androidx.media3.ui.PlayerView
import org.pss.stremio.tv.CoreMessagePort
import org.pss.stremio.tv.CoreSkipSegment
import org.pss.stremio.tv.NativeSkipOutcome
import org.pss.stremio.tv.NativeMedia3FailureIntake
import org.pss.stremio.tv.NativeSeekPort
import org.pss.stremio.tv.NativeTvTitleWatchedOutcome
import org.pss.stremio.tv.SnapshotBoundNativeSeekPort
import org.pss.stremio.tv.NativeTvSkipActuator
import org.pss.stremio.tv.SegmentIdentity
import org.pss.stremio.tv.SkipKind
import org.pss.stremio.tv.SkipMode
import org.pss.stremio.tv.TvPlaybackState
import org.pss.stremio.tv.TvSkipCoordinator
import org.pss.stremio.tv.TvWatchedCoordinator
import org.stremio.nightaudio.reference.NightAudioMedia3Processor
import org.stremio.nightaudio.reference.PcmDynamicsProcessor
import java.io.File

/**
 * Side-by-side Android TV Media3 proof using existing tested Core bridge and
 * the existing independently tested Night Audio PCM processor.
 * No Stremio credentials, account state, external streams or addon sessions.
 */
@UnstableApi
class CompanionTvActivity : Activity() {
    private lateinit var player: ExoPlayer
    private val dsp = NightAudioMedia3Processor()
    private val ui = Handler(Looper.getMainLooper())
    private lateinit var status: TextView
    private lateinit var controls: LinearLayout
    private val manualButtons = LinkedHashMap<SkipKind, Button>()
    private var watchedOwner: AndroidTvTitleWatchedScreenOwner? = null
    private val watchedHost = SyntheticTitleWatchedAccountHost()
    private lateinit var watchedStatus: TextView
    private lateinit var storyOrderToggle: Button
    private lateinit var storyOrderSummary: TextView
    private var storyOrderSelected = false
    private var storyDemoCurrentId = STORY_CANONICAL.first().id
    private val skipCoordinator = TvSkipCoordinator()
    private var generation = 0L
    private var fixtureLoaded = false
    private var skipMode = SkipMode.Ask
    private var nightAudioName = "Off"
    private var errorMessage: String? = null

    // The real Media3 seek must be guarded against the selected programme changing
    // AFTER the Core dismiss acknowledgement but BEFORE the native player actuator.
    private val nativePlayer: NativeSeekPort = SnapshotBoundNativeSeekPort(
        observe = {
            val duration = player.duration.takeIf { it in 1L..120_100L } ?: 0L
            TvPlaybackState(
                generation = generation,
                videoId = if (fixtureLoaded) FIXTURE_ID else "",
                positionMs = player.currentPosition.coerceAtLeast(0L),
                durationMs = duration,
                loaded = fixtureLoaded && player.playbackState == Player.STATE_READY,
                paused = !player.playWhenReady,
                buffering = player.playbackState == Player.STATE_BUFFERING,
                seeking = false,
                switchingSource = false,
                menuOpen = false,
                nextVideoPopupOpen = false
            )
        },
        applySeek = { ms ->
            player.seekTo(ms)
            true
        }
    )

    // A SYNTHETIC fixture-only Core acknowledgement, not a signed-in Stremio reducer.
    private val fixtureCore = object : CoreMessagePort {
        override fun dispatch(message: Map<String, Any>): Boolean =
            fixtureLoaded && message["action"] == "Player" &&
                (message["args"] as? Map<*, *>)?.get("action") == "DismissSkipSegment"
    }
    private val skipActuator = NativeTvSkipActuator(skipCoordinator, fixtureCore, nativePlayer)

    private val refresh = object : Runnable {
        override fun run() {
            if (::player.isInitialized && ::status.isInitialized) updateUi()
            pollWatchedOutcome()
            ui.postDelayed(this, 300L)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val renderers = object : DefaultRenderersFactory(this) {
            override fun buildAudioSink(
                context: Context,
                enableFloatOutput: Boolean,
                enableAudioOutputPlaybackParams: Boolean
            ): AudioSink = DefaultAudioSink.Builder(context)
                .setEnableFloatOutput(false)
                .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                .setAudioProcessors(arrayOf<AudioProcessor>(dsp))
                .build()
        }
        player = ExoPlayer.Builder(this, renderers).build()
        player.volume = 0.20f
        player.addListener(object : Player.Listener {
            override fun onPlaybackStateChanged(playbackState: Int) { updateUi() }
            override fun onPlayerError(error: PlaybackException) {
                val classification = NativeMedia3FailureIntake.classify(error.errorCode)
                errorMessage =
                    "Local synthetic playback stopped: ${classification.category}. " +
                        "No automatic retry or source change."
                dsp.request(null)
                nightAudioName = "Off"
                updateUi()
            }
        })

        val container = ScrollView(this).apply {
            setFillViewport(true)
            setBackgroundColor(Color.rgb(16, 23, 34))
        }
        controls = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 28, 40, 28)
        }
        container.addView(controls)
        header("Stremio Companion Lab")
        small("Independent Android TV build. Existing Stremio remains untouched. No account or network access.")
        val video = PlayerView(this).apply {
            useController = false
            setShowBuffering(PlayerView.SHOW_BUFFERING_NEVER)
            player = this@CompanionTvActivity.player
            isFocusable = false
            setBackgroundColor(Color.BLACK)
        }
        controls.addView(video, LinearLayout.LayoutParams(
            LinearLayout.LayoutParams.MATCH_PARENT, 280.dp
        ))
        val play = button("Play offline 12-second synthetic Media3 fixture") { startFixture() }
        button("Pause / Resume") {
            if (fixtureLoaded) player.playWhenReady = !player.playWhenReady
        }
        small("Skip demonstrations below operate ONLY on the synthetic local clip. They do not infer timings for a real programme.")
        for (kind in listOf(SkipKind.Recap, SkipKind.Intro, SkipKind.Outro)) {
            val b = button(kind.label()) { skipFixture(kind) }
            b.isEnabled = false
            manualButtons[kind] = b
        }
        button("Mode Ask / Always / Never") {
            skipMode = when (skipMode) { SkipMode.Ask -> SkipMode.Always; SkipMode.Always -> SkipMode.Never; SkipMode.Never -> SkipMode.Ask }
            updateUi()
        }
        small("Night Audio is Off by default. The strength controls use the already-tested Media3 PCM processor.")
        button("Night Audio Off") { setNightAudio(null, "Off") }
        button("Night Audio Gentle") { setNightAudio(PcmDynamicsProcessor.Strength.GENTLE, "Gentle") }
        button("Night Audio Balanced") { setNightAudio(PcmDynamicsProcessor.Strength.BALANCED, "Balanced") }
        button("Night Audio Strong") { setNightAudio(PcmDynamicsProcessor.Strength.STRONG, "Strong") }
        button("Stop playback and reset Night Audio") { stopFixture() }
        small("Story Order below uses synthetic local metadata only. It cannot read a Stremio catalogue, start a stream or change watched state.")
        storyOrderToggle = button("Presentation: Canonical") {
            storyOrderSelected = !storyOrderSelected
            updateStoryOrderDemo()
        }
        button("Next Story item (local metadata only)") { advanceStoryOrderDemo() }
        storyOrderSummary = small("")
        updateStoryOrderDemo()
        small(
            "Title actions below use the real bounded Android TV widget and owner " +
                "against this process's local in-memory fixture only. They cannot read " +
                "or change a Stremio account."
        )
        watchedStatus = small("Preparing synthetic title actions.")
        val watchedActions = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }
        controls.addView(watchedActions)
        val createdWatched = AndroidTvTitleWatchedScreenOwner.create(
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
        status = header("Ready. No media or account loaded.")
        small("No external media, credentials, Stremio account, history or source URL are read or written by this build.")
        setContentView(container)
        play.requestFocus()
        ui.post(refresh)
    }

    private fun startFixture() {
        errorMessage = null
        fixtureLoaded = false
        generation++
        player.stop()
        try {
            val clip: File = SyntheticAudioFixture.createIfMissing(cacheDir)
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(clip)))
            player.volume = 0.20f
            fixtureLoaded = true
            player.prepare()
            player.playWhenReady = true
        } catch (_: Exception) {
            errorMessage = "Unable to prepare the offline test media."
            fixtureLoaded = false
            player.stop()
        }
        updateUi()
    }

    private fun currentSegment(kind: SkipKind, state: TvPlaybackState): CoreSkipSegment? {
        if (!fixtureLoaded || state.durationMs !in 11_900L..12_100L) return null
        val interval = when (kind) {
            SkipKind.Recap -> 0L to 1_500L
            SkipKind.Intro -> 1_500L to 3_500L
            SkipKind.Outro -> 8_500L to 10_000L
            SkipKind.Preview -> return null
        }
        return CoreSkipSegment(kind, generation, FIXTURE_ID, interval.first, interval.second,
            state.durationMs, skipMode, true, false, interval.second)
    }

    private fun skipFixture(kind: SkipKind) {
        val snapshot = nativePlayer.snapshot()
        val segment = currentSegment(kind, snapshot)
        val focused: SegmentIdentity? = skipCoordinator.presentation(segment, snapshot)?.focusedIdentity
        when (skipActuator.manual(segment, focused, "DPAD_CENTER")) {
            NativeSkipOutcome.APPLIED -> errorMessage = null
            else -> errorMessage = "Skip unavailable: player position, focus or media state changed."
        }
        updateUi()
    }

    private fun updateUi() {
        if (!::status.isInitialized) return
        val p = nativePlayer.snapshot()
        if (skipMode == SkipMode.Always && fixtureLoaded && !p.paused) {
            val candidate = listOf(SkipKind.Recap, SkipKind.Intro, SkipKind.Outro)
                .mapNotNull { currentSegment(it, p) }
                .firstOrNull { it.active && p.positionMs in it.fromMs until it.toMs }
            if (candidate != null && skipActuator.automatic(candidate) == NativeSkipOutcome.APPLIED) {
                // The media controller owns current-position readback on the next frame.
            }
        }
        for ((kind, b) in manualButtons) {
            val s = currentSegment(kind, p)
            b.isEnabled = skipMode == SkipMode.Ask && skipCoordinator.presentation(s, p) != null
        }
        val state = when {
            errorMessage != null -> errorMessage!!
            !fixtureLoaded -> "Ready. Stremio account not connected. No content playing."
            player.playbackState == Player.STATE_ENDED -> "Local test finished. No next episode and no watched write."
            player.isPlaying -> "Synthetic playback at ${p.positionMs / 1000}s of ${p.durationMs / 1000}s."
            else -> "Synthetic playback paused or loading."
        }
        val dspStatus = when {
            nightAudioName == "Off" -> "Night Audio Off."
            dsp.isPcmProcessingObserved() -> "Night Audio $nightAudioName: PCM processed; audible route NOT verified."
            else -> "Night Audio $nightAudioName: waiting for eligible PCM; no active output claim."
        }
        status.text = "$state  Skip mode $skipMode.  $dspStatus"
    }

    private fun updateStoryOrderDemo(message: String? = null) {
        val visible = StoryOrderContract.presentation(
            STORY_CANONICAL,
            STORY_HINTS,
            storyOrderSelected
        )
        storyOrderToggle.text = if (storyOrderSelected) {
            "Presentation: Story Order"
        } else {
            "Presentation: Canonical"
        }
        val sequence = visible.joinToString("  →  ") {
            "${it.id} [S${it.season} E${it.episode ?: "?"}]"
        }
        storyOrderSummary.text = listOfNotNull(
            message,
            "Selected stable ID: $storyDemoCurrentId",
            "Visible local sequence: $sequence",
            "No player, account or watched-state binding."
        ).joinToString("\n")
    }

    private fun advanceStoryOrderDemo() {
        if (!storyOrderSelected) {
            updateStoryOrderDemo("Story Order is not selected; existing continuation remains untouched.")
            return
        }
        when (val decision = StoryOrderContract.next(
            STORY_CANONICAL,
            STORY_HINTS,
            storyDemoCurrentId
        )) {
            is NextStoryDecision.UseCanonicalEpisode -> {
                storyDemoCurrentId = decision.episode.id
                updateStoryOrderDemo("Resolved the original canonical object by stable ID.")
            }
            is NextStoryDecision.UseExistingContinuation -> {
                updateStoryOrderDemo("No Story Order override: ${decision.reason}.")
            }
        }
    }

    private fun pollWatchedOutcome() {
        watchedOwner?.let { current ->
            val outcome = current.consumeOutcome() ?: return
            val result = when (outcome) {
                NativeTvTitleWatchedOutcome.APPLIED ->
                    "Applied to local synthetic watched state."
                NativeTvTitleWatchedOutcome.DISPATCHED_PENDING_READBACK ->
                    "Local readback was not available; no further action is enabled."
                NativeTvTitleWatchedOutcome.REFUSED ->
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

    private fun stopFixture() {
        player.stop()
        fixtureLoaded = false
        generation++
        setNightAudio(null, "Off")
    }

    private fun setNightAudio(strength: PcmDynamicsProcessor.Strength?, name: String) {
        nightAudioName = name
        dsp.request(strength)
        updateUi()
    }

    private fun header(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 23f
        setTextColor(Color.WHITE)
        setPadding(0, 6.dp, 0, 10.dp)
        controls.addView(this)
    }

    private fun small(text: String): TextView = TextView(this).apply {
        this.text = text
        textSize = 16f
        setTextColor(Color.rgb(210, 223, 237))
        setPadding(0, 2.dp, 0, 12.dp)
        controls.addView(this)
    }

    private fun button(text: String, click: () -> Unit): Button = Button(this).apply {
        this.text = text
        isAllCaps = false
        textSize = 18f
        isFocusable = true
        setOnClickListener { click() }
        controls.addView(this)
    }

    private val Int.dp get() = (this * resources.displayMetrics.density).toInt()
    private fun SkipKind.label() = when (this) {
        SkipKind.Recap -> "Skip Recap"
        SkipKind.Intro -> "Skip Intro"
        SkipKind.Outro -> "Skip Credits"
        SkipKind.Preview -> "Skip Preview (not supported)"
    }

    override fun onStop() {
        if (::player.isInitialized) player.pause()
        dsp.request(null)
        nightAudioName = "Off"
        super.onStop()
    }

    override fun onDestroy() {
        ui.removeCallbacks(refresh)
        watchedOwner?.close()
        watchedOwner = null
        if (::player.isInitialized) player.release()
        dsp.request(null)
        super.onDestroy()
    }

    companion object {
        private const val FIXTURE_ID = "local:synthetic-media:12s"
        private val STORY_CANONICAL = listOf(
            CanonicalEpisode("local:story:1:1", 1, 1, "Episode one"),
            CanonicalEpisode("local:story:0:2", 0, 2, "Narrative special"),
            CanonicalEpisode("local:story:1:2", 1, 2, "Episode two"),
            CanonicalEpisode("local:story:0:99", 0, 99, "Ancillary extra")
        )
        private val STORY_HINTS = StoryOrderHints(
            storyOrderVersion = 1,
            storyOrder = listOf(
                "local:story:1:1",
                "local:story:0:2",
                "local:story:1:2"
            )
        )
    }
}
