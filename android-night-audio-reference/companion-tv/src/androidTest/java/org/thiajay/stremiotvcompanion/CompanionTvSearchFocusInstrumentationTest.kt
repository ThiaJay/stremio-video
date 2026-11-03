package org.thiajay.stremiotvcompanion

import android.os.SystemClock
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
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

    @Test
    fun testTypedSubmitStaysLocalAndReportsHeldWithoutEchoOnApi30() {
        assertTrue(
            "Synthetic typed field did not receive real visible window focus",
            awaitInputFocus(64)
        )

        val instrumentation = InstrumentationRegistry.getInstrumentation()
        instrumentation.sendStringSync(BOUNDARY_INPUT)
        instrumentation.waitForIdleSync()
        assertTrue(
            "Synthetic keyboard input was not retained locally",
            awaitInputText(BOUNDARY_INPUT, 5_000L)
        )

        scenario.onActivity { activity ->
            val input = findViews(activity.window.decorView)
                .filterIsInstance<EditText>()
                .singleOrNull {
                    it.contentDescription?.toString() ==
                        CompanionTvActivity.SYNTHETIC_SEARCH_INPUT_DESCRIPTION
                }
            assertNotNull("Synthetic typed field was not uniquely rendered", input)
            assertTrue(
                "Synthetic typed field did not expose the explicit Search action",
                (input!!.imeOptions and EditorInfo.IME_MASK_ACTION) ==
                    EditorInfo.IME_ACTION_SEARCH
            )
            input.onEditorAction(EditorInfo.IME_ACTION_SEARCH)
        }
        instrumentation.waitForIdleSync()

        val status = awaitSearchStatus(HELD_REASON, 5_000L)
        assertTrue(status, status.contains(HELD_REASON))
        assertTrue(status, status.contains("Retained ${BOUNDARY_INPUT.length} typed characters"))
        assertTrue(status, !status.contains(BOUNDARY_INPUT))
        scenario.onActivity { activity ->
            val input = findViews(activity.window.decorView)
                .filterIsInstance<EditText>()
                .singleOrNull {
                    it.contentDescription?.toString() ==
                        CompanionTvActivity.SYNTHETIC_SEARCH_INPUT_DESCRIPTION
                }
            assertNotNull("Synthetic typed field was not uniquely rendered", input)
            assertTrue(
                "Held submission must preserve local typed input",
                input!!.text.toString() == BOUNDARY_INPUT
            )
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

    private fun awaitInputText(expected: String, timeoutMs: Long): Boolean {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        while (SystemClock.uptimeMillis() < deadline) {
            var retained = false
            scenario.onActivity { activity ->
                val input = findViews(activity.window.decorView)
                    .filterIsInstance<EditText>()
                    .singleOrNull {
                        it.contentDescription?.toString() ==
                            CompanionTvActivity.SYNTHETIC_SEARCH_INPUT_DESCRIPTION
                    }
                input?.let { candidate ->
                    retained = candidate.isShown && candidate.hasWindowFocus() &&
                        candidate.hasFocus() && candidate.text.toString() == expected
                }
            }
            if (retained) return true
            SystemClock.sleep(100L)
        }
        return false
    }

    private fun awaitSearchStatus(needle: String, timeoutMs: Long): String {
        val deadline = SystemClock.uptimeMillis() + timeoutMs
        var snapshot = ""
        while (SystemClock.uptimeMillis() < deadline) {
            scenario.onActivity { activity ->
                snapshot = activity.boundedSyntheticSearchRuntimeStatusForInstrumentation()
                    ?: "SYNTHETIC_SEARCH_RUNTIME_STATUS_UNAVAILABLE"
            }
            if (snapshot.contains(needle)) return snapshot
            SystemClock.sleep(100L)
        }
        return snapshot
    }

    private fun findViews(root: View): List<View> = buildList {
        add(root)
        if (root is ViewGroup) {
            for (index in 0 until root.childCount) addAll(findViews(root.getChildAt(index)))
        }
    }

    private companion object {
        const val BOUNDARY_INPUT = "localquery42"
        const val HELD_REASON = "PRESERVE_TYPED_SEARCH: STREMIO_SEARCH_NOT_ACTIVE"
    }
}
