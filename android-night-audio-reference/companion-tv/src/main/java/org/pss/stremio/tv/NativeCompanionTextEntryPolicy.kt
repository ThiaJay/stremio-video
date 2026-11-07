package org.pss.stremio.tv

/**
 * Native, side-effect-free binding of Foundation PR #74's companion text-entry
 * contract. A documented keyboard surface is not evidence that text reached
 * Stremio or that results stayed inside Stremio.
 */
data class CompanionTextEntryFacts(
    val searchSurfaceActive: Boolean?,
    val typedSearchFocused: Boolean?,
    val explicitCompanionInputGesture: Boolean?,
    val sensitiveInput: Boolean?,
    val deliveryConfirmedInStremio: Boolean?,
    val resultsRemainInStremio: Boolean?,
    val companionRoute: String?,
    val companionSessionIdentity: String?
)

data class CompanionTextEntryPlan(
    val action: String,
    val reason: String,
    val acceptance: Boolean
)

object NativeCompanionTextEntryPolicy {
    const val VERIFIED_OFFICIAL_FIRE_TV_APP = "verified-official-fire-tv-app"
    const val VERIFIED_CURRENT_DEVICE = "verified-current-device"

    private val routes = setOf(
        VERIFIED_OFFICIAL_FIRE_TV_APP,
        "unavailable",
        "unknown"
    )
    private val identities = setOf(
        VERIFIED_CURRENT_DEVICE,
        "mismatch",
        "unknown"
    )

    fun plan(facts: CompanionTextEntryFacts?): CompanionTextEntryPlan {
        if (facts == null ||
            facts.searchSurfaceActive == null ||
            facts.typedSearchFocused == null ||
            facts.explicitCompanionInputGesture == null ||
            facts.sensitiveInput == null ||
            facts.deliveryConfirmedInStremio == null ||
            facts.resultsRemainInStremio == null ||
            facts.companionRoute !in routes ||
            facts.companionSessionIdentity !in identities
        ) {
            return preserve("INVALID_OR_INCOMPLETE_FACTS")
        }

        if (facts.searchSurfaceActive != true) {
            return preserve("STREMIO_SEARCH_NOT_ACTIVE")
        }
        if (facts.typedSearchFocused != true) {
            return CompanionTextEntryPlan(
                action = "FOCUS_TYPED_SEARCH",
                reason = "TEXT_FIELD_NOT_FOCUSED",
                acceptance = false
            )
        }
        if (facts.sensitiveInput == true) {
            return preserve("SENSITIVE_INPUT_FORBIDDEN")
        }
        if (facts.explicitCompanionInputGesture != true) {
            return preserve("EXPLICIT_USER_GESTURE_REQUIRED")
        }
        if (facts.companionRoute != VERIFIED_OFFICIAL_FIRE_TV_APP) {
            return preserve("COMPANION_ROUTE_NOT_VERIFIED")
        }
        if (facts.companionSessionIdentity != VERIFIED_CURRENT_DEVICE) {
            return preserve("COMPANION_DEVICE_NOT_VERIFIED")
        }
        if (facts.deliveryConfirmedInStremio != true ||
            facts.resultsRemainInStremio != true
        ) {
            return CompanionTextEntryPlan(
                action = "OFFER_COMPANION_TEXT_ENTRY",
                reason = "SUPPORTED_TEXT_ROUTE_AWAITING_ACCEPTANCE",
                acceptance = false
            )
        }
        return CompanionTextEntryPlan(
            action = "ACCEPT_COMPANION_TEXT_ENTRY",
            reason = "TEXT_DELIVERY_AND_IN_APP_RESULTS_CONFIRMED",
            acceptance = true
        )
    }

    private fun preserve(reason: String) = CompanionTextEntryPlan(
        action = "PRESERVE_TYPED_SEARCH",
        reason = reason,
        acceptance = false
    )
}
