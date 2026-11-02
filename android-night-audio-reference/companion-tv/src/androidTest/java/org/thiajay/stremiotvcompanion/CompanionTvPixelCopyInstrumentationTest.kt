package org.thiajay.stremiotvcompanion

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ScrollView
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes the disclosed offline subtitle path in an ephemeral Android 11
 * emulator. It observes only the Activity's bounded status strings. No bitmap,
 * cue text, account state, source URL or screenshot is retained or exported.
 *
 * This is emulator window-buffer evidence. It is not physical-display,
 * signed-installation, private-client, Fire TV or hardware acceptance.
 */
@RunWith(AndroidJUnit4::class)
class CompanionTvPixelCopyInstrumentationTest {
    private lateinit var scenario: ActivityScenario<CompanionTvActivity>

    @Before
    fun setUp() {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        val context = instrumentation.targetContext
        assertTrue(context.getSharedPreferences(TV_STATE, 0).edit().clear().commit())
        assertTrue(context.getSharedPreferences(TV_RECEIPT, 0).edit().clear().commit())
        scenario = ActivityScenario.launch(CompanionTvActivity::class.java)
        instrumentation.waitForIdleSync()
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun testExplicitOffExecutesMedia3DrawAndWindowPixelCopyOnApi30() {
        clickButton(PLAY)
        val status = awaitStatus(OFF_PIXELS, 25_000L)
        assertTrue(status, status.contains("Media3 synthetic text track present and unselected"))
        assertTrue(status, status.contains("OFF_EMPTY_DRAW_PIPELINE_OBSERVED"))
        assertTrue(status, status.contains(OFF_PIXELS))
        assertTrue(status, status.contains("physical display and device output NOT accepted"))
    }

    @Test
    fun testAdmittedEnabledExecutesMedia3CuesDrawAndWindowPixelCopyOnApi30() {
        clickButton(ENABLE)
        awaitStatus("Accepted explicit synthetic enabled choice", 5_000L)
        clickButton(PLAY)
        val status = awaitStatus(ENABLED_PIXELS, 25_000L)
        assertTrue(status, status.contains("Media3 synthetic text track present and selected"))
        assertTrue(status, status.contains("Media3 synthetic cue delivery"))
        assertTrue(status, status.contains("ENABLED_DRAW_PIPELINE_OBSERVED"))
        assertTrue(status, status.contains(ENABLED_PIXELS))
        assertTrue(status, status.contains("physical display and device output NOT accepted"))
    }

    private fun clickButton(label: String) {
        var clicked = false
        scenario.onActivity { activity ->
            val views = findViews(activity.window.decorView)
            val button = views.filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == label }
            if (label == PLAY) {
                views.filterIsInstance<ScrollView>().singleOrNull()?.scrollTo(0, 0)
            }
            clicked = button?.performClick() == true
            if (label == PLAY) {
                views.filterIsInstance<ScrollView>().singleOrNull()?.scrollTo(0, 0)
            }
        }
        assertTrue("Button was not uniquely found or activated: $label", clicked)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun awaitStatus(needle: String, timeoutMs: Long): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var snapshot = ""
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                snapshot = activity.boundedSubtitleRuntimeStatusForInstrumentation()
                    ?: "SUBTITLE_RUNTIME_STATUS_UNAVAILABLE"
            }
            if (snapshot.contains(needle)) return snapshot
            SystemClock.sleep(200L)
        }
        fail(
            "Timed out waiting for '$needle'. Final bounded UI status: " +
                snapshot.replace('\n', ' ')
        )
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
