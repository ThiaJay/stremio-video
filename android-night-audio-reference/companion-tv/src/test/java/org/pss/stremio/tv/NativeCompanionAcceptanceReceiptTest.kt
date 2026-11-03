package org.pss.stremio.tv

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCompanionAcceptanceReceiptTest {
    private val source = "a".repeat(64)
    private val executor = "b".repeat(64)
    private val device = "c".repeat(64)
    private val evaluatedAt = "2026-11-03T03:00:00Z"

    private fun expected(
        contractSourceSha256: String? = source,
        deviceIdentityDigest: String? = device,
        deviceModel: String? = "AFTKRT",
        appPackage: String? = "com.stremio.one",
        appVersion: String? = "1.11.2"
    ) = ExpectedCompanionAcceptance(
        contractSourceSha256,
        deviceIdentityDigest,
        deviceModel,
        appPackage,
        appVersion
    )

    private fun receipt(
        schemaVersion: Int? = 1,
        contractId: String? = NativeCompanionAcceptanceReceipt.CONTRACT_ID,
        contractSourceSha256: String? = source,
        executorSourceSha256: String? = executor,
        executorReviewed: Boolean? = true,
        deviceIdentityDigest: String? = device,
        deviceModel: String? = "AFTKRT",
        deviceIdentityStatus: String? = NativeCompanionTextEntryPolicy.VERIFIED_CURRENT_DEVICE,
        appPackage: String? = "com.stremio.one",
        appVersion: String? = "1.11.2",
        route: String? = NativeCompanionTextEntryPolicy.VERIFIED_OFFICIAL_FIRE_TV_APP,
        observedAt: String? = "2026-11-03T02:55:00Z",
        explicitCompanionInputGesture: Boolean? = true,
        searchSurfaceBefore: String? = "stremio",
        typedSearchFocused: Boolean? = true,
        sensitiveInput: Boolean? = false,
        audioCaptured: Boolean? = false,
        accountMutation: Boolean? = false,
        settingsMutation: Boolean? = false,
        playbackStarted: Boolean? = false,
        expectedText: String? = "bounded query",
        readBackText: String? = expectedText,
        searchSurfaceAfter: String? = "stremio",
        resultsRemainInStremio: Boolean? = true
    ) = CompanionAcceptanceReceipt(
        schemaVersion,
        contractId,
        contractSourceSha256,
        executorSourceSha256,
        executorReviewed,
        deviceIdentityDigest,
        deviceModel,
        deviceIdentityStatus,
        appPackage,
        appVersion,
        route,
        observedAt,
        explicitCompanionInputGesture,
        searchSurfaceBefore,
        typedSearchFocused,
        sensitiveInput,
        audioCaptured,
        accountMutation,
        settingsMutation,
        playbackStarted,
        expectedText,
        readBackText,
        searchSurfaceAfter,
        resultsRemainInStremio
    )

    @Test fun exactFreshSourceBoundReceiptIsAcceptedWithoutQueryProjection() {
        val result = NativeCompanionAcceptanceReceipt.validate(
            receipt(), expected(), evaluatedAt
        )
        assertTrue(result.accepted)
        assertEquals("COMPANION_TEXT_ENTRY_ACCEPTED", result.reason)
        assertEquals(device, result.deviceIdentityDigest)
        assertEquals("com.stremio.one", result.appPackage)
        assertEquals("1.11.2", result.appVersion)
        assertEquals("2026-11-03T02:55:00Z", result.observedAt)
        val fields = CompanionAcceptanceValidation::class.java.declaredFields.map { it.name }
        assertFalse(fields.any { it.contains("text", ignoreCase = true) })
        assertFalse(result.toString().contains("bounded query"))
        assertFalse(receipt().toString().contains("bounded query"))
    }

    @Test fun acceptedReceiptAloneCanPromoteCompanionPolicy() {
        val decision = NativeCompanionAcceptanceReceipt.validateAndPlan(
            receipt(), expected(), evaluatedAt
        )
        assertTrue(decision.validation.accepted)
        assertEquals("ACCEPT_COMPANION_TEXT_ENTRY", decision.plan.action)
        assertTrue(decision.plan.acceptance)
    }

    @Test fun nullAndUnsupportedContractsFailClosed() {
        assertEquals(
            "INVALID_INPUT",
            NativeCompanionAcceptanceReceipt.validate(null, expected(), evaluatedAt).reason
        )
        assertEquals(
            "INVALID_INPUT",
            NativeCompanionAcceptanceReceipt.validate(receipt(), null, evaluatedAt).reason
        )
        assertEquals(
            "UNSUPPORTED_RECEIPT_CONTRACT",
            NativeCompanionAcceptanceReceipt.validate(receipt(schemaVersion = 2), expected(), evaluatedAt).reason
        )
        assertEquals(
            "UNSUPPORTED_RECEIPT_CONTRACT",
            NativeCompanionAcceptanceReceipt.validate(receipt(contractId = "other"), expected(), evaluatedAt).reason
        )
    }

    @Test fun exactReviewedSourceBindingIsMandatory() {
        val invalid = listOf(
            receipt(contractSourceSha256 = "A".repeat(64)),
            receipt(contractSourceSha256 = "d".repeat(64)),
            receipt(executorSourceSha256 = "short"),
            receipt(executorReviewed = false),
            receipt(executorReviewed = null)
        )
        for (value in invalid) {
            assertEquals(
                "SOURCE_BINDING_NOT_VERIFIED",
                NativeCompanionAcceptanceReceipt.validate(value, expected(), evaluatedAt).reason
            )
        }
    }

    @Test fun exactPrivateDeviceIdentityAndModelAreMandatory() {
        val invalid = listOf(
            receipt(deviceIdentityDigest = "d".repeat(64)),
            receipt(deviceIdentityDigest = "raw-device-name"),
            receipt(deviceModel = "other"),
            receipt(deviceModel = null),
            receipt(deviceIdentityStatus = "unknown")
        )
        for (value in invalid) {
            assertEquals(
                "DEVICE_IDENTITY_NOT_VERIFIED",
                NativeCompanionAcceptanceReceipt.validate(value, expected(), evaluatedAt).reason
            )
        }
    }

    @Test fun exactAppVersionAndOfficialRouteAreMandatory() {
        val invalid = listOf(
            receipt(appPackage = "org.thiajay.stremiotvcompanion"),
            receipt(appPackage = null),
            receipt(appVersion = "1.11.3"),
            receipt(appVersion = " 1.11.2"),
            receipt(route = "unknown")
        )
        for (value in invalid) {
            assertEquals(
                "APP_OR_ROUTE_MISMATCH",
                NativeCompanionAcceptanceReceipt.validate(value, expected(), evaluatedAt).reason
            )
        }
    }

    @Test fun observationWindowAllowsOnlyTenMinutesAndThirtySecondsFutureSkew() {
        assertTrue(
            NativeCompanionAcceptanceReceipt.validate(
                receipt(observedAt = "2026-11-03T02:50:00Z"), expected(), evaluatedAt
            ).accepted
        )
        assertTrue(
            NativeCompanionAcceptanceReceipt.validate(
                receipt(observedAt = "2026-11-03T03:00:30Z"), expected(), evaluatedAt
            ).accepted
        )
        for (time in listOf(
            "2026-11-03T02:49:59.999Z",
            "2026-11-03T03:00:30.001Z",
            "not-a-time",
            "+999999999-12-31T23:59:59Z"
        )) {
            assertEquals(
                "STALE_OR_INVALID_OBSERVATION_TIME",
                NativeCompanionAcceptanceReceipt.validate(
                    receipt(observedAt = time), expected(), evaluatedAt
                ).reason
            )
        }
    }

    @Test fun explicitFocusedStremioInputPreconditionsAreMandatory() {
        val invalid = listOf(
            receipt(explicitCompanionInputGesture = false),
            receipt(searchSurfaceBefore = "fire-tv-global"),
            receipt(typedSearchFocused = false)
        )
        for (value in invalid) {
            assertEquals(
                "INPUT_PRECONDITION_NOT_PROVEN",
                NativeCompanionAcceptanceReceipt.validate(value, expected(), evaluatedAt).reason
            )
        }
    }

    @Test fun everyPreservationInvariantMustBeExplicitlyFalse() {
        val invalid = listOf(
            receipt(sensitiveInput = true),
            receipt(audioCaptured = true),
            receipt(accountMutation = true),
            receipt(settingsMutation = true),
            receipt(playbackStarted = true),
            receipt(audioCaptured = null)
        )
        for (value in invalid) {
            assertEquals(
                "PRESERVATION_INVARIANT_FAILED",
                NativeCompanionAcceptanceReceipt.validate(value, expected(), evaluatedAt).reason
            )
        }
    }

    @Test fun exactBoundedTextReadbackIsMandatory() {
        val invalid = listOf(
            receipt(expectedText = "", readBackText = ""),
            receipt(expectedText = " padded", readBackText = " padded"),
            receipt(expectedText = "x".repeat(161), readBackText = "x".repeat(161)),
            receipt(expectedText = "one", readBackText = "two"),
            receipt(expectedText = null, readBackText = null)
        )
        for (value in invalid) {
            assertEquals(
                "EXACT_TEXT_DELIVERY_NOT_PROVEN",
                NativeCompanionAcceptanceReceipt.validate(value, expected(), evaluatedAt).reason
            )
        }
    }

    @Test fun resultsMustRemainInsideStremio() {
        for (value in listOf(
            receipt(searchSurfaceAfter = "fire-tv-global"),
            receipt(resultsRemainInStremio = false),
            receipt(resultsRemainInStremio = null)
        )) {
            assertEquals(
                "IN_APP_RESULT_RETENTION_NOT_PROVEN",
                NativeCompanionAcceptanceReceipt.validate(value, expected(), evaluatedAt).reason
            )
        }
    }

    @Test fun rejectedReceiptPreservesTypedSearchWithoutAcceptance() {
        val decision = NativeCompanionAcceptanceReceipt.validateAndPlan(
            receipt(readBackText = "different"), expected(), evaluatedAt
        )
        assertFalse(decision.validation.accepted)
        assertEquals("PRESERVE_TYPED_SEARCH", decision.plan.action)
        assertEquals(
            "COMPANION_ACCEPTANCE_RECEIPT_EXACT_TEXT_DELIVERY_NOT_PROVEN",
            decision.plan.reason
        )
        assertFalse(decision.plan.acceptance)
        assertNull(decision.validation.deviceIdentityDigest)
    }

    @Test fun receiptSchemaCarriesNoRawIdentityOrAudioPayload() {
        val fields = CompanionAcceptanceReceipt::class.java.declaredFields.map { it.name }.toSet()
        assertFalse(fields.any { it.contains("serial", ignoreCase = true) })
        assertFalse(fields.any { it.contains("address", ignoreCase = true) })
        assertFalse(fields.any { it.contains("ip", ignoreCase = true) })
        assertFalse(fields.any { it.contains("audioData", ignoreCase = true) })
        assertFalse(fields.any { it.contains("transcript", ignoreCase = true) })
        assertTrue("deviceIdentityDigest" in fields)
    }
}
