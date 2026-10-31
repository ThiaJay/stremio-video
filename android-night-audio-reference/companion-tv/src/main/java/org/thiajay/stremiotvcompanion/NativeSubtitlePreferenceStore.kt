package org.thiajay.stremiotvcompanion

/**
 * Pure, owner-thread installation-local storage transaction boundary for
 * NativeSubtitlePreferenceProvenance.
 *
 * The host supplies atomic compare-and-set operations for a digest slot and a
 * canonical encoded-receipt slot. This class has no Android storage, account,
 * player, network or UI access and is deliberately not wired into either
 * synthetic Activity.
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
        fun readPreferenceReceipt(): StorageRead<String>?

        fun compareAndSetInstallationDigest(
            expected: String?,
            update: String?
        ): Boolean

        /** Exact-string CAS; a null update removes the receipt slot. */
        fun compareAndSetPreferenceReceipt(
            expected: String?,
            update: String?
        ): Boolean
    }

    private data class StoredTuple(
        val installationDigest: String?,
        val receiptRaw: String?,
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
        if (before.installationDigest == null && before.receiptRaw != null) return rejected()

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
        val receiptRaw = NativeSubtitlePreferenceReceiptCodec.encode(receipt) ?: return rejected()
        val target = StoredTuple(installationDigest, receiptRaw, receipt)

        if (before.installationDigest == null &&
            !safeCompareDigest(null, installationDigest)
        ) return rejected(rollback(before, target))

        if (!safeCompareReceipt(before.receiptRaw, receiptRaw)) {
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
        if (before.receiptRaw == null) {
            return CommitResult(ok = true, provenance = null, rollbackVerified = true)
        }
        val target = before.copy(receiptRaw = null, receipt = null)
        if (!safeCompareReceipt(before.receiptRaw, null)) {
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
            val raw = receiptRead.value
            val receipt = raw?.let(NativeSubtitlePreferenceReceiptCodec::decode) ?: run {
                if (raw == null) null else return null
            }
            StoredTuple(
                installationDigest = digestRead.value,
                receiptRaw = raw,
                receipt = receipt
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
        val receiptOwned = current.receiptRaw == before.receiptRaw ||
            current.receiptRaw == target.receiptRaw
        if (!digestOwned || !receiptOwned) return false

        if (current.receiptRaw != before.receiptRaw &&
            !safeCompareReceipt(current.receiptRaw, before.receiptRaw)
        ) return false

        val afterReceipt = readStableTuple() ?: return false
        if (afterReceipt.installationDigest != current.installationDigest ||
            afterReceipt.receiptRaw != before.receiptRaw
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

    private fun safeCompareReceipt(expected: String?, update: String?): Boolean {
        return try {
            storage.compareAndSetPreferenceReceipt(expected, update)
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
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
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}
