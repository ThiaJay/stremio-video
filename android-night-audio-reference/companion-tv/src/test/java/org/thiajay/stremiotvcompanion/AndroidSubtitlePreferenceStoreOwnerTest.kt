package org.thiajay.stremiotvcompanion

import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AndroidSubtitlePreferenceStoreOwnerTest {
    @Test fun exactPreferencesObjectHasOneProcessLocalOwner() {
        val preferences = FakeSharedPreferences()
        val first = owner(preferences, 0x11)

        assertNotNull(first)
        assertNull(owner(preferences, 0x22))
        assertTrue(first!!.close())

        val reopened = owner(preferences, 0x33)
        assertNotNull(reopened)
        assertTrue(reopened!!.close())
    }

    @Test fun distinctPreferencesObjectsHaveIndependentOwners() {
        val first = owner(FakeSharedPreferences(), 0x11)
        val second = owner(FakeSharedPreferences(), 0x22)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first!!.close())
        assertTrue(second!!.close())
    }

    @Test fun loadWithoutReceiptDoesNotGenerateOrWriteIdentity() {
        val preferences = FakeSharedPreferences()
        var entropyCalls = 0
        val owner = AndroidSubtitlePreferenceStoreOwner.openForTest(preferences) { target ->
            entropyCalls += 1
            target.fill(0x11)
            true
        }!!

        assertNull(owner.loadStoredPreference(enabledPreference(), "English"))
        assertEquals(0, entropyCalls)
        assertTrue(preferences.snapshot().isEmpty())
        assertTrue(owner.close())
    }

    @Test fun explicitChoiceComposesDigestStorageAndCanonicalStore() {
        val preferences = FakeSharedPreferences()
        val owner = owner(preferences, 0x2a)!!

        val recorded = owner.recordExplicitLocalChoice(
            enabledPreference(),
            "English",
            "first-run"
        )

        assertTrue(recorded.ok)
        assertEquals("same-installation-setting", recorded.provenance?.get("kind"))
        assertEquals("2a".repeat(32), preferences.value(DIGEST_KEY))
        assertTrue((preferences.value(RECEIPT_KEY) as String).startsWith("nsp1:"))
        assertEquals(
            recorded.provenance,
            owner.loadStoredPreference(enabledPreference(), "English")
        )
        assertEquals(setOf(DIGEST_KEY, RECEIPT_KEY), preferences.snapshot().keys)
        assertTrue(owner.close())
    }

    @Test fun entropyRefusalLeavesEveryPreferenceUntouched() {
        val preferences = FakeSharedPreferences()
        preferences.seed("unrelated", "preserved")
        val owner = AndroidSubtitlePreferenceStoreOwner.openForTest(preferences) { target ->
            target.fill(0x44)
            false
        }!!

        val result = owner.recordExplicitLocalChoice(
            enabledPreference(),
            "English",
            "subtitle-settings"
        )

        assertFalse(result.ok)
        assertEquals(mapOf("unrelated" to "preserved"), preferences.snapshot())
        assertTrue(owner.close())
    }

    @Test fun explicitOffClearsOnlyReceiptAndKeepsIdentity() {
        val preferences = FakeSharedPreferences()
        preferences.seed("unrelated", "preserved")
        val owner = owner(preferences, 0x35)!!
        assertTrue(
            owner.recordExplicitLocalChoice(
                enabledPreference(),
                "English",
                "subtitle-settings"
            ).ok
        )

        val cleared = owner.clearStoredReceiptForExplicitOff(
            NativeSubtitlePreferenceProvenance.SessionPreference(enabled = false)
        )

        assertTrue(cleared.ok)
        assertNull(preferences.value(RECEIPT_KEY))
        assertEquals("35".repeat(32), preferences.value(DIGEST_KEY))
        assertEquals("preserved", preferences.value("unrelated"))
        assertTrue(owner.close())
    }

    @Test fun foreignThreadCannotUseCloseOrReleaseOwnerLease() {
        val preferences = FakeSharedPreferences()
        val owner = owner(preferences, 0x51)!!
        val foreignCommit = AtomicReference<NativeSubtitlePreferenceStore.CommitResult>()
        val foreignLoad = AtomicReference<Map<String, Any?>?>()
        val foreignClose = AtomicBoolean(true)

        Thread {
            foreignCommit.set(
                owner.recordExplicitLocalChoice(
                    enabledPreference(),
                    "English",
                    "first-run"
                )
            )
            foreignLoad.set(owner.loadStoredPreference(enabledPreference(), "English"))
            foreignClose.set(owner.close())
        }.apply { start(); join() }

        assertFalse(foreignCommit.get().ok)
        assertNull(foreignLoad.get())
        assertFalse(foreignClose.get())
        assertTrue(preferences.snapshot().isEmpty())
        assertNull(owner(preferences, 0x52))
        assertTrue(owner.close())
        assertNotNull(owner(preferences, 0x53)?.also { assertTrue(it.close()) })
    }

    @Test fun closeRevokesOperationsAndFreshOwnerCanReadReceipt() {
        val preferences = FakeSharedPreferences()
        val first = owner(preferences, 0x61)!!
        assertTrue(
            first.recordExplicitLocalChoice(
                enabledPreference(),
                "English",
                "first-run"
            ).ok
        )
        assertTrue(first.close())

        assertNull(first.loadStoredPreference(enabledPreference(), "English"))
        assertFalse(
            first.recordExplicitLocalChoice(
                enabledPreference(),
                "English",
                "first-run"
            ).ok
        )
        assertFalse(first.close())

        var freshEntropyCalls = 0
        val second = AndroidSubtitlePreferenceStoreOwner.openForTest(preferences) { target ->
            freshEntropyCalls += 1
            target.fill(0x62)
            true
        }!!
        assertEquals(
            "same-installation-setting",
            second.loadStoredPreference(enabledPreference(), "English")?.get("kind")
        )
        assertEquals(0, freshEntropyCalls)
        assertEquals("61".repeat(32), preferences.value(DIGEST_KEY))
        assertTrue(second.close())
    }

    @Test fun corruptStoredTypeFailsClosedWithoutOverwrite() {
        val preferences = FakeSharedPreferences()
        preferences.seed(DIGEST_KEY, 7)
        val owner = owner(preferences, 0x71)!!

        assertNull(owner.loadStoredPreference(enabledPreference(), "English"))
        assertFalse(
            owner.recordExplicitLocalChoice(
                enabledPreference(),
                "English",
                "first-run"
            ).ok
        )
        assertEquals(7, preferences.value(DIGEST_KEY))
        assertNull(preferences.value(RECEIPT_KEY))
        assertTrue(owner.close())
    }

    private fun owner(
        preferences: FakeSharedPreferences,
        value: Int
    ): AndroidSubtitlePreferenceStoreOwner? =
        AndroidSubtitlePreferenceStoreOwner.openForTest(preferences) { target ->
            target.fill(value.toByte())
            true
        }

    private fun enabledPreference() =
        NativeSubtitlePreferenceProvenance.SessionPreference(
            enabled = true,
            language = "English",
            source = "embedded"
        )

    private companion object {
        const val DIGEST_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.installation_digest.v1"
        const val RECEIPT_KEY =
            "org.thiajay.stremiotvcompanion.subtitle.preference.receipt.v1"
    }
}

