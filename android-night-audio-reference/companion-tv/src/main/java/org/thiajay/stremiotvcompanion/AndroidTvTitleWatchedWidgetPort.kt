package org.thiajay.stremiotvcompanion

import android.app.AlertDialog
import android.os.Looper
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import org.pss.stremio.tv.NativeTvTitleWatchedViewAction
import org.pss.stremio.tv.NativeTvTitleWatchedViewPort
import org.pss.stremio.tv.NativeTvTitleWatchedWidget
import org.pss.stremio.tv.NativeTvTitleWatchedWidgetController

/**
 * Actual Android TV focusable-button implementation of the title-action view
 * port. It remains deliberately uninstantiated by the synthetic companion.
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
    private var closed = false

    override fun replaceTitleActions(
        actions: List<NativeTvTitleWatchedViewAction>
    ): Boolean {
        if (!onMainOwnerThread() || closed) return false
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
        val controllerCleared = controller.clear()
        val viewsCleared = removeOwnedViews()
        if (!controllerCleared || !viewsCleared) return poison()
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
                val intent = controller.consume(
                    widget = widget,
                    focusedActionId = widget.actionId,
                    remoteKey = remoteKey,
                    keyAction = keyAction
                ) ?: return@setOnKeyListener false

                if (!removeOwnedViews()) {
                    poison()
                    return@setOnKeyListener true
                }
                if (widget.requiresAcknowledgement) {
                    showUnwatchConfirmation(widget, intent.action, intent.remoteKey)
                } else {
                    deliver(intent.action, intent.focusedActionId, intent.remoteKey, false)
                }
                true
            }
        }

    private fun showUnwatchConfirmation(
        widget: NativeTvTitleWatchedWidget,
        action: NativeTvTitleWatchedViewAction,
        remoteKey: String
    ) {
        var resolved = false
        fun resolve(acknowledged: Boolean) {
            if (resolved) return
            resolved = true
            deliver(action, widget.actionId, remoteKey, acknowledged)
        }
        try {
            AlertDialog.Builder(container.context)
                .setTitle(widget.label)
                .setMessage(widget.confirmation)
                .setPositiveButton("Confirm") { _, _ -> resolve(true) }
                .setNegativeButton("Cancel") { _, _ -> resolve(false) }
                .setOnCancelListener { resolve(false) }
                .show()
        } catch (_: Exception) {
            resolve(false)
        } catch (_: LinkageError) {
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

    private fun poison(): Boolean {
        if (!closed) {
            closed = true
            controller.close()
        }
        removeOwnedViews()
        return false
    }

    private fun onMainOwnerThread(): Boolean =
        Thread.currentThread() === ownerThread &&
            Looper.myLooper() === Looper.getMainLooper()
}
