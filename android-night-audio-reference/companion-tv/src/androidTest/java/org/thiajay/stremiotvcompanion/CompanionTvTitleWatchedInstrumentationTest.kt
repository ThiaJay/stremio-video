package org.thiajay.stremiotvcompanion

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import androidx.test.core.app.ActivityScenario
import androidx.test.espresso.Espresso.onView
import androidx.test.espresso.matcher.ViewMatchers.withText
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
 * Executes one synthetic title-level watched/unwatched round trip through the
 * real Android TV Button, remote-key, acknowledgement dialog, screen-owner,
 * canonical adapter and readback path on API 30.
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
    fun testDpadCenterAppliesAndClearsSyntheticSeriesWatchedWithCanonicalReadbackOnApi30() {
        scenario.onActivity { activity ->
            val button = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == MARK_SERIES_WATCHED }
            assertNotNull("Synthetic series watched action was not uniquely rendered", button)
        }
        assertTrue(
            "Synthetic watched window did not become visibly focused",
            awaitVisibleWindowFocus(5_000L)
        )
        assertTrue(
            "Synthetic watched action did not receive real visible window focus",
            awaitRemoteFocus(MARK_SERIES_WATCHED, 64)
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.waitForIdleSync()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.waitForIdleSync()

        val status = awaitWatchedStatus(APPLIED, 5_000L)
        assertTrue(status, status.contains(EXPECTED_EPISODE_1))
        assertTrue(status, status.contains(EXPECTED_EPISODE_2))
        assertTrue(status, status.contains(NARRATIVE_SPECIAL))
        assertFalse(status, status.contains(FUTURE_EPISODE))
        assertFalse(status, status.contains(ANCILLARY_SPECIAL))

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

        assertTrue(
            "Synthetic unwatched action did not receive real visible window focus",
            awaitRemoteFocus(MARK_SERIES_UNWATCHED, 64)
        )
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.waitForIdleSync()

        assertTrue(
            "Destructive acknowledgement did not expose a remotely focused Confirm control",
            awaitRemoteDialogFocus(CONFIRM, 8)
        )
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.waitForIdleSync()

        val cleared = awaitWatchedStatus(EMPTY_WATCHED_READBACK, 5_000L)
        assertFalse(cleared, cleared.contains(EXPECTED_EPISODE_1))
        assertFalse(cleared, cleared.contains(EXPECTED_EPISODE_2))
        assertFalse(cleared, cleared.contains(NARRATIVE_SPECIAL))
        assertFalse(cleared, cleared.contains(FUTURE_EPISODE))
        assertFalse(cleared, cleared.contains(ANCILLARY_SPECIAL))

        assertTrue(
            "Canonical unwatched readback did not re-arm a remotely focusable watched action",
            awaitRemoteFocus(MARK_SERIES_WATCHED, 64)
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

    private fun awaitVisibleWindowFocus(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var focused = false
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                focused = root.isShown && root.hasWindowFocus()
            }
            if (focused) return true
            SystemClock.sleep(100L)
        }
        return false
    }

    private fun awaitRemoteFocus(label: String, maximumMoves: Int): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(maximumMoves + 1) { move ->
            var focused = false
            scenario.onActivity { activity ->
                val button = findViews(activity.window.decorView)
                    .filterIsInstance<Button>()
                    .singleOrNull { it.text.toString() == label }
                button?.let { candidate ->
                    focused = candidate.isShown && candidate.hasWindowFocus() &&
                        candidate.hasFocus()
                }
            }
            if (focused) return true
            if (move < maximumMoves) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
                instrumentation.waitForIdleSync()
            }
        }
        return false
    }

    private fun awaitRemoteDialogFocus(label: String, maximumMoves: Int): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(maximumMoves + 1) { move ->
            var focused = false
            onView(withText(label)).check { view, noViewFoundException ->
                focused = noViewFoundException == null &&
                    view?.let { it.isShown && it.hasWindowFocus() && it.hasFocus() } == true
            }
            if (focused) return true
            if (move < maximumMoves) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_RIGHT)
                instrumentation.waitForIdleSync()
            }
        }
        return false
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
        const val NARRATIVE_SPECIAL = "local:mobile-watched:0:2"
        const val FUTURE_EPISODE = "local:mobile-watched:1:3"
        const val ANCILLARY_SPECIAL = "local:mobile-watched:0:99"
    }
}
