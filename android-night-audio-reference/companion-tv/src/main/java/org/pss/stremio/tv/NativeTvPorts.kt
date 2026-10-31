package org.pss.stremio.tv

/**
 * Production client interface to Stremio Core and the selected native Media3 renderer.
 * This pure adapter has zero account credentials, network code and device permissions.
 * The host must supply the actual current player and account state, not cached guesses.
 */
interface CoreMessagePort {
    fun dispatch(message: Map<String, Any>): Boolean
}

interface NativeSeekPort {
    fun snapshot(): TvPlaybackState
    fun seekWithinSelectedVideo(ms: Long): Boolean
}

/**
 * Host-owned final check before a Media3 seek. Every snapshot stores a one-use
 * observation, and seek re-observes the actual player immediately before calling
 * Media3. The host MUST call this adapter on its single player/Looper thread.
 * It guards the narrow gap between Core dismissal and the actual seek, while
 * retaining the original unmodified validated coordinator contract.
 */
class SnapshotBoundNativeSeekPort(
    private val observe: () -> TvPlaybackState,
    private val applySeek: (Long) -> Boolean
) : NativeSeekPort {
    private var latest: TvPlaybackState? = null

    override fun snapshot(): TvPlaybackState = observe().also { latest = it }

    override fun seekWithinSelectedVideo(ms: Long): Boolean {
        val expected = latest ?: return false
        latest = null // never reuse the permission after an attempted seek
        val now = observe()
        if (!expected.loaded || expected.videoId.isBlank() || expected.generation < 0 ||
            now.generation != expected.generation || now.videoId != expected.videoId ||
            now.durationMs != expected.durationMs || !now.loaded ||
            now.buffering || now.seeking || now.switchingSource ||
            now.nextVideoPopupOpen || now.menuOpen ||
            ms <= 0L || ms >= now.durationMs ||
            now.positionMs < 0L || now.positionMs >= ms) return false
        return applySeek(ms)
    }
}

enum class NativeSkipOutcome {
    APPLIED, NO_ACTION, CORE_REJECTED, PLAYBACK_CHANGED, SEEK_FAILED
}

class NativeTvSkipActuator(
    private val coordinator: TvSkipCoordinator,
    private val core: CoreMessagePort,
    private val player: NativeSeekPort
) {
    fun manual(s: CoreSkipSegment?, focused: SegmentIdentity?, key: String): NativeSkipOutcome {
        val before = player.snapshot()
        val proposed = coordinator.presentation(s, before) ?: return NativeSkipOutcome.NO_ACTION
        if (proposed.focusedIdentity != focused) return NativeSkipOutcome.NO_ACTION
        val cmd = coordinator.manual(s, before, focused, key) ?: return NativeSkipOutcome.NO_ACTION
        return deliver(cmd, before)
    }

    fun automatic(s: CoreSkipSegment?): NativeSkipOutcome {
        val before = player.snapshot()
        val cmd = coordinator.automatic(s, before) ?: return NativeSkipOutcome.NO_ACTION
        return deliver(cmd, before)
    }

    private fun deliver(command: NativeSkipCommand, before: TvPlaybackState): NativeSkipOutcome {
        // Core acknowledgement must precede the ordinary same-media native seek.
        val fresh = player.snapshot()
        if (fresh.generation != before.generation || fresh.videoId != before.videoId ||
            !fresh.loaded || fresh.durationMs != before.durationMs || fresh.switchingSource ||
            fresh.buffering || fresh.seeking || fresh.nextVideoPopupOpen || fresh.menuOpen) {
            return NativeSkipOutcome.PLAYBACK_CHANGED
        }
        if (!core.dispatch(command.dismissCoreAction)) return NativeSkipOutcome.CORE_REJECTED
        // Check again in case a synchronous Core reducer event moved to a new source.
        val ready = player.snapshot()
        if (ready.generation != before.generation || ready.videoId != before.videoId ||
            !ready.loaded || ready.durationMs != before.durationMs || ready.switchingSource ||
            ready.buffering || ready.seeking || ready.nextVideoPopupOpen) {
            return NativeSkipOutcome.PLAYBACK_CHANGED
        }
        return if (player.seekWithinSelectedVideo(command.seekWithinCurrentMediaMs))
            NativeSkipOutcome.APPLIED else NativeSkipOutcome.SEEK_FAILED
    }
}

interface StremioAccountPort {
    fun currentSnapshot(): SeriesAccountSnapshot?
    fun dispatch(message: Map<String, Any>): Boolean
}

/** Never turns a prepared intent into a mutation if the Core account bridge is unavailable. */
class NativeTvWatchedActuator(
    private val coordinator: TvWatchedCoordinator,
    private val account: StremioAccountPort
) {
    fun prepare(scope: WatchedScope, watched: Boolean): WatchedPlan? =
        account.currentSnapshot()?.let { coordinator.prepare(it, scope, watched) }

    fun confirm(plan: WatchedPlan, input: TvWatchedConfirmation): Boolean {
        val fresh = account.currentSnapshot() ?: return false
        val command = coordinator.confirm(plan, fresh, input) ?: return false
        return account.dispatch(command.coreAction)
    }
}
