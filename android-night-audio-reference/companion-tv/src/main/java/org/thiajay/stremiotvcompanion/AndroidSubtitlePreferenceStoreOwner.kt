package org.thiajay.stremiotvcompanion

import android.content.SharedPreferences
import java.util.IdentityHashMap

/**
 * Process-local, owner-thread composition root for the source-validated native
 * subtitle-preference store.
 *
 * [open] leases the exact supplied [SharedPreferences] object to one live owner
 * in this process, then binds the canonical two-slot Android storage port to a
 * fresh opaque installation-identity source and [NativeSubtitlePreferenceStore].
 * The caller must supply a private preferences object whose two fixed keys are
 * not used by another component.
 *
 * This boundary has no Context, account, network, player or UI access and is
 * deliberately not wired into either synthetic Activity. It cannot prove a
 * genuine first-run or subtitle-settings choice.
 */
class AndroidSubtitlePreferenceStoreOwner private constructor(
    private val preferences: SharedPreferences,
    private val leaseToken: Any,
    entropy: SecureRandomSubtitlePreferenceDigestSource.EntropyPort?
) {
    private val ownerThread = Thread.currentThread()
    private val storagePort = AndroidSubtitlePreferenceSharedPreferencesPort(preferences)
    private val digestSource = if (entropy == null) {
        SecureRandomSubtitlePreferenceDigestSource()
    } else {
        SecureRandomSubtitlePreferenceDigestSource(entropy)
    }
    private val store = NativeSubtitlePreferenceStore(storagePort, digestSource)
    private var closed = false

    fun loadStoredPreference(
        sessionPreference: NativeSubtitlePreferenceProvenance.SessionPreference?,
        globalLanguage: String?
    ): Map<String, Any?>? {
        if (!usable()) return null
        return store.loadStoredPreference(sessionPreference, globalLanguage)
    }

    /**
     * The caller still owns proof that this came from a genuine local choice
     * event. The owner only preserves the exact validated source contract.
     */
    fun recordExplicitLocalChoice(
        sessionPreference: NativeSubtitlePreferenceProvenance.SessionPreference?,
        globalLanguage: String?,
        surface: String?
    ): NativeSubtitlePreferenceStore.CommitResult {
        if (!usable()) return rejected()
        return store.recordExplicitLocalChoice(sessionPreference, globalLanguage, surface)
    }

    fun clearStoredReceiptForExplicitOff(
        sessionPreference: NativeSubtitlePreferenceProvenance.SessionPreference?
    ): NativeSubtitlePreferenceStore.CommitResult {
        if (!usable()) return rejected()
        return store.clearStoredReceiptForExplicitOff(sessionPreference)
    }

    /**
     * Close revokes this owner before closing every composed component. The
     * exact preferences lease is released only after all three close readbacks
     * succeed; an ambiguous close therefore fails closed without admitting a
     * second owner.
     */
    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        return try {
            val storeClosed = store.close()
            val digestClosed = digestSource.close()
            val storageClosed = storagePort.close()
            val allClosed = storeClosed && digestClosed && storageClosed
            if (allClosed) releaseLease(preferences, leaseToken)
            allClosed
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun usable(): Boolean = onOwnerThread() && !closed

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    private fun rejected(): NativeSubtitlePreferenceStore.CommitResult =
        NativeSubtitlePreferenceStore.CommitResult(
            ok = false,
            provenance = null,
            rollbackVerified = true
        )

    companion object {
        private val activeOwners = IdentityHashMap<SharedPreferences, Any>()

        /**
         * Refuses a second live owner for the exact same preferences object.
         * This is a process-local identity lease, not a cross-process lock.
         */
        fun open(
            preferences: SharedPreferences
        ): AndroidSubtitlePreferenceStoreOwner? = openInternal(preferences, null)

        internal fun openForTest(
            preferences: SharedPreferences,
            entropy: SecureRandomSubtitlePreferenceDigestSource.EntropyPort
        ): AndroidSubtitlePreferenceStoreOwner? = openInternal(preferences, entropy)

        private fun openInternal(
            preferences: SharedPreferences,
            entropy: SecureRandomSubtitlePreferenceDigestSource.EntropyPort?
        ): AndroidSubtitlePreferenceStoreOwner? {
            val token = Any()
            synchronized(activeOwners) {
                if (activeOwners.containsKey(preferences)) return null
                activeOwners[preferences] = token
            }
            return try {
                AndroidSubtitlePreferenceStoreOwner(preferences, token, entropy)
            } catch (_: RuntimeException) {
                releaseLease(preferences, token)
                null
            } catch (_: LinkageError) {
                releaseLease(preferences, token)
                null
            }
        }

        private fun releaseLease(
            preferences: SharedPreferences,
            token: Any
        ) {
            synchronized(activeOwners) {
                if (activeOwners[preferences] === token) {
                    activeOwners.remove(preferences)
                }
            }
        }
    }
}

