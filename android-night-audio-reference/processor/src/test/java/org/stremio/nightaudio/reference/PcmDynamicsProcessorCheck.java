package org.stremio.nightaudio.reference;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Arrays;

/** No JUnit or Android dependency. Compile and execute with any Java 17 JDK. */
public final class PcmDynamicsProcessorCheck {
    private static void check(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    private static byte[] sine(int channels, double quiet, double loud) {
        final int frames = 48000 * 4;
        final ByteBuffer buffer = ByteBuffer.allocate(frames * channels * 2)
                .order(ByteOrder.LITTLE_ENDIAN);
        for (int i = 0; i < frames; i++) {
            double amplitude = i < 96000 ? quiet : loud;
            short value = (short) Math.round(Math.sin(2 * Math.PI * 440 * i / 48000)
                    * amplitude * 32767);
            for (int channel = 0; channel < channels; channel++) buffer.putShort(value);
        }
        return buffer.array();
    }

    private static byte[] run(PcmDynamicsProcessor processor, byte[] samples,
                              int channels, PcmDynamicsProcessor.Strength strength, int block) {
        final ByteBuffer result = ByteBuffer.allocate(samples.length).order(ByteOrder.LITTLE_ENDIAN);
        long frames = 0;
        for (int pos = 0; pos < samples.length; pos += block) {
            final int end = Math.min(samples.length, pos + block);
            final ByteBuffer in = ByteBuffer.wrap(samples, pos, end - pos);
            final ByteBuffer out = ByteBuffer.allocate(end - pos).order(ByteOrder.LITTLE_ENDIAN);
            frames += processor.process(in, out, channels, 48000, strength);
            check(!in.hasRemaining(), "input buffer not consumed");
            result.put(out.array());
        }
        if (strength != null) check(frames == 192000L, "frame count changed");
        return result.array();
    }

    private static double rms(byte[] samples, int channels, double from, double to) {
        final ByteBuffer pcm = ByteBuffer.wrap(samples).order(ByteOrder.LITTLE_ENDIAN);
        double sum = 0;
        int count = 0;
        for (int frame = (int) (48000 * from); frame < (int) (48000 * to); frame++) {
            final double value = pcm.getShort(frame * channels * 2) / 32768.0;
            sum += value * value;
            count++;
        }
        return Math.sqrt(sum / count);
    }

    private static void testCompression() {
        for (int channels : new int[] { 1, 2, 6, 8 }) {
            final byte[] input = sine(channels, .035, .80);
            final byte[] exact = run(new PcmDynamicsProcessor(), input, channels, null, channels * 2 * 384);
            check(Arrays.equals(input, exact), "Off must be bit exact for " + channels);
            for (PcmDynamicsProcessor.Strength strength : PcmDynamicsProcessor.Strength.values()) {
                final int block = channels * 2 * 384;
                final byte[] output = run(new PcmDynamicsProcessor(), input, channels, strength, block);
                final double beforeRatio = rms(input, channels, 2.6, 3.7) /
                        rms(input, channels, .4, 1.7);
                final double afterRatio = rms(output, channels, 2.6, 3.7) /
                        rms(output, channels, .4, 1.7);
                check(afterRatio < beforeRatio * .85, "compression insufficient for " + strength);
                check(rms(output, channels, .4, 1.7) >= rms(input, channels, .4, 1.7) * .90,
                        "quiet audio suppressed");
                check(output.length == input.length, "duration changed");
                final ByteBuffer pcm = ByteBuffer.wrap(output).order(ByteOrder.LITTLE_ENDIAN);
                for (int i = 0; i < pcm.capacity(); i += 2) {
                    check(Math.abs((int) pcm.getShort(i)) <= 29500, "peak exceeded limit");
                }
            }
        }
    }

    private static void testSafetyAndState() {
        final byte[] input = sine(2, .035, .80);
        final PcmDynamicsProcessor processor = new PcmDynamicsProcessor();
        final byte[] active = run(processor, input, 2, PcmDynamicsProcessor.Strength.STRONG, 2 * 2 * 480);
        final byte[] bypass = run(processor, input, 2, null, 2 * 2 * 480);
        check(Arrays.equals(input, bypass), "Off after On must be bit exact");
        final byte[] activatedAgain = run(processor, input, 2,
                PcmDynamicsProcessor.Strength.STRONG, 2 * 2 * 480);
        check(Arrays.equals(active, activatedAgain), "mode change must reset envelope");
        final ByteBuffer malformed = ByteBuffer.wrap(new byte[] {1, 2, 3});
        final ByteBuffer result = ByteBuffer.allocate(3);
        check(processor.process(malformed, result, 2, 48000,
                PcmDynamicsProcessor.Strength.BALANCED) == 0, "partial frame was processed");
        check(Arrays.equals(result.array(), new byte[] {1, 2, 3}), "partial frame lost data");
        final ByteBuffer unsupported = ByteBuffer.wrap(new byte[] {4, 5, 6, 7});
        final ByteBuffer out = ByteBuffer.allocate(4);
        check(processor.process(unsupported, out, 9, 48000,
                PcmDynamicsProcessor.Strength.GENTLE) == 0, "unsupported channel count processed");
        check(Arrays.equals(out.array(), new byte[] {4, 5, 6, 7}), "unsupported input altered");
    }

    private static void testStatusGate() {
        final NightAudioPlaybackGate.Path PCM = NightAudioPlaybackGate.Path.PCM;
        final NightAudioPlaybackGate.Status OFF = NightAudioPlaybackGate.Status.OFF;
        final NightAudioPlaybackGate.Status BYPASSED = NightAudioPlaybackGate.Status.BYPASSED;
        final NightAudioPlaybackGate.Status UNAVAILABLE = NightAudioPlaybackGate.Status.UNAVAILABLE;
        final NightAudioPlaybackGate.Status CHECKING = NightAudioPlaybackGate.Status.CHECKING;
        final NightAudioPlaybackGate.Status ACTIVE = NightAudioPlaybackGate.Status.ACTIVE;
        check(NightAudioPlaybackGate.evaluate(false, PCM, true, true, true, true) == OFF,
                "Off should never claim processing");
        check(NightAudioPlaybackGate.evaluate(true, NightAudioPlaybackGate.Path.BITSTREAM,
                true, true, true, true) == BYPASSED, "passthrough must be bypassed");
        check(NightAudioPlaybackGate.evaluate(true, NightAudioPlaybackGate.Path.OFFLOAD,
                true, true, true, true) == BYPASSED, "offload must be bypassed");
        check(NightAudioPlaybackGate.evaluate(true, NightAudioPlaybackGate.Path.UNKNOWN,
                true, true, true, true) == UNAVAILABLE, "unknown backend is not supported");
        check(NightAudioPlaybackGate.evaluate(true, PCM, true, false, true, true) == UNAVAILABLE,
                "unconfirmed route is not supported");
        check(NightAudioPlaybackGate.evaluate(true, PCM, true, true, false, true) == CHECKING,
                "saved preference is not applied DSP");
        check(NightAudioPlaybackGate.evaluate(true, PCM, false, true, true, true) == CHECKING,
                "stale playback cannot claim active");
        check(NightAudioPlaybackGate.evaluate(true, PCM, true, true, true, false) == CHECKING,
                "stale strength cannot claim active");
        check(NightAudioPlaybackGate.evaluate(true, PCM, true, true, true, true) == ACTIVE,
                "fully verified path not active");
    }

    public static void main(String[] args) {
        testCompression();
        testSafetyAndState();
        testStatusGate();
        System.out.println("PASS PCM16 bypass, three compression levels, mono/stereo/5.1/7.1, " +
                "peak bound, equal frames, resets and malformed input");
    }
}
