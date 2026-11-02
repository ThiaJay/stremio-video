package org.thiajay.stremiotvcompanion

import android.app.Activity
import android.os.SystemClock
import android.test.ActivityInstrumentationTestCase2
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView

/**
 * Executes the disclosed offline subtitle path in an ephemeral Android 11
 * emulator. It observes only the Activity's bounded status strings. No bitmap,
 * cue text, account state, source URL or screenshot is retained or exported.
 *
 * This is emulator window-buffer evidence. It is not physical-display,
 * signed-installation, private-client, Fire TV or hardware acceptance.
 */
@Suppress("DEPRECATION")
class CompanionTvPixelCopyInstrumentationTest :
    ActivityInstrumentationTestCase2<CompanionTvActivity>(CompanionTvActivity::class.java) {

    override fun setUp() {
        super.setUp()
        setActivityInitialTouchMode(true)
        val context = instrumentation.targetContext
        assertTrue(context.getSharedPreferences(TV_STATE, 0).edit().clear().commit())
        assertTrue(context.getSharedPreferences(TV_RECEIPT, 0).edit().clear().commit())
    }

    fun testExplicitOffExecutesMedia3DrawAndWindowPixelCopyOnApi30() {
        val launched = activity
        clickButton(launched, PLAY)
        val status = awaitStatus(launched, OFF_PIXELS, 25_000L)
        assertTrue(status, status.contains("Media3 synthetic text track present and unselected"))
        assertTrue(status, status.contains("OFF_EMPTY_DRAW_PIPELINE_OBSERVED"))
        assertTrue(status, status.contains(OFF_PIXELS))
        assertTrue(status, status.contains("physical display and device output NOT accepted"))
    }

    fun testAdmittedEnabledExecutesMedia3CuesDrawAndWindowPixelCopyOnApi30() {
        val launched = activity
        clickButton(launched, ENABLE)
        awaitStatus(launched, "Accepted explicit synthetic enabled choice", 5_000L)
        clickButton(launched, PLAY)
        val status = awaitStatus(launched, ENABLED_PIXELS, 25_000L)
        assertTrue(status, status.contains("Media3 synthetic text track present and selected"))
        assertTrue(status, status.contains("Media3 synthetic cue delivery"))
        assertTrue(status, status.contains("ENABLED_DRAW_PIPELINE_OBSERVED"))
        assertTrue(status, status.contains(ENABLED_PIXELS))
        assertTrue(status, status.contains("physical display and device output NOT accepted"))
    }

    private fun clickButton(activity: Activity, label: String) {
        var clicked = false
        instrumentation.runOnMainSync {
            val button = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == label }
            clicked = button?.performClick() == true
        }
        assertTrue("Button was not uniquely found or activated: $label", clicked)
        instrumentation.waitForIdleSync()
    }

    private fun awaitStatus(activity: Activity, needle: String, timeoutMs: Long): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var snapshot = ""
        while (SystemClock.uptimeMillis() < deadline) {
            instrumentation.runOnMainSync {
                snapshot = findViews(activity.window.decorView)
                    .filterIsInstance<TextView>()
                    .joinToString("\n") { it.text.toString() }
            }
            if (snapshot.contains(needle)) return snapshot
            SystemClock.sleep(200L)
        }
        fail("Timed out waiting for '$needle'. Final bounded UI status:\n$snapshot")
        return snapshot
    }

    private fun findViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(findViews(root.getChildAt(index)))
        }
    }

    private companion object {
        const val TV_STATE = "synthetic_tv_subtitle_state_v1"
        const val TV_RECEIPT = "synthetic_tv_subtitle_receipt_v1"
        const val PLAY = "Play offline synthetic Media3 fixture with local subtitles"
        const val ENABLE = "Use automatic subtitles"
        const val OFF_PIXELS = "OFF_DARK_WINDOW_OBSERVED"
        const val ENABLED_PIXELS = "ENABLED_WINDOW_PIXELS_OBSERVED"
    }
}
