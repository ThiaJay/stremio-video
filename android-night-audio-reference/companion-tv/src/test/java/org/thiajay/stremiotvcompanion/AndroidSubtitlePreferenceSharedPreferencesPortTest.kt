package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSubtitlePreferenceSharedPreferencesPortTest {
    @Test fun absentSlotsReadAsSuccessfulNullWithoutMutation() {
        val fake = FakeSharedPreferences()
        val port = AndroidSubtitlePreferenceSharedPreferencesPort(fake)

        assertEquals(NativeSubtitlePreferenceStore.StorageRead<String>(true, null), port.readInstallationDigest())
        assertEquals(NativeSubtitlePreferenceStore.StorageRead<String>(true, null), port.readPreferenceReceipt())
        assertTrue(fake.snapshot().isEmpty())
    }

    @Test fun exactCommitsUseOnlyTheTwoFixedPrivateKeys() {
        val fake = FakeSharedPreferences()
        val port = AndroidSubtitlePreferenceSharedPreferencesPort(fake)

        assertTrue(port.compareAndSetInstallationDigest(null, DIGEST))
        assertTrue(port.compareAndSetPreferenceReceipt(null, RECEIPT))
        assertEquals(setOf(DIGEST_KEY, RECEIPT_KEY), fake.snapshot().keys)
        assertEquals(DIGEST, port.readInstallationDigest().value)
        assertEquals(RECEIPT, port.readPreferenceReceipt().value)
    }

    @Test fun compareAndSetMismatchDoesNotWrite() {
        val fake = FakeSharedPreferences()
        fake.seed(DIGEST_KEY, DIGEST)
        val port = AndroidSubtitlePreferenceSharedPreferencesPort(fake)

        assertFalse(port.compareAndSetInstallationDigest(null, OTHER_DIGEST))
        assertEquals(DIGEST, fake.value(DIGEST_KEY))
    }

    @Test fun wrongTypeAndThrowingReadsFailClosed() {
        val wrongType = FakeSharedPreferences()
        wrongType.seed(DIGEST_KEY, 7)
        val wrongTypePort = AndroidSubtitlePreferenceSharedPreferencesPort(wrongType)
        assertFalse(wrongTypePort.readInstallationDigest().ok)
        assertFalse(wrongTypePort.compareAndSetInstallationDigest(null, DIGEST))
        assertEquals(7, wrongType.value(DIGEST_KEY))

        val throwing = FakeSharedPreferences()
        throwing.setThrowReads(true)
        val throwingPort = AndroidSubtitlePreferenceSharedPreferencesPort(throwing)
        assertFalse(throwingPort.readPreferenceReceipt().ok)
        assertFalse(throwingPort.compareAndSetPreferenceReceipt(null, RECEIPT))
    }

    @Test fun commitRefusalFailsClosedWithoutAWriteClaim() {
        val fake = FakeSharedPreferences()
        fake.setFailCommit(true)
        val port = AndroidSubtitlePreferenceSharedPreferencesPort(fake)

        assertFalse(port.compareAndSetPreferenceReceipt(null, RECEIPT))
        assertNull(fake.value(RECEIPT_KEY))
    }

    @Test fun changedPostCommitReadbackFailsClosedAndPreservesDrift() {
        val fake = FakeSharedPreferences()
        fake.setMutateAfterCommitKey(RECEIPT_KEY)
        val port = AndroidSubtitlePreferenceSharedPreferencesPort(fake)

        assertFalse(port.compareAndSetPreferenceReceipt(null, RECEIPT))
        assertEquals("__drift__", fake.value(RECEIPT_KEY))
    }

    @Test fun nullUpdateRemovesOnlyTheExactReceiptSlot() {
        val fake = FakeSharedPreferences()
        fake.seed(DIGEST_KEY, DIGEST)
        fake.seed(RECEIPT_KEY, RECEIPT)
        fake.seed("unrelated", "preserved")
        val port = AndroidSubtitlePreferenceSharedPreferencesPort(fake)

        assertTrue(port.compareAndSetPreferenceReceipt(RECEIPT, null))
        assertEquals(DIGEST, fake.value(DIGEST_KEY))
        assertNull(fake.value(RECEIPT_KEY))
        assertEquals("preserved", fake.value("unrelated"))
    }

    @Test fun adapterInstancesSharingOneObjectRespectExactCas() {
        val fake = FakeSharedPreferences()
        val first = AndroidSubtitlePreferenceSharedPreferencesPort(fake)
        val second = AndroidSubtitlePreferenceSharedPreferencesPort(fake)

        assertTrue(first.compareAndSetPreferenceReceipt(null, RECEIPT))
        assertFalse(second.compareAndSetPreferenceReceipt(null, OTHER_RECEIPT))
        assertTrue(second.compareAndSetPreferenceReceipt(RECEIPT, OTHER_RECEIPT))
        assertEquals(OTHER_RECEIPT, first.readPreferenceReceipt().value)
    }

    @Test fun editAndCommitExceptionsFailClosed() {
        val editFailure = FakeSharedPreferences()
        editFailure.setThrowEdit(true)
        assertFalse(
            AndroidSubtitlePreferenceSharedPreferencesPort(editFailure)
                .compareAndSetInstallationDigest(null, DIGEST)
        )

        val commitFailure = FakeSharedPreferences()
        commitFailure.setThrowCommit(true)
        assertFalse(
            AndroidSubtitlePreferenceSharedPreferencesPort(commitFailure)
                .compareAndSetPreferenceReceipt(null, RECEIPT)
        )
    }

    @Test fun foreignThreadAndClosedPortFailClosed() {
        val fake = FakeSharedPreferences()
        val port = AndroidSubtitlePreferenceSharedPreferencesPort(fake)
        val foreignRead = AtomicReference<NativeSubtitlePreferenceStore.StorageRead<String>>()
        val foreignCas = AtomicBoolean(true)
        val foreignClose = AtomicBoolean(true)

        Thread {
            foreignRead.set(port.readInstallationDigest())
            foreignCas.set(port.compareAndSetInstallationDigest(null, DIGEST))
            foreignClose.set(port.close())
        }.apply { start(); join() }

        assertFalse(foreignRead.get().ok)
        assertFalse(foreignCas.get())
        assertFalse(foreignClose.get())
        assertTrue(port.compareAndSetInstallationDigest(null, DIGEST))
        assertTrue(port.close())
        assertFalse(port.readInstallationDigest().ok)
        assertFalse(port.compareAndSetInstallationDigest(DIGEST, OTHER_DIGEST))
        assertFalse(port.close())
    }

    private companion object {
        const val DIGEST_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.installation_digest.v1"
        const val RECEIPT_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.receipt.v1"
        const val DIGEST =
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
        const val OTHER_DIGEST =
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
        const val RECEIPT = "nsp1:receipt"
        const val OTHER_RECEIPT = "nsp1:other"
    }
}
