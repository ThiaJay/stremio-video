package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSubtitlePreferenceChoiceWidgetControllerTest {
    @Test fun widgetControllerBuildsTwoSemanticChoicesWithoutConsent() {
        val controller = AndroidSubtitlePreferenceChoiceWidgetController()
        val widgets = controller.replace(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        )!!

        assertEquals(2, widgets.size)
        assertEquals(setOf(
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED,
            AndroidSubtitlePreferenceChoiceSession.Choice.OFF
        ), widgets.map { it.choice }.toSet())
        assertTrue(widgets.all { it.label.isNotBlank() })
        assertTrue(controller.close())
    }

    @Test fun semanticClickReturnsExactIntentAndRevokesGeneration() {
        val controller = AndroidSubtitlePreferenceChoiceWidgetController()
        val widgets = controller.replace(
            AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS
        )!!
        val enabled = widgets.first {
            it.choice == AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED
        }

        val intent = controller.consume(
            enabled,
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        )
        assertNotNull(intent)
        assertTrue(controller.accepts(intent!!))
        assertFalse(controller.accepts(intent))
        assertEquals(AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS, intent.surface)
        assertNull(controller.consume(
            widgets.last(),
            widgets.last().choice,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        ))
        assertTrue(controller.close())
    }

    @Test fun rawPointerOrLongPressNeverAuthorizesAndRevokesGeneration() {
        for (event in listOf("POINTER", "LONG_PRESS")) {
            val controller = AndroidSubtitlePreferenceChoiceWidgetController()
            val widget = controller.replace(
                AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
            )!!.first()

            assertNull(controller.consume(widget, widget.choice, event))
            assertNull(controller.consume(
                widget,
                widget.choice,
                AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
            ))
            assertTrue(controller.close())
        }
    }

    @Test fun selectedChoiceMismatchNeverAuthorizesAndRevokesGeneration() {
        val controller = AndroidSubtitlePreferenceChoiceWidgetController()
        val widget = controller.replace(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        )!!.first()

        assertNull(controller.consume(
            widget,
            AndroidSubtitlePreferenceChoiceSession.Choice.OFF,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        ))
        assertNull(controller.consume(
            widget,
            widget.choice,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        ))
        assertTrue(controller.close())
    }

    @Test fun forgedOrCrossControllerWidgetCannotAuthorize() {
        val first = AndroidSubtitlePreferenceChoiceWidgetController()
        val second = AndroidSubtitlePreferenceChoiceWidgetController()
        val firstWidget = first.replace(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        )!!.first()
        val secondWidget = second.replace(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        )!!.first()
        val forged = AndroidSubtitlePreferenceChoiceWidget(
            firstWidget.surface,
            firstWidget.choice,
            firstWidget.label,
            Any()
        )

        assertNull(first.consume(
            forged,
            forged.choice,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        ))
        assertNull(second.consume(
            firstWidget,
            firstWidget.choice,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        ))
        assertNull(second.consume(
            secondWidget,
            secondWidget.choice,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        ))
        assertTrue(first.close())
        assertTrue(second.close())
    }

    @Test fun replacementMakesEarlierWidgetStale() {
        val controller = AndroidSubtitlePreferenceChoiceWidgetController()
        val stale = controller.replace(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        )!!.first()
        val current = controller.replace(
            AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS
        )!!.first()

        assertNull(controller.consume(
            stale,
            stale.choice,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        ))
        assertNull(controller.consume(
            current,
            current.choice,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        ))
        assertTrue(controller.close())
    }

    @Test fun nullSurfaceClearAndCloseFailClosed() {
        val controller = AndroidSubtitlePreferenceChoiceWidgetController()
        assertNull(controller.replace(null))
        val widget = controller.replace(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        )!!.first()
        assertTrue(controller.clear())
        assertNull(controller.consume(
            widget,
            widget.choice,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        ))
        assertTrue(controller.close())
        assertFalse(controller.clear())
        assertFalse(controller.close())
    }

    @Test fun foreignThreadCannotRenderConsumeClearOrClose() {
        val controller = AndroidSubtitlePreferenceChoiceWidgetController()
        val widget = controller.replace(
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        )!!.first()
        val replacement = AtomicReference<List<AndroidSubtitlePreferenceChoiceWidget>?>()
        val consumed = AtomicReference<AndroidSubtitlePreferenceChoiceWidgetIntent?>()
        val cleared = AtomicBoolean(true)
        val closed = AtomicBoolean(true)

        Thread {
            replacement.set(controller.replace(
                AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS
            ))
            consumed.set(controller.consume(
                widget,
                widget.choice,
                AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
            ))
            cleared.set(controller.clear())
            closed.set(controller.close())
        }.apply { start(); join() }

        assertNull(replacement.get())
        assertNull(consumed.get())
        assertFalse(cleared.get())
        assertFalse(closed.get())
        assertNotNull(controller.consume(
            widget,
            widget.choice,
            AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
        ))
        assertTrue(controller.close())
    }
}
