package org.stremio.nightaudio.reference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;

/**
 * Isolated, deterministic PCM16 dynamics processor for an Android player adapter.
 * It has no account access, codec negotiation, output routing or volume controls.
 * A player must confirm decoded PCM eligibility before invoking it.
 */
public final class PcmDynamicsProcessor {
    public enum Strength {
        GENTLE(0.16, 1.7, 30.0, 400.0),
        BALANCED(0.13, 2.8, 20.0, 320.0),
        STRONG(0.10, 4.0, 12.0, 250.0);

        final double threshold;
        final double ratio;
        final double attackMs;
        final double releaseMs;

        Strength(double threshold, double ratio, double attackMs, double releaseMs) {
            this.threshold = threshold;
            this.ratio = ratio;
            this.attackMs = attackMs;
            this.releaseMs = releaseMs;
        }
    }

    private Strength appliedStrength;
    private int appliedSampleRate;
    private double attackCoefficient;
    private double releaseCoefficient;
    private double envelope;
    private final double[] frame = new double[8];

    public void reset() {
        appliedStrength = null;
        appliedSampleRate = 0;
        envelope = 0;
    }

    /**
     * Process complete interleaved little-endian PCM16 frames without changing their count.
     * A null strength means true byte-for-byte bypass. A malformed partial frame
     * is passed through unchanged rather than discarded or misinterpreted.
     * Input and output must be distinct buffers, with output space at least the input size.
     * Returns the number of complete PCM frames actually compressed.
     */
    public long process(ByteBuffer input, ByteBuffer output,
                        int channels, int sampleRate, Strength strength) {
        Objects.requireNonNull(input, "input");
        Objects.requireNonNull(output, "output");
        final int bytes = input.remaining();
        if (input == output || output.remaining() < bytes) {
            throw new IllegalArgumentException("Distinct output buffer with enough space required");
        }
        if (strength == null || channels < 1 || channels > 8 ||
                sampleRate < 8000 || sampleRate > 192000 ||
                (bytes % (2 * channels)) != 0) {
            reset();
            output.put(input);
            return 0;
        }
        if (appliedStrength != strength || appliedSampleRate != sampleRate) {
            envelope = 0;
            appliedStrength = strength;
            appliedSampleRate = sampleRate;
            attackCoefficient = Math.exp(-1000.0 / (sampleRate * strength.attackMs));
            releaseCoefficient = Math.exp(-1000.0 / (sampleRate * strength.releaseMs));
        }
        final int frames = bytes / (2 * channels);
        final ByteBuffer source = input.duplicate().order(ByteOrder.LITTLE_ENDIAN);
        final ByteBuffer destination = output.order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            double peak = 0;
            for (int ch = 0; ch < channels; ch++) {
                frame[ch] = source.getShort() / 32768.0;
                peak = Math.max(peak, Math.abs(frame[ch]));
            }
            final double coefficient = peak > envelope ? attackCoefficient : releaseCoefficient;
            envelope = coefficient * envelope + (1.0 - coefficient) * peak;
            final double gain = envelope > strength.threshold
                    ? Math.pow(strength.threshold / envelope, 1.0 - 1.0 / strength.ratio)
                    : 1.0;
            for (int ch = 0; ch < channels; ch++) {
                // A smooth final limiting knee begins at 0.7; output never exceeds 0.9.
                // Identical linked frame gain preserves multichannel balance.
                final double value = frame[ch] * gain;
                final double magnitude = Math.abs(value);
                final double limited = magnitude <= 0.7 ? magnitude
                        : 0.7 + 0.2 * (1.0 - Math.exp(-(magnitude - 0.7) / 0.2));
                final int scaled = (int) Math.round(Math.copySign(limited, value) * 32768.0);
                destination.putShort((short) Math.max(-32768, Math.min(32767, scaled)));
            }
        }
        input.position(input.limit());
        return frames;
    }
}
