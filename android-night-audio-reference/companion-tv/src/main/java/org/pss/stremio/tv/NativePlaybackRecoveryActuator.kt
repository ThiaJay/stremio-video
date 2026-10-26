package org.pss.stremio.tv

/**
 * Immutable native state used to lease one exact playback recovery action.
 * The host owns capture and must call this boundary on its single player thread.
 */
data class NativeRecoverySnapshot(
    val recoveryContextId: String,
    val videoId: String,
    val sourceId: String,
    val loadGeneration: Long,
    val watchedStateFingerprint: String,
    val resumeStateFingerprint: String,
    val loaded: Boolean,
    val playerReleased: Boolean = false,
    val switchingSource: Boolean = false,
    val nextEpisodePopupOpen: Boolean = false
)

interface NativePlaybackRecoveryPort {
    fun currentSnapshot(): NativeRecoverySnapshot
    fun executeRecovery(action: String): Boolean
}

data class PreparedNativeRecovery(
    val plan: NativeRecoveryPlan,
    val lease: NativeRecoverySnapshot,
    val token: String
)

enum class NativeRecoveryExecutionOutcome {
    APPLIED,
    REFUSED,
    ALREADY_CONSUMED,
    EXECUTION_REJECTED
}

/**
 * Converts a safe policy result into a single-use, final-state-checked port call.
 * It does not implement a player recovery, source retry, network or account write.
 */
class NativePlaybackRecoveryActuator(
    private val port: NativePlaybackRecoveryPort
) {
    private val ownerThreadId = Thread.currentThread().id
    private val issued = LinkedHashSet<String>()
    private val consumed = LinkedHashSet<String>()

    fun prepare(
        classification: ClassifiedStreamFailure?,
        observation: PlaybackHealthObservation?
    ): PreparedNativeRecovery? {
        if (!onOwnerThread()) return null
        val plan = NativePlaybackRecoveryPolicy.plan(classification, observation)
        if (!trustedPlaybackPlan(plan)) return null

        val lease = port.currentSnapshot()
        if (!trustedSnapshot(lease) || observation == null) return null
        if (lease.recoveryContextId != observation.recoveryContextId ||
            lease.recoveryContextId != observation.currentContextId
        ) return null

        val token = token(plan, lease)
        if (!issued.add(token)) return null
        return PreparedNativeRecovery(plan, lease, token)
    }

    fun execute(prepared: PreparedNativeRecovery): NativeRecoveryExecutionOutcome {
        if (!onOwnerThread()) return NativeRecoveryExecutionOutcome.REFUSED
        if (!trustedPlaybackPlan(prepared.plan) || !trustedSnapshot(prepared.lease)) {
            return NativeRecoveryExecutionOutcome.REFUSED
        }
        if (prepared.token != token(prepared.plan, prepared.lease)) {
            return NativeRecoveryExecutionOutcome.REFUSED
        }
        if (prepared.token !in issued) {
            return NativeRecoveryExecutionOutcome.REFUSED
        }

        val current = port.currentSnapshot()
        if (!trustedSnapshot(current) || current != prepared.lease) {
            return NativeRecoveryExecutionOutcome.REFUSED
        }
        if (!consumed.add(prepared.token)) {
            return NativeRecoveryExecutionOutcome.ALREADY_CONSUMED
        }

        return if (port.executeRecovery(prepared.plan.action)) {
            NativeRecoveryExecutionOutcome.APPLIED
        } else {
            NativeRecoveryExecutionOutcome.EXECUTION_REJECTED
        }
    }

    private fun trustedPlaybackPlan(plan: NativeRecoveryPlan): Boolean =
        plan.authority == "playback_health" &&
            plan.playbackRecovery == plan.action &&
            plan.action in RECOVERY_ACTIONS &&
            plan.classifierAttemptsGranted == 0 &&
            plan.preserveEpisode &&
            plan.preserveWatchedState &&
            plan.preserveProviderConfiguration &&
            plan.preserveStreamIdentity

    private fun trustedSnapshot(value: NativeRecoverySnapshot): Boolean =
        validId(value.recoveryContextId) &&
            validId(value.videoId) &&
            validId(value.sourceId) &&
            value.loadGeneration >= 0 &&
            FINGERPRINT.matches(value.watchedStateFingerprint) &&
            FINGERPRINT.matches(value.resumeStateFingerprint) &&
            value.loaded &&
            !value.playerReleased &&
            !value.switchingSource &&
            !value.nextEpisodePopupOpen

    private fun validId(value: String): Boolean =
        value.length in 1..MAX_ID_LENGTH && ID.matches(value)

    private fun onOwnerThread(): Boolean =
        Thread.currentThread().id == ownerThreadId

    private fun token(plan: NativeRecoveryPlan, lease: NativeRecoverySnapshot): String =
        listOf(
            plan.action,
            lease.recoveryContextId,
            lease.videoId,
            lease.sourceId,
            lease.loadGeneration.toString(),
            lease.watchedStateFingerprint,
            lease.resumeStateFingerprint
        ).joinToString("|")

    private companion object {
        const val MAX_ID_LENGTH = 160
        val ID = Regex("^[A-Za-z0-9][A-Za-z0-9._:-]*$")
        val FINGERPRINT = Regex("^[0-9a-f]{64}$")
        val RECOVERY_ACTIONS = setOf(
            "convertDolbyVisionProfile7To8_1",
            "fallbackToHdr10BaseLayer",
            "restoreStableVideoAndTranscodeAudio",
            "transcodeAudio",
            "switchPlaybackEngine",
            "switchStream"
        )
    }
}
