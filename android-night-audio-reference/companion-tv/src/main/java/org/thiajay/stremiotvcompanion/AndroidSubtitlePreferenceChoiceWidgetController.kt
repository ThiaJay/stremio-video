package org.thiajay.stremiotvcompanion

/**
 * One immutable semantic choice rendered by a future Android subtitle surface.
 *
 * Choice identity alone is never authority. Consumption requires the exact
 * controller-issued object and returns an exact controller-issued intent.
 */
class AndroidSubtitlePreferenceChoiceWidget internal constructor(
    val surface: AndroidSubtitlePreferenceChoiceSession.Surface,
    val choice: AndroidSubtitlePreferenceChoiceSession.Choice,
    val label: String,
    private val provenanceSeal: Any
) {
    internal fun issuedBy(seal: Any): Boolean = provenanceSeal === seal
}

class AndroidSubtitlePreferenceChoiceWidgetIntent internal constructor(
    val surface: AndroidSubtitlePreferenceChoiceSession.Surface,
    val choice: AndroidSubtitlePreferenceChoiceSession.Choice,
    val semanticEvent: String,
    private val provenanceSeal: Any
) {
    internal fun issuedBy(seal: Any): Boolean = provenanceSeal === seal
}

/**
 * Owner-thread, one-generation gate for real Android click/accessibility input.
 *
 * A semantic activation attempt consumes the complete rendered generation
 * before validating provenance, selected choice or event type. Raw pointer and
 * long-press input are not consent and can never produce an intent.
 */
class AndroidSubtitlePreferenceChoiceWidgetController {
    private val ownerThread = Thread.currentThread()
    private val controllerSeal = Any()
    private val issued = LinkedHashMap<AndroidSubtitlePreferenceChoiceSession.Choice, AndroidSubtitlePreferenceChoiceWidget>()
    private var pendingIntent: AndroidSubtitlePreferenceChoiceWidgetIntent? = null
    private var closed = false

    fun replace(
        surface: AndroidSubtitlePreferenceChoiceSession.Surface?
    ): List<AndroidSubtitlePreferenceChoiceWidget>? {
        if (!usable() || surface == null) return null
        issued.clear()
        pendingIntent = null
        val widgets = listOf(
            AndroidSubtitlePreferenceChoiceWidget(
                surface,
                AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED,
                "Use automatic subtitles",
                controllerSeal
            ),
            AndroidSubtitlePreferenceChoiceWidget(
                surface,
                AndroidSubtitlePreferenceChoiceSession.Choice.OFF,
                "Keep automatic subtitles off",
                controllerSeal
            )
        )
        for (widget in widgets) {
            if (widget.label.isBlank() || issued.put(widget.choice, widget) != null) {
                issued.clear()
                return null
            }
        }
        return widgets.toList()
    }

    fun consume(
        widget: AndroidSubtitlePreferenceChoiceWidget,
        selectedChoice: AndroidSubtitlePreferenceChoiceSession.Choice?,
        semanticEvent: String
    ): AndroidSubtitlePreferenceChoiceWidgetIntent? {
        if (!usable()) return null
        val exact = issued.remove(widget.choice)
        issued.clear()
        pendingIntent = null
        if (exact !== widget || !widget.issuedBy(controllerSeal) ||
            selectedChoice != widget.choice || semanticEvent != SEMANTIC_ACTIVATION
        ) return null

        return AndroidSubtitlePreferenceChoiceWidgetIntent(
            widget.surface,
            widget.choice,
            semanticEvent,
            controllerSeal
        ).also { pendingIntent = it }
    }

    fun clear(): Boolean {
        if (!usable()) return false
        issued.clear()
        pendingIntent = null
        return true
    }

    fun close(): Boolean {
        if (!usable()) return false
        closed = true
        issued.clear()
        pendingIntent = null
        return true
    }

    internal fun accepts(intent: AndroidSubtitlePreferenceChoiceWidgetIntent): Boolean {
        if (!usable()) return false
        val exact = pendingIntent
        pendingIntent = null
        return exact === intent && intent.issuedBy(controllerSeal) &&
            intent.semanticEvent == SEMANTIC_ACTIVATION
    }

    private fun usable(): Boolean =
        Thread.currentThread() === ownerThread && !closed

    companion object {
        const val SEMANTIC_ACTIVATION = "ACTIVATE"
    }
}
