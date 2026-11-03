package org.thiajay.stremiotvcompanion

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.After
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Verifies that the process-local typed field remains reachable without trapping
 * Android TV DPAD navigation. No speech, microphone, network, account, catalogue
 * or production Stremio surface is available to this test.
 */
@RunWith(AndroidJUnit4::class)
class CompanionTvSearchFocusInstrumentationTest {
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
    fun testDpadDownEscapesSyntheticTypedFieldToLocalSubmitOnApi30() {
        assertTrue(
            "Synthetic typed field did not receive real visible window focus",
            awaitInputFocus(64)
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_DOWN)
        instrumentation.waitForIdleSync()

        assertTrue(
            "Synthetic typed field trapped DPAD_DOWN instead of reaching local submit",
            awaitSubmitFocus(5_000L)
        )
        scenario.onActivity { activity ->
            val input = findViews(activity.window.decorView)
                .filterIsInstance<EditText>()
                .singleOrNull {
                    it.contentDescription?.toString() ==
                        CompanionTvActivity.SYNTHETIC_SEARCH_INPUT_DESCRIPTION
                }
            val submit = findViews(activity.window.decorView)
                .filterIsInstance<Button>()
                .singleOrNull {
                    it.text.toString() == CompanionTvActivity.SYNTHETIC_SEARCH_BUTTON_LABEL
                }
            assertNotNull("Synthetic typed field was not uniquely rendered", input)
            assertNotNull("Synthetic local submit was not uniquely rendered", submit)
            assertTrue("Synthetic typed field must remain process-local and empty", input!!.text.isEmpty())
        }
    }

    private fun awaitInputFocus(maximumMoves: Int): Boolean {
        val instrumentation = InstrumentationRegistry.getInstrumentation()
        repeat(maximumMoves + 1) { move ->
            var focused = false
            scenario.onActivity { activity ->
                val input = findViews(activity.window.decorView)
                    .filterIsInstance<EditText>()
                    .singleOrNull {
                        it.contentDescription?.toString() ==
                            CompanionTvActivity.SYNTHETIC_SEARCH_INPUT_DESCRIPTION
                    }
                input?.let { candidate ->
                    focused = candidate.isShown && candidate.hasWindowFocus() &&
                        candidate.hasFocus()
                }
            }
            if (focused) return true
            if (move < maximumMoves) {
                instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_DPAD_UP)
                instrumentation.waitForIdleSync()
            }
        }
        return false
    }

    private fun awaitSubmitFocus(timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var focused = false
            scenario.onActivity { activity ->
                val submit = findViews(activity.window.decorView)
                    .filterIsInstance<Button>()
                    .singleOrNull {
                        it.text.toString() == CompanionTvActivity.SYNTHETIC_SEARCH_BUTTON_LABEL
                    }
                submit?.let { candidate ->
                    focused = candidate.isShown && candidate.hasWindowFocus() &&
                        candidate.hasFocus()
                }
            }
            if (focused) return true
            SystemClock.sleep(100L)
        }
        return false
    }

    private fun findViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(findViews(root.getChildAt(index)))
        }
    }
}
