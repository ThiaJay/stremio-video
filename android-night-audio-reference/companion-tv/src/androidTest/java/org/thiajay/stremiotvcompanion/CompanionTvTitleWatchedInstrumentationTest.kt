package org.thiajay.stremiotvcompanion

import android.graphics.Rect
import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes one synthetic title-level watched action through the real Android TV
 * Button, remote-key, screen-owner, canonical adapter and readback path on API 30.
 * The fixture is process-local and has no credentials, network or Stremio account.
 * This is not private-client, Fire TV, account or hardware acceptance.
 */
@RunWith(AndroidJUnit4::class)
class CompanionTvTitleWatchedInstrumentationTest {
    private lateinit var scenario: ActivityScenario<CompanionTvActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(CompanionTvActivity::class.java)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun testDpadCenterAppliesSyntheticSeriesWatchedAndCanonicalReadbackOnApi30() {
        var focused = false
        scenario.onActivity { activity ->
            val button = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == MARK_SERIES_WATCHED }
            assertNotNull("Synthetic series watched action was not uniquely rendered", button)
            button?.let { candidate ->
                focused = candidate.requestFocus() && candidate.hasFocus()
                candidate.requestRectangleOnScreen(
                    Rect(
                        0,
                        0,
                        candidate.width.coerceAtLeast(1),
                        candidate.height.coerceAtLeast(1)
                    ),
                    true
                )
            }
        }
        assertTrue("Synthetic watched action did not receive real focus", focused)

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.waitForIdleSync()

        val status = awaitWatchedStatus(APPLIED, 5_000L)
        assertTrue(status, status.contains(EXPECTED_EPISODE_1))
        assertTrue(status, status.contains(EXPECTED_EPISODE_2))
        assertFalse(status, status.contains(SEASON_ZERO_EPISODE))

        var oppositeActionReady = false
        scenario.onActivity { activity ->
            val button = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == MARK_SERIES_UNWATCHED }
            oppositeActionReady = button?.isFocusable == true && button.isEnabled
        }
        assertTrue(
            "Canonical watched readback did not re-arm the opposite synthetic action",
            oppositeActionReady
        )
    }

    private fun awaitWatchedStatus(needle: String, timeoutMs: Long): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var snapshot = ""
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                snapshot = activity.boundedTitleWatchedRuntimeStatusForInstrumentation()
                    ?: "TITLE_WATCHED_RUNTIME_STATUS_UNAVAILABLE"
            }
            if (snapshot.contains(needle)) return snapshot
            SystemClock.sleep(100L)
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
        const val MARK_SERIES_WATCHED = "Mark all released episodes watched"
        const val MARK_SERIES_UNWATCHED = "Mark all released episodes unwatched"
        const val APPLIED = "Applied to local synthetic watched state."
        const val EXPECTED_EPISODE_1 = "local:mobile-watched:1:1"
        const val EXPECTED_EPISODE_2 = "local:mobile-watched:1:2"
        const val SEASON_ZERO_EPISODE = "local:mobile-watched:0:2"
    }
}
