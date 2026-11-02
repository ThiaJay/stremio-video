package org.thiajay.stremiotvcompanion

import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.TextView
import androidx.test.core.app.ActivityScenario
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
 * Exercises the isolated Android mobile Story Order launcher through its actual
 * framework Button callbacks on API 30. Every episode is synthetic local state;
 * no catalogue, player, account, network or production Stremio state is present.
 */
@RunWith(AndroidJUnit4::class)
class MobileStoryOrderInstrumentationTest {
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
    fun testFrameworkClickSwitchesMobileStoryOrderAndSelectsOriginalStableIdOnApi30() {
        assertStoryRows(shouldContainAncillary = false)
        clickUniqueButton(SHOW_SEASON_ORDER)
        assertStoryRows(shouldContainAncillary = true)
        clickUniqueButton(SHOW_STORY_ORDER)
        assertStoryRows(shouldContainAncillary = false)

        clickUniqueButton(NARRATIVE_SPECIAL_LABEL)
        val selected = awaitText(SELECTED_STABLE_ID, 5_000L)
        assertTrue(selected, selected.contains(ORIGINAL_OBJECT_MESSAGE))
        assertFalse(selected, selected.contains(ANCILLARY_STABLE_ID))
    }

    private fun assertStoryRows(shouldContainAncillary: Boolean) {
        scenario.onActivity { activity ->
            val labels = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .map { it.text.toString() }
            assertTrue(labels.toString(), labels.any { it.contains(REGULAR_EPISODE_1) })
            assertTrue(labels.toString(), labels.any { it.contains(NARRATIVE_SPECIAL_TITLE) })
            assertTrue(labels.toString(), labels.any { it.contains(REGULAR_EPISODE_2) })
            if (shouldContainAncillary) {
                assertTrue(labels.toString(), labels.any { it.contains(ANCILLARY_TITLE) })
            } else {
                assertFalse(labels.toString(), labels.any { it.contains(ANCILLARY_TITLE) })
            }
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
            assertTrue("Synthetic mobile button was not enabled", exactButton.isEnabled)
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
        fail("Timed out waiting for '$needle'. Final bounded UI text: " +
            snapshot.replace('\n', ' '))
        return snapshot
    }

    private fun findViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(findViews(root.getChildAt(index)))
        }
    }

    private companion object {
        const val SHOW_SEASON_ORDER = "Show season order"
        const val SHOW_STORY_ORDER = "Show story order"
        const val REGULAR_EPISODE_1 = "S1 E1  Episode one"
        const val NARRATIVE_SPECIAL_TITLE = "Narrative special"
        const val NARRATIVE_SPECIAL_LABEL = "S0 E2  Narrative special"
        const val REGULAR_EPISODE_2 = "S1 E2  Episode two"
        const val ANCILLARY_TITLE = "Ancillary extra"
        const val SELECTED_STABLE_ID =
            "Selected exact synthetic episode: local:mobile-story:0:2"
        const val ORIGINAL_OBJECT_MESSAGE =
            "Selection resolved the original synthetic canonical object by stable ID."
        const val ANCILLARY_STABLE_ID = "local:mobile-story:0:99"
    }
}
