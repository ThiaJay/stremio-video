package org.pss.stremio.tv

import java.time.Instant

/**
 * One-shot owner for a single explicit companion text request. The attempt is
 * consumed before untrusted receipt adaptation so neither replay nor a failed
 * first attempt can later promote the same request. The session clock also
 * prevents a still-fresh receipt from an earlier request being reused by a
 * later session for identical text.
 */
class NativeCompanionAcceptanceSession private constructor(
    private val expected: ExpectedCompanionAcceptance,
    private val ownerThread: Thread,
    private val openedAtMillis: Long
) {
    private var consumed = false
    private var closed = false

    fun evaluate(
        raw: Map<*, *>?,
        evaluatedAt: String?
    ): CompanionAcceptanceDecision {
        if (Thread.currentThread() !== ownerThread) {
            return reject("SESSION_OWNER_THREAD_MISMATCH")
        }
        if (closed) return reject("SESSION_CLOSED")
        if (consumed) return reject("SESSION_ALREADY_CONSUMED")

        consumed = true
        val evaluatedAtMillis = parseMillis(evaluatedAt)
            ?: return reject("SESSION_EVALUATION_TIME_INVALID")
        if (evaluatedAtMillis < openedAtMillis) {
            return reject("SESSION_CLOCK_ROLLBACK")
        }
        val expiresAt = safeAdd(openedAtMillis, MAX_SESSION_AGE_MILLIS)
            ?: return reject("SESSION_EVALUATION_TIME_INVALID")
        if (evaluatedAtMillis > expiresAt) {
            return reject("SESSION_EXPIRED")
        }

        val decision = NativeCompanionAcceptanceReceiptAdapter.adaptValidateAndPlan(
            raw,
            expected,
            evaluatedAt
        )
        if (!decision.validation.accepted) return decision

        val observedAtMillis = parseMillis(decision.validation.observedAt)
            ?: return reject("SESSION_OBSERVATION_TIME_INVALID")
        if (observedAtMillis < openedAtMillis) {
            return reject("SESSION_OBSERVATION_PRECEDES_REQUEST")
        }
        return decision
    }

    fun close(): Boolean {
        if (Thread.currentThread() !== ownerThread || closed) return false
        consumed = true
        closed = true
        return true
    }

    override fun toString() = "NativeCompanionAcceptanceSession(state=REDACTED)"

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

    private fun reject(reason: String): CompanionAcceptanceDecision {
        val validation = CompanionAcceptanceValidation(
            accepted = false,
            reason = reason
        )
        return CompanionAcceptanceDecision(
            validation,
            CompanionTextEntryPlan(
                action = "PRESERVE_TYPED_SEARCH",
                reason = "COMPANION_ACCEPTANCE_$reason",
                acceptance = false
            )
        )
    }

    companion object {
        private const val MAX_SESSION_AGE_MILLIS = 10 * 60 * 1000L

        fun open(
            expected: ExpectedCompanionAcceptance?,
            openedAt: String?
        ): NativeCompanionAcceptanceSession? {
            if (expected == null) return null
            val openedAtMillis = try {
                openedAt?.let { Instant.parse(it).toEpochMilli() }
            } catch (_: RuntimeException) {
                null
            } ?: return null
            return NativeCompanionAcceptanceSession(
                expected,
                Thread.currentThread(),
                openedAtMillis
            )
        }
    }
}
