package org.thiajay.stremiotvcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thiajay.stremiotvcompanion.NativeSubtitlePreferenceProvenance.SessionPreference
import org.thiajay.stremiotvcompanion.NativeSubtitlePreferenceStore.CommitResult
import org.thiajay.stremiotvcompanion.NativeSubtitlePreferenceStore.StorageRead

class NativeSubtitlePreferenceStoreTest {
    private val digest = "a".repeat(64)
    private val otherDigest = "b".repeat(64)
    private val session = SessionPreference(
        enabled = true,
        language = "eng",
        source = "embedded"
    )

    @Test fun absentStorageLoadsNoPreference() {
        val port = FakeStorage()
        assertNull(store(port).loadStoredPreference(session, null))
        assertEquals(0, port.digestCasCalls)
        assertEquals(0, port.revisionCasCalls)
        assertEquals(0, port.receiptCasCalls)
    }

    @Test fun firstExplicitChoicePersistsAndPromotes() {
        val port = FakeStorage()
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertAccepted(result, 1L)
        assertEquals(digest, port.installationDigest)
        assertEquals("explicit-local-choice", port.receipt?.get("kind"))
        assertEquals("first-run", port.receipt?.get("surface"))
        assertTrue(port.receiptRaw?.startsWith("nsp1:") == true)
        assertEquals(port.receiptRaw, NativeSubtitlePreferenceReceiptCodec.encode(port.receipt))
        assertEquals(1, port.digestCasCalls)
        assertEquals(1, port.revisionCasCalls)
        assertEquals(1, port.receiptCasCalls)
    }

    @Test fun repeatedChoiceIncrementsRevision() {
        val port = FakeStorage()
        assertAccepted(store(port).recordExplicitLocalChoice(session, null, "first-run"), 1L)
        val second = store(port) { throw AssertionError("digest source must not be called") }
            .recordExplicitLocalChoice(session, null, "subtitle-settings")
        assertAccepted(second, 2L)
        assertEquals("subtitle-settings", port.receipt?.get("surface"))
        assertEquals(1, port.digestCasCalls)
        assertEquals(2, port.revisionCasCalls)
        assertEquals(2, port.receiptCasCalls)
    }

    @Test fun changedExplicitChoiceAdvancesIndependentRevision() {
        val port = FakeStorage()
        assertAccepted(store(port).recordExplicitLocalChoice(session, null, "first-run"), 1L)

        val changed = session.copy(language = "spa", source = "external")
        val second = store(port) { throw AssertionError("digest source must not be called") }
            .recordExplicitLocalChoice(changed, "spa", "subtitle-settings")

        assertAccepted(second, 2L)
        assertEquals("2", port.preferenceRevisionRaw)
        assertEquals("spa", (port.receipt?.get("binding") as Map<*, *>)["sessionLanguage"])
        assertEquals("spa", (port.receipt?.get("binding") as Map<*, *>)["globalLanguage"])
        assertEquals("external", (port.receipt?.get("binding") as Map<*, *>)["sessionSource"])
        assertNull(store(port).loadStoredPreference(session, null))
        assertEquals(2L, store(port).loadStoredPreference(changed, "spa")?.get("preferenceRevision"))
    }

    @Test fun existingInstallationDigestIsReused() {
        val port = FakeStorage(installationDigest = digest)
        val result = store(port) { throw IllegalStateException("unused digest source") }
            .recordExplicitLocalChoice(session, null, "subtitle-settings")
        assertAccepted(result, 1L)
        assertEquals(digest, port.installationDigest)
        assertEquals("1", port.preferenceRevisionRaw)
        assertEquals(0, port.digestCasCalls)
    }

    @Test fun invalidOrThrowingDigestSourceLeavesStorageUntouched() {
        val invalidPort = FakeStorage()
        val invalid = store(invalidPort) { "not-a-digest" }
            .recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(invalid)
        assertNull(invalidPort.installationDigest)
        assertNull(invalidPort.preferenceRevisionRaw)
        assertNull(invalidPort.receiptRaw)

        val throwingPort = FakeStorage()
        val throwing = store(throwingPort) { throw IllegalStateException("no entropy") }
            .recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(throwing)
        assertNull(throwingPort.installationDigest)
        assertNull(throwingPort.preferenceRevisionRaw)
        assertNull(throwingPort.receiptRaw)
    }

    @Test fun receiptWithoutInstallationDigestFailsWithoutMutation() {
        val port = FakeStorage(receipt = validReceipt(digest, 1L))
        val before = port.receiptRaw
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertNull(port.installationDigest)
        assertEquals(before, port.receiptRaw)
        assertEquals(0, port.digestCasCalls)
        assertEquals(0, port.revisionCasCalls)
        assertEquals(0, port.receiptCasCalls)
    }

    @Test fun corruptStoredReceiptFailsWithoutMutation() {
        val corrupt = "nsp1:not-canonical"
        val port = FakeStorage(installationDigest = digest, receiptRaw = corrupt)
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertEquals(corrupt, port.receiptRaw)
        assertEquals(0, port.digestCasCalls)
        assertEquals(0, port.revisionCasCalls)
        assertEquals(0, port.receiptCasCalls)
    }

    @Test fun unstablePrewriteTupleFailsWithoutMutation() {
        val port = FakeStorage()
        port.digestReadHook = { count ->
            StorageRead(ok = true, value = if (count == 1) null else otherDigest)
        }
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertEquals(0, port.digestCasCalls)
        assertEquals(0, port.revisionCasCalls)
        assertEquals(0, port.receiptCasCalls)
    }

    @Test fun digestCompareAndSetRefusalDoesNotWriteReceipt() {
        val port = FakeStorage()
        port.digestCasHook = { _, _ -> false }
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertNull(port.installationDigest)
        assertNull(port.preferenceRevisionRaw)
        assertNull(port.receiptRaw)
        assertEquals(0, port.receiptCasCalls)
    }

    @Test fun receiptCompareAndSetRefusalRollsBackGeneratedDigest() {
        val port = FakeStorage()
        port.receiptCasHook = { _, _ -> false }
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertTrue(result.rollbackVerified)
        assertNull(port.installationDigest)
        assertNull(port.preferenceRevisionRaw)
        assertNull(port.receiptRaw)
        assertEquals(2, port.revisionCasCalls)
        assertEquals(2, port.digestCasCalls)
    }

    @Test fun falsePositiveReceiptWriteRollsBackExactTuple() {
        val port = FakeStorage()
        port.receiptCasHook = { _, _ -> true }
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertTrue(result.rollbackVerified)
        assertNull(port.installationDigest)
        assertNull(port.preferenceRevisionRaw)
        assertNull(port.receiptRaw)
    }

    @Test fun unknownConcurrentDriftIsPreservedWithoutRollbackClaim() {
        val foreign = validReceipt(otherDigest, 99L)
        val foreignRaw = NativeSubtitlePreferenceReceiptCodec.encode(foreign)!!
        val port = FakeStorage()
        port.receiptCasHook = { _, _ ->
            port.receiptRaw = foreignRaw
            false
        }
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertFalse(result.ok)
        assertFalse(result.rollbackVerified)
        assertEquals(digest, port.installationDigest)
        assertEquals("1", port.preferenceRevisionRaw)
        assertEquals(foreign, port.receipt)
    }

    @Test fun corruptConcurrentDriftIsPreservedWithoutRollbackClaim() {
        val port = FakeStorage()
        port.receiptCasHook = { _, _ ->
            port.receiptRaw = "nsp1:foreign-corrupt-value"
            false
        }
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertFalse(result.ok)
        assertFalse(result.rollbackVerified)
        assertEquals(digest, port.installationDigest)
        assertEquals("1", port.preferenceRevisionRaw)
        assertEquals("nsp1:foreign-corrupt-value", port.receiptRaw)
    }

    @Test fun impossibleHybridConcurrentDriftIsPreservedWithoutRollbackClaim() {
        val port = FakeStorage()
        var foreignReceiptRaw: String? = null
        port.receiptCasHook = { _, update ->
            // Digest -> revision -> receipt is the only transaction order. A
            // target receipt paired with the old revision can only be foreign
            // drift and must never be claimed by rollback.
            port.preferenceRevisionRaw = null
            port.receiptRaw = update
            foreignReceiptRaw = update
            false
        }

        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")

        assertFalse(result.ok)
        assertFalse(result.rollbackVerified)
        assertEquals(digest, port.installationDigest)
        assertNull(port.preferenceRevisionRaw)
        assertEquals(foreignReceiptRaw, port.receiptRaw)
        assertEquals("explicit-local-choice", port.receipt?.get("kind"))
    }

    @Test fun explicitOffClearsOnlyReceiptAndKeepsInstallationDigest() {
        val port = FakeStorage()
        assertAccepted(store(port).recordExplicitLocalChoice(session, null, "first-run"), 1L)
        val result = store(port) { throw AssertionError("unused") }
            .clearStoredReceiptForExplicitOff(SessionPreference(enabled = false))
        assertTrue(result.ok)
        assertTrue(result.rollbackVerified)
        assertNull(result.provenance)
        assertEquals(digest, port.installationDigest)
        assertEquals("1", port.preferenceRevisionRaw)
        assertNull(port.receiptRaw)
    }

    @Test fun receiptlessImpossibleTupleFailsClosedWithoutMutation() {
        val orphanRevision = FakeStorage(preferenceRevisionRaw = "1")
        val orphanResult = store(orphanRevision)
            .clearStoredReceiptForExplicitOff(SessionPreference(enabled = false))
        assertRejected(orphanResult)
        assertNull(orphanRevision.installationDigest)
        assertEquals("1", orphanRevision.preferenceRevisionRaw)
        assertNull(orphanRevision.receiptRaw)
        assertEquals(0, orphanRevision.digestCasCalls)
        assertEquals(0, orphanRevision.revisionCasCalls)
        assertEquals(0, orphanRevision.receiptCasCalls)

        val corruptDigest = FakeStorage(installationDigest = "not-a-digest")
        val corruptResult = store(corruptDigest)
            .clearStoredReceiptForExplicitOff(SessionPreference(enabled = false))
        assertRejected(corruptResult)
        assertEquals("not-a-digest", corruptDigest.installationDigest)
        assertNull(corruptDigest.preferenceRevisionRaw)
        assertNull(corruptDigest.receiptRaw)
        assertEquals(0, corruptDigest.digestCasCalls)
        assertEquals(0, corruptDigest.revisionCasCalls)
        assertEquals(0, corruptDigest.receiptCasCalls)
    }

    @Test fun explicitOffThenReenableAdvancesIndependentRevisionAndRejectsReplay() {
        val port = FakeStorage()
        assertAccepted(store(port).recordExplicitLocalChoice(session, null, "first-run"), 1L)
        val staleReceipt = port.receiptRaw

        assertTrue(
            store(port) { throw AssertionError("unused") }
                .clearStoredReceiptForExplicitOff(SessionPreference(enabled = false)).ok
        )
        assertEquals("1", port.preferenceRevisionRaw)
        assertNull(port.receiptRaw)

        assertAccepted(
            store(port) { throw AssertionError("unused") }
                .recordExplicitLocalChoice(session, null, "subtitle-settings"),
            2L
        )
        assertEquals("2", port.preferenceRevisionRaw)

        port.receiptRaw = staleReceipt
        assertNull(
            store(port) { throw AssertionError("unused") }
                .loadStoredPreference(session, null)
        )
    }

    @Test fun legacyReceiptCanAdvanceOnlyThroughNewExplicitChoice() {
        val legacy = validReceipt(digest, 7L)
        val port = FakeStorage(installationDigest = digest, receipt = legacy)

        assertNull(store(port).loadStoredPreference(session, null))
        assertAccepted(
            store(port) { throw AssertionError("unused") }
                .recordExplicitLocalChoice(session, null, "subtitle-settings"),
            8L
        )
        assertEquals("8", port.preferenceRevisionRaw)
    }

    @Test fun noncanonicalIndependentRevisionFailsClosedWithoutMutation() {
        val receipt = validReceipt(digest, 1L)
        val port = FakeStorage(
            installationDigest = digest,
            preferenceRevisionRaw = "01",
            receipt = receipt
        )
        val before = port.receiptRaw

        assertNull(store(port).loadStoredPreference(session, null))
        assertRejected(store(port).recordExplicitLocalChoice(session, null, "subtitle-settings"))
        assertEquals("01", port.preferenceRevisionRaw)
        assertEquals(before, port.receiptRaw)
        assertEquals(0, port.revisionCasCalls)
        assertEquals(0, port.receiptCasCalls)
    }

    @Test fun explicitOffRefusalLeavesReceiptUntouched() {
        val original = validReceipt(digest, 1L)
        val port = FakeStorage(installationDigest = digest, receipt = original)
        port.receiptCasHook = { _, _ -> false }
        val result = store(port)
            .clearStoredReceiptForExplicitOff(SessionPreference(enabled = false))
        assertRejected(result)
        assertEquals(digest, port.installationDigest)
        assertEquals(original, port.receipt)
        assertNull(port.preferenceRevisionRaw)
    }

    @Test fun loadingRequiresStableExactReadbackAndBinding() {
        val port = FakeStorage()
        assertAccepted(store(port).recordExplicitLocalChoice(session, null, "first-run"), 1L)
        assertEquals(
            1L,
            store(port) { throw AssertionError("unused") }
                .loadStoredPreference(session, null)?.get("preferenceRevision")
        )
        assertNull(
            store(port) { throw AssertionError("unused") }
                .loadStoredPreference(session.copy(language = "spa"), null)
        )

        val unstable = FakeStorage(
            installationDigest = digest,
            receiptRaw = port.receiptRaw
        )
        unstable.digestReadHook = { count ->
            StorageRead(ok = true, value = if (count % 2 == 1) digest else otherDigest)
        }
        assertNull(store(unstable).loadStoredPreference(session, null))
    }

    @Test fun foreignThreadAndClosedStoreFailClosed() {
        val port = FakeStorage()
        val owner = store(port)
        var foreignResult: CommitResult? = null
        val thread = Thread {
            foreignResult = owner.recordExplicitLocalChoice(session, null, "first-run")
        }
        thread.start()
        thread.join()
        assertRejected(foreignResult!!)
        assertEquals(0, port.digestCasCalls)
        assertEquals(0, port.revisionCasCalls)
        assertEquals(0, port.receiptCasCalls)

        assertTrue(owner.close())
        assertFalse(owner.close())
        assertRejected(owner.recordExplicitLocalChoice(session, null, "first-run"))
        assertNull(owner.loadStoredPreference(session, null))
    }

    private fun store(
        port: FakeStorage,
        source: () -> String? = { digest }
    ): NativeSubtitlePreferenceStore = NativeSubtitlePreferenceStore(
        port,
        NativeSubtitlePreferenceStore.DigestSource { source() }
    )

    private fun assertAccepted(result: CommitResult, revision: Long) {
        assertTrue(result.ok)
        assertTrue(result.rollbackVerified)
        assertEquals("same-installation-setting", result.provenance?.get("kind"))
        assertEquals(revision, result.provenance?.get("preferenceRevision"))
    }

    private fun assertRejected(result: CommitResult) {
        assertFalse(result.ok)
        assertNull(result.provenance)
    }

    private fun validReceipt(digestValue: String, revision: Long): Map<String, Any?> =
        NativeSubtitlePreferenceProvenance.issueExplicitLocalChoiceReceipt(
            activeInstallationDigest = digestValue,
            preferenceRevision = revision,
            sessionPreference = session,
            globalLanguage = null,
            localChoiceEvent = linkedMapOf(
                "installationDigest" to digestValue,
                "preferenceRevision" to revision,
                "surface" to "first-run",
                "choice" to "enabled"
            )
        )!!

    private class FakeStorage(
        var installationDigest: String? = null,
        var preferenceRevisionRaw: String? = null,
        receipt: Map<String, Any?>? = null,
        var receiptRaw: String? = receipt?.let(NativeSubtitlePreferenceReceiptCodec::encode)
    ) : NativeSubtitlePreferenceStore.StoragePort {
        var receipt: Map<String, Any?>?
            get() = receiptRaw?.let(NativeSubtitlePreferenceReceiptCodec::decode)
            set(value) {
                receiptRaw = value?.let(NativeSubtitlePreferenceReceiptCodec::encode)
            }
        var digestReads = 0
        var revisionReads = 0
        var receiptReads = 0
        var digestCasCalls = 0
        var revisionCasCalls = 0
        var receiptCasCalls = 0
        var digestReadHook: ((Int) -> StorageRead<String>?)? = null
        var revisionReadHook: ((Int) -> StorageRead<String>?)? = null
        var receiptReadHook: ((Int) -> StorageRead<String>?)? = null
        var digestCasHook: ((String?, String?) -> Boolean)? = null
        var revisionCasHook: ((String?, String?) -> Boolean)? = null
        var receiptCasHook: ((String?, String?) -> Boolean)? = null

        override fun readInstallationDigest(): StorageRead<String>? {
            digestReads++
            return digestReadHook?.invoke(digestReads)
                ?: StorageRead(ok = true, value = installationDigest)
        }

        override fun readPreferenceReceipt(): StorageRead<String>? {
            receiptReads++
            return receiptReadHook?.invoke(receiptReads)
                ?: StorageRead(ok = true, value = receiptRaw)
        }

        override fun readPreferenceRevision(): StorageRead<String>? {
            revisionReads++
            return revisionReadHook?.invoke(revisionReads)
                ?: StorageRead(ok = true, value = preferenceRevisionRaw)
        }

        override fun compareAndSetInstallationDigest(
            expected: String?,
            update: String?
        ): Boolean {
            digestCasCalls++
            digestCasHook?.let { return it(expected, update) }
            if (installationDigest != expected) return false
            installationDigest = update
            return true
        }

        override fun compareAndSetPreferenceReceipt(
            expected: String?,
            update: String?
        ): Boolean {
            receiptCasCalls++
            receiptCasHook?.let { return it(expected, update) }
            if (receiptRaw != expected) return false
            receiptRaw = update
            return true
        }

        override fun compareAndSetPreferenceRevision(
            expected: String?,
            update: String?
        ): Boolean {
            revisionCasCalls++
            revisionCasHook?.let { return it(expected, update) }
            if (preferenceRevisionRaw != expected) return false
            preferenceRevisionRaw = update
            return true
        }
    }
}
