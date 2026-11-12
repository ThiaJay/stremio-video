package org.thiajay.stremiotvcompanion

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
 * Exercises the synthetic Story Order selector and next-item action through real
 * Android TV focus traversal, DPAD_CENTER and the standard framework click path
 * used by accessibility and keyboard activation on API 30. All metadata is local
 * and no catalogue, player, account, network or Stremio state is available here.
 */
@RunWith(AndroidJUnit4::class)
class CompanionTvStoryOrderInstrumentationTest {
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
    fun testDpadCenterSelectsStoryOrderAndAdvancesByStableIdOnApi30() {
        val canonical = awaitStoryStatus(CANONICAL_LABEL, 5_000L)
        assertNoWatchedMarkers(canonical, shouldContainAncillary = true)
        scenario.onActivity { activity ->
            val toggle = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == CANONICAL_LABEL }
            assertNotNull("Synthetic Story Order selector was not uniquely rendered", toggle)
        }
        assertTrue(
            "Synthetic Story Order window did not obtain one stable visible view focus",
            awaitStableVisibleWindowFocus(5_000L)
        )
        assertTrue(
            "Synthetic Story Order selector did not receive real visible window focus",
            awaitRemoteFocus(CANONICAL_LABEL, 64)
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.waitForIdleSync()

        val selected = awaitStoryStatus(STORY_LABEL, 5_000L)
        assertTrue(selected, selected.contains(REGULAR_EPISODE_1))
        assertTrue(selected, selected.contains(NARRATIVE_SPECIAL))
        assertTrue(selected, selected.contains(REGULAR_EPISODE_2))
        assertTrue(selected, selected.contains(FUTURE_EPISODE))
        assertFalse(selected, selected.contains(ANCILLARY_SPECIAL))
        assertNoWatchedMarkers(selected, shouldContainAncillary = false)

        assertTrue(
            "Synthetic Next Story action did not receive real visible window focus",
            awaitRemoteFocus(NEXT_STORY_LABEL, 8)
        )
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.waitForIdleSync()

        val advanced = awaitStoryStatus(ADVANCED_STABLE_ID, 5_000L)
        assertTrue(advanced, advanced.contains(ORIGINAL_OBJECT_MESSAGE))
        assertTrue(advanced, advanced.contains(NARRATIVE_SPECIAL))
        assertTrue(advanced, advanced.contains(FUTURE_EPISODE))
        assertFalse(advanced, advanced.contains(ANCILLARY_SPECIAL))
        assertNoWatchedMarkers(advanced, shouldContainAncillary = false)
    }

    @Test
    fun testFrameworkClicksSelectStoryOrderAndAdvanceByStableIdOnApi30() {
        val canonical = awaitStoryStatus(CANONICAL_LABEL, 5_000L)
        assertNoWatchedMarkers(canonical, shouldContainAncillary = true)

        var toggleClicked = false
        scenario.onActivity { activity ->
            val toggle = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == CANONICAL_LABEL }
            assertNotNull("Synthetic Story Order selector was not uniquely rendered", toggle)
            assertTrue("Synthetic Story Order selector was not clickable", toggle!!.isClickable)
            toggleClicked = toggle.performClick()
        }
        assertTrue("Story Order framework click was not dispatched", toggleClicked)

        val selected = awaitStoryStatus(STORY_LABEL, 5_000L)
        assertTrue(selected, selected.contains(REGULAR_EPISODE_1))
        assertTrue(selected, selected.contains(NARRATIVE_SPECIAL))
        assertTrue(selected, selected.contains(REGULAR_EPISODE_2))
        assertTrue(selected, selected.contains(FUTURE_EPISODE))
        assertFalse(selected, selected.contains(ANCILLARY_SPECIAL))
        assertNoWatchedMarkers(selected, shouldContainAncillary = false)

        var nextClicked = false
        scenario.onActivity { activity ->
            val next = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == NEXT_STORY_LABEL }
            assertNotNull("Synthetic Next Story action was not uniquely rendered", next)
            assertTrue("Synthetic Next Story action was not clickable", next!!.isClickable)
            nextClicked = next.performClick()
        }
        assertTrue("Next Story framework click was not dispatched", nextClicked)

        val advanced = awaitStoryStatus(ADVANCED_STABLE_ID, 5_000L)
        assertTrue(advanced, advanced.contains(ORIGINAL_OBJECT_MESSAGE))
        assertTrue(advanced, advanced.contains(NARRATIVE_SPECIAL))
        assertTrue(advanced, advanced.contains(FUTURE_EPISODE))
        assertFalse(advanced, advanced.contains(ANCILLARY_SPECIAL))
        assertNoWatchedMarkers(advanced, shouldContainAncillary = false)
    }

    private fun assertNoWatchedMarkers(
        snapshot: String,
        shouldContainAncillary: Boolean
    ) {
        assertFalse(snapshot, snapshot.contains(WATCHED_REGULAR_EPISODE_1))
        assertFalse(snapshot, snapshot.contains(WATCHED_NARRATIVE_SPECIAL))
        assertFalse(snapshot, snapshot.contains(WATCHED_REGULAR_EPISODE_2))
        assertFalse(snapshot, snapshot.contains(WATCHED_FUTURE_EPISODE))
        assertFalse(snapshot, snapshot.contains(WATCHED_ANCILLARY_SPECIAL))
        if (shouldContainAncillary) {
            assertTrue(snapshot, snapshot.contains(ANCILLARY_SPECIAL))
        } else {
            assertFalse(snapshot, snapshot.contains(ANCILLARY_SPECIAL))
        }
    }

    private fun awaitStoryStatus(needle: String, timeoutMs: Long): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var snapshot = ""
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                snapshot = activity.boundedStoryOrderRuntimeStatusForInstrumentation()
                    ?: "STORY_ORDER_RUNTIME_STATUS_UNAVAILABLE"
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

    private fun awaitStableVisibleWindowFocus(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var stableFocusIdentity = 0
        var stableSince = 0L
        while (SystemClock.uptimeMillis() < deadline) {
            var focusedIdentity = 0
            scenario.onActivity { activity ->
                val root = activity.window.decorView
                val focusedView = root.findFocus()
                if (root.isShown && root.hasWindowFocus() &&
                    focusedView?.isShown == true
                ) {
                    focusedIdentity = System.identityHashCode(focusedView)
                }
            }
            val now = SystemClock.uptimeMillis()
            if (focusedIdentity == 0) {
                stableFocusIdentity = 0
                stableSince = 0L
            } else if (focusedIdentity != stableFocusIdentity) {
                stableFocusIdentity = focusedIdentity
                stableSince = now
            } else if (now - stableSince >= STABLE_WINDOW_FOCUS_MILLIS) {
                return true
            }
            SystemClock.sleep(FOCUS_POLL_MILLIS)
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
                SystemClock.sleep(REMOTE_MOVE_SETTLE_MILLIS)
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
        const val CANONICAL_LABEL = "Presentation: Canonical"
        const val STORY_LABEL = "Presentation: Story Order"
        const val NEXT_STORY_LABEL = "Next Story item (local metadata only)"
        const val REGULAR_EPISODE_1 = "local:mobile-watched:1:1"
        const val NARRATIVE_SPECIAL = "local:mobile-watched:0:2"
        const val REGULAR_EPISODE_2 = "local:mobile-watched:1:2"
        const val FUTURE_EPISODE = "local:mobile-watched:1:3"
        const val ANCILLARY_SPECIAL = "local:mobile-watched:0:99"
        const val WATCHED_NARRATIVE_SPECIAL =
            "local:mobile-watched:0:2 [S0 E2] • Watched"
        const val WATCHED_REGULAR_EPISODE_2 =
            "local:mobile-watched:1:2 [S1 E2] • Watched"
        const val WATCHED_REGULAR_EPISODE_1 =
            "local:mobile-watched:1:1 [S1 E1] • Watched"
        const val WATCHED_FUTURE_EPISODE =
            "local:mobile-watched:1:3 [S1 E3] • Watched"
        const val WATCHED_ANCILLARY_SPECIAL =
            "local:mobile-watched:0:99 [S0 E99] • Watched"
        const val ADVANCED_STABLE_ID =
            "Selected stable ID: local:mobile-watched:0:2"
        const val ORIGINAL_OBJECT_MESSAGE =
            "Resolved the original canonical object by stable ID."
        const val REMOTE_MOVE_SETTLE_MILLIS = 50L
        const val FOCUS_POLL_MILLIS = 50L
        const val STABLE_WINDOW_FOCUS_MILLIS = 500L
    }
}
