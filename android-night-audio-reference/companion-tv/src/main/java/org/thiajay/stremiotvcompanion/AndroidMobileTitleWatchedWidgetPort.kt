package org.thiajay.stremiotvcompanion

import android.app.AlertDialog
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.pss.stremio.tv.NativeMobileTitleWatchedMenu
import org.pss.stremio.tv.NativeMobileTitleWatchedViewAction
import org.pss.stremio.tv.NativeMobileTitleWatchedViewPort
import org.pss.stremio.tv.NativeMobileTitleWatchedWidget
import org.pss.stremio.tv.NativeMobileTitleWatchedWidgetController
import org.pss.stremio.tv.NativeMobileTitleWatchedWidgetIntent

/**
 * Actual Android mobile Button implementation of the title-action view port.
 *
 * Android framework click, accessibility click and keyboard activation all
 * arrive through performClick/setOnClickListener as one semantic ACTIVATE
 * event. Raw touch coordinates are never accepted and long-press is consumed
 * without activation. The synthetic companion deliberately never instantiates
 * this adapter.
 */
class AndroidMobileTitleWatchedWidgetPort(
    private val container: ViewGroup,
    private val onActivation: (
        action: NativeMobileTitleWatchedViewAction,
        selectedActionId: String,
        semanticEvent: String,
        unwatchAcknowledged: Boolean
    ) -> Unit
) : NativeMobileTitleWatchedViewPort {
    private val ownerThread = Thread.currentThread()
    private val controller = NativeMobileTitleWatchedWidgetController()
    private val ownedViews = ArrayList<View>()
    private var activeConfirmationDialog: AlertDialog? = null
    private var closed = false

    override fun replaceTitleActions(
        actions: List<NativeMobileTitleWatchedViewAction>
    ): Boolean {
        if (!onMainOwnerThread() || closed) return false
        if (!controller.invalidateConfirmation() || !dismissActiveConfirmation()) {
            return poison()
        }
        val widgets = controller.replace(actions) ?: return false
        val candidateViews = try {
            widgets.map { widget -> createButton(widget) }
        } catch (_: Exception) {
            controller.clear()
            return false
        } catch (_: LinkageError) {
            controller.clear()
            return false
        }

        if (!removeOwnedViews()) return poison()
        return try {
            for (view in candidateViews) {
                container.addView(view)
                ownedViews.add(view)
            }
            true
        } catch (_: Exception) {
            poison()
        } catch (_: LinkageError) {
            poison()
        }
    }

    override fun clearTitleActions(): Boolean {
        if (!onMainOwnerThread() || closed) return false
        val confirmationInvalidated = controller.invalidateConfirmation()
        val confirmationDismissed = dismissActiveConfirmation()
        val controllerCleared = controller.clear()
        val viewsCleared = removeOwnedViews()
        if (!confirmationInvalidated || !confirmationDismissed ||
            !controllerCleared || !viewsCleared
        ) return poison()
        return true
    }

    private fun createButton(widget: NativeMobileTitleWatchedWidget): Button =
        Button(container.context).apply {
            id = View.generateViewId()
            text = widget.label
            contentDescription = widget.label
            isAllCaps = false
            isFocusable = true
            isClickable = true
            setOnLongClickListener { true }
            setOnClickListener {
                if (!onMainOwnerThread() || closed) return@setOnClickListener
                val intent = controller.consume(
                    widget = widget,
                    selectedActionId = widget.actionId,
                    semanticEvent = NativeMobileTitleWatchedMenu.SEMANTIC_ACTIVATION
                ) ?: return@setOnClickListener

                if (!removeOwnedViews()) {
                    poison()
                    return@setOnClickListener
                }
                if (widget.requiresAcknowledgement) {
                    showUnwatchConfirmation(widget, intent)
                } else {
                    deliver(
                        intent.action,
                        intent.selectedActionId,
                        intent.semanticEvent,
                        false
                    )
                }
            }
        }

    private fun showUnwatchConfirmation(
        widget: NativeMobileTitleWatchedWidget,
        intent: NativeMobileTitleWatchedWidgetIntent
    ) {
        val confirmation = controller.beginConfirmation(intent) ?: run {
            poison()
            return
        }
        var resolved = false
        fun resolve(acknowledged: Boolean) {
            if (resolved) return
            resolved = true
            val result = controller.resolveConfirmation(
                confirmation,
                acknowledged
            ) ?: return
            activeConfirmationDialog = null
            deliver(
                result.intent.action,
                result.intent.selectedActionId,
                result.intent.semanticEvent,
                result.acknowledged
            )
        }
        try {
            val dialog = AlertDialog.Builder(container.context)
                .setTitle(widget.label)
                .setMessage(widget.confirmation)
                .setPositiveButton("Confirm") { _, _ -> resolve(true) }
                .setNegativeButton("Cancel") { _, _ -> resolve(false) }
                .setOnCancelListener { resolve(false) }
                .create()
            activeConfirmationDialog = dialog
            dialog.show()
        } catch (_: Exception) {
            activeConfirmationDialog = null
            resolve(false)
        } catch (_: LinkageError) {
            activeConfirmationDialog = null
            resolve(false)
        }
    }

    private fun deliver(
        action: NativeMobileTitleWatchedViewAction,
        selectedActionId: String,
        semanticEvent: String,
        acknowledged: Boolean
    ) {
        try {
            onActivation(action, selectedActionId, semanticEvent, acknowledged)
        } catch (_: Exception) {
            poison()
        } catch (_: LinkageError) {
            poison()
        }
    }

    private fun removeOwnedViews(): Boolean = try {
        for (view in ownedViews) container.removeView(view)
        ownedViews.clear()
        true
    } catch (_: Exception) {
        false
    } catch (_: LinkageError) {
        false
    }

    private fun dismissActiveConfirmation(): Boolean {
        val dialog = activeConfirmationDialog ?: return true
        activeConfirmationDialog = null
        return try {
            dialog.dismiss()
            true
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun poison(): Boolean {
        if (!closed) {
            closed = true
            controller.close()
        }
        dismissActiveConfirmation()
        removeOwnedViews()
        return false
    }

    private fun onMainOwnerThread(): Boolean =
        Thread.currentThread() === ownerThread &&
            Looper.myLooper() === Looper.getMainLooper()
}
