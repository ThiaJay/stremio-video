package org.thiajay.stremiotvcompanion

import android.content.SharedPreferences

/**
 * Concrete, owner-thread Android storage port for the two canonical subtitle
 * provenance slots.
 *
 * The supplied [SharedPreferences] instance and the two fixed keys must be
 * exclusive to this adapter. Synchronisation is process-local and coordinates
 * only adapter instances sharing the same object; this class does not claim
 * cross-process atomicity. It has no Context, account, network, player or UI
 * access and is deliberately not wired into either synthetic Activity.
 */
class AndroidSubtitlePreferenceSharedPreferencesPort(
    private val preferences: SharedPreferences
) : NativeSubtitlePreferenceStore.StoragePort {
    private val ownerThread = Thread.currentThread()
    private var closed = false

    override fun readInstallationDigest(): NativeSubtitlePreferenceStore.StorageRead<String> =
        readSlot(INSTALLATION_DIGEST_KEY)

    override fun readPreferenceReceipt(): NativeSubtitlePreferenceStore.StorageRead<String> =
        readSlot(PREFERENCE_RECEIPT_KEY)

    override fun compareAndSetInstallationDigest(
        expected: String?,
        update: String?
    ): Boolean = compareAndSetSlot(INSTALLATION_DIGEST_KEY, expected, update)

    override fun compareAndSetPreferenceReceipt(
        expected: String?,
        update: String?
    ): Boolean = compareAndSetSlot(PREFERENCE_RECEIPT_KEY, expected, update)

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        return true
    }

    private fun readSlot(
        key: String
    ): NativeSubtitlePreferenceStore.StorageRead<String> {
        if (!onOwnerThread() || closed) return failedRead()
        return try {
            synchronized(preferences) { readSlotLocked(key) }
        } catch (_: RuntimeException) {
            failedRead()
        } catch (_: LinkageError) {
            failedRead()
        }
    }

    private fun compareAndSetSlot(
        key: String,
        expected: String?,
        update: String?
    ): Boolean {
        if (!onOwnerThread() || closed) return false
        return try {
            synchronized(preferences) {
                val current = readSlotLocked(key)
                if (!current.ok || current.value != expected) return@synchronized false

                val editor = preferences.edit()
                if (update == null) editor.remove(key) else editor.putString(key, update)
                if (!editor.commit()) return@synchronized false

                val readback = readSlotLocked(key)
                readback.ok && readback.value == update
            }
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun readSlotLocked(
        key: String
    ): NativeSubtitlePreferenceStore.StorageRead<String> {
        return try {
            if (!preferences.contains(key)) {
                NativeSubtitlePreferenceStore.StorageRead(ok = true, value = null)
            } else {
                val value = preferences.getString(key, null)
                if (value == null) failedRead()
                else NativeSubtitlePreferenceStore.StorageRead(ok = true, value = value)
            }
        } catch (_: RuntimeException) {
            failedRead()
        } catch (_: LinkageError) {
            failedRead()
        }
    }

    private fun failedRead(): NativeSubtitlePreferenceStore.StorageRead<String> =
        NativeSubtitlePreferenceStore.StorageRead(ok = false, value = null)

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    private companion object {
        const val INSTALLATION_DIGEST_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.installation_digest.v1"
        const val PREFERENCE_RECEIPT_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.receipt.v1"
    }
}
