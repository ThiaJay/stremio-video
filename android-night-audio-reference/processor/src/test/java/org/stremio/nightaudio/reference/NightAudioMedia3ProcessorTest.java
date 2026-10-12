package org.stremio.nightaudio.reference;

import static org.junit.Assert.*;

import androidx.media3.common.C;
import androidx.media3.common.audio.AudioProcessor.AudioFormat;
import androidx.media3.common.util.UnstableApi;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.junit.Test;

@UnstableApi
public final class NightAudioMedia3ProcessorTest {
    private static ByteBuffer pcm() {
        final ByteBuffer result = ByteBuffer.allocateDirect(4800 * 4)
                .order(ByteOrder.nativeOrder());
        for (int i = 0; i < 4800; i++) {
            final short sample = (short) Math.round(0.8 * 32767 *
                    Math.sin(2 * Math.PI * 440 * i / 48000));
            result.putShort(sample).putShort(sample);
        }
        result.flip();
        return result;
    }

    @Test
    public void noSignalChangeWhenOffAndNoFalseActiveBeforeFrames() throws Exception {
        final NightAudioMedia3Processor player = new NightAudioMedia3Processor();
        final AudioFormat stereo = new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT);
        assertEquals(stereo, player.configure(stereo));
        player.flush();
        final ByteBuffer original = pcm();
        final ByteBuffer backup = original.duplicate();
        player.queueInput(original);
        final ByteBuffer off = player.getOutput();
        assertEquals(backup.remaining(), off.remaining());
        while (backup.hasRemaining()) assertEquals(backup.get(), off.get());
        assertFalse(player.isPcmProcessingObserved());
        player.request(PcmDynamicsProcessor.Strength.BALANCED);
        assertFalse(player.isPcmProcessingObserved());
        player.queueInput(pcm());
        assertTrue(player.isPcmProcessingObserved());
        assertTrue(player.getOutput().hasRemaining());
        player.request(null);
        assertFalse(player.isPcmProcessingObserved());
        player.queueInput(pcm());
        assertFalse(player.isPcmProcessingObserved());
        player.getOutput();
        player.reset();
        assertFalse(player.isPcmProcessingObserved());
    }

    @Test
    public void unsupportedAndFlushDoNotLeaveStaleActiveState() throws Exception {
        final NightAudioMedia3Processor player = new NightAudioMedia3Processor();
        player.request(PcmDynamicsProcessor.Strength.GENTLE);
        final AudioFormat stereo = new AudioFormat(48000, 2, C.ENCODING_PCM_16BIT);
        player.configure(stereo);
        player.flush();
        player.queueInput(pcm());
        assertTrue(player.isPcmProcessingObserved());
        player.getOutput();
        player.flush();
        assertFalse(player.isPcmProcessingObserved());
        assertEquals(AudioFormat.NOT_SET,
                player.configure(new AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT)));
        player.flush();
        assertFalse(player.isPcmProcessingObserved());
    }
}
