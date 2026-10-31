package org.thiajay.stremiotvcompanion

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import org.thiajay.stremiotvcompanion.NativeSubtitlePreferenceProvenance.SessionPreference

class NativeSubtitlePreferenceProvenanceTest {
    private val installation = "a".repeat(64)
    private val session = SessionPreference(
        enabled = true,
        language = "eng",
        source = "embedded"
    )

    @Test fun sideBySideFirstRunRequiresLocalChoiceDespiteLanguageDefaults() {
        assertTrue(needs(null, "eng", null, null))
        assertTrue(needs(SessionPreference(enabled = true), "eng", null, installation))
    }

    @Test fun explicitOffRemainsAuthoritativeWithoutInventingConsent() {
        assertFalse(needs(SessionPreference(enabled = false), "eng", null, installation))
        assertFalse(needs(null, null, null, null))
    }

    @Test fun sameInstallationReceiptMustBindExactPreferenceAndInstallation() {
        val receipt = sameInstallationReceipt()
        assertTrue(verify(session, "eng", receipt, installation))
        assertFalse(needs(session, "eng", receipt, installation))
        assertFalse(verify(session, "eng", receipt, "b".repeat(64)))
    }

    @Test fun malformedDigestAndRevisionFailClosed() {
        assertFalse(verify(session, "eng", sameInstallationReceipt(), "A".repeat(64)))
        assertFalse(verify(session, "eng", sameInstallationReceipt(), "short"))
        for (revision in listOf<Any>(0, -1, 1.5, "1")) {
            assertFalse(
                verify(
                    session,
                    "eng",
                    sameInstallationReceipt(mapOf("preferenceRevision" to revision)),
                    installation
                )
            )
        }
    }

    @Test fun receiptCannotSurviveBoundLanguageOrSourceChange() {
        val receipt = sameInstallationReceipt()
        assertFalse(verify(session.copy(language = "fra"), "eng", receipt, installation))
        assertFalse(verify(session.copy(source = "external"), "eng", receipt, installation))
        assertFalse(verify(session, "fra", receipt, installation))
    }

    @Test fun bindingIsNormalisedDetachedAndClosed() {
        val binding = NativeSubtitlePreferenceProvenance.preferenceBinding(
            SessionPreference(true, " EN_us ", "embedded"),
            " EN_GB "
        )
        assertEquals(
            mapOf(
                "sessionEnabled" to true,
                "sessionLanguage" to "en-us",
                "globalLanguage" to "en-gb",
                "sessionSource" to "embedded"
            ),
            binding
        )
        @Suppress("UNCHECKED_CAST")
        val mutable = binding as MutableMap<String, Any?>
        assertThrows(UnsupportedOperationException::class.java) {
            mutable["accountHydrated"] = true
        }
        assertFalse(
            verify(
                session,
                "eng",
                sameInstallationReceipt(
                    mapOf(
                        "binding" to (
                            NativeSubtitlePreferenceProvenance.preferenceBinding(session, "eng") +
                                mapOf("accountHydrated" to true)
                            )
                    )
                ),
                installation
            )
        )
    }

    @Test fun explicitChoiceAcceptsOnlyReviewedSurfacesAndEnabledSession() {
        for (surface in listOf("first-run", "subtitle-settings")) {
            val receipt = issue(surface = surface)
            assertEquals("explicit-local-choice", receipt?.get("kind"))
            assertTrue(verify(session, "eng", receipt, installation))
        }
        assertNull(issue(surface = "player-overlay"))
        assertNull(issue(sessionPreference = SessionPreference(enabled = false)))
    }

    @Test fun explicitChoiceEventUsesExactClosedSchema() {
        val injected = choiceEvent().toMutableMap().apply {
            put("accountHydrated", true)
        }
        assertNull(
            NativeSubtitlePreferenceProvenance.issueExplicitLocalChoiceReceipt(
                installation,
                1,
                session,
                "eng",
                injected
            )
        )
        assertNull(issue(choice = "disabled"))
        assertNull(issue(eventInstallation = "b".repeat(64)))
    }

    @Test fun sameInstallationReceiptCannotMasqueradeAsUiChoice() {
        val injected = sameInstallationReceipt(
            mapOf("surface" to "first-run", "choice" to "enabled")
        )
        assertFalse(verify(session, "eng", injected, installation))
    }

    @Test fun exactInstallationLocalReadbackPromotesReceipt() {
        val explicit = issue()!!
        val promoted = NativeSubtitlePreferenceProvenance.promoteStoredPreferenceReceipt(
            installation,
            session,
            "eng",
            explicit,
            readback(explicit)
        )
        assertEquals("same-installation-setting", promoted?.get("kind"))
        assertEquals(1L, promoted?.get("preferenceRevision"))
        assertTrue(verify(session, "eng", promoted, installation))
    }

    @Test fun promotionRejectsScopeInstallationRevisionAndBindingDrift() {
        val explicit = issue()!!
        assertNull(promote(explicit, readback(explicit, mapOf("scope" to "account-hydrated"))))
        assertNull(promote(explicit, readback(explicit, mapOf("installationDigest" to "b".repeat(64)))))
        assertNull(promote(explicit, readback(explicit, mapOf("preferenceRevision" to 2))))
        assertNull(
            promote(
                explicit,
                readback(
                    explicit,
                    mapOf(
                        "binding" to NativeSubtitlePreferenceProvenance.preferenceBinding(
                            session.copy(language = "fra"),
                            "eng"
                        )
                    )
                )
            )
        )
    }

    @Test fun promotionRejectsInjectedStorageOrReceiptClaimsWithoutMutation() {
        val explicit = issue()!!
        val originalEvent = choiceEvent()
        val injectedReadback = readback(explicit).toMutableMap().apply {
            put("migratedFromOfficialApp", true)
        }
        assertNull(promote(explicit, injectedReadback))
        assertNull(
            promote(
                explicit.toMutableMap().apply { put("accountHydrated", true) },
                readback(explicit)
            )
        )
        issue(localChoiceEvent = originalEvent)
        assertEquals(choiceEvent(), originalEvent)
    }

    private fun verify(
        preference: SessionPreference?,
        globalLanguage: String?,
        evidence: Map<*, *>?,
        digest: String?
    ): Boolean = NativeSubtitlePreferenceProvenance.verifySubtitlePreferenceProvenance(
        preference,
        globalLanguage,
        evidence,
        digest
    )

    private fun needs(
        preference: SessionPreference?,
        globalLanguage: String?,
        evidence: Map<*, *>?,
        digest: String?
    ): Boolean = NativeSubtitlePreferenceProvenance.needsSubtitlePreferenceChoice(
        preference,
        globalLanguage,
        evidence,
        digest
    )

    private fun sameInstallationReceipt(
        overrides: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "schemaVersion" to 1L,
        "kind" to "same-installation-setting",
        "installationDigest" to installation,
        "preferenceRevision" to 1L,
        "binding" to NativeSubtitlePreferenceProvenance.preferenceBinding(session, "eng")
    ).apply { putAll(overrides) }

    private fun choiceEvent(
        surface: String = "first-run",
        choice: String = "enabled",
        eventInstallation: String = installation
    ): Map<String, Any?> = linkedMapOf(
        "installationDigest" to eventInstallation,
        "preferenceRevision" to 1L,
        "surface" to surface,
        "choice" to choice
    )

    private fun issue(
        surface: String = "first-run",
        choice: String = "enabled",
        eventInstallation: String = installation,
        sessionPreference: SessionPreference = session,
        localChoiceEvent: Map<String, Any?> = choiceEvent(
            surface,
            choice,
            eventInstallation
        )
    ): Map<String, Any?>? =
        NativeSubtitlePreferenceProvenance.issueExplicitLocalChoiceReceipt(
            installation,
            1L,
            sessionPreference,
            "eng",
            localChoiceEvent
        )

    private fun readback(
        receipt: Map<String, Any?>,
        overrides: Map<String, Any?> = emptyMap()
    ): Map<String, Any?> = linkedMapOf<String, Any?>(
        "scope" to "installation-local",
        "installationDigest" to installation,
        "preferenceRevision" to receipt["preferenceRevision"],
        "binding" to receipt["binding"]
    ).apply { putAll(overrides) }

    private fun promote(
        receipt: Map<*, *>,
        storageReadback: Map<*, *>
    ): Map<String, Any?>? =
        NativeSubtitlePreferenceProvenance.promoteStoredPreferenceReceipt(
            installation,
            session,
            "eng",
            receipt,
            storageReadback
        )
}
