package org.thiajay.stremiotvcompanion

import android.content.SharedPreferences
import java.util.IdentityHashMap

/**
 * App-private subtitle preference authority for the disclosed mobile and TV
 * labs only.
 *
 * Each lab has a distinct current key. Opening under the v2 source performs one
 * bounded v1-to-v2 migration while the namespace lease is held. A lone legacy
 * boolean is moved atomically with stable exact readback; collisions, corrupt
 * types, ambiguous commits and concurrent drift fail closed without claiming
 * migration. An absent current and legacy key is explicitly Off and is
 * presented as first-run; an explicit choice is synchronously committed and
 * read back before a transition is issued. Rollback restores the exact prior
 * storage shape, including absence, only while the current storage still
 * equals the transition-owned after value. Unrelated keys are never cleared.
 *
 * The identity lease and owner-thread gate are process-local. This class has
 * no account, network, provider, player or official Stremio preference path.
 */
internal class PersistentSyntheticSubtitlePreferenceHost private constructor(
    private val preferences: SharedPreferences,
    private val namespace: Namespace,
    private val leaseToken: Any
) : AndroidSubtitlePreferenceChoiceSession.SnapshotPort,
    AndroidSubtitlePreferenceChoiceTransaction.ApplicationPort {

    enum class Namespace(internal val legacyKey: String, internal val key: String) {
        MOBILE(
            "org.thiajay.stremiotvcompanion.synthetic.mobile.subtitle.enabled.v1",
            "org.thiajay.stremiotvcompanion.synthetic.mobile.subtitle.enabled.v2"
        ),
        TV(
            "org.thiajay.stremiotvcompanion.synthetic.tv.subtitle.enabled.v1",
            "org.thiajay.stremiotvcompanion.synthetic.tv.subtitle.enabled.v2"
        )
    }

    private data class StoredState(val present: Boolean, val enabled: Boolean)

    private data class MigrationState(
        val legacy: StoredState,
        val current: StoredState
    )

    private data class IssuedTransition(
        val transition: AndroidSubtitlePreferenceChoiceTransaction.Transition,
        val before: StoredState,
        val after: StoredState
    )

    private val ownerThread = Thread.currentThread()
    private var lastIssued: IssuedTransition? = null
    private var closed = false

    fun presentationSurface(): AndroidSubtitlePreferenceChoiceSession.Surface? {
        if (!usable()) return null
        val state = readStableState() ?: return null
        return if (state.present) {
            AndroidSubtitlePreferenceChoiceSession.Surface.SUBTITLE_SETTINGS
        } else {
            AndroidSubtitlePreferenceChoiceSession.Surface.FIRST_RUN
        }
    }

    override fun readPreferenceSnapshot():
        AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot? {
        if (!usable()) return null
        return readStableState()?.let { snapshot(it.enabled) }
    }

    override fun applyChoice(
        surface: AndroidSubtitlePreferenceChoiceSession.Surface,
        choice: AndroidSubtitlePreferenceChoiceSession.Choice,
        expected: AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot
    ): AndroidSubtitlePreferenceChoiceTransaction.Transition? {
        if (!usable()) return null
        val beforeState = readStableState() ?: return null
        val before = snapshot(beforeState.enabled)
        if (before != expected) return null

        val enabled = when (choice) {
            AndroidSubtitlePreferenceChoiceSession.Choice.ENABLED -> true
            AndroidSubtitlePreferenceChoiceSession.Choice.OFF -> false
        }
        val after = snapshot(enabled)
        val transition = AndroidSubtitlePreferenceChoiceTransaction.Transition(
            surface = surface,
            choice = choice,
            before = before,
            after = after
        )
        val afterState = StoredState(present = true, enabled = enabled)
        if (!writeState(afterState)) {
            val current = readStableState()
            if (current == afterState) restoreState(beforeState)
            return null
        }

        val exactReadback = readStableState()
        if (exactReadback != afterState) return null
        lastIssued = IssuedTransition(transition, beforeState, afterState)
        return transition
    }

    override fun rollbackChoice(
        transition: AndroidSubtitlePreferenceChoiceTransaction.Transition
    ): Boolean {
        if (!usable()) return false
        val issued = lastIssued ?: return false
        if (transition !== issued.transition) return false
        val current = readStableState() ?: return false
        if (current != issued.after) return false
        if (!restoreState(issued.before)) return false
        val verified = readStableState() == issued.before
        if (verified) lastIssued = null
        return verified
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        lastIssued = null
        releaseLease(preferences, namespace, leaseToken)
        return true
    }

    private fun readStableState(): StoredState? {
        return try {
            val first = readState()
            val second = readState()
            first.takeIf { it == second }
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun readState(): StoredState {
        return readState(namespace.key)
    }

    private fun readState(key: String): StoredState {
        val present = preferences.contains(key)
        val enabled = if (present) preferences.getBoolean(key, false) else false
        return StoredState(present, enabled)
    }

    private fun migrateLegacyState(): Boolean {
        if (!usable()) return false
        val before = readStableMigrationState() ?: return false
        if (before.current.present) return !before.legacy.present
        if (!before.legacy.present) return true

        val after = MigrationState(
            legacy = StoredState(present = false, enabled = false),
            current = StoredState(present = true, enabled = before.legacy.enabled)
        )
        val committed = writeMigrationState(after)
        val exactReadback = readStableMigrationState()
        if (committed && exactReadback == after) return true
        if (exactReadback == after) restoreMigrationState(before, after)
        return false
    }

    private fun readStableMigrationState(): MigrationState? {
        return try {
            val first = MigrationState(
                legacy = readState(namespace.legacyKey),
                current = readState(namespace.key)
            )
            val second = MigrationState(
                legacy = readState(namespace.legacyKey),
                current = readState(namespace.key)
            )
            first.takeIf { it == second }
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun writeMigrationState(state: MigrationState): Boolean {
        return try {
            val editor = preferences.edit()
            writeSlot(editor, namespace.legacyKey, state.legacy)
            writeSlot(editor, namespace.key, state.current)
            editor.commit()
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun writeSlot(
        editor: SharedPreferences.Editor,
        key: String,
        state: StoredState
    ) {
        if (state.present) editor.putBoolean(key, state.enabled) else editor.remove(key)
    }

    private fun restoreMigrationState(before: MigrationState, owned: MigrationState) {
        val current = readStableMigrationState() ?: return
        if (current != owned) return
        if (!writeMigrationState(before)) return
        readStableMigrationState()
    }

    private fun writeState(state: StoredState): Boolean {
        return try {
            val editor = preferences.edit()
            if (state.present) editor.putBoolean(namespace.key, state.enabled) else editor.remove(namespace.key)
            editor.commit()
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun restoreState(state: StoredState): Boolean = writeState(state)

    private fun usable(): Boolean = onOwnerThread() && !closed

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    private fun snapshot(enabled: Boolean) =
        AndroidSubtitlePreferenceChoiceSession.PreferenceSnapshot(
            sessionPreference = NativeSubtitlePreferenceProvenance.SessionPreference(
                enabled = enabled,
                language = "English",
                source = "embedded"
            ),
            globalLanguage = "English"
        )

    companion object {
        private val activeOwners =
            IdentityHashMap<SharedPreferences, MutableMap<Namespace, Any>>()

        fun open(
            preferences: SharedPreferences,
            namespace: Namespace
        ): PersistentSyntheticSubtitlePreferenceHost? {
            val token = Any()
            synchronized(activeOwners) {
                val leases = activeOwners.getOrPut(preferences) { mutableMapOf() }
                if (leases.containsKey(namespace)) return null
                leases[namespace] = token
            }
            return try {
                val host = PersistentSyntheticSubtitlePreferenceHost(
                    preferences,
                    namespace,
                    token
                )
                if (host.migrateLegacyState()) {
                    host
                } else {
                    host.close()
                    null
                }
            } catch (_: RuntimeException) {
                releaseLease(preferences, namespace, token)
                null
            } catch (_: LinkageError) {
                releaseLease(preferences, namespace, token)
                null
            }
        }

        private fun releaseLease(
            preferences: SharedPreferences,
            namespace: Namespace,
            token: Any
        ) {
            synchronized(activeOwners) {
                val leases = activeOwners[preferences] ?: return
                if (leases[namespace] === token) leases.remove(namespace)
                if (leases.isEmpty()) activeOwners.remove(preferences)
            }
        }
    }
}
