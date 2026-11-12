package org.pss.stremio.tv

import java.time.Instant
import java.security.MessageDigest

/**
 * Native, side-effect-free binding of Foundation PR #74's companion acceptance
 * receipt. A successful result deliberately omits the delivered text: exact
 * equality is checked locally without promoting the query into status or
 * longer-lived state.
 */
data class CompanionAcceptanceReceipt(
    val schemaVersion: Int?,
    val contractId: String?,
    val contractSourceSha256: String?,
    val executorSourceSha256: String?,
    val executorReviewed: Boolean?,
    val deviceIdentityDigest: String?,
    val deviceModel: String?,
    val deviceIdentityStatus: String?,
    val appPackage: String?,
    val appVersion: String?,
    val route: String?,
    val observedAt: String?,
    val explicitCompanionInputGesture: Boolean?,
    val searchSurfaceBefore: String?,
    val typedSearchFocused: Boolean?,
    val sensitiveInput: Boolean?,
    val audioCaptured: Boolean?,
    val accountMutation: Boolean?,
    val settingsMutation: Boolean?,
    val playbackStarted: Boolean?,
    val expectedText: String?,
    val readBackText: String?,
    val searchSurfaceAfter: String?,
    val resultsRemainInStremio: Boolean?
) {
    override fun toString() = "CompanionAcceptanceReceipt(query=REDACTED)"
}

data class ExpectedCompanionAcceptance(
    val contractSourceSha256: String?,
    val executorSourceSha256: String?,
    val deviceIdentityDigest: String?,
    val deviceModel: String?,
    val appPackage: String?,
    val appVersion: String?,
    val expectedTextSha256: String?
) {
    override fun toString() = "ExpectedCompanionAcceptance(query=REDACTED)"
}

data class CompanionAcceptanceValidation(
    val accepted: Boolean,
    val reason: String,
    val deviceIdentityDigest: String? = null,
    val appPackage: String? = null,
    val appVersion: String? = null,
    val observedAt: String? = null
)

data class CompanionAcceptanceDecision(
    val validation: CompanionAcceptanceValidation,
    val plan: CompanionTextEntryPlan
)

object NativeCompanionAcceptanceReceipt {
    const val CONTRACT_ID = "fire-tv-companion-text-entry-acceptance"
    const val OFFICIAL_STREMIO_PACKAGE = "com.stremio.one"
    private const val MAX_TEXT_LENGTH = 160
    private const val MAX_AGE_MILLIS = 10 * 60 * 1000L
    private const val MAX_FUTURE_SKEW_MILLIS = 30 * 1000L
    private val sha256 = Regex("^[a-f0-9]{64}$")

    fun validate(
        receipt: CompanionAcceptanceReceipt?,
        expected: ExpectedCompanionAcceptance?,
        evaluatedAt: String?
    ): CompanionAcceptanceValidation {
        if (receipt == null || expected == null || evaluatedAt == null) {
            return reject("INVALID_INPUT")
        }
        if (receipt.schemaVersion != 1 || receipt.contractId != CONTRACT_ID) {
            return reject("UNSUPPORTED_RECEIPT_CONTRACT")
        }
        if (!isSha256(receipt.contractSourceSha256) ||
            !isSha256(expected.contractSourceSha256) ||
            receipt.contractSourceSha256 != expected.contractSourceSha256 ||
            !isSha256(receipt.executorSourceSha256) ||
            !isSha256(expected.executorSourceSha256) ||
            receipt.executorSourceSha256 != expected.executorSourceSha256 ||
            receipt.executorReviewed != true
        ) {
            return reject("SOURCE_BINDING_NOT_VERIFIED")
        }
        if (!isSha256(receipt.deviceIdentityDigest) ||
            receipt.deviceIdentityDigest != expected.deviceIdentityDigest ||
            !exactIdentifier(receipt.deviceModel) ||
            !exactIdentifier(expected.deviceModel) ||
            receipt.deviceModel != expected.deviceModel ||
            receipt.deviceIdentityStatus != NativeCompanionTextEntryPolicy.VERIFIED_CURRENT_DEVICE
        ) {
            return reject("DEVICE_IDENTITY_NOT_VERIFIED")
        }
        if (!exactIdentifier(receipt.appPackage) ||
            !exactIdentifier(expected.appPackage) ||
            !exactIdentifier(receipt.appVersion) ||
            !exactIdentifier(expected.appVersion) ||
            receipt.appPackage != OFFICIAL_STREMIO_PACKAGE ||
            expected.appPackage != OFFICIAL_STREMIO_PACKAGE ||
            receipt.appPackage != expected.appPackage ||
            receipt.appVersion != expected.appVersion ||
            receipt.route != NativeCompanionTextEntryPolicy.VERIFIED_OFFICIAL_FIRE_TV_APP
        ) {
            return reject("APP_OR_ROUTE_MISMATCH")
        }

        val observedMillis = parseMillis(receipt.observedAt)
            ?: return reject("STALE_OR_INVALID_OBSERVATION_TIME")
        val evaluatedMillis = parseMillis(evaluatedAt)
            ?: return reject("STALE_OR_INVALID_OBSERVATION_TIME")
        val latestAllowed = safeAdd(evaluatedMillis, MAX_FUTURE_SKEW_MILLIS)
            ?: return reject("STALE_OR_INVALID_OBSERVATION_TIME")
        val earliestAllowed = safeAdd(evaluatedMillis, -MAX_AGE_MILLIS)
            ?: return reject("STALE_OR_INVALID_OBSERVATION_TIME")
        if (observedMillis > latestAllowed || observedMillis < earliestAllowed) {
            return reject("STALE_OR_INVALID_OBSERVATION_TIME")
        }

        if (receipt.explicitCompanionInputGesture != true ||
            receipt.searchSurfaceBefore != "stremio" ||
            receipt.typedSearchFocused != true
        ) {
            return reject("INPUT_PRECONDITION_NOT_PROVEN")
        }
        if (receipt.sensitiveInput != false ||
            receipt.audioCaptured != false ||
            receipt.accountMutation != false ||
            receipt.settingsMutation != false ||
            receipt.playbackStarted != false
        ) {
            return reject("PRESERVATION_INVARIANT_FAILED")
        }
        if (!exactText(receipt.expectedText) ||
            !exactText(receipt.readBackText) ||
            receipt.expectedText != receipt.readBackText
        ) {
            return reject("EXACT_TEXT_DELIVERY_NOT_PROVEN")
        }
        if (!isSha256(expected.expectedTextSha256) ||
            digestExpectedText(receipt.expectedText) != expected.expectedTextSha256
        ) {
            return reject("EXPECTED_TEXT_BINDING_NOT_VERIFIED")
        }
        if (receipt.searchSurfaceAfter != "stremio" ||
            receipt.resultsRemainInStremio != true
        ) {
            return reject("IN_APP_RESULT_RETENTION_NOT_PROVEN")
        }
        return CompanionAcceptanceValidation(
            accepted = true,
            reason = "COMPANION_TEXT_ENTRY_ACCEPTED",
            deviceIdentityDigest = receipt.deviceIdentityDigest,
            appPackage = receipt.appPackage,
            appVersion = receipt.appVersion,
            observedAt = receipt.observedAt
        )
    }

    /**
     * A receipt may promote the policy only after full validation. Rejected
     * evidence preserves typed search and cannot expose or retain query text.
     */
    fun validateAndPlan(
        receipt: CompanionAcceptanceReceipt?,
        expected: ExpectedCompanionAcceptance?,
        evaluatedAt: String?
    ): CompanionAcceptanceDecision {
        val validation = validate(receipt, expected, evaluatedAt)
        if (!validation.accepted) {
            return CompanionAcceptanceDecision(
                validation,
                CompanionTextEntryPlan(
                    action = "PRESERVE_TYPED_SEARCH",
                    reason = "COMPANION_ACCEPTANCE_RECEIPT_${validation.reason}",
                    acceptance = false
                )
            )
        }
        return CompanionAcceptanceDecision(
            validation,
            NativeCompanionTextEntryPolicy.plan(
                CompanionTextEntryFacts(
                    searchSurfaceActive = true,
                    typedSearchFocused = true,
                    explicitCompanionInputGesture = true,
                    sensitiveInput = false,
                    deliveryConfirmedInStremio = true,
                    resultsRemainInStremio = true,
                    companionRoute = NativeCompanionTextEntryPolicy.VERIFIED_OFFICIAL_FIRE_TV_APP,
                    companionSessionIdentity = NativeCompanionTextEntryPolicy.VERIFIED_CURRENT_DEVICE
                )
            )
        )
    }

    private fun isSha256(value: String?) = value != null && sha256.matches(value)

    internal fun hasExpectedSourceAuthority(expected: ExpectedCompanionAcceptance?) =
        expected != null &&
            isSha256(expected.contractSourceSha256) &&
            isSha256(expected.executorSourceSha256)

    private fun exactText(value: String?) = value != null &&
        value.isNotEmpty() &&
        value.length <= MAX_TEXT_LENGTH &&
        value.trim() == value &&
        value.none { it.isISOControl() } &&
        wellFormedUtf16(value)

    /**
     * Produces the query-redacted binding held by the Android request owner.
     * Invalid or non-canonical text cannot acquire a digest.
     */
    fun digestExpectedText(value: String?): String? {
        if (!exactText(value)) return null
        return MessageDigest.getInstance("SHA-256")
            .digest(value!!.toByteArray(Charsets.UTF_8))
            .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }
    }

    private fun wellFormedUtf16(value: String): Boolean {
        var index = 0
        while (index < value.length) {
            val current = value[index]
            when {
                current.isHighSurrogate() -> {
                    if (index + 1 >= value.length || !value[index + 1].isLowSurrogate()) {
                        return false
                    }
                    index += 2
                }
                current.isLowSurrogate() -> return false
                else -> index += 1
            }
        }
        return true
    }

    private fun exactIdentifier(value: String?) = exactText(value) &&
        value!!.none { it.isISOControl() }

    private fun parseMillis(value: String?): Long? = try {
        value?.let { Instant.parse(it).toEpochMilli() }
    } catch (_: RuntimeException) {
        null
    }

    private fun safeAdd(value: Long, delta: Long): Long? = try {
        Math.addExact(value, delta)
    } catch (_: ArithmeticException) {
        null
    }

    private fun reject(reason: String) = CompanionAcceptanceValidation(
        accepted = false,
        reason = reason
    )
}
