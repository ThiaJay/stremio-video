package org.pss.stremio.tv

/**
 * Immutable same-episode state used to lease one classifier-owned Media3
 * transport retry. The host must capture this state on its single player
 * thread and keep episodeSessionId stable for the whole episode load, including
 * any retry-triggered generation or source refresh.
 */
data class NativeMedia3RetrySnapshot(
    val episodeSessionId: String,
    val videoId: String,
    val sourceId: String,
    val loadGeneration: Long,
    val backendSwitchCount: Int = 0,
    val loaded: Boolean,
    val playerReleased: Boolean = false,
    val switchingSource: Boolean = false,
    val episodeAdvanced: Boolean = false,
    val userRequestedNextEpisode: Boolean = false
)

interface NativeMedia3RetryPort {
    fun currentRetrySnapshot(): NativeMedia3RetrySnapshot
    fun executeRetry(action: String): Boolean
}

data class PreparedNativeMedia3Retry(
    val plan: NativeRecoveryPlan,
    val lease: NativeMedia3RetrySnapshot,
    val token: String
)

enum class NativeMedia3RetryOutcome {
    APPLIED,
    REFUSED,
    ALREADY_CONSUMED,
    EXECUTION_REJECTED
}

/**
 * Episode-scoped retry authority retained by the host across actuator and
 * player-adapter replacement. It is the sole same-episode attempt counter and
 * has no reset operation: a new budget is valid only for a genuinely new
 * episode session.
 */
class NativeMedia3RetryBudget private constructor(
    private val episodeSessionId: String,
    private val videoId: String
) {
    private val ownerThread = Thread.currentThread()
    private var reserved = false
    private var closed = false

    internal fun attemptCount(value: NativeMedia3RetrySnapshot): Int? {
        if (!onOwnerThread() || closed || !matches(value)) return null
        return if (reserved) 1 else 0
    }

    internal fun reserve(value: NativeMedia3RetrySnapshot): Boolean {
        if (!onOwnerThread() || closed || reserved || !matches(value)) return false
        reserved = true
        return true
    }

    internal fun contains(value: NativeMedia3RetrySnapshot): Boolean =
        onOwnerThread() && !closed && reserved && matches(value)

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        return true
    }

    private fun matches(value: NativeMedia3RetrySnapshot): Boolean =
        value.episodeSessionId == episodeSessionId && value.videoId == videoId

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    companion object {
        fun open(episodeSessionId: String, videoId: String): NativeMedia3RetryBudget? {
            if (!validId(episodeSessionId) || !validId(videoId)) return null
            return NativeMedia3RetryBudget(episodeSessionId, videoId)
        }

        private fun validId(value: String): Boolean =
            value.length in 1..MAX_ID_LENGTH && ID.matches(value)

        private const val MAX_ID_LENGTH = 160
        private val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")
    }
}

/**
 * Binds the numeric-only Media3 intake to the classification-first policy and
 * one immutable same-episode budget. Preparation reserves the episode budget,
 * so abandoning a lease, final-state drift or a rejected port call cannot mint
 * another attempt. The host must retain the budget across actuator or player
 * adapter replacement for the same episode session. The port cannot declare
 * or reset the attempt count. No exception, URL, credential, account state or
 * raw log can cross this boundary.
 */
class NativeMedia3RetryActuator(
    private val port: NativeMedia3RetryPort,
    private val budget: NativeMedia3RetryBudget
) {
    private val ownerThread = Thread.currentThread()
    private val issuedTokens = LinkedHashSet<String>()
    private val consumedTokens = LinkedHashSet<String>()
    private var closed = false

    fun prepare(errorCode: Int): PreparedNativeMedia3Retry? {
        if (!onOwnerThread() || closed) return null
        val lease = snapshotOrNull() ?: return null
        if (!trustedSnapshot(lease)) return null
        val sameEpisodeRetryCount = budget.attemptCount(lease) ?: return null

        val facts = NativeMedia3FailureIntake.sanitise(
            errorCode = errorCode,
            sameEpisodeRetryCount = sameEpisodeRetryCount,
            backendSwitchCount = lease.backendSwitchCount,
            episodeAdvanced = lease.episodeAdvanced,
            userRequestedNextEpisode = lease.userRequestedNextEpisode
        )
        val plan = NativePlaybackRecoveryPolicy.plan(facts = facts, observation = null)
        if (!trustedTransportRetryPlan(plan)) return null

        val token = token(plan, lease)
        if (token in issuedTokens) return null
        if (!budget.reserve(lease)) return null
        issuedTokens.add(token)
        return PreparedNativeMedia3Retry(plan = plan, lease = lease, token = token)
    }

    fun execute(prepared: PreparedNativeMedia3Retry): NativeMedia3RetryOutcome {
        if (!onOwnerThread() || closed) return NativeMedia3RetryOutcome.REFUSED
        if (!trustedTransportRetryPlan(prepared.plan) ||
            !trustedSnapshot(prepared.lease) ||
            prepared.token != token(prepared.plan, prepared.lease) ||
            prepared.token !in issuedTokens ||
            !budget.contains(prepared.lease)
        ) return NativeMedia3RetryOutcome.REFUSED

        val current = snapshotOrNull() ?: return NativeMedia3RetryOutcome.REFUSED
        if (!trustedSnapshot(current) || current != prepared.lease) {
            return NativeMedia3RetryOutcome.REFUSED
        }
        if (!consumedTokens.add(prepared.token)) {
            return NativeMedia3RetryOutcome.ALREADY_CONSUMED
        }
        return if (executeOrFalse(prepared.plan.action)) {
            NativeMedia3RetryOutcome.APPLIED
        } else {
            NativeMedia3RetryOutcome.EXECUTION_REJECTED
        }
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        issuedTokens.clear()
        consumedTokens.clear()
        return true
    }

    private fun trustedTransportRetryPlan(value: NativeRecoveryPlan): Boolean =
        value.authority == "stream_classifier" &&
            value.category == "http_transport" &&
            value.action == "retry_same_transport_route" &&
            value.classifierAttemptsGranted == 1 &&
            value.playbackRecovery == null &&
            value.preserveEpisode &&
            value.preserveWatchedState &&
            value.preserveProviderConfiguration &&
            value.preserveStreamIdentity

    private fun trustedSnapshot(value: NativeMedia3RetrySnapshot): Boolean =
        validId(value.episodeSessionId) &&
            validId(value.videoId) &&
            validId(value.sourceId) &&
            value.loadGeneration >= 0 &&
            value.backendSwitchCount in 0..MAX_BACKEND_SWITCH_COUNT &&
            value.loaded &&
            !value.playerReleased &&
            !value.switchingSource &&
            !value.episodeAdvanced &&
            !value.userRequestedNextEpisode

    private fun episodeBudgetKey(value: NativeMedia3RetrySnapshot): String =
        "${value.episodeSessionId}\u001f${value.videoId}"

    private fun token(plan: NativeRecoveryPlan, value: NativeMedia3RetrySnapshot): String =
        listOf(
            episodeBudgetKey(value),
            value.sourceId,
            value.loadGeneration.toString(),
            value.backendSwitchCount.toString(),
            plan.category.orEmpty(),
            plan.action
        ).joinToString("\u001e")

    private fun validId(value: String): Boolean =
        value.length in 1..MAX_ID_LENGTH && ID.matches(value)

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    private fun snapshotOrNull(): NativeMedia3RetrySnapshot? =
        try {
            port.currentRetrySnapshot()
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }

    private fun executeOrFalse(action: String): Boolean =
        try {
            port.executeRetry(action)
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }

    private companion object {
        const val MAX_BACKEND_SWITCH_COUNT = 2
        const val MAX_ID_LENGTH = 160
        val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")
    }
}
