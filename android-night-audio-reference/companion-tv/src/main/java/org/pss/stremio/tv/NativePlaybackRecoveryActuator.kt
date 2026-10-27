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
    val supportedRecoveryActions: Set<String>,
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
    private val ownerThread = Thread.currentThread()
    private val issued = LinkedHashSet<String>()
    private val consumed = LinkedHashSet<String>()
    private var closed = false

    fun prepare(
        facts: SanitisedStreamFailureFacts?,
        observation: PlaybackHealthObservation?
    ): PreparedNativeRecovery? {
        if (!onOwnerThread() || closed) return null
        val plan = NativePlaybackRecoveryPolicy.plan(facts, observation)
        if (!trustedPlaybackPlan(plan)) return null

        val lease = snapshotOrNull() ?: return null
        if (!trustedSnapshot(lease) || observation == null) return null
        if (plan.action !in lease.supportedRecoveryActions) return null
        if (lease.recoveryContextId != observation.recoveryContextId ||
            lease.recoveryContextId != observation.currentContextId
        ) return null

        if (issued.size >= MAX_ISSUED_LEASES) return null
        val token = token(plan, lease)
        if (!issued.add(token)) return null
        return PreparedNativeRecovery(plan, lease, token)
    }

    fun execute(prepared: PreparedNativeRecovery): NativeRecoveryExecutionOutcome {
        if (!onOwnerThread() || closed) return NativeRecoveryExecutionOutcome.REFUSED
        if (!trustedPlaybackPlan(prepared.plan) ||
            !trustedSnapshot(prepared.lease) ||
            prepared.plan.action !in prepared.lease.supportedRecoveryActions
        ) {
            return NativeRecoveryExecutionOutcome.REFUSED
        }
        if (prepared.token != token(prepared.plan, prepared.lease)) {
            return NativeRecoveryExecutionOutcome.REFUSED
        }
        if (prepared.token !in issued) {
            return NativeRecoveryExecutionOutcome.REFUSED
        }

        val current = snapshotOrNull() ?: return NativeRecoveryExecutionOutcome.REFUSED
        if (!trustedSnapshot(current) || current != prepared.lease) {
            return NativeRecoveryExecutionOutcome.REFUSED
        }
        if (!consumed.add(prepared.token)) {
            return NativeRecoveryExecutionOutcome.ALREADY_CONSUMED
        }

        return if (executeOrFalse(prepared.plan.action)) {
            NativeRecoveryExecutionOutcome.APPLIED
        } else {
            NativeRecoveryExecutionOutcome.EXECUTION_REJECTED
        }
    }

    /** Revokes every outstanding lease when the host-owned player is released. */
    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        issued.clear()
        consumed.clear()
        return true
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
            value.supportedRecoveryActions.isNotEmpty() &&
            value.supportedRecoveryActions.size <= RECOVERY_ACTIONS.size &&
            value.supportedRecoveryActions.all { it in RECOVERY_ACTIONS } &&
            value.loaded &&
            !value.playerReleased &&
            !value.switchingSource &&
            !value.nextEpisodePopupOpen

    private fun validId(value: String): Boolean =
        value.length in 1..MAX_ID_LENGTH && ID.matches(value)

    private fun onOwnerThread(): Boolean =
        Thread.currentThread() === ownerThread

    private fun snapshotOrNull(): NativeRecoverySnapshot? =
        try {
            port.currentSnapshot()
        } catch (_: Exception) {
            null
        } catch (_: LinkageError) {
            null
        }

    private fun executeOrFalse(action: String): Boolean =
        try {
            port.executeRecovery(action)
        } catch (_: Exception) {
            false
        } catch (_: LinkageError) {
            false
        }

    private fun token(plan: NativeRecoveryPlan, lease: NativeRecoverySnapshot): String =
        listOf(
            plan.action,
            lease.recoveryContextId,
            lease.videoId,
            lease.sourceId,
            lease.loadGeneration.toString(),
            lease.watchedStateFingerprint,
            lease.resumeStateFingerprint,
            lease.supportedRecoveryActions.sorted().joinToString(",")
        ).joinToString("|")

    private companion object {
        const val MAX_ID_LENGTH = 160
        const val MAX_ISSUED_LEASES = 256
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
