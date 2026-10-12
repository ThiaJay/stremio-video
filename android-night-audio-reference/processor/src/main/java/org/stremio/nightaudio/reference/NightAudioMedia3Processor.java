package org.stremio.nightaudio.reference;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.audio.AudioProcessor.StreamMetadata;
import androidx.media3.common.audio.BaseAudioProcessor;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Proof-only Media3 PCM adapter. It has no integration into the official Stremio
 * Android TV application. Media3 will not invoke this on bitstream/offload audio.
 * Host code must separately verify the active output route and actual playback.
 */
@UnstableApi
public final class NightAudioMedia3Processor extends BaseAudioProcessor {
    private static final class Selection {
        final long revision;
        final PcmDynamicsProcessor.Strength strength;

        Selection(long revision, PcmDynamicsProcessor.Strength strength) {
            this.revision = revision;
            this.strength = strength;
        }
    }

    private final PcmDynamicsProcessor compressor = new PcmDynamicsProcessor();
    private final AtomicLong revisions = new AtomicLong();
    private volatile Selection selection = new Selection(0, null);
    private volatile long verifiedRevision = -1;
    private volatile long observedFrames;
    private volatile boolean pcmConfigured;
    private long processedRevision = -1;

    /** Null means off. Changing strength never touches an audio track or output route. */
    public void request(PcmDynamicsProcessor.Strength strength) {
        selection = new Selection(revisions.incrementAndGet(), strength);
        verifiedRevision = -1;
        observedFrames = 0;
    }

    /**
     * PCM frames were observed in this processor at the current requested revision.
     * This is NOT proof that an installed client routed audible sound through it.
     */
    public boolean isPcmProcessingObserved() {
        final Selection active = selection;
        return active.strength != null && pcmConfigured &&
                verifiedRevision == active.revision && observedFrames > 0;
    }

    @Override
    protected AudioFormat onConfigure(AudioFormat format) {
        // Keep the processor present even while Off, for byte-identical local bypass.
        // Unsupported audio is never transformed or used to force PCM conversion.
        if (format.encoding != C.ENCODING_PCM_16BIT || format.channelCount < 1 ||
                format.channelCount > 8 || format.sampleRate < 8000 ||
                format.sampleRate > 192000) {
            pcmConfigured = false;
            return AudioFormat.NOT_SET;
        }
        pcmConfigured = false;
        return format;
    }

    @Override
    protected void onFlush(StreamMetadata streamMetadata) {
        compressor.reset();
        processedRevision = -1;
        verifiedRevision = -1;
        observedFrames = 0;
        pcmConfigured = inputAudioFormat != AudioFormat.NOT_SET &&
                inputAudioFormat.encoding == C.ENCODING_PCM_16BIT;
    }

    @Override
    protected void onReset() {
        compressor.reset();
        processedRevision = -1;
        verifiedRevision = -1;
        observedFrames = 0;
        pcmConfigured = false;
        selection = new Selection(revisions.incrementAndGet(), null);
    }

    @Override
    public void queueInput(ByteBuffer input) {
        final int count = input.remaining();
        if (count == 0) return;
        final ByteBuffer output = replaceOutputBuffer(count);
        final Selection requested = selection;
        if (requested.revision != processedRevision) {
            compressor.reset();
            processedRevision = requested.revision;
        }
        final long frames = compressor.process(input, output,
                inputAudioFormat.channelCount, inputAudioFormat.sampleRate,
                requested.strength);
        output.flip();
        if (frames > 0 && requested.revision == selection.revision &&
                requested.strength != null && pcmConfigured) {
            observedFrames += frames;
            verifiedRevision = requested.revision;
        }
    }
}
