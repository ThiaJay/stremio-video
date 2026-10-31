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
        assertEquals(0, port.receiptCasCalls)
    }

    @Test fun firstExplicitChoicePersistsAndPromotes() {
        val port = FakeStorage()
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertAccepted(result, 1L)
        assertEquals(digest, port.installationDigest)
        assertEquals("explicit-local-choice", port.receipt?.get("kind"))
        assertEquals("first-run", port.receipt?.get("surface"))
        assertEquals(1, port.digestCasCalls)
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
        assertEquals(2, port.receiptCasCalls)
    }

    @Test fun existingInstallationDigestIsReused() {
        val port = FakeStorage(installationDigest = digest)
        val result = store(port) { throw IllegalStateException("unused digest source") }
            .recordExplicitLocalChoice(session, null, "subtitle-settings")
        assertAccepted(result, 1L)
        assertEquals(digest, port.installationDigest)
        assertEquals(0, port.digestCasCalls)
    }

    @Test fun invalidOrThrowingDigestSourceLeavesStorageUntouched() {
        val invalidPort = FakeStorage()
        val invalid = store(invalidPort) { "not-a-digest" }
            .recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(invalid)
        assertNull(invalidPort.installationDigest)
        assertNull(invalidPort.receipt)

        val throwingPort = FakeStorage()
        val throwing = store(throwingPort) { throw IllegalStateException("no entropy") }
            .recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(throwing)
        assertNull(throwingPort.installationDigest)
        assertNull(throwingPort.receipt)
    }

    @Test fun receiptWithoutInstallationDigestFailsWithoutMutation() {
        val port = FakeStorage(receipt = validReceipt(digest, 1L))
        val before = port.receipt
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertNull(port.installationDigest)
        assertEquals(before, port.receipt)
        assertEquals(0, port.digestCasCalls)
        assertEquals(0, port.receiptCasCalls)
    }

    @Test fun corruptStoredReceiptFailsWithoutMutation() {
        val corrupt = linkedMapOf<String, Any?>("kind" to "explicit-local-choice")
        val port = FakeStorage(installationDigest = digest, receipt = corrupt)
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertEquals(corrupt, port.receipt)
        assertEquals(0, port.digestCasCalls)
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
        assertEquals(0, port.receiptCasCalls)
    }

    @Test fun digestCompareAndSetRefusalDoesNotWriteReceipt() {
        val port = FakeStorage()
        port.digestCasHook = { _, _ -> false }
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertNull(port.installationDigest)
        assertNull(port.receipt)
        assertEquals(0, port.receiptCasCalls)
    }

    @Test fun receiptCompareAndSetRefusalRollsBackGeneratedDigest() {
        val port = FakeStorage()
        port.receiptCasHook = { _, _ -> false }
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertTrue(result.rollbackVerified)
        assertNull(port.installationDigest)
        assertNull(port.receipt)
        assertEquals(2, port.digestCasCalls)
    }

    @Test fun falsePositiveReceiptWriteRollsBackExactTuple() {
        val port = FakeStorage()
        port.receiptCasHook = { _, _ -> true }
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertRejected(result)
        assertTrue(result.rollbackVerified)
        assertNull(port.installationDigest)
        assertNull(port.receipt)
    }

    @Test fun unknownConcurrentDriftIsPreservedWithoutRollbackClaim() {
        val foreign = validReceipt(otherDigest, 99L)
        val port = FakeStorage()
        port.receiptCasHook = { _, _ ->
            port.receipt = foreign
            false
        }
        val result = store(port).recordExplicitLocalChoice(session, null, "first-run")
        assertFalse(result.ok)
        assertFalse(result.rollbackVerified)
        assertEquals(digest, port.installationDigest)
        assertEquals(foreign, port.receipt)
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
        assertNull(port.receipt)
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

        val unstable = FakeStorage(installationDigest = digest, receipt = port.receipt)
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
        var receipt: Map<String, Any?>? = null
    ) : NativeSubtitlePreferenceStore.StoragePort {
        var digestReads = 0
        var receiptReads = 0
        var digestCasCalls = 0
        var receiptCasCalls = 0
        var digestReadHook: ((Int) -> StorageRead<String>?)? = null
        var receiptReadHook: ((Int) -> StorageRead<Map<*, *>>?)? = null
        var digestCasHook: ((String?, String?) -> Boolean)? = null
        var receiptCasHook: ((
            Map<String, Any?>?,
            Map<String, Any?>?
        ) -> Boolean)? = null

        override fun readInstallationDigest(): StorageRead<String>? {
            digestReads++
            return digestReadHook?.invoke(digestReads)
                ?: StorageRead(ok = true, value = installationDigest)
        }

        override fun readPreferenceReceipt(): StorageRead<Map<*, *>>? {
            receiptReads++
            return receiptReadHook?.invoke(receiptReads)
                ?: StorageRead(ok = true, value = receipt)
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
            expected: Map<String, Any?>?,
            update: Map<String, Any?>?
        ): Boolean {
            receiptCasCalls++
            receiptCasHook?.let { return it(expected, update) }
            if (receipt != expected) return false
            receipt = update
            return true
        }
    }
}
