package org.thiajay.stremiotvcompanion

import android.content.SharedPreferences
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.Button

data class AndroidSubtitlePreferenceChoiceOutcome(
    val surface: AndroidSubtitlePreferenceChoiceSession.Surface,
    val choice: AndroidSubtitlePreferenceChoiceSession.Choice,
    val result: NativeSubtitlePreferenceStore.CommitResult
)

/**
 * Actual Android Button boundary for an explicit subtitle preference choice.
 *
 * Framework click, keyboard and accessibility activation share the semantic
 * performClick path. Long press is consumed without activation and raw touch
 * coordinates are never accepted. The complete view generation is removed
 * before the exact controller intent is translated into a one-shot session
 * lease and committed through the accepted store owner.
 *
 * This port is deliberately absent from both lab Activities. A private client
 * must still own reviewed lifecycle wiring, exclusive preferences keys,
 * authenticated behaviour, accessibility acceptance and device validation.
 */
class AndroidSubtitlePreferenceChoiceWidgetPort private constructor(
    private val container: ViewGroup,
    private val session: AndroidSubtitlePreferenceChoiceSession,
    private val onOutcome: (AndroidSubtitlePreferenceChoiceOutcome) -> Unit
) {
    private val ownerThread = Thread.currentThread()
    private val controller = AndroidSubtitlePreferenceChoiceWidgetController()
    private val ownedViews = ArrayList<View>()
    private var closed = false

    fun render(surface: AndroidSubtitlePreferenceChoiceSession.Surface): Boolean {
        if (!onMainOwnerThread() || closed) return false
        val widgets = controller.replace(surface) ?: return false
        val candidateViews = try {
            widgets.map { createButton(it) }
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

    fun clear(): Boolean {
        if (!onMainOwnerThread() || closed) return false
        if (!controller.clear() || !removeOwnedViews()) return poison()
        return true
    }

    fun close(): Boolean {
        if (!onMainOwnerThread() || closed) return false
        closed = true
        val controllerClosed = controller.close()
        val viewsRemoved = removeOwnedViews()
        val sessionClosed = session.close()
        return controllerClosed && viewsRemoved && sessionClosed
    }

    private fun createButton(widget: AndroidSubtitlePreferenceChoiceWidget): Button =
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
                    widget,
                    widget.choice,
                    AndroidSubtitlePreferenceChoiceWidgetController.SEMANTIC_ACTIVATION
                ) ?: return@setOnClickListener
                if (!controller.accepts(intent) || !removeOwnedViews()) {
                    poison()
                    return@setOnClickListener
                }

                val result = try {
                    val lease = session.prepareChoice(intent.surface, intent.choice)
                    if (lease == null) rejected() else session.commitChoice(lease)
                } catch (_: Exception) {
                    rejected()
                } catch (_: LinkageError) {
                    rejected()
                }
                deliver(AndroidSubtitlePreferenceChoiceOutcome(
                    intent.surface,
                    intent.choice,
                    result
                ))
            }
        }

    private fun deliver(outcome: AndroidSubtitlePreferenceChoiceOutcome) {
        try {
            onOutcome(outcome)
        } catch (_: Exception) {
            poison()
        } catch (_: LinkageError) {
            poison()
        }
    }

    private fun rejected() = NativeSubtitlePreferenceStore.CommitResult(
        ok = false,
        provenance = null,
        rollbackVerified = true
    )

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
            session.close()
        }
        removeOwnedViews()
        return false
    }

    private fun onMainOwnerThread(): Boolean =
        Thread.currentThread() === ownerThread &&
            Looper.myLooper() === Looper.getMainLooper()

    companion object {
        fun open(
            preferences: SharedPreferences,
            snapshotPort: AndroidSubtitlePreferenceChoiceSession.SnapshotPort,
            container: ViewGroup,
            onOutcome: (AndroidSubtitlePreferenceChoiceOutcome) -> Unit
        ): AndroidSubtitlePreferenceChoiceWidgetPort? {
            if (Looper.myLooper() !== Looper.getMainLooper()) return null
            val session = AndroidSubtitlePreferenceChoiceSession.open(
                preferences,
                snapshotPort
            ) ?: return null
            return try {
                AndroidSubtitlePreferenceChoiceWidgetPort(
                    container,
                    session,
                    onOutcome
                )
            } catch (_: Exception) {
                session.close()
                null
            } catch (_: LinkageError) {
                session.close()
                null
            }
        }
    }
}
