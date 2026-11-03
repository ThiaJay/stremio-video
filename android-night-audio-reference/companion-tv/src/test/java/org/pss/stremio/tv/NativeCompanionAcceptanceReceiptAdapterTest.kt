package org.pss.stremio.tv

import java.util.AbstractMap.SimpleImmutableEntry
import kotlin.collections.AbstractMap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NativeCompanionAcceptanceReceiptAdapterTest {
    private val source = "a".repeat(64)
    private val device = "c".repeat(64)
    private val evaluatedAt = "2026-11-03T04:00:00Z"

    @Test fun exactClosedMapAdaptsAndValidatesWithoutQueryProjection() {
        val raw = wire()
        val adapted = NativeCompanionAcceptanceReceiptAdapter.adapt(raw)
        assertTrue(adapted.accepted)
        assertEquals("WIRE_RECEIPT_ADAPTED", adapted.reason)
        assertNotNull(adapted.receipt)
        assertFalse(adapted.toString().contains("bounded query"))

        val decision = NativeCompanionAcceptanceReceiptAdapter.adaptValidateAndPlan(
            raw,
            expected(),
            evaluatedAt
        )
        assertTrue(decision.validation.accepted)
        assertEquals("ACCEPT_COMPANION_TEXT_ENTRY", decision.plan.action)
        assertFalse(decision.toString().contains("bounded query"))
    }

    @Test fun missingAndUnknownKeysRejectClosedSchema() {
        val missing = wire().apply { remove("route") }
        val unknown = wire().apply { put("unknown", "value") }
        val replaced = wire().apply {
            remove("route")
            put("unknown", "value")
        }
        for (value in listOf(missing, unknown, replaced)) {
            val result = NativeCompanionAcceptanceReceiptAdapter.adapt(value)
            assertFalse(result.accepted)
            assertEquals("WIRE_SCHEMA_MISMATCH", result.reason)
            assertNull(result.receipt)
        }
    }

    @Test fun wrongScalarTypesAndNullsRejectBeforeValidation() {
        val invalid = listOf(
            wire().apply { this["schemaVersion"] = 1.0 },
            wire().apply { this["executorReviewed"] = "true" },
            wire().apply { this["resultsRemainInStremio"] = null },
            wire().apply { this["appVersion"] = 112L },
            wire().apply { this["expectedText"] = listOf("bounded query") }
        )
        for (value in invalid) {
            assertEquals(
                "WIRE_TYPE_OR_BOUND_VIOLATION",
                NativeCompanionAcceptanceReceiptAdapter.adapt(value).reason
            )
        }
    }

    @Test fun integralSchemaNumbersRemainTypedForContractValidation() {
        for (one in listOf<Any>(1.toByte(), 1.toShort(), 1, 1L)) {
            val adapted = NativeCompanionAcceptanceReceiptAdapter.adapt(
                wire().apply { this["schemaVersion"] = one }
            )
            assertTrue(adapted.accepted)
            assertEquals(1, adapted.receipt?.schemaVersion)
        }
        assertEquals(
            "UNSUPPORTED_RECEIPT_CONTRACT",
            NativeCompanionAcceptanceReceiptAdapter.adaptValidateAndPlan(
                wire().apply { this["schemaVersion"] = 2L },
                expected(),
                evaluatedAt
            ).validation.reason
        )
    }

    @Test fun oversizedAndControlCharacterTextRejectsWireObject() {
        val invalid = listOf(
            wire().apply { this["expectedText"] = "x".repeat(161) },
            wire().apply { this["deviceModel"] = "AFTKRT\nother" },
            wire().apply { this["observedAt"] = "x".repeat(161) }
        )
        for (value in invalid) {
            assertEquals(
                "WIRE_TYPE_OR_BOUND_VIOLATION",
                NativeCompanionAcceptanceReceiptAdapter.adapt(value).reason
            )
        }
    }

    @Test fun aggregateTextBudgetRejectsIndividuallyBoundedFields() {
        val raw = wire()
        for (key in listOf(
            "contractId",
            "contractSourceSha256",
            "executorSourceSha256",
            "deviceIdentityDigest",
            "deviceModel",
            "deviceIdentityStatus",
            "appPackage"
        )) {
            raw[key] = "x".repeat(160)
        }
        assertEquals(
            "WIRE_TYPE_OR_BOUND_VIOLATION",
            NativeCompanionAcceptanceReceiptAdapter.adapt(raw).reason
        )
    }

    @Test fun throwingMapFailsClosedWithoutEscaping() {
        val result = NativeCompanionAcceptanceReceiptAdapter.adapt(ThrowingEntriesMap())
        assertFalse(result.accepted)
        assertEquals("INVALID_OR_HOSTILE_WIRE_INPUT", result.reason)
    }

    @Test fun iteratorCardinalityDriftFailsClosed() {
        val source = wire().entries.map { it.key to it.value }
        val result = NativeCompanionAcceptanceReceiptAdapter.adapt(
            CardinalityDriftMap(source, source.size - 1)
        )
        assertFalse(result.accepted)
        assertEquals("WIRE_SCHEMA_MISMATCH", result.reason)
    }

    @Test fun duplicateIteratorKeyFailsClosed() {
        val source = wire().entries.map { it.key to it.value }.toMutableList()
        source[source.lastIndex] = source.first()
        val result = NativeCompanionAcceptanceReceiptAdapter.adapt(
            CardinalityDriftMap(source, source.size)
        )
        assertFalse(result.accepted)
        assertEquals("WIRE_SCHEMA_MISMATCH", result.reason)
    }

    @Test fun adaptedReceiptIsDetachedFromLaterCallerMutation() {
        val raw = wire()
        val adapted = NativeCompanionAcceptanceReceiptAdapter.adapt(raw)
        raw["readBackText"] = "later mutation"
        raw["resultsRemainInStremio"] = false
        assertEquals("bounded query", adapted.receipt?.readBackText)
        assertEquals(true, adapted.receipt?.resultsRemainInStremio)
        assertTrue(
            NativeCompanionAcceptanceReceipt.validate(
                adapted.receipt,
                expected(),
                evaluatedAt
            ).accepted
        )
    }

    @Test fun rejectedWirePreservesTypedSearchAndNeverInventsAcceptance() {
        val decision = NativeCompanionAcceptanceReceiptAdapter.adaptValidateAndPlan(
            wire().apply { remove("route") },
            expected(),
            evaluatedAt
        )
        assertFalse(decision.validation.accepted)
        assertEquals("RECEIPT_WIRE_WIRE_SCHEMA_MISMATCH", decision.validation.reason)
        assertEquals("PRESERVE_TYPED_SEARCH", decision.plan.action)
        assertFalse(decision.plan.acceptance)
    }

    @Test fun adapterSchemaExposesNoMutationOrAudioPayloadOperation() {
        val fieldNames = CompanionAcceptanceAdaptation::class.java.declaredFields.map { it.name }
        assertFalse(fieldNames.any { it.contains("query", ignoreCase = true) })
        assertFalse(fieldNames.any { it.contains("audio", ignoreCase = true) })
        assertFalse(fieldNames.any { it.contains("microphone", ignoreCase = true) })
        assertFalse(fieldNames.any { it.contains("accountMutation", ignoreCase = true) })
    }

    private fun expected() = ExpectedCompanionAcceptance(
        contractSourceSha256 = source,
        deviceIdentityDigest = device,
        deviceModel = "AFTKRT",
        appPackage = "com.stremio.one",
        appVersion = "1.11.2"
    )

    private fun wire() = linkedMapOf<String, Any?>(
        "schemaVersion" to 1L,
        "contractId" to NativeCompanionAcceptanceReceipt.CONTRACT_ID,
        "contractSourceSha256" to source,
        "executorSourceSha256" to "b".repeat(64),
        "executorReviewed" to true,
        "deviceIdentityDigest" to device,
        "deviceModel" to "AFTKRT",
        "deviceIdentityStatus" to NativeCompanionTextEntryPolicy.VERIFIED_CURRENT_DEVICE,
        "appPackage" to "com.stremio.one",
        "appVersion" to "1.11.2",
        "route" to NativeCompanionTextEntryPolicy.VERIFIED_OFFICIAL_FIRE_TV_APP,
        "observedAt" to "2026-11-03T03:55:00Z",
        "explicitCompanionInputGesture" to true,
        "searchSurfaceBefore" to "stremio",
        "typedSearchFocused" to true,
        "sensitiveInput" to false,
        "audioCaptured" to false,
        "accountMutation" to false,
        "settingsMutation" to false,
        "playbackStarted" to false,
        "expectedText" to "bounded query",
        "readBackText" to "bounded query",
        "searchSurfaceAfter" to "stremio",
        "resultsRemainInStremio" to true
    )

    private class ThrowingEntriesMap : AbstractMap<String, Any?>() {
        override val entries: Set<Map.Entry<String, Any?>>
            get() = throw IllegalStateException("hostile map")
    }

    private class CardinalityDriftMap(
        source: List<Pair<String, Any?>>,
        private val advertisedSize: Int
    ) : AbstractMap<String, Any?>() {
        private val detached = source.map { (key, value) ->
            SimpleImmutableEntry<String, Any?>(key, value)
        }
        override val entries: Set<Map.Entry<String, Any?>> =
            object : AbstractSet<Map.Entry<String, Any?>>() {
                override val size: Int
                    get() = advertisedSize

                override fun iterator(): Iterator<Map.Entry<String, Any?>> =
                    detached.iterator()
            }
    }
}
