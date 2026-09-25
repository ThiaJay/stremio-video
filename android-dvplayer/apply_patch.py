#!/usr/bin/env python3
from pathlib import Path
import sys

root = Path(sys.argv[1]).resolve()
if not root.is_dir():
    raise SystemExit(f"Android source root not found: {root}")

def read(rel):
    return (root / rel).read_text(encoding="utf-8")

def write(rel, text):
    p = root / rel
    p.parent.mkdir(parents=True, exist_ok=True)
    p.write_text(text, encoding="utf-8")

settings = read("settings.gradle.kts")
if 'include(":dvplayer")' not in settings:
    settings = settings.rstrip() + '\ninclude(":dvplayer")\n'
write("settings.gradle.kts", settings)

mpv_gradle = read("third_party/mpv-android-lib/app/build.gradle.kts")
if "compileSdk = 37" in mpv_gradle:
    mpv_gradle = mpv_gradle.replace("compileSdk = 37", "compileSdk = 36", 1)
write("third_party/mpv-android-lib/app/build.gradle.kts", mpv_gradle)

props = read("gradle.properties")
if "android.experimental.disableCompileSdkChecks=true" not in props:
    props = props.rstrip() + "\nandroid.experimental.disableCompileSdkChecks=true\n"
write("gradle.properties", props)

write("dvplayer/build.gradle.kts", r'''plugins {
    id("com.android.application")
}

fun stringPropertyOrEnv(name: String): String? =
    (findProperty(name) as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv(name)?.takeIf { it.isNotBlank() }

val signingValues = listOf(
    stringPropertyOrEnv("ANDROID_KEYSTORE_FILE"),
    stringPropertyOrEnv("ANDROID_KEYSTORE_PASSWORD"),
    stringPropertyOrEnv("ANDROID_KEY_ALIAS"),
    stringPropertyOrEnv("ANDROID_KEY_PASSWORD"),
)

android {
    namespace = "com.stremio.dvplayer"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.stremio.dvplayer"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"
        ndk {
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

    signingConfigs {
        if (signingValues.all { it != null }) {
            create("stable") {
                storeFile = file(signingValues[0]!!)
                storePassword = signingValues[1]
                keyAlias = signingValues[2]
                keyPassword = signingValues[3]
            }
        }
    }

    buildTypes {
        debug {
            signingConfigs.findByName("stable")?.let { signingConfig = it }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

dependencies {
    implementation(project(":mpv-android-lib"))
}
''')

write("dvplayer/src/main/AndroidManifest.xml", r'''<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <uses-permission android:name="android.permission.INTERNET" />
    <uses-permission android:name="android.permission.ACCESS_NETWORK_STATE" />
    <uses-permission android:name="android.permission.WAKE_LOCK" />

    <uses-feature android:name="android.software.leanback" android:required="false" />
    <uses-feature android:name="android.hardware.touchscreen" android:required="false" />

    <application
        android:allowBackup="false"
        android:label="Stremio DV Player"
        android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen"
        android:usesCleartextTraffic="true">

        <activity
            android:name=".ExternalPlayerActivity"
            android:configChanges="keyboard|keyboardHidden|orientation|screenLayout|screenSize|smallestScreenSize"
            android:excludeFromRecents="true"
            android:exported="true"
            android:launchMode="singleTask"
            android:screenOrientation="landscape"
            android:theme="@android:style/Theme.Black.NoTitleBar.Fullscreen">

            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="http" />
                <data android:scheme="https" />
                <data android:scheme="content" />
                <data android:scheme="file" />
                <data android:mimeType="video/*" />
            </intent-filter>

            <intent-filter>
                <action android:name="android.intent.action.VIEW" />
                <category android:name="android.intent.category.DEFAULT" />
                <data android:scheme="http" />
                <data android:scheme="https" />
                <data android:mimeType="application/octet-stream" />
            </intent-filter>
        </activity>
    </application>
</manifest>
''')

write("dvplayer/src/main/java/com/stremio/dvplayer/ExternalPlayerActivity.kt", r'''package com.stremio.dvplayer

import android.app.Activity
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.KeyEvent
import android.view.SurfaceHolder
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.TextView
import \`is\`.xyz.mpv.BaseMPVView
import \`is\`.xyz.mpv.MPVLib
import java.io.File
import kotlin.math.max
import kotlin.math.min

class ExternalPlayerActivity : Activity() {
    private lateinit var mpvView: PlayerView
    private lateinit var overlay: TextView
    private val handler = Handler(Looper.getMainLooper())
    private var startPositionMs = 0L
    private var pendingInitialSeek = false
    private var eofReached = false
    private var finishingWithResult = false

    private val hideOverlay = Runnable { overlay.visibility = View.GONE }

    private val observer = object : MPVLib.EventObserver {
        override fun eventProperty(property: String) = Unit
        override fun eventProperty(property: String, value: Long) = Unit
        override fun eventProperty(property: String, value: String) = Unit
        override fun eventProperty(property: String, value: Double) = Unit

        override fun eventProperty(property: String, value: Boolean) {
            if (property == "eof-reached") eofReached = value
        }

        override fun event(eventId: Int) {
            when (eventId) {
                MPVLib.MpvEvent.MPV_EVENT_FILE_LOADED -> {
                    if (pendingInitialSeek && startPositionMs > 0) {
                        runCatching { MPVLib.setPropertyDouble("time-pos", startPositionMs / 1000.0) }
                    }
                    pendingInitialSeek = false
                }
                MPVLib.MpvEvent.MPV_EVENT_END_FILE -> {
                    if (finishingWithResult) return
                    val duration = durationMs()
                    val position = positionMs()
                    val completed = eofReached || (duration > 0 && duration - position <= 1_500)
                    if (completed) finishWithPlaybackResult("end")
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        window.decorView.systemUiVisibility =
            View.SYSTEM_UI_FLAG_FULLSCREEN or
            View.SYSTEM_UI_FLAG_HIDE_NAVIGATION or
            View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY

        val uri = intent?.data
        if (uri == null) {
            setResult(RESULT_CANCELED)
            finish()
            return
        }

        startPositionMs = extractStartPositionMs(intent)
        pendingInitialSeek = startPositionMs > 0

        copyCertificateAsset()

        val root = FrameLayout(this)
        mpvView = PlayerView(this)
        root.addView(
            mpvView,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT,
                FrameLayout.LayoutParams.MATCH_PARENT,
            ),
        )

        overlay = TextView(this).apply {
            setTextColor(Color.WHITE)
            setBackgroundColor(0x99000000.toInt())
            textSize = 22f
            gravity = Gravity.CENTER
            setPadding(32, 18, 32, 18)
            visibility = View.GONE
        }
        root.addView(
            overlay,
            FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.WRAP_CONTENT,
                FrameLayout.LayoutParams.WRAP_CONTENT,
                Gravity.CENTER,
            ),
        )

        setContentView(root)

        MPVLib.addObserver(observer)
        mpvView.playFile(uri.toString())
        mpvView.initialize(
            configDir = filesDir.absolutePath,
            cacheDir = cacheDir.absolutePath,
        )
    }

    override fun onNewIntent(newIntent: Intent) {
        super.onNewIntent(newIntent)
        setIntent(newIntent)
        val uri = newIntent.data ?: return
        startPositionMs = extractStartPositionMs(newIntent)
        pendingInitialSeek = startPositionMs > 0
        eofReached = false
        MPVLib.command(arrayOf("loadfile", uri.toString(), "replace"))
    }

    override fun onPause() {
        super.onPause()
        if (!isFinishing) runCatching { MPVLib.setPropertyBoolean("pause", true) }
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        runCatching { MPVLib.removeObserver(observer) }
        runCatching { mpvView.destroy() }
        super.onDestroy()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        finishWithPlaybackResult("user")
    }

    override fun onKeyDown(keyCode: Int, event: KeyEvent?): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_CENTER,
            KeyEvent.KEYCODE_ENTER,
            KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE,
            KeyEvent.KEYCODE_SPACE -> {
                togglePause()
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PLAY -> {
                MPVLib.setPropertyBoolean("pause", false)
                showOverlay("Play")
                return true
            }

            KeyEvent.KEYCODE_MEDIA_PAUSE -> {
                MPVLib.setPropertyBoolean("pause", true)
                showOverlay("Pause")
                return true
            }

            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_MEDIA_REWIND -> {
                seekBy(if (keyCode == KeyEvent.KEYCODE_MEDIA_REWIND) -30_000 else -10_000)
                return true
            }

            KeyEvent.KEYCODE_DPAD_RIGHT,
            KeyEvent.KEYCODE_MEDIA_FAST_FORWARD -> {
                seekBy(if (keyCode == KeyEvent.KEYCODE_MEDIA_FAST_FORWARD) 30_000 else 10_000)
                return true
            }

            KeyEvent.KEYCODE_BACK -> {
                finishWithPlaybackResult("user")
                return true
            }
        }

        return super.onKeyDown(keyCode, event)
    }

    private fun togglePause() {
        val paused = runCatching { MPVLib.getPropertyBoolean("pause") }.getOrNull() ?: false
        MPVLib.setPropertyBoolean("pause", !paused)
        showOverlay(if (paused) "Play" else "Pause")
    }

    private fun seekBy(deltaMs: Long) {
        val duration = durationMs()
        val next = (positionMs() + deltaMs).coerceAtLeast(0L).let {
            if (duration > 0) min(it, duration) else it
        }
        MPVLib.setPropertyDouble("time-pos", next / 1000.0)
        val seconds = kotlin.math.abs(deltaMs) / 1000
        showOverlay(if (deltaMs < 0) "-${{seconds}s" else "+${{seconds}s")
    }

    private fun showOverlay(message: String) {
        overlay.text = message
        overlay.visibility = View.VISIBLE
        handler.removeCallbacks(hideOverlay)
        handler.postDelayed(hideOverlay, 900)
    }

    private fun positionMs(): Long =
        ((runCatching { MPVLib.getPropertyDouble("time-pos/full") }.getOrNull() ?: 0.0) * 1000.0)
            .toLong()
            .coerceAtLeast(0L)

    private fun durationMs(): Long =
        ((runCatching { MPVLib.getPropertyDouble("duration/full") }.getOrNull() ?: 0.0) * 1000.0)
            .toLong()
            .coerceAtLeast(0L)

    private fun finishWithPlaybackResult(endBy: String) {
        if (finishingWithResult) return
        finishingWithResult = true

        val position = positionMs().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()
        val duration = durationMs().coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

        val result = Intent().apply {
            putExtra("position", position)
            putExtra("duration", duration)
            putExtra("end_by", endBy)
        }
        setResult(RESULT_OK, result)
        finish()
    }

    private fun extractStartPositionMs(intent: Intent): Long {
        val position = numericExtra(intent, "position")
        val startFrom = numericExtra(intent, "startfrom")
        return max(position ?: 0L, startFrom ?: 0L).coerceAtLeast(0L)
    }

    private fun numericExtra(intent: Intent, key: String): Long? {
        val extras = intent.extras ?: return null
        val value = extras.get(key) ?: return null
        return when (value) {
            is Int -> value.toLong()
            is Long -> value
            is Short -> value.toLong()
            is Float -> value.toLong()
            is Double -> value.toLong()
            is String -> value.toLongOrNull()
            else -> null
        }
    }

    private fun copyCertificateAsset() {
        val out = File(filesDir, "cacert.pem")
        assets.open("cacert.pem").use { input ->
            if (out.exists() && out.length() == input.available().toLong()) return
            out.outputStream().use { output -> input.copyTo(output) }
        }
    }

    private class PlayerView(context: android.content.Context) : BaseMPVView(context, null) {
        override fun initOptions() {
            setVo("gpu")
            MPVLib.setOptionString("profile", "fast")
            MPVLib.setOptionString("gpu-context", "android")
            MPVLib.setOptionString("opengl-es", "yes")

            MPVLib.setOptionString("hwdec", "mediacodec,mediacodec-copy")
            MPVLib.setOptionString("hwdec-codecs", "h264,hevc,mpeg4,mpeg2video,vp8,vp9,av1")

            MPVLib.setOptionString("audio-spdif", "ac3,dts,eac3,truehd,dts-hd")
            MPVLib.setOptionString("audio-channels", "auto-safe")
            MPVLib.setOptionString("ao", "audiotrack,opensles")

            MPVLib.setOptionString("audio-set-media-role", "yes")
            MPVLib.setOptionString("tls-verify", "yes")
            MPVLib.setOptionString("tls-ca-file", "${{context.filesDir.absolutePath}/cacert.pem")

            MPVLib.setOptionString("input-default-bindings", "yes")
            MPVLib.setOptionString("demuxer-max-bytes", "${{64 * 1024 * 1024}")
            MPVLib.setOptionString("demuxer-max-back-bytes", "${{64 * 1024 * 1024}")
        }

        override fun postInitOptions() {
            MPVLib.setOptionString("save-position-on-quit", "no")
        }

        override fun observeProperties() {
            MPVLib.observeProperty("time-pos/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("duration/full", MPVLib.MpvFormat.MPV_FORMAT_DOUBLE)
            MPVLib.observeProperty("pause", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
            MPVLib.observeProperty("eof-reached", MPVLib.MpvFormat.MPV_FORMAT_FLAG)
        }

        override fun surfaceCreated(holder: SurfaceHolder) {
            super.surfaceCreated(holder)
        }
    }
}
''')

print("DV player sidecar patch applied.")
