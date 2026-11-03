package org.pss.stremio.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCompanionTextEntryPolicyTest {
    private fun facts(
        searchSurfaceActive: Boolean? = true,
        typedSearchFocused: Boolean? = true,
        explicitCompanionInputGesture: Boolean? = true,
        sensitiveInput: Boolean? = false,
        deliveryConfirmedInStremio: Boolean? = false,
        resultsRemainInStremio: Boolean? = false,
        companionRoute: String? = NativeCompanionTextEntryPolicy.VERIFIED_OFFICIAL_FIRE_TV_APP,
        companionSessionIdentity: String? = NativeCompanionTextEntryPolicy.VERIFIED_CURRENT_DEVICE
    ) = CompanionTextEntryFacts(
        searchSurfaceActive,
        typedSearchFocused,
        explicitCompanionInputGesture,
        sensitiveInput,
        deliveryConfirmedInStremio,
        resultsRemainInStremio,
        companionRoute,
        companionSessionIdentity
    )

    @Test fun supportedRouteIsOnlyOfferedBeforeLiveAcceptance() {
        val plan = NativeCompanionTextEntryPolicy.plan(facts())
        assertEquals("OFFER_COMPANION_TEXT_ENTRY", plan.action)
        assertEquals("SUPPORTED_TEXT_ROUTE_AWAITING_ACCEPTANCE", plan.reason)
        assertFalse(plan.acceptance)
    }

    @Test fun acceptanceRequiresDeliveryAndResultsToRemainInsideStremio() {
        assertFalse(
            NativeCompanionTextEntryPolicy.plan(
                facts(deliveryConfirmedInStremio = true)
            ).acceptance
        )
        val accepted = NativeCompanionTextEntryPolicy.plan(
            facts(deliveryConfirmedInStremio = true, resultsRemainInStremio = true)
        )
        assertEquals("ACCEPT_COMPANION_TEXT_ENTRY", accepted.action)
        assertTrue(accepted.acceptance)
    }

    @Test fun unknownOrUnavailableRoutePreservesTypedSearch() {
        for (route in listOf("unknown", "unavailable")) {
            val plan = NativeCompanionTextEntryPolicy.plan(facts(companionRoute = route))
            assertEquals("PRESERVE_TYPED_SEARCH", plan.action)
            assertEquals("COMPANION_ROUTE_NOT_VERIFIED", plan.reason)
            assertFalse(plan.acceptance)
        }
    }

    @Test fun differentOrUnknownDeviceCannotBeAccepted() {
        for (identity in listOf("mismatch", "unknown")) {
            val plan = NativeCompanionTextEntryPolicy.plan(
                facts(companionSessionIdentity = identity)
            )
            assertEquals("COMPANION_DEVICE_NOT_VERIFIED", plan.reason)
            assertFalse(plan.acceptance)
        }
    }

    @Test fun missingExplicitGestureNeverOffersCompanionEntry() {
        val plan = NativeCompanionTextEntryPolicy.plan(
            facts(explicitCompanionInputGesture = false)
        )
        assertEquals("EXPLICIT_USER_GESTURE_REQUIRED", plan.reason)
        assertFalse(plan.acceptance)
    }

    @Test fun sensitiveInputIsAlwaysForbidden() {
        val plan = NativeCompanionTextEntryPolicy.plan(facts(sensitiveInput = true))
        assertEquals("SENSITIVE_INPUT_FORBIDDEN", plan.reason)
        assertFalse(plan.acceptance)
    }

    @Test fun inactiveSearchSurfaceCannotOfferCompanionEntry() {
        val plan = NativeCompanionTextEntryPolicy.plan(facts(searchSurfaceActive = false))
        assertEquals("STREMIO_SEARCH_NOT_ACTIVE", plan.reason)
        assertFalse(plan.acceptance)
    }

    @Test fun unfocusedFieldRequestsFocusWithoutAcceptance() {
        val plan = NativeCompanionTextEntryPolicy.plan(facts(typedSearchFocused = false))
        assertEquals("FOCUS_TYPED_SEARCH", plan.action)
        assertEquals("TEXT_FIELD_NOT_FOCUSED", plan.reason)
        assertFalse(plan.acceptance)
    }

    @Test fun nullOrIncompleteFactsFailClosed() {
        assertEquals(
            "INVALID_OR_INCOMPLETE_FACTS",
            NativeCompanionTextEntryPolicy.plan(null).reason
        )
        val incomplete = listOf(
            facts(searchSurfaceActive = null),
            facts(typedSearchFocused = null),
            facts(explicitCompanionInputGesture = null),
            facts(sensitiveInput = null),
            facts(deliveryConfirmedInStremio = null),
            facts(resultsRemainInStremio = null),
            facts(companionRoute = null),
            facts(companionSessionIdentity = null)
        )
        for (value in incomplete) {
            assertEquals(
                "INVALID_OR_INCOMPLETE_FACTS",
                NativeCompanionTextEntryPolicy.plan(value).reason
            )
        }
    }

    @Test fun unrecognisedRouteAndIdentityFailClosed() {
        assertEquals(
            "INVALID_OR_INCOMPLETE_FACTS",
            NativeCompanionTextEntryPolicy.plan(facts(companionRoute = "claimed-phone-dictation")).reason
        )
        assertEquals(
            "INVALID_OR_INCOMPLETE_FACTS",
            NativeCompanionTextEntryPolicy.plan(facts(companionSessionIdentity = "trusted")).reason
        )
    }

    @Test fun dataObjectsDoNotExposeAnyMutationOrVoiceOperation() {
        val fieldNames = CompanionTextEntryFacts::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fieldNames.any { it.contains("audio", ignoreCase = true) })
        assertFalse(fieldNames.any { it.contains("microphone", ignoreCase = true) })
        assertFalse(fieldNames.any { it.contains("permission", ignoreCase = true) })
        assertFalse(fieldNames.any { it.contains("locale", ignoreCase = true) })
        assertFalse(fieldNames.any { it.contains("account", ignoreCase = true) })
    }
}
