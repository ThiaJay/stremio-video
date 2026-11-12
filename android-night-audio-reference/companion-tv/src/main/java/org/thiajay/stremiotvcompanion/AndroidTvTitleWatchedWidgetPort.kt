package org.thiajay.stremiotvcompanion

import android.app.AlertDialog
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.pss.stremio.tv.NativeTvTitleWatchedViewAction
import org.pss.stremio.tv.NativeTvTitleWatchedViewPort
import org.pss.stremio.tv.NativeTvTitleWatchedWidgetIntent
import org.pss.stremio.tv.NativeTvTitleWatchedWidget
import org.pss.stremio.tv.NativeTvTitleWatchedWidgetController

/**
 * Actual Android TV focusable-button implementation of the title-action view
 * port. The disclosed side-by-side synthetic TV launcher instantiates it only
 * against local in-memory state; private and official clients remain unwired.
 *
 * Remote ENTER/DPAD_CENTER key-up keeps its real-focus gate. Android framework
 * click, accessibility click and keyboard click are also accepted through
 * performClick/setOnClickListener and normalised to the existing ENTER
 * confirmation path. Raw coordinates and long press are never activation.
 *
 * This adapter owns only Android Views and confirmation presentation. It has no
 * account/Core/network surface. The host callback must pass the exact action
 * back to NativeTvTitleWatchedHostBinding on the same main thread.
 */
class AndroidTvTitleWatchedWidgetPort(
    private val container: ViewGroup,
    private val onActivation: (
        action: NativeTvTitleWatchedViewAction,
        focusedActionId: String,
        remoteKey: String,
        unwatchAcknowledged: Boolean
    ) -> Unit
) : NativeTvTitleWatchedViewPort {
    private val ownerThread = Thread.currentThread()
    private val controller = NativeTvTitleWatchedWidgetController()
    private val ownedViews = ArrayList<View>()
    private var activeConfirmationDialog: AlertDialog? = null
    private var closed = false

    override fun replaceTitleActions(
        actions: List<NativeTvTitleWatchedViewAction>
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

    private fun createButton(widget: NativeTvTitleWatchedWidget): Button =
        Button(container.context).apply {
            id = View.generateViewId()
            text = widget.label
            contentDescription = widget.label
            isAllCaps = false
            isFocusable = true
            isFocusableInTouchMode = false
            isClickable = true
            setOnLongClickListener { true }
            setOnClickListener {
                activateWidget(
                    widget,
                    NativeTvTitleWatchedWidgetController.KEY_ENTER
                )
            }
            setOnKeyListener { view, keyCode, event ->
                if (!onMainOwnerThread() || closed || !view.hasFocus()) {
                    return@setOnKeyListener false
                }
                val remoteKey = when (keyCode) {
                    KeyEvent.KEYCODE_ENTER -> NativeTvTitleWatchedWidgetController.KEY_ENTER
                    KeyEvent.KEYCODE_DPAD_CENTER ->
                        NativeTvTitleWatchedWidgetController.KEY_DPAD_CENTER
                    else -> return@setOnKeyListener false
                }
                val keyAction = if (event.action == KeyEvent.ACTION_UP) {
                    NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
                } else {
                    "OTHER"
                }
                activateWidget(widget, remoteKey, keyAction)
            }
        }

    private fun activateWidget(
        widget: NativeTvTitleWatchedWidget,
        activationKey: String,
        keyAction: String = NativeTvTitleWatchedWidgetController.KEY_ACTION_UP
    ): Boolean {
        if (!onMainOwnerThread() || closed) return false
        val intent = controller.consume(
            widget = widget,
            focusedActionId = widget.actionId,
            remoteKey = activationKey,
            keyAction = keyAction
        ) ?: return false

        if (!removeOwnedViews()) {
            poison()
            return true
        }
        if (widget.requiresAcknowledgement) {
            showUnwatchConfirmation(widget, intent)
        } else {
            deliver(intent.action, intent.focusedActionId, intent.remoteKey, false)
        }
        return true
    }

    private fun showUnwatchConfirmation(
        widget: NativeTvTitleWatchedWidget,
        intent: NativeTvTitleWatchedWidgetIntent
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
                result.intent.focusedActionId,
                result.intent.remoteKey,
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
        action: NativeTvTitleWatchedViewAction,
        focusedActionId: String,
        remoteKey: String,
        acknowledged: Boolean
    ) {
        try {
            onActivation(action, focusedActionId, remoteKey, acknowledged)
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
