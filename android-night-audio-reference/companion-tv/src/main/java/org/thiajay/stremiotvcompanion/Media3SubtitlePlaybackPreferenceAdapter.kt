package org.thiajay.stremiotvcompanion

import androidx.media3.common.C
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer

/**
 * Narrow lab-only Media3 binding for the accepted subtitle preference
 * actuator. It changes only whether the text track type is disabled and never
 * clears or replaces a manual text-track override. All other track-selection
 * parameters remain owned by the player host.
 */
@UnstableApi
internal class Media3SubtitlePlaybackPreferenceAdapter private constructor(
    contextPort: ContextPort,
    preferencePort: NativeSubtitlePlaybackPreferenceActuator.PreferencePort,
    private val mediaPort: MediaPort
) {
    data class Context(
        val playerGeneration: Long,
        val loadGeneration: Long,
        val videoId: String,
        val sourceId: String,
        val transitioning: Boolean,
        val nextEpisodeRequested: Boolean
    )

    fun interface ContextPort {
        fun readContext(): Context?
    }

    internal data class MediaState(
        val ready: Boolean,
        val released: Boolean,
        val textDisabled: Boolean,
        val manualTextOverride: Boolean
    )

    internal interface MediaPort {
        fun readState(): MediaState?
        fun setTextDisabled(expected: MediaState, disabled: Boolean): Boolean
    }

    private val ownerThread = Thread.currentThread()
    private var closed = false
    private val actuator = NativeSubtitlePlaybackPreferenceActuator(
        snapshotPort = NativeSubtitlePlaybackPreferenceActuator.SnapshotPort {
            val context = contextPort.readContext() ?: return@SnapshotPort null
            val media = mediaPort.readState() ?: return@SnapshotPort null
            NativeSubtitlePlaybackPreferenceActuator.Snapshot(
                playerGeneration = context.playerGeneration,
                loadGeneration = context.loadGeneration,
                videoId = context.videoId,
                sourceId = context.sourceId,
                ready = media.ready,
                released = media.released,
                transitioning = context.transitioning,
                nextEpisodeRequested = context.nextEpisodeRequested,
                selectionOrigin = selectionOrigin(media)
            )
        },
        preferencePort = preferencePort,
        actionPort = NativeSubtitlePlaybackPreferenceActuator.ActionPort { action, _, snapshot ->
            val current = mediaPort.readState() ?: return@ActionPort false
            if (!current.ready || current.released ||
                selectionOrigin(current) != snapshot.selectionOrigin) {
                return@ActionPort false
            }
            mediaPort.setTextDisabled(
                current,
                action == NativeSubtitlePlaybackPreferenceActuator.Action.CLEAR_AUTOMATIC
            )
        }
    )

    /** Null means the exact current state required no mutation. */
    fun reconcile(trigger: NativeSubtitlePlaybackPreferenceActuator.Trigger): Boolean? {
        if (!usable()) return false
        val lease = actuator.prepare(trigger) ?: return null
        return actuator.execute(lease)
    }

    fun close(): Boolean {
        if (!onOwnerThread() || closed) return false
        closed = true
        return actuator.close()
    }

    private fun usable(): Boolean = onOwnerThread() && !closed

    private fun onOwnerThread(): Boolean = Thread.currentThread() === ownerThread

    companion object {
        private fun selectionOrigin(state: MediaState) = when {
            state.manualTextOverride ->
                NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.MANUAL
            state.textDisabled ->
                NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.NONE
            else -> NativeSubtitlePlaybackPreferenceActuator.SelectionOrigin.AUTOMATIC
        }

        fun open(
            player: ExoPlayer,
            contextPort: ContextPort,
            preferencePort: NativeSubtitlePlaybackPreferenceActuator.PreferencePort
        ): Media3SubtitlePlaybackPreferenceAdapter =
            Media3SubtitlePlaybackPreferenceAdapter(
                contextPort,
                preferencePort,
                ExoPlayerMediaPort(player)
            )

        internal fun openForTest(
            contextPort: ContextPort,
            preferencePort: NativeSubtitlePlaybackPreferenceActuator.PreferencePort,
            mediaPort: MediaPort
        ) = Media3SubtitlePlaybackPreferenceAdapter(
            contextPort,
            preferencePort,
            mediaPort
        )
    }

    private class ExoPlayerMediaPort(
        private val player: ExoPlayer
    ) : MediaPort {
        override fun readState(): MediaState {
            val parameters = player.trackSelectionParameters
            return MediaState(
                ready = player.playbackState == Player.STATE_READY &&
                    player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS),
                released = false,
                textDisabled = C.TRACK_TYPE_TEXT in parameters.disabledTrackTypes,
                manualTextOverride = parameters.overrides.keys.any {
                    it.type == C.TRACK_TYPE_TEXT
                }
            )
        }

        override fun setTextDisabled(
            expected: MediaState,
            disabled: Boolean
        ): Boolean {
            if (readState() != expected || expected.manualTextOverride ||
                !player.isCommandAvailable(Player.COMMAND_SET_TRACK_SELECTION_PARAMETERS)) {
                return false
            }
            val before = player.trackSelectionParameters
            if (before.overrides.keys.any { it.type == C.TRACK_TYPE_TEXT }) return false
            val updated = before.buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_TEXT, disabled)
                .build()
            player.trackSelectionParameters = updated
            val first = player.trackSelectionParameters
            val second = player.trackSelectionParameters
            return first == updated && second == updated &&
                (C.TRACK_TYPE_TEXT in second.disabledTrackTypes) == disabled &&
                second.overrides.keys.none { it.type == C.TRACK_TYPE_TEXT }
        }
    }
}
