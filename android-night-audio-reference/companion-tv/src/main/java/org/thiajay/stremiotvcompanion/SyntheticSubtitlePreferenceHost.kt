package org.thiajay.stremiotvcompanion

/**
 * In-memory subtitle preference authority for the separate mobile lab only.
 *
 * It starts explicitly Off, has no account, network, provider, player or
 * production preference surface, and never reads official Stremio state. The
 * complete snapshot is copied on every read. Rollback accepts only the exact
 * most recently issued transition object while the current value still equals
 * that transition's after snapshot.
 */
internal class SyntheticSubtitlePreferenceHost :
    AndroidSubtitlePreferenceChoiceSession.SnapshotPort,
    AndroidSubtitlePreferenceChoiceTransaction.ApplicationPort {
    private val ownerThread = Thread.currentThread()
    private var current = snapshot(enabled = false)
    private var lastIssued: AndroidSubtitlePreferenceChoiceTransaction.Transition? = null
    private var closed = false

    override fun readPreferenceSnapshot():
        AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot? {
        if (!usable()) return null
        return detached(current)
    }

    override fun applyChoice(
        surface: AndroidSubtitlePreferenceChoiceSession.Surface,
        choice: AndroidSubtitlePreferenceChoiceSession.Choice,
        expected: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot
    ): AndroidSubtitlePreferenceChoiceTransaction.Transition? {
        if (!usable() || expected != current) return null
        val beforePreference = current.sessionPreference ?: return null
        val enabled = when (choice) {
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED -> true
            AndroidSubtitlePreferenceChoiceSession.Choice.OFF -> false
        }
        val before = detached(current)
        val after = before.copy(
            sessionPreference = beforePreference.copy(enabled = enabled)
        )
        val transition = AndroidSubtitlePreferenceChoiceTransaction.Transition(
            surface = surface,
            choice = choice,
            before = before,
            after = after
        )
        current = detached(after)
        lastIssued = transition
        return transition
    }

    override fun rollbackChoice(
        transition: AndroidSubtitlePreferenceChoiceTransaction.Transition
    ): Boolean {
        if (!usable() || transition !== lastIssued || current != transition.after) {
            return false
        }
        lastIssued = null
        current = detached(transition.before)
        return current == transition.before
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        lastIssued = null
        return true
    }

    private fun usable(): Boolean = onOwnerThread() && !closed

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    private fun detached(
        value: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot
    ) = value.copy(sessionPreference = value.sessionPreference?.copy())

    private companion object {
        fun snapshot(enabled: Boolean) =
            AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot(
                sessionPreference = NativeSubtitlePreferenceProvenance.SessionPreference(
                    enabled = enabled,
                    language = "English",
                    source = "embedded"
                ),
                globalLanguage = "English"
            )
    }
}
