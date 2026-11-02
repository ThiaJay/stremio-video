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
 * Android TV focus traversal and DPAD_CENTER on API 30. All metadata is local and
 * no catalogue, player, account, network or Stremio state is available here.
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
        scenario.onActivity { activity ->
            val toggle = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull { it.text.toString() == CANONICAL_LABEL }
            assertNotNull("Synthetic Story Order selector was not uniquely rendered", toggle)
        }
        assertTrue(
            "Synthetic Story Order window did not become visibly focused",
            awaitVisibleWindowFocus(5_000L)
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
        assertFalse(selected, selected.contains(ANCILLARY_SPECIAL))

        assertTrue(
            "Synthetic Next Story action did not receive real visible window focus",
            awaitRemoteFocus(NEXT_STORY_LABEL, 8)
        )
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_CENTER)
        instrumentation.waitForIdleSync()

        val advanced = awaitStoryStatus(ADVANCED_STABLE_ID, 5_000L)
        assertTrue(advanced, advanced.contains(ORIGINAL_OBJECT_MESSAGE))
        assertTrue(advanced, advanced.contains(NARRATIVE_SPECIAL))
        assertFalse(advanced, advanced.contains(ANCILLARY_SPECIAL))
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
        const val REGULAR_EPISODE_1 = "local:story:1:1"
        const val NARRATIVE_SPECIAL = "local:story:0:2"
        const val REGULAR_EPISODE_2 = "local:story:1:2"
        const val ANCILLARY_SPECIAL = "local:story:0:99"
        const val ADVANCED_STABLE_ID = "Selected stable ID: local:story:0:2"
        const val ORIGINAL_OBJECT_MESSAGE =
            "Resolved the original canonical object by stable ID."
    }
}
