package org.thiajay.stremiotvcompanion

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.action.ViewActions.click
import androidx.test.espresso.matcher.ViewMatchers.withText
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Executes one synthetic title-level watched action through the real Android
 * mobile Button, screen owner, canonical adapter and readback path on API 30.
 * The fixture is process-local and has no credentials, network or Stremio account.
 */
@RunWith(AndroidJUnit4::class)
class MobileTitleWatchedInstrumentationTest {
    private lateinit var scenario: ActivityScenario<MobileStoryOrderLabActivity>

    @Before
    fun setUp() {
        scenario = ActivityScenario.launch(MobileStoryOrderLabActivity::class.java)
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    @After
    fun tearDown() {
        scenario.close()
    }

    @Test
    fun testFrameworkClicksApplyAndClearMobileSeriesWatchedWithCanonicalReadbackOnApi30() {
        clickUniqueButton(MARK_SERIES_WATCHED)

        val status = awaitText(APPLIED, 5_000L)
        assertTrue(status, status.contains(EXPECTED_EPISODE_1))
        assertTrue(status, status.contains(EXPECTED_EPISODE_2))
        assertFalse(status, status.contains(SEASON_ZERO_EPISODE))

        scenario.onActivity { activity ->
            val opposite = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == MARK_SERIES_UNWATCHED }
            assertTrue(
                "Canonical mobile watched readback did not re-arm the opposite action",
                opposite?.let { it.isEnabled && it.isClickable } == true
            )
        }

        clickUniqueButton(MARK_SERIES_UNWATCHED)
        onView(withText(CONFIRM)).perform(click())
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()

        val cleared = awaitText(EMPTY_WATCHED_READBACK, 5_000L)
        assertFalse(cleared, cleared.contains(EXPECTED_EPISODE_1))
        assertFalse(cleared, cleared.contains(EXPECTED_EPISODE_2))
        assertFalse(cleared, cleared.contains(SEASON_ZERO_EPISODE))

        scenario.onActivity { activity ->
            val opposite = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == MARK_SERIES_WATCHED }
            assertTrue(
                "Canonical mobile unwatched readback did not re-arm the watched action",
                opposite?.let { it.isEnabled && it.isClickable } == true
            )
        }
    }

    private fun clickUniqueButton(label: String) {
        scenario.onActivity { activity ->
            val button = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == label }
            val exactButton = requireNotNull(button) {
                "Expected one synthetic mobile button labelled '$label'"
            }
            assertTrue("Synthetic mobile title action was not enabled", exactButton.isEnabled)
            assertTrue("Framework click was not dispatched", exactButton.performClick())
        }
        InstrumentationRegistry.getInstrumentation().waitForIdleSync()
    }

    private fun awaitText(needle: String, timeoutMs: Long): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var snapshot = ""
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                snapshot = findViews(activity.window.decorView)
                    .filterIsInstance<TextView>()
                    .map { it.text.toString() }
                    .filter { it.length <= 2_048 }
                    .joinToString("\n")
            }
            if (snapshot.contains(needle)) return snapshot
            SystemClock.sleep(100L)
        }
        fail(
            "Timed out waiting for '$needle'. Final bounded UI text: " +
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
        const val CONFIRM = "Confirm"
        const val APPLIED = "Applied to local synthetic watched state."
        const val EMPTY_WATCHED_READBACK = "Synthetic watched IDs: none"
        const val EXPECTED_EPISODE_1 = "local:mobile-watched:1:1"
        const val EXPECTED_EPISODE_2 = "local:mobile-watched:1:2"
        const val SEASON_ZERO_EPISODE = "local:mobile-watched:0:2"
    }
}
