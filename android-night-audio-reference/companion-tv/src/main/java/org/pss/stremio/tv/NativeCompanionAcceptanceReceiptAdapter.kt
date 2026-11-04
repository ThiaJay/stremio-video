package org.pss.stremio.tv

data class CompanionAcceptanceAdaptation(
    val accepted: Boolean,
    val reason: String,
    val receipt: CompanionAcceptanceReceipt? = null
) {
    override fun toString() = "CompanionAcceptanceAdaptation(accepted=$accepted, reason=$reason, query=REDACTED)"
}

/**
 * Closed-schema boundary for an untrusted decoded receipt object. The source
 * map is snapshotted once, exact keys and bounded scalar values are required,
 * and no query is included in diagnostic output.
 */
object NativeCompanionAcceptanceReceiptAdapter {
    private const val MAX_TEXT_LENGTH = 160
    private const val MAX_TOTAL_TEXT_LENGTH = 1_024
    private val keys = setOf(
        "schemaVersion",
        "contractId",
        "contractSourceSha256",
        "executorSourceSha256",
        "executorReviewed",
        "deviceIdentityDigest",
        "deviceModel",
        "deviceIdentityStatus",
        "appPackage",
        "appVersion",
        "route",
        "observedAt",
        "explicitCompanionInputGesture",
        "searchSurfaceBefore",
        "typedSearchFocused",
        "sensitiveInput",
        "audioCaptured",
        "accountMutation",
        "settingsMutation",
        "playbackStarted",
        "expectedText",
        "readBackText",
        "searchSurfaceAfter",
        "resultsRemainInStremio"
    )

    fun adapt(raw: Map<*, *>?): CompanionAcceptanceAdaptation {
        if (raw == null) return reject("INVALID_OR_HOSTILE_WIRE_INPUT")
        val snapshot = try {
            val advertisedSize = raw.size
            if (advertisedSize != keys.size) return reject("WIRE_SCHEMA_MISMATCH")
            val copy = LinkedHashMap<String, Any?>(advertisedSize)
            for (entry in raw.entries) {
                val key = entry.key as? String
                    ?: return reject("WIRE_SCHEMA_MISMATCH")
                if (copy.containsKey(key)) return reject("WIRE_SCHEMA_MISMATCH")
                copy[key] = entry.value
            }
            if (raw.size != advertisedSize || copy.size != advertisedSize || copy.keys != keys) {
                return reject("WIRE_SCHEMA_MISMATCH")
            }
            copy
        } catch (_: RuntimeException) {
            return reject("INVALID_OR_HOSTILE_WIRE_INPUT")
        }

        val strings = keys.filter { key -> key !in booleanKeys && key != "schemaVersion" }
            .map { key -> snapshot[key] as? String ?: return reject("WIRE_TYPE_OR_BOUND_VIOLATION") }
        if (strings.any { !boundedText(it) } || strings.sumOf { it.length } > MAX_TOTAL_TEXT_LENGTH) {
            return reject("WIRE_TYPE_OR_BOUND_VIOLATION")
        }
        val schemaVersion = wholeNumber(snapshot["schemaVersion"])
            ?: return reject("WIRE_TYPE_OR_BOUND_VIOLATION")
        if (schemaVersion !in Int.MIN_VALUE.toLong()..Int.MAX_VALUE.toLong()) {
            return reject("WIRE_TYPE_OR_BOUND_VIOLATION")
        }
        val booleans = booleanKeys.associateWith { key ->
            snapshot[key] as? Boolean ?: return reject("WIRE_TYPE_OR_BOUND_VIOLATION")
        }

        return CompanionAcceptanceAdaptation(
            accepted = true,
            reason = "WIRE_RECEIPT_ADAPTED",
            receipt = CompanionAcceptanceReceipt(
                schemaVersion = schemaVersion.toInt(),
                contractId = snapshot.string("contractId"),
                contractSourceSha256 = snapshot.string("contractSourceSha256"),
                executorSourceSha256 = snapshot.string("executorSourceSha256"),
                executorReviewed = booleans.getValue("executorReviewed"),
                deviceIdentityDigest = snapshot.string("deviceIdentityDigest"),
                deviceModel = snapshot.string("deviceModel"),
                deviceIdentityStatus = snapshot.string("deviceIdentityStatus"),
                appPackage = snapshot.string("appPackage"),
                appVersion = snapshot.string("appVersion"),
                route = snapshot.string("route"),
                observedAt = snapshot.string("observedAt"),
                explicitCompanionInputGesture = booleans.getValue("explicitCompanionInputGesture"),
                searchSurfaceBefore = snapshot.string("searchSurfaceBefore"),
                typedSearchFocused = booleans.getValue("typedSearchFocused"),
                sensitiveInput = booleans.getValue("sensitiveInput"),
                audioCaptured = booleans.getValue("audioCaptured"),
                accountMutation = booleans.getValue("accountMutation"),
                settingsMutation = booleans.getValue("settingsMutation"),
                playbackStarted = booleans.getValue("playbackStarted"),
                expectedText = snapshot.string("expectedText"),
                readBackText = snapshot.string("readBackText"),
                searchSurfaceAfter = snapshot.string("searchSurfaceAfter"),
                resultsRemainInStremio = booleans.getValue("resultsRemainInStremio")
            )
        )
    }

    fun adaptValidateAndPlan(
        raw: Map<*, *>?,
        expected: ExpectedCompanionAcceptance?,
        evaluatedAt: String?
    ): CompanionAcceptanceDecision {
        if (!NativeCompanionAcceptanceReceipt.hasExpectedSourceAuthority(expected)) {
            val validation = CompanionAcceptanceValidation(
                accepted = false,
                reason = "EXPECTED_SOURCE_AUTHORITY_INVALID"
            )
            return CompanionAcceptanceDecision(
                validation,
                CompanionTextEntryPlan(
                    action = "PRESERVE_TYPED_SEARCH",
                    reason = "COMPANION_ACCEPTANCE_${validation.reason}",
                    acceptance = false
                )
            )
        }
        val adaptation = adapt(raw)
        if (!adaptation.accepted || adaptation.receipt == null) {
            val validation = CompanionAcceptanceValidation(
                accepted = false,
                reason = "RECEIPT_WIRE_${adaptation.reason}"
            )
            return CompanionAcceptanceDecision(
                validation,
                CompanionTextEntryPlan(
                    action = "PRESERVE_TYPED_SEARCH",
                    reason = "COMPANION_ACCEPTANCE_${validation.reason}",
                    acceptance = false
                )
            )
        }
        return NativeCompanionAcceptanceReceipt.validateAndPlan(
            adaptation.receipt,
            expected,
            evaluatedAt
        )
    }

    private val booleanKeys = setOf(
        "executorReviewed",
        "explicitCompanionInputGesture",
        "typedSearchFocused",
        "sensitiveInput",
        "audioCaptured",
        "accountMutation",
        "settingsMutation",
        "playbackStarted",
        "resultsRemainInStremio"
    )

    private fun boundedText(value: String) = value.length <= MAX_TEXT_LENGTH &&
        value.none { it.isISOControl() }

    private fun wholeNumber(value: Any?): Long? = when (value) {
        is Byte -> value.toLong()
        is Short -> value.toLong()
        is Int -> value.toLong()
        is Long -> value
        else -> null
    }

    private fun Map<String, Any?>.string(key: String) = getValue(key) as String

    private fun reject(reason: String) = CompanionAcceptanceAdaptation(
        accepted = false,
        reason = reason
    )
}
