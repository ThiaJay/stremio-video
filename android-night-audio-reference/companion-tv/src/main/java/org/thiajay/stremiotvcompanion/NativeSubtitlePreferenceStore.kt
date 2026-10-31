package org.thiajay.stremiotvcompanion

import java.util.Collections

/**
 * Pure, owner-thread installation-local storage transaction boundary for
 * NativeSubtitlePreferenceProvenance.
 *
 * The host must supply atomic compare-and-set operations for two typed slots.
 * This class has no Android storage, account, player, network or UI access and
 * is deliberately not wired into either synthetic Activity.
 */
class NativeSubtitlePreferenceStore(
    private val storage: StoragePort,
    private val digestSource: DigestSource
) {
    data class StorageRead<T>(
        val ok: Boolean,
        val value: T?
    )

    data class CommitResult(
        val ok: Boolean,
        val provenance: Map<String, Any?>?,
        val rollbackVerified: Boolean
    )

    fun interface DigestSource {
        fun nextInstallationDigest(): String?
    }

    interface StoragePort {
        fun readInstallationDigest(): StorageRead<String>?
        fun readPreferenceReceipt(): StorageRead<Map<*, *>>?

        /**
         * Atomically update only when the current slot still equals expected.
         * A null update removes the slot.
         */
        fun compareAndSetInstallationDigest(
            expected: String?,
            update: String?
        ): Boolean

        /**
         * Atomically update only when the current detached receipt still equals
         * expected. A null update removes the slot.
         */
        fun compareAndSetPreferenceReceipt(
            expected: Map<String, Any?>?,
            update: Map<String, Any?>?
        ): Boolean
    }

    private data class StoredTuple(
        val installationDigest: String?,
        val receipt: Map<String, Any?>?
    )

    private val ownerThread = Thread.currentThread()
    private var closed = false

    fun loadStoredPreference(
        sessionPreference: NativeSubtitlePreferenceProvenance.SessionPreference?,
        globalLanguage: String?
    ): Map<String, Any?>? {
        if (!onOwnerThread() || closed) return null
        val stored = readStableTuple() ?: return null
        val promoted = promote(stored, sessionPreference, globalLanguage) ?: return null
        return if (readStableTuple() == stored) promoted else null
    }

    /**
     * The caller must invoke this only from a real first-run or subtitle
     * settings choice event. This boundary validates and stores the resulting
     * receipt; it cannot prove or manufacture that UI event.
     */
    fun recordExplicitLocalChoice(
        sessionPreference: NativeSubtitlePreferenceProvenance.SessionPreference?,
        globalLanguage: String?,
        surface: String?
    ): CommitResult {
        if (!onOwnerThread() || closed ||
            sessionPreference?.enabled != true ||
            surface !in CHOICE_SURFACES
        ) return rejected()

        val before = readStableTuple() ?: return rejected()
        if (before.installationDigest == null && before.receipt != null) return rejected()

        val installationDigest = before.installationDigest ?: safeDigest() ?: return rejected()
        if (!DIGEST.matches(installationDigest)) return rejected()

        val previousRevision = if (before.receipt == null) {
            0L
        } else {
            val prior = promote(before, sessionPreference, globalLanguage) ?: return rejected()
            positiveRevision(prior["preferenceRevision"]) ?: return rejected()
        }
        if (previousRevision >= MAX_SAFE_INTEGER) return rejected()
        val revision = previousRevision + 1L

        val receipt = NativeSubtitlePreferenceProvenance.issueExplicitLocalChoiceReceipt(
            activeInstallationDigest = installationDigest,
            preferenceRevision = revision,
            sessionPreference = sessionPreference,
            globalLanguage = globalLanguage,
            localChoiceEvent = linkedMapOf(
                "installationDigest" to installationDigest,
                "preferenceRevision" to revision,
                "surface" to surface,
                "choice" to "enabled"
            )
        ) ?: return rejected()
        val target = StoredTuple(installationDigest, receipt)

        if (before.installationDigest == null &&
            !safeCompareDigest(null, installationDigest)
        ) return rejected(rollback(before, target))

        if (!safeCompareReceipt(before.receipt, receipt)) {
            return rejected(rollback(before, target))
        }

        val readback = readStableTuple()
        if (readback != target) return rejected(rollback(before, target))
        val promoted = promote(readback, sessionPreference, globalLanguage)
            ?: return rejected(rollback(before, target))
        if (readStableTuple() != target) return rejected(rollback(before, target))
        return CommitResult(
            ok = true,
            provenance = promoted,
            rollbackVerified = true
        )
    }

    /**
     * Explicit Off is authoritative. It clears only the receipt while retaining
     * the installation identity. No enabled preference can call this path.
     */
    fun clearStoredReceiptForExplicitOff(
        sessionPreference: NativeSubtitlePreferenceProvenance.SessionPreference?
    ): CommitResult {
        if (!onOwnerThread() || closed || sessionPreference?.enabled != false) {
            return rejected()
        }
        val before = readStableTuple() ?: return rejected()
        if (before.receipt == null) {
            return CommitResult(ok = true, provenance = null, rollbackVerified = true)
        }
        val target = before.copy(receipt = null)
        if (!safeCompareReceipt(before.receipt, null)) {
            return rejected(rollback(before, target))
        }
        if (readStableTuple() != target || readStableTuple() != target) {
            return rejected(rollback(before, target))
        }
        return CommitResult(ok = true, provenance = null, rollbackVerified = true)
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        return true
    }

    private fun promote(
        stored: StoredTuple,
        sessionPreference: NativeSubtitlePreferenceProvenance.SessionPreference?,
        globalLanguage: String?
    ): Map<String, Any?>? {
        val digest = stored.installationDigest ?: return null
        if (!DIGEST.matches(digest)) return null
        val receipt = stored.receipt ?: return null
        if (receipt["kind"] != "explicit-local-choice") return null
        val revision = positiveRevision(receipt["preferenceRevision"]) ?: return null
        val binding = receipt["binding"] as? Map<*, *> ?: return null
        return NativeSubtitlePreferenceProvenance.promoteStoredPreferenceReceipt(
            activeInstallationDigest = digest,
            sessionPreference = sessionPreference,
            globalLanguage = globalLanguage,
            storedReceipt = receipt,
            storageReadback = linkedMapOf(
                "scope" to "installation-local",
                "installationDigest" to digest,
                "preferenceRevision" to revision,
                "binding" to binding
            )
        )
    }

    private fun readStableTuple(): StoredTuple? {
        val first = readTupleOnce() ?: return null
        val second = readTupleOnce() ?: return null
        return first.takeIf { it == second }
    }

    private fun readTupleOnce(): StoredTuple? {
        return try {
            val digestRead = storage.readInstallationDigest() ?: return null
            val receiptRead = storage.readPreferenceReceipt() ?: return null
            if (!digestRead.ok || !receiptRead.ok) return null
            StoredTuple(
                installationDigest = digestRead.value,
                receipt = receiptRead.value?.let(::snapshotReceipt) ?: run {
                    if (receiptRead.value == null) null else return null
                }
            )
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    /**
     * Rollback owns only the exact before/target tuple. Any other current value
     * is concurrent drift and is preserved without an overwrite.
     */
    private fun rollback(before: StoredTuple, target: StoredTuple): Boolean {
        val current = readStableTuple() ?: return false
        val digestOwned = current.installationDigest == before.installationDigest ||
            current.installationDigest == target.installationDigest
        val receiptOwned = current.receipt == before.receipt || current.receipt == target.receipt
        if (!digestOwned || !receiptOwned) return false

        if (current.receipt != before.receipt &&
            !safeCompareReceipt(current.receipt, before.receipt)
        ) return false

        val afterReceipt = readStableTuple() ?: return false
        if (afterReceipt.installationDigest != current.installationDigest ||
            afterReceipt.receipt != before.receipt
        ) return false

        if (afterReceipt.installationDigest != before.installationDigest &&
            !safeCompareDigest(afterReceipt.installationDigest, before.installationDigest)
        ) return false

        return readStableTuple() == before
    }

    private fun safeDigest(): String? {
        return try {
            digestSource.nextInstallationDigest()?.takeIf(DIGEST::matches)
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun safeCompareDigest(expected: String?, update: String?): Boolean {
        return try {
            storage.compareAndSetInstallationDigest(expected, update)
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun safeCompareReceipt(
        expected: Map<String, Any?>?,
        update: Map<String, Any?>?
    ): Boolean {
        return try {
            storage.compareAndSetPreferenceReceipt(expected, update)
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun snapshotReceipt(value: Map<*, *>): Map<String, Any?>? {
        return try {
            val size = value.size
            if (size !in SAME_INSTALLATION_KEYS.size..EXPLICIT_CHOICE_KEYS.size) return null
            val copy = LinkedHashMap<String, Any?>(size)
            val iterator = value.entries.iterator()
            var count = 0
            while (iterator.hasNext()) {
                if (count >= size) return null
                val entry = iterator.next()
                val key = entry.key as? String ?: return null
                if (copy.containsKey(key)) return null
                copy[key] = if (key == "binding") {
                    snapshotBinding(entry.value as? Map<*, *> ?: return null) ?: return null
                } else {
                    entry.value
                }
                count++
            }
            if (count != size) return null
            val expected = if (copy["kind"] == "explicit-local-choice") {
                EXPLICIT_CHOICE_KEYS
            } else {
                SAME_INSTALLATION_KEYS
            }
            if (copy.keys != expected) return null
            Collections.unmodifiableMap(copy)
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun snapshotBinding(value: Map<*, *>): Map<String, Any?>? {
        return try {
            if (value.size != BINDING_KEYS.size) return null
            val copy = LinkedHashMap<String, Any?>(BINDING_KEYS.size)
            val iterator = value.entries.iterator()
            var count = 0
            while (iterator.hasNext()) {
                if (count >= BINDING_KEYS.size) return null
                val entry = iterator.next()
                val key = entry.key as? String ?: return null
                if (key !in BINDING_KEYS || copy.containsKey(key)) return null
                copy[key] = entry.value
                count++
            }
            if (count != BINDING_KEYS.size || copy.keys != BINDING_KEYS) return null
            Collections.unmodifiableMap(copy)
        } catch (_: RuntimeException) {
            null
        } catch (_: LinkageError) {
            null
        }
    }

    private fun rejected(rollbackVerified: Boolean = true): CommitResult =
        CommitResult(ok = false, provenance = null, rollbackVerified = rollbackVerified)

    private fun positiveRevision(value: Any?): Long? = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }?.takeIf { it in 1L..MAX_SAFE_INTEGER }

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    companion object {
        private val DIGEST = Regex("^[0-9a-f]{64}$")
        private val CHOICE_SURFACES = setOf("first-run", "subtitle-settings")
        private val BINDING_KEYS = setOf(
            "sessionEnabled",
            "sessionLanguage",
            "globalLanguage",
            "sessionSource"
        )
        private val SAME_INSTALLATION_KEYS = setOf(
            "schemaVersion",
            "kind",
            "installationDigest",
            "preferenceRevision",
            "binding"
        )
        private val EXPLICIT_CHOICE_KEYS =
            SAME_INSTALLATION_KEYS + setOf("surface", "choice")
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}
