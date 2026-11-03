package org.pss.stremio.tv

/**
 * One-shot owner for a single explicit companion text request. The attempt is
 * consumed before untrusted receipt adaptation so neither replay nor a failed
 * first attempt can later promote the same request.
 */
class NativeCompanionAcceptanceSession private constructor(
    private val expected: ExpectedCompanionAcceptance,
    private val ownerThread: Thread
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
        return NativeCompanionAcceptanceReceiptAdapter.adaptValidateAndPlan(
            raw,
            expected,
            evaluatedAt
        )
    }

    fun close(): Boolean {
        if (Thread.currentThread() !== ownerThread || closed) return false
        consumed = true
        closed = true
        return true
    }

    override fun toString() = "NativeCompanionAcceptanceSession(state=REDACTED)"

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
        fun open(expected: ExpectedCompanionAcceptance?): NativeCompanionAcceptanceSession? =
            expected?.let {
                NativeCompanionAcceptanceSession(it, Thread.currentThread())
            }
    }
}
