#!/usr/bin/env python3
from pathlib import Path
import json
import sys

root = Path(sys.argv[1]).resolve()

def read(rel):
    return (root / rel).read_text(encoding="utf-8")

def write(rel, text):
    path = root / rel
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")

def replace_once(text, old, new, label):
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)

player_path = "app/src/main/java/com/stremio/mobile/player/Player.kt"
player = read(player_path)
player = replace_once(
    player,
    """    val videoFrameRate: Float = 0f,
)""",
    """    val videoFrameRate: Float = 0f,
    val playbackReady: Boolean = false,
    val videoFramePresent: Boolean = false,
)""",
    "PlayerRuntimeState fields",
)
write(player_path, player)

exo_path = "app/src/main/java/com/stremio/mobile/player/ExoStreamPlayer.kt"
exo = read(exo_path)
exo = replace_once(
    exo,
    """    private var currentSubtitleStyle = PlayerSubtitleStyle()

    private val listener""",
    """    private var currentSubtitleStyle = PlayerSubtitleStyle()
    private var firstVideoFrameRendered = false

    private val listener""",
    "Exo first-frame field",
)
exo = replace_once(
    exo,
    """    private val listener = object : androidx.media3.common.Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) {""",
    """    private val listener = object : androidx.media3.common.Player.Listener {
        override fun onRenderedFirstFrame() {
            firstVideoFrameRendered = true
            publishState()
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {""",
    "Exo first-frame listener",
)
exo = replace_once(
    exo,
    """    ) {
        currentUri = uri
        currentStartPositionMs = startPositionMs""",
    """    ) {
        firstVideoFrameRendered = false
        currentUri = uri
        currentStartPositionMs = startPositionMs""",
    "Exo load reset",
)
exo = replace_once(
    exo,
    """    override fun retry() {
        mutableRuntimeState.value = mutableRuntimeState.value.copy(error = null, ended = false)""",
    """    override fun retry() {
        firstVideoFrameRendered = false
        mutableRuntimeState.value = mutableRuntimeState.value.copy(error = null, ended = false)""",
    "Exo retry reset",
)
exo = replace_once(
    exo,
    """            videoHeight = videoHeight,
            videoFrameRate = videoFrameRate,
        )""",
    """            videoHeight = videoHeight,
            videoFrameRate = videoFrameRate,
            playbackReady = exoPlayer.playbackState == androidx.media3.common.Player.STATE_READY,
            videoFramePresent = firstVideoFrameRendered,
        )""",
    "Exo runtime state",
)
write(exo_path, exo)

policy_path = "app/src/main/java/com/stremio/mobile/player/ExoStartupRecoveryPolicy.kt"
write(policy_path, """package com.stremio.mobile.player

object ExoStartupRecoveryPolicy {
    const val READY_NO_FRAME_GRACE_MS = 3_000L

    fun shouldFallbackToMpv(
        state: PlayerRuntimeState,
        readyWithoutFrameMs: Long,
    ): Boolean {
        if (state.videoFramePresent) return false
        if (state.error != null) return true
        if (!state.playbackReady) return false
        return readyWithoutFrameMs >= READY_NO_FRAME_GRACE_MS
    }
}
""")

manager_path = "app/src/main/java/com/stremio/mobile/player/PlaybackManager.kt"
write(manager_path, """package com.stremio.mobile.player

import android.content.Context
import android.net.Uri
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class PlaybackState(
    val activeUri: String? = null,
    val title: String? = null,
    val isPlaying: Boolean = false,
    val engine: PlayerEngine? = null,
    val recoveryMessage: String? = null,
)

class PlaybackManager(
    private val context: Context,
) {
    private val mutableState = MutableStateFlow(PlaybackState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var player: Player? = null
    private var startupWatchdog: Job? = null
    private var loadGeneration = 0L
    private var recoveryUsed = false

    val state: StateFlow<PlaybackState> = mutableState

    fun load(
        uri: Uri,
        title: String? = null,
        startPositionMs: Long = 0,
        subtitles: List<ExternalSubtitle> = emptyList(),
        preferredSubtitleLang: String? = null,
        engine: PlayerEngine = PlayerEngine.EXO,
        settings: com.stremio.core.types.profile.Profile.Settings? = null,
    ) {
        startupWatchdog?.cancel()
        recoveryUsed = false
        loadGeneration += 1
        val generation = loadGeneration

        replacePlayer(
            uri = uri,
            title = title,
            startPositionMs = startPositionMs,
            subtitles = subtitles,
            preferredSubtitleLang = preferredSubtitleLang,
            requestedEngine = engine,
            settings = settings,
            recoveryMessage = null,
        )

        if (player?.engine == PlayerEngine.EXO) {
            startExoStartupWatchdog(
                generation = generation,
                uri = uri,
                title = title,
                originalStartPositionMs = startPositionMs,
                subtitles = subtitles,
                preferredSubtitleLang = preferredSubtitleLang,
                settings = settings,
            )
        }
    }

    private fun replacePlayer(
        uri: Uri,
        title: String?,
        startPositionMs: Long,
        subtitles: List<ExternalSubtitle>,
        preferredSubtitleLang: String?,
        requestedEngine: PlayerEngine,
        settings: com.stremio.core.types.profile.Profile.Settings?,
        recoveryMessage: String?,
    ) {
        val oldPlayer = player
        val fallbackMessage = if (requestedEngine == PlayerEngine.MPV) {
            "MPV unavailable; using ExoPlayer."
        } else {
            null
        }

        val replacement = runCatching {
            PlayerFactory.create(context, requestedEngine, settings).also {
                it.load(uri, startPositionMs, subtitles, preferredSubtitleLang, settings)
                it.play()
            }
        }.getOrElse { failure ->
            if (requestedEngine != PlayerEngine.MPV) throw failure
            ExoStreamPlayer(context, settings).also {
                it.load(uri, startPositionMs, subtitles, preferredSubtitleLang, settings)
                it.play()
                it.reportNonFatalError(fallbackMessage)
            }
        }

        player = replacement
        oldPlayer?.release()
        mutableState.value = PlaybackState(
            activeUri = uri.toString(),
            title = title,
            isPlaying = true,
            engine = replacement.engine,
            recoveryMessage = recoveryMessage,
        )
    }

    private fun startExoStartupWatchdog(
        generation: Long,
        uri: Uri,
        title: String?,
        originalStartPositionMs: Long,
        subtitles: List<ExternalSubtitle>,
        preferredSubtitleLang: String?,
        settings: com.stremio.core.types.profile.Profile.Settings?,
    ) {
        startupWatchdog?.cancel()
        startupWatchdog = scope.launch {
            var readyWithoutFrameMs = 0L

            while (isActive && generation == loadGeneration && !recoveryUsed) {
                delay(500)
                val activePlayer = player ?: return@launch
                if (activePlayer.engine != PlayerEngine.EXO) return@launch

                val runtime = activePlayer.runtimeState.value
                if (runtime.videoFramePresent) return@launch

                readyWithoutFrameMs = if (runtime.playbackReady) {
                    readyWithoutFrameMs + 500L
                } else {
                    0L
                }

                if (ExoStartupRecoveryPolicy.shouldFallbackToMpv(runtime, readyWithoutFrameMs)) {
                    recoverSameSourceToMpv(
                        generation = generation,
                        uri = uri,
                        title = title,
                        originalStartPositionMs = originalStartPositionMs,
                        subtitles = subtitles,
                        preferredSubtitleLang = preferredSubtitleLang,
                        settings = settings,
                    )
                    return@launch
                }
            }
        }
    }

    private fun recoverSameSourceToMpv(
        generation: Long,
        uri: Uri,
        title: String?,
        originalStartPositionMs: Long,
        subtitles: List<ExternalSubtitle>,
        preferredSubtitleLang: String?,
        settings: com.stremio.core.types.profile.Profile.Settings?,
    ) {
        if (generation != loadGeneration || recoveryUsed) return
        val activePlayer = player
        if (activePlayer?.engine != PlayerEngine.EXO) return

        recoveryUsed = true
        val resumePositionMs = activePlayer.runtimeState.value.positionMs
            .coerceAtLeast(originalStartPositionMs)

        runCatching {
            replacePlayer(
                uri = uri,
                title = title,
                startPositionMs = resumePositionMs,
                subtitles = subtitles,
                preferredSubtitleLang = preferredSubtitleLang,
                requestedEngine = PlayerEngine.MPV,
                settings = settings,
                recoveryMessage = "ExoPlayer did not render video. Retrying the same source with MPV.",
            )
        }.onFailure {
            (player as? ExoStreamPlayer)?.reportNonFatalError(
                "ExoPlayer did not render video and MPV recovery failed."
            )
        }
    }

    fun attachView(view: android.view.View) = Unit

    fun detachView() = Unit

    fun play() {
        player?.play()
        mutableState.value = mutableState.value.copy(isPlaying = true)
    }

    fun pause() {
        player?.pause()
        mutableState.value = mutableState.value.copy(isPlaying = false)
    }

    fun addExternalSubtitleTracks(tracks: List<ExternalSubtitle>) {
        player?.addExternalSubtitleTracks(tracks)
    }

    fun addLocalSubtitle(track: ExternalSubtitle) {
        player?.addLocalSubtitle(track)
    }

    fun release() {
        startupWatchdog?.cancel()
        startupWatchdog = null
        loadGeneration += 1
        recoveryUsed = false
        player?.release()
        player = null
        mutableState.value = PlaybackState()
    }

    fun getPlayer(): Player? = player
}
""")

test_path = "app/src/test/java/com/stremio/mobile/player/ExoStartupRecoveryPolicyTest.kt"
write(test_path, """package com.stremio.mobile.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class ExoStartupRecoveryPolicyTest {
    @Test
    fun bufferingNeverTriggersFallback() {
        assertFalse(
            ExoStartupRecoveryPolicy.shouldFallbackToMpv(
                PlayerRuntimeState(isBuffering = true, playbackReady = false),
                readyWithoutFrameMs = 30_000,
            )
        )
    }

    @Test
    fun renderedFrameCancelsFallback() {
        assertFalse(
            ExoStartupRecoveryPolicy.shouldFallbackToMpv(
                PlayerRuntimeState(playbackReady = true, videoFramePresent = true),
                readyWithoutFrameMs = 30_000,
            )
        )
    }

    @Test
    fun readyDecoderWithoutFrameTriggersBoundedFallback() {
        assertTrue(
            ExoStartupRecoveryPolicy.shouldFallbackToMpv(
                PlayerRuntimeState(playbackReady = true, videoFramePresent = false),
                readyWithoutFrameMs = ExoStartupRecoveryPolicy.READY_NO_FRAME_GRACE_MS,
            )
        )
    }

    @Test
    fun explicitExoPlaybackErrorTriggersFallbackImmediately() {
        assertTrue(
            ExoStartupRecoveryPolicy.shouldFallbackToMpv(
                PlayerRuntimeState(error = "decoder failed"),
                readyWithoutFrameMs = 0,
            )
        )
    }
}
""")

build_path = "app/build.gradle.kts"
build = read(build_path)
build = replace_once(
    build,
    '        applicationId = "com.stremio.mobile"',
    '        applicationId = "com.stremio.dvfix"',
    "side-by-side applicationId",
)
build = replace_once(
    build,
    "    compileSdk = 37",
    "    compileSdk = 36",
    "test-build compileSdk",
)
build = replace_once(
    build,
    "        targetSdk = 37",
    "        targetSdk = 36",
    "test-build targetSdk",
)
build = replace_once(
    build,
    '    debugImplementation("com.squareup.leakcanary:leakcanary-android:2.14")',
    '    // LeakCanary intentionally omitted from the Fire TV DV Fix build.',
    "remove LeakCanary developer UI",
)
write(build_path, build)

mpv_build_path = "third_party/mpv-android-lib/app/build.gradle.kts"
mpv_build = read(mpv_build_path)
mpv_build = replace_once(
    mpv_build,
    "    compileSdk = 37",
    "    compileSdk = 36",
    "MPV test-build compileSdk",
)
write(mpv_build_path, mpv_build)

gradle_properties_path = "gradle.properties"
gradle_properties = read(gradle_properties_path)
if "android.experimental.disableCompileSdkChecks=true" not in gradle_properties:
    gradle_properties = gradle_properties.rstrip() + "\nandroid.experimental.disableCompileSdkChecks=true\n"
write(gradle_properties_path, gradle_properties)

# 7. Give the side-by-side build its own streaming-server port so it can coexist with official Stremio.
jni_controller_path = "app/src/main/java/com/stremio/mobile/server/JniStreamingServerController.kt"
jni_controller = read(jni_controller_path)
jni_controller = replace_once(
    jni_controller,
    "startServerNative(context.applicationContext, configDir, cacheDir, 11470)",
    "startServerNative(context.applicationContext, configDir, cacheDir, 11471)",
    "DV Fix native streaming-server port",
)
write(jni_controller_path, jni_controller)

core_path = "app/src/main/java/com/stremio/mobile/core/StremioCore.kt"
core_text = read(core_path)
core_text = replace_once(
    core_text,
    'const val STREAMING_SERVER_BASE = "http://127.0.0.1:11470"',
    'const val STREAMING_SERVER_BASE = "http://127.0.0.1:11471"',
    "DV Fix Core streaming-server base",
)
write(core_path, core_text)

player_screen_path = "app/src/main/java/com/stremio/mobile/presentation/screens/PlayerScreen.kt"
player_screen = read(player_screen_path)
player_screen = replace_once(
    player_screen,
    'URL("http://127.0.0.1:11470/$infoHash/stats.json")',
    'URL("http://127.0.0.1:11471/$infoHash/stats.json")',
    "DV Fix player stats port",
)
write(player_screen_path, player_screen)

server_utils_path = "app/src/main/java/com/stremio/mobile/server/ServerUtils.kt"
server_utils = read(server_utils_path)
server_utils = server_utils.replace('message.contains("11470")', 'message.contains("11471")')
server_utils = server_utils.replace('Port 11470 is already in use.', 'Port 11471 is already in use.')
write(server_utils_path, server_utils)

strings_path = "app/src/main/res/values/strings.xml"
strings = read(strings_path)
strings = replace_once(
    strings,
    '<string name="app_name">Stremio</string>',
    '<string name="app_name">Stremio DV Fix</string>',
    "side-by-side app label",
)
write(strings_path, strings)

google_path = "app/google-services.json"
google = json.loads(read(google_path))
matched = 0
for client in google.get("client", []):
    info = client.get("client_info", {}).get("android_client_info", {})
    if info.get("package_name") == "com.stremio.mobile":
        info["package_name"] = "com.stremio.dvfix"
        matched += 1
if matched != 1:
    raise SystemExit(f"google-services package rewrite expected one client, found {matched}")
write(google_path, json.dumps(google, indent=2) + "\n")

print("Android Dolby Vision black-screen fallback patch applied successfully.")
