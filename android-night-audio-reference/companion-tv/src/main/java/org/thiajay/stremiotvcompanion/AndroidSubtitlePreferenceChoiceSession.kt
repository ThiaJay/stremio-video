package org.thiajay.stremiotvcompanion

import android.content.SharedPreferences

/**
 * Unwired owner-thread handoff between a future real Android subtitle-choice
 * surface and [AndroidSubtitlePreferenceStoreOwner].
 *
 * This boundary never infers consent. A host must first present one reviewed
 * surface against a stable preference snapshot, retain the exact returned
 * [ChoiceLease], and return that same object from its later semantic UI
 * callback. The complete snapshot is read again immediately before storage and
 * the lease is consumed before any write is attempted.
 *
 * Object provenance cannot prove that a callback came from a genuine Android
 * view. The class is composed only by the disclosed synthetic mobile lab; a
 * private client still owns real UI wiring, accessibility acceptance, lifecycle
 * integration and device validation.
 */
class AndroidSubtitlePreferenceChoiceSession private constructor(
    private val storeOwner: AndroidSubtitlePreferenceStoreOwner,
    private val snapshotPort: SnapshotPort
) {
    enum class Surface(internal val wireValue: String) {
        FIRST_RUN("first-run"),
        SUBTITLE_SETTINGS("subtitle-settings")
    }

    enum class Choice {
        ENABLED,
        OFF
    }

    data class PreferenceSnapshot(
        val sessionPreference: NativeSubtitlePreferenceProvenance.SessionPreference?,
        val globalLanguage: String?
    )

    fun interface SnapshotPort {
        fun readPreferenceSnapshot(): PreferenceSnapshot?
    }

    /**
     * The constructor is module-visible for adversarial tests, but execution
     * still requires exact object identity with the instance-issued lease.
     */
    class ChoiceLease internal constructor(
        internal val issuer: Any,
        val surface: Surface,
        val choice: Choice
    )

    private data class PendingChoice(
        val lease: ChoiceLease,
        val snapshot: PreferenceSnapshot
    )

    private val ownerThread = Thread.currentThread()
    private val issuer = Any()
    private var pending: PendingChoice? = null
    private var closed = false

    fun loadStoredPreference(): Map<String, Any?>? {
        if (!usable()) return null
        val snapshot = readStableSnapshot() ?: return null
        return storeOwner.loadStoredPreference(
            snapshot.sessionPreference,
            snapshot.globalLanguage
        )
    }

    fun prepareChoice(
        surface: Surface?,
        choice: Choice?
    ): ChoiceLease? {
        if (!usable() || surface == null || choice == null || pending != null) {
            return null
        }
        val snapshot = readStableSnapshot() ?: return null
        val expectedEnabled = when (choice) {
            Choice.ENABLED -> true
            Choice.OFF -> false
        }
        if (snapshot.sessionPreference?.enabled != expectedEnabled) return null

        val lease = ChoiceLease(issuer, surface, choice)
        pending = PendingChoice(lease, snapshot)
        return lease
    }

    fun commitChoice(
        lease: ChoiceLease?
    ): NativeSubtitlePreferenceStore.CommitResult {
        if (!usable()) return rejected()
        val active = pending ?: return rejected()
        // Any mismatched callback invalidates the displayed generation.
        pending = null
        if (lease !== active.lease || lease.issuer !== issuer) return rejected()

        val finalSnapshot = readStableSnapshot() ?: return rejected()
        if (finalSnapshot != active.snapshot) return rejected()

        return when (lease.choice) {
            Choice.ENABLED -> storeOwner.recordExplicitLocalChoice(
                finalSnapshot.sessionPreference,
                finalSnapshot.globalLanguage,
                lease.surface.wireValue
            )
            Choice.OFF -> storeOwner.clearStoredReceiptForExplicitOff(
                finalSnapshot.sessionPreference
            )
        }
    }

    fun cancelChoice(lease: ChoiceLease?): Boolean {
        if (!usable()) return false
        val active = pending ?: return false
        pending = null
        return lease === active.lease && lease.issuer === issuer
    }

    /** Close revokes UI authority before releasing the owned storage lease. */
    fun close(): Boolean {
        if (!usable()) return false
        closed = true
        pending = null
        return try {
            storeOwner.close()
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun readStableSnapshot(): PreferenceSnapshot? {
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

    private fun rejected() = NativeSubtitlePreferenceStore.CommitResult(
        ok = false,
        provenance = null,
        rollbackVerified = true
    )

    companion object {
        fun open(
            preferences: SharedPreferences,
            snapshotPort: SnapshotPort
        ): AndroidSubtitlePreferenceChoiceSession? {
            val owner = AndroidSubtitlePreferenceStoreOwner.open(preferences) ?: return null
            return create(owner, snapshotPort)
        }

        internal fun openForTest(
            preferences: SharedPreferences,
            snapshotPort: SnapshotPort,
            entropy: SecureRandomSubtitlePreferenceDigestSource.EntropyPort
        ): AndroidSubtitlePreferenceChoiceSession? {
            val owner = AndroidSubtitlePreferenceStoreOwner.openForTest(
                preferences,
                entropy
            ) ?: return null
            return create(owner, snapshotPort)
        }

        private fun create(
            owner: AndroidSubtitlePreferenceStoreOwner,
            snapshotPort: SnapshotPort
        ): AndroidSubtitlePreferenceChoiceSession? {
            return try {
                AndroidSubtitlePreferenceChoiceSession(owner, snapshotPort)
            } catch (_: RuntimeException) {
                owner.close()
                null
            } catch (_: LinkageError) {
                owner.close()
                null
            }
        }
    }
}
