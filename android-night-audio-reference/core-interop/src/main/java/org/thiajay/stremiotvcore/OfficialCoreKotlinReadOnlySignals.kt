package org.thiajay.stremiotvcore

import com.stremio.core.Core
import com.stremio.core.Field
import com.stremio.core.models.Ctx
import com.stremio.core.models.MetaDetails

/**
 * Compile-only interoperability with official archived stremio-core-kotlin
 * 1.15.0: uses Core.getState only, never initialize, authenticate or dispatch.
 * Host must provide a trusted native Core instance AND increase epoch on every
 * account, player or metadata selection change. This module is intentionally
 * NOT a dependency of the zero-permission Fire TV proof APK.
 */
class OfficialCoreKotlinReadOnlySignals(
    private val sessionEpoch: () -> Long,
    private val hostApprovedCoreReady: () -> Boolean
) : CoreReadOnlySignals {
    override fun observe(): SafeCoreObservation? {
        if (!hostApprovedCoreReady()) return null
        try {
            val before = sessionEpoch()
            if (before < 0) return null
            val ctxBefore: Ctx = Core.getState(Field.CTX)
            val meta: MetaDetails = Core.getState(Field.META_DETAILS)
            val ctxAfter: Ctx = Core.getState(Field.CTX)
            val after = sessionEpoch()
            if (before != after || !hostApprovedCoreReady()) return null
            // Compare identities transiently but NEVER return/log identifiers.
            val userA = ctxBefore.profile.auth?.user?.id
            val userB = ctxAfter.profile.auth?.user?.id
            if (userA != userB) return null
            return SafeCoreObservation(
                sessionEpoch = after,
                authenticated = userA != null,
                metaId = meta.selected?.metaPath?.id,
                videoId = meta.selected?.streamPath?.id,
                streamListCount = meta.streams.size
            )
        } catch (_: Exception) {
            // Native SDK may not be initialised; do not log exception content.
            return null
        } catch (_: LinkageError) {
            // AAR/JNI may be absent or mismatched on a developer device.
            return null
        }
    }
}
