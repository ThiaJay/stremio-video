package org.stremio.nightaudio.reference;

/** Pure status contract. The player supplies real route and playback observations. */
public final class NightAudioPlaybackGate {
    private NightAudioPlaybackGate() { }

    public enum Path { PCM, BITSTREAM, OFFLOAD, UNKNOWN }
    public enum Status { OFF, BYPASSED, UNAVAILABLE, CHECKING, ACTIVE }

    /**
     * "Active" requires current decoded PCM, a positively identified output
     * route, matching playback and strength, plus observed processing of frames.
     * Saving settings or passing a synthetic audio test never satisfies this gate.
     */
    public static Status evaluate(boolean requested, Path path,
                                  boolean activeTrackAndPlaybackConfirmed,
                                  boolean outputRouteConfirmed,
                                  boolean pcmFramesObserved,
                                  boolean strengthMatched) {
        if (!requested) return Status.OFF;
        if (path == Path.BITSTREAM || path == Path.OFFLOAD) return Status.BYPASSED;
        if (path != Path.PCM || !outputRouteConfirmed) return Status.UNAVAILABLE;
        if (!activeTrackAndPlaybackConfirmed || !pcmFramesObserved || !strengthMatched) {
            return Status.CHECKING;
        }
        return Status.ACTIVE;
    }
}
