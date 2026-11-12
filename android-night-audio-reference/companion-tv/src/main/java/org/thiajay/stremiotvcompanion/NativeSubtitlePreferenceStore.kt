package org.thiajay.stremiotvcompanion

/**
 * Pure, owner-thread installation-local storage transaction boundary for
 * NativeSubtitlePreferenceProvenance.
 *
 * The host supplies atomic compare-and-set operations for an installation
 * digest, an independent monotonic preference revision and a canonical
 * encoded receipt. Keeping the revision outside the receipt prevents an old
 * receipt generation from becoming current again after explicit Off removes
 * the receipt. This class has no Android storage, account, player, network or
 * UI access and is deliberately not wired into either synthetic Activity.
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
        fun readPreferenceRevision(): StorageRead<String>?
        fun readPreferenceReceipt(): StorageRead<String>?

        fun compareAndSetInstallationDigest(
            expected: String?,
            update: String?
        ): Boolean

        fun compareAndSetPreferenceRevision(
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
        val preferenceRevisionRaw: String?,
        val preferenceRevision: Long?,
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
        if (before.installationDigest == null &&
            (before.preferenceRevisionRaw != null || before.receiptRaw != null)
        ) return rejected()

        val installationDigest = before.installationDigest ?: safeDigest() ?: return rejected()
        if (!validDigest(installationDigest)) return rejected()

        val previousRevision = if (before.preferenceRevision != null) {
            // A fresh explicit local choice is allowed to change the binding.
            // The independent stored marker owns monotonicity; requiring the
            // previous receipt to promote against the new choice would make a
            // legitimate language/source change impossible.
            before.preferenceRevision
        } else if (before.receipt == null) {
            0L
        } else {
            // A source-only predecessor stored its generation only inside the
            // receipt. A genuine explicit choice may migrate that canonical
            // receipt by advancing beyond it; the legacy receipt is never
            // promoted on load without the independent marker.
            receiptRevisionForInstallation(before) ?: return rejected()
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
        val revisionRaw = revision.toString()
        val target = StoredTuple(
            installationDigest = installationDigest,
            preferenceRevisionRaw = revisionRaw,
            preferenceRevision = revision,
            receiptRaw = receiptRaw,
            receipt = receipt
        )

        if (before.installationDigest == null &&
            !safeCompareDigest(null, installationDigest)
        ) return rejected(rollback(before, target))

        if (!safeCompareRevision(before.preferenceRevisionRaw, revisionRaw)) {
            return rejected(rollback(before, target))
        }

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
     * the installation identity and independent revision marker. No enabled
     * preference can call this path.
     */
    fun clearStoredReceiptForExplicitOff(
        sessionPreference: NativeSubtitlePreferenceProvenance.SessionPreference?
    ): CommitResult {
        if (!onOwnerThread() || closed || sessionPreference?.enabled != false) {
            return rejected()
        }
        val before = readStableTuple() ?: return rejected()
        // Explicit Off may remove receipt authority, but it must not turn an
        // impossible tuple into an accepted installation identity. Preserve
        // every incoherent value for diagnosis without mutating storage.
        if (before.installationDigest == null &&
            (before.preferenceRevisionRaw != null || before.receiptRaw != null)
        ) return rejected()
        if (before.installationDigest != null &&
            !validDigest(before.installationDigest)
        ) return rejected()
        if (before.receiptRaw == null) {
            // A no-op Off result is accepted only for a coherent empty or
            // previously-cleared installation tuple.
            return CommitResult(ok = true, provenance = null, rollbackVerified = true)
        }
        val retainedRevision = before.preferenceRevision
            ?: receiptRevisionForInstallation(before)
            ?: return rejected()
        val retainedRevisionRaw = retainedRevision.toString()
        val target = before.copy(
            preferenceRevisionRaw = retainedRevisionRaw,
            preferenceRevision = retainedRevision,
            receiptRaw = null,
            receipt = null
        )
        if (before.preferenceRevisionRaw != retainedRevisionRaw &&
            !safeCompareRevision(before.preferenceRevisionRaw, retainedRevisionRaw)
        ) return rejected(rollback(before, target))
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
        if (!validDigest(digest)) return null
        val receipt = stored.receipt ?: return null
        if (receipt["kind"] != "explicit-local-choice") return null
        val revision = positiveRevision(receipt["preferenceRevision"]) ?: return null
        if (stored.preferenceRevision != revision) return null
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
            val revisionRead = storage.readPreferenceRevision() ?: return null
            val receiptRead = storage.readPreferenceReceipt() ?: return null
            if (!digestRead.ok || !revisionRead.ok || !receiptRead.ok) return null
            val revisionRaw = revisionRead.value
            val revision = revisionRaw?.let(::canonicalRevision) ?: run {
                if (revisionRaw == null) null else return null
            }
            val raw = receiptRead.value
            val receipt = raw?.let(NativeSubtitlePreferenceReceiptCodec::decode) ?: run {
                if (raw == null) null else return null
            }
            StoredTuple(
                installationDigest = digestRead.value,
                preferenceRevisionRaw = revisionRaw,
                preferenceRevision = revision,
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
     * Rollback owns only the exact before tuple or an ordered prefix written by
     * this transaction. Any other current value is concurrent drift and is
     * preserved without an overwrite.
     */
    private fun rollback(before: StoredTuple, target: StoredTuple): Boolean {
        val current = readStableTuple() ?: return false
        // Writes are ordered digest -> revision -> receipt. Ownership is
        // therefore limited to the exact before tuple or one of those ordered
        // prefix states. A field-by-field old/target mixture outside this
        // sequence is concurrent drift and must be preserved without rollback.
        val afterDigest = before.copy(
            installationDigest = target.installationDigest
        )
        val ownedAfterRevision = afterDigest.copy(
            preferenceRevisionRaw = target.preferenceRevisionRaw,
            preferenceRevision = target.preferenceRevision
        )
        if (current != before &&
            current != afterDigest &&
            current != ownedAfterRevision &&
            current != target
        ) return false

        if (current.receiptRaw != before.receiptRaw &&
            !safeCompareReceipt(current.receiptRaw, before.receiptRaw)
        ) return false

        val afterReceipt = readStableTuple() ?: return false
        if (afterReceipt.installationDigest != current.installationDigest ||
            afterReceipt.preferenceRevisionRaw != current.preferenceRevisionRaw ||
            afterReceipt.receiptRaw != before.receiptRaw
        ) return false

        if (afterReceipt.preferenceRevisionRaw != before.preferenceRevisionRaw &&
            !safeCompareRevision(
                afterReceipt.preferenceRevisionRaw,
                before.preferenceRevisionRaw
            )
        ) return false

        val afterRevision = readStableTuple() ?: return false
        if (afterRevision.installationDigest != current.installationDigest ||
            afterRevision.preferenceRevisionRaw != before.preferenceRevisionRaw ||
            afterRevision.receiptRaw != before.receiptRaw
        ) return false

        if (afterRevision.installationDigest != before.installationDigest &&
            !safeCompareDigest(afterRevision.installationDigest, before.installationDigest)
        ) return false

        return readStableTuple() == before
    }

    private fun safeDigest(): String? {
        return try {
            digestSource.nextInstallationDigest()?.takeIf(::validDigest)
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

    private fun safeCompareRevision(expected: String?, update: String?): Boolean {
        return try {
            storage.compareAndSetPreferenceRevision(expected, update)
        } catch (_: RuntimeException) {
            false
        } catch (_: LinkageError) {
            false
        }
    }

    private fun receiptRevisionForInstallation(stored: StoredTuple): Long? {
        val digest = stored.installationDigest ?: return null
        if (!validDigest(digest)) return null
        val receipt = stored.receipt ?: return null
        if (receipt["kind"] != "explicit-local-choice" ||
            receipt["installationDigest"] != digest
        ) return null
        return positiveRevision(receipt["preferenceRevision"])
    }

    private fun canonicalRevision(value: String): Long? {
        if (!REVISION.matches(value)) return null
        val parsed = value.toLongOrNull() ?: return null
        return parsed.takeIf { it in 1L..MAX_SAFE_INTEGER && it.toString() == value }
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

    private fun validDigest(value: String?): Boolean =
        value != null && value != NULL_DIGEST && DIGEST.matches(value)

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    companion object {
        private val DIGEST = Regex("^[0-9a-f]{64}$")
        private val NULL_DIGEST = "0".repeat(64)
        private val REVISION = Regex("^[1-9][0-9]{0,15}$")
        private val CHOICE_SURFACES = setOf("first-run", "subtitle-settings")
        private const val MAX_SAFE_INTEGER = 9_007_199_254_740_991L
    }
}
