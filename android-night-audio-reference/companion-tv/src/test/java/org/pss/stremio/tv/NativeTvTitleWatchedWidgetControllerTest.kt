package org.pss.stremio.tv

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NativeTvTitleWatchedWidgetControllerTest {
    private fun action(
        id: String,
        watched: Boolean = true,
        focusable: Boolean = true
    ): NativeTvTitleWatchedViewAction {
        val menu = NativeTvTitleWatchedAction(
            actionId = id,
            label = "Label $id",
            confirmation = "Confirm $id",
            scope = WatchedScope.Series,
            watched = watched,
            provenanceSeal = Any()
        )
        return NativeTvTitleWatchedViewAction(
            actionId = id,
            label = "Label $id",
            confirmation = "Confirm $id",
            scope = WatchedScope.Series,
            watched = watched,
            focusable = focusable,
            menuAction = menu,
            provenanceSeal = Any()
        )
    }

    @Test fun widgetControllerBuildsFocusableModelsWithoutActivation() {
        val controller = NativeTvTitleWatchedWidgetController()
        val actions = listOf(
            action("series-watched"),
            action("series-unwatched", watched = false),
            action("season-watched"),
            action("season-unwatched", watched = false)
        )
        val widgets = controller.replace(actions)!!

        assertEquals(4, widgets.size)
        assertEquals(actions.map { it.actionId }, widgets.map { it.actionId })
        assertEquals(2, widgets.count { it.requiresAcknowledgement })
    }

    @Test fun actionUpEnterReturnsExactIntentAndRevokesWidgetGeneration() {
        val controller = NativeTvTitleWatchedWidgetController()
        val action = action("series-watched")
        val widget = controller.replace(listOf(action))!!.single()
        val intent = controller.consume(
            widget,
            widget.actionId,
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        )

        assertSame(action, intent!!.action)
        assertEquals(widget.actionId, intent.focusedActionId)
        assertNull(controller.consume(
            widget,
            widget.actionId,
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        ))
    }

    @Test fun actionUpDpadCenterReturnsExactIntent() {
        val controller = NativeTvTitleWatchedWidgetController()
        val action = action("series-watched")
        val widget = controller.replace(listOf(action))!!.single()

        val intent = controller.consume(
            widget,
            widget.actionId,
            NativeTvTitleWatchedWidgetController.KEY_DPAD_CENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        )
        assertSame(action, intent!!.action)
        assertEquals(NativeTvTitleWatchedWidgetController.KEY_DPAD_CENTER, intent.remoteKey)
    }

    @Test fun keyDownDoesNotConsumeWidgetGeneration() {
        val controller = NativeTvTitleWatchedWidgetController()
        val widget = controller.replace(listOf(action("series-watched")))!!.single()

        assertNull(controller.consume(
            widget,
            widget.actionId,
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            "DOWN"
        ))
        assertNotNull(controller.consume(
            widget,
            widget.actionId,
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        ))
    }

    @Test fun focusMismatchAndOtherRemoteKeyRevokeWidgetGeneration() {
        val controller = NativeTvTitleWatchedWidgetController()
        var widget = controller.replace(listOf(action("series-watched")))!!.single()
        assertNull(controller.consume(
            widget,
            "other",
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        ))
        assertNull(controller.consume(
            widget,
            widget.actionId,
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        ))

        widget = controller.replace(listOf(action("series-watched")))!!.single()
        assertNull(controller.consume(
            widget,
            widget.actionId,
            "BACK",
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        ))
    }

    @Test fun staleAndForgedWidgetObjectsCannotActivate() {
        val controller = NativeTvTitleWatchedWidgetController()
        val action = action("series-watched")
        val stale = controller.replace(listOf(action))!!.single()
        controller.replace(listOf(action("series-watched")))
        assertNull(controller.consume(
            stale,
            stale.actionId,
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        ))

        val current = controller.replace(listOf(action))!!.single()
        val forged = NativeTvTitleWatchedWidget(
            current.actionId,
            current.label,
            current.confirmation,
            current.watched,
            current.requiresAcknowledgement,
            action,
            Any()
        )
        assertNull(controller.consume(
            forged,
            forged.actionId,
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        ))
    }

    @Test fun malformedOrNonFocusableWidgetGenerationFailsClosed() {
        val controller = NativeTvTitleWatchedWidgetController()
        assertNull(controller.replace(emptyList()))
        assertNull(controller.replace(listOf(action("duplicate"), action("duplicate"))))
        assertNull(controller.replace(listOf(action("not-focusable", focusable = false))))
        assertNull(controller.replace(List(5) { action("action-$it") }))
    }

    @Test fun widgetOwnerThreadClearAndCloseFailClosed() {
        val controller = NativeTvTitleWatchedWidgetController()
        val widget = controller.replace(listOf(action("series-watched")))!!.single()
        val foreign = AtomicReference<NativeTvTitleWatchedWidgetIntent?>()
        val thread = Thread {
            foreign.set(controller.consume(
                widget,
                widget.actionId,
                NativeTvTitleWatchedWidgetController.KEY_ENTER,
                NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
            ))
        }
        thread.start()
        thread.join()

        assertNull(foreign.get())
        assertTrue(controller.clear())
        assertTrue(controller.close())
        assertNull(controller.replace(listOf(action("new"))))
        assertFalse(controller.clear())
        assertFalse(controller.close())
    }

    @Test fun destructiveConfirmationLeaseReturnsExactIntentOnce() {
        val controller = NativeTvTitleWatchedWidgetController()
        val widget = controller.replace(
            listOf(action("series-unwatched", watched = false))
        )!!.single()
        val intent = controller.consume(
            widget,
            widget.actionId,
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        )!!
        val confirmation = controller.beginConfirmation(intent)!!

        val result = controller.resolveConfirmation(confirmation, true)!!
        assertSame(intent, result.intent)
        assertTrue(result.acknowledged)
        assertNull(controller.resolveConfirmation(confirmation, true))
        assertNull(controller.beginConfirmation(intent))
    }

    @Test fun replacementInvalidatesStaleConfirmationWithoutRevokingCurrentLease() {
        val controller = NativeTvTitleWatchedWidgetController()
        val oldWidget = controller.replace(
            listOf(action("old-unwatched", watched = false))
        )!!.single()
        val oldIntent = controller.consume(
            oldWidget,
            oldWidget.actionId,
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        )!!
        val oldConfirmation = controller.beginConfirmation(oldIntent)!!

        val currentWidget = controller.replace(
            listOf(action("current-unwatched", watched = false))
        )!!.single()
        val currentIntent = controller.consume(
            currentWidget,
            currentWidget.actionId,
            NativeTvTitleWatchedWidgetController.KEY_DPAD_CENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        )!!
        val currentConfirmation = controller.beginConfirmation(currentIntent)!!

        assertNull(controller.resolveConfirmation(oldConfirmation, true))
        val currentResult = controller.resolveConfirmation(currentConfirmation, false)!!
        assertSame(currentIntent, currentResult.intent)
        assertFalse(currentResult.acknowledged)
    }

    @Test fun forgedAndForeignConfirmationCallbacksPreserveOwnerLease() {
        val controller = NativeTvTitleWatchedWidgetController()
        val widget = controller.replace(
            listOf(action("series-unwatched", watched = false))
        )!!.single()
        val intent = controller.consume(
            widget,
            widget.actionId,
            NativeTvTitleWatchedWidgetController.KEY_ENTER,
            NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
        )!!
        val confirmation = controller.beginConfirmation(intent)!!
        val forged = NativeTvTitleWatchedConfirmation(intent, Any())
        assertNull(controller.resolveConfirmation(forged, true))

        val foreignResolution =
            AtomicReference<NativeTvTitleWatchedConfirmationResult?>()
        val foreignInvalidation = AtomicReference<Boolean>()
        val thread = Thread {
            foreignResolution.set(controller.resolveConfirmation(confirmation, true))
            foreignInvalidation.set(controller.invalidateConfirmation())
        }
        thread.start()
        thread.join()

        assertNull(foreignResolution.get())
        assertFalse(foreignInvalidation.get())
        assertNotNull(controller.resolveConfirmation(confirmation, false))
    }

    @Test fun clearAndCloseRevokePendingConfirmations() {
        val controller = NativeTvTitleWatchedWidgetController()
        fun pending(id: String): NativeTvTitleWatchedConfirmation {
            val widget = controller.replace(listOf(action(id, watched = false)))!!.single()
            val intent = controller.consume(
                widget,
                widget.actionId,
                NativeTvTitleWatchedWidgetController.KEY_ENTER,
                NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
            )!!
            return controller.beginConfirmation(intent)!!
        }

        val cleared = pending("clear-unwatched")
        assertTrue(controller.clear())
        assertNull(controller.resolveConfirmation(cleared, true))

        val closed = pending("close-unwatched")
        assertTrue(controller.close())
        assertNull(controller.resolveConfirmation(closed, true))
        assertFalse(controller.invalidateConfirmation())
    }
}
