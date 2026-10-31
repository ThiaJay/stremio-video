package org.thiajay.stremiotvcompanion

/**
 * Unwired transaction between a semantic subtitle-choice widget and the
 * private client's existing preference authority.
 *
 * The injected port owns the real preference mutation. This boundary accepts
 * only an exact before/after transition that changes the enabled value and
 * preserves language, source and global-language binding. The accepted
 * provenance receipt is written only after exact stable readback. If receipt
 * admission fails, rollback is attempted only while the preference still
 * equals the transaction-owned after snapshot; concurrent drift is preserved.
 *
 * This is not an account-write path and contains no production preference
 * implementation. It remains absent from both synthetic Activities.
 */
class AndroidSubtitlePreferenceChoiceTransaction internal constructor(
    private val session: AndroidSubtitlePreferenceChoiceSession,
    private val snapshotPort: AndroidSubtitlePreferenceChoiceSession.SnapshotPort,
    private val applicationPort: ApplicationPort
) {
    data class Transition(
        val surface: AndroidSubtitlePreferenceChoiceSession.Surface,
        val choice: AndroidSubtitlePreferenceChoiceSession.Choice,
        val before: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot,
        val after: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot
    )

    interface ApplicationPort {
        fun applyChoice(
            surface: AndroidSubtitlePreferenceChoiceSession.Surface,
            choice: AndroidSubtitlePreferenceChoiceSession.Choice,
            expected: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot
        ): Transition?

        /** Must compare-and-set only the exact [Transition.after] to [Transition.before]. */
        fun rollbackChoice(transition: Transition): Boolean
    }

    private val ownerThread = Thread.currentThread()
    private var closed = false

    fun applyAndCommitChoice(
        surface: AndroidSubtitlePreferenceChoiceSession.Surface?,
        choice: AndroidSubtitlePreferenceChoiceSession.Choice?
    ): NativeSubtitlePreferenceStore.CommitResult {
        if (!usable() || surface == null || choice == null) return rejected()
        val before = readStableSnapshot() ?: return rejected()
        val transition = try {
            applicationPort.applyChoice(surface, choice, before)
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        } ?: return rejected(readStableSnapshot() == before)

        val exactReadback = readStableSnapshot()
        val owned = transition.before == before && exactReadback == transition.after
        val valid = owned &&
            transition.surface == surface &&
            transition.choice == choice &&
            validSemanticTransition(transition)
        if (!valid) return rejected(rollbackIfOwned(transition, before, exactReadback))

        val lease = session.prepareChoice(surface, choice)
            ?: return rejected(rollbackTransition(transition))
        val committed = session.commitChoice(lease)
        if (!committed.ok) {
            return rejected(committed.rollbackVerified && rollbackTransition(transition))
        }

        // Promotion is the final exact binding readback. A stale receipt is
        // harmless but cannot be reported as accepted provenance.
        val promoted = session.loadStoredPreference()
        if (promoted != committed.provenance) {
            rollbackTransition(transition)
            return rejected(false)
        }
        return committed
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        return try {
            session.close()
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun validSemanticTransition(transition: Transition): Boolean {
        val before = transition.before
        val after = transition.after
        val beforePreference = before.sessionPreference ?: return false
        val expectedEnabled = when (transition.choice) {
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED -> true
            AndroidSubtitlePreferenceChoiceSession.Choice.OFF -> false
        }
        return after.globalLanguage == before.globalLanguage &&
            after.sessionPreference == beforePreference.copy(enabled = expectedEnabled)
    }

    private fun rollbackIfOwned(
        transition: Transition,
        before: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot,
        current: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot?
    ): Boolean {
        if (transition.before != before || current != transition.after) {
            return current == before
        }
        return rollbackTransition(transition)
    }

    private fun rollbackTransition(transition: Transition): Boolean {
        val current = readStableSnapshot() ?: return false
        if (current == transition.before) return true
        if (current != transition.after) return false
        val acknowledged = try {
            applicationPort.rollbackChoice(transition)
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
        return acknowledged && readStableSnapshot() == transition.before
    }

    private fun readStableSnapshot(): AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot? {
        return try {
            val first = snapshotPort.readPreferenceSnapshot() ?: return null
            val second = snapshotPort.readPreferenceSnapshot() ?: return null
            first.takeIf { it == second }
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun usable(): Boolean = onOwnerThread() && !closed

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    private fun rejected(rollbackVerified: Boolean = true) =
        NativeSubtitlePreferenceStore.CommitResult(
            ok = false,
            provenance = null,
            rollbackVerified = rollbackVerified
        )
}
