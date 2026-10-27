package org.pss.stremio.tv

import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

class NativeMobileTitleWatchedWidgetControllerTest {
    private fun action(
        id: String,
        watched: Boolean = true
    ): NativeMobileTitleWatchedViewAction {
        val tvAction = NativeTvTitleWatchedAction(
            actionId = id,
            label = "Label $id",
            confirmation = "Confirm $id",
            scope = WatchedScope.Series,
            watched = watched,
            provenanceSeal = Any()
        )
        val mobileAction = NativeMobileTitleWatchedAction(
            actionId = id,
            label = "Label $id",
            confirmation = "Confirm $id",
            scope = WatchedScope.Series,
            watched = watched,
            delegateAction = tvAction,
            provenanceSeal = Any()
        )
        return NativeMobileTitleWatchedViewAction(
            actionId = id,
            label = "Label $id",
            confirmation = "Confirm $id",
            scope = WatchedScope.Series,
            watched = watched,
            mobileAction = mobileAction,
            provenanceSeal = Any()
        )
    }

    @Test fun mobileWidgetControllerBuildsDisplayModelsWithoutActivation() {
        val controller = NativeMobileTitleWatchedWidgetController()
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

    @Test fun semanticMobileClickReturnsExactIntentAndRevokesGeneration() {
        val controller = NativeMobileTitleWatchedWidgetController()
        val action = action("series-watched")
        val widget = controller.replace(listOf(action))!!.single()
        val intent = controller.consume(
            widget,
            widget.actionId,
            NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
        )

        assertSame(action, intent!!.action)
        assertEquals(widget.actionId, intent.selectedActionId)
        assertNull(controller.consume(
            widget,
            widget.actionId,
            NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
        ))
    }

    @Test fun nonSemanticMobileEventNeverActivatesAndRevokesGeneration() {
        val controller = NativeMobileTitleWatchedWidgetController()
        val widget = controller.replace(listOf(action("series-watched")))!!.single()

        assertNull(controller.consume(widget, widget.actionId, "RAW_POINTER"))
        assertNull(controller.consume(
            widget,
            widget.actionId,
            NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
        ))
    }

    @Test fun mobileSelectionMismatchRevokesWidgetGeneration() {
        val controller = NativeMobileTitleWatchedWidgetController()
        val widget = controller.replace(listOf(action("series-watched")))!!.single()

        assertNull(controller.consume(
            widget,
            "other",
            NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
        ))
        assertNull(controller.consume(
            widget,
            widget.actionId,
            NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
        ))
    }

    @Test fun staleAndForgedMobileWidgetObjectsCannotActivate() {
        val controller = NativeMobileTitleWatchedWidgetController()
        val action = action("series-watched")
        val stale = controller.replace(listOf(action))!!.single()
        controller.replace(listOf(action("series-watched")))
        assertNull(controller.consume(
            stale,
            stale.actionId,
            NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
        ))

        val current = controller.replace(listOf(action))!!.single()
        val forged = NativeMobileTitleWatchedWidget(
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
            NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
        ))
    }

    @Test fun malformedMobileWidgetGenerationFailsClosed() {
        val controller = NativeMobileTitleWatchedWidgetController()
        assertNull(controller.replace(emptyList()))
        assertNull(controller.replace(listOf(action("duplicate"), action("duplicate"))))
        assertNull(controller.replace(List(5) { action("action-$it") }))
    }

    @Test fun mobileWidgetPreservesDestructiveAcknowledgementMetadata() {
        val controller = NativeMobileTitleWatchedWidgetController()
        val watched = controller.replace(listOf(action("watched")))!!.single()
        assertFalse(watched.requiresAcknowledgement)
        val unwatched = controller.replace(listOf(action("unwatched", watched = false)))!!.single()
        assertTrue(unwatched.requiresAcknowledgement)
    }

    @Test fun mobileWidgetOwnerThreadClearAndCloseFailClosed() {
        val controller = NativeMobileTitleWatchedWidgetController()
        val widget = controller.replace(listOf(action("series-watched")))!!.single()
        val foreign = AtomicReference<NativeMobileTitleWatchedWidgetIntent?>()
        val thread = Thread {
            foreign.set(controller.consume(
                widget,
                widget.actionId,
                NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
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
}
