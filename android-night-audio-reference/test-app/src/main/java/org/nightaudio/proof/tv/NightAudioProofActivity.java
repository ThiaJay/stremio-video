package org.nightaudio.proof.tv;

import android.app.Activity;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.view.View;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import androidx.media3.common.MediaItem;
import androidx.media3.common.PlaybackException;
import androidx.media3.common.Player;
import androidx.media3.common.audio.AudioProcessor;
import androidx.media3.common.util.UnstableApi;
import androidx.media3.exoplayer.DefaultRenderersFactory;
import androidx.media3.exoplayer.ExoPlayer;
import androidx.media3.exoplayer.audio.AudioSink;
import androidx.media3.exoplayer.audio.DefaultAudioSink;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import org.stremio.nightaudio.reference.NightAudioMedia3Processor;
import org.stremio.nightaudio.reference.PcmDynamicsProcessor;

/**
 * Explicit local synthetic-audio acceptance harness. No Stremio account,
 * internet permission, stream source, device configuration or production state.
 */
@UnstableApi
public final class NightAudioProofActivity extends Activity {
    private static final int RATE = 48000;
    private static final int SECONDS = 12;
    private static final int BACKGROUND = Color.rgb(255, 250, 242);
    private static final int TEAL = Color.rgb(0, 103, 105);
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final NightAudioMedia3Processor audioProcessor = new NightAudioMedia3Processor();
    private final Runnable statusTicker = new Runnable() {
        @Override public void run() {
            refreshStatus();
            handler.postDelayed(this, 750);
        }
    };
    private ExoPlayer player;
    private TextView status;
    private String mode = "Off";
    private String errorMessage;

    @Override
    protected void onCreate(Bundle state) {
        super.onCreate(state);
        DefaultRenderersFactory renderers = new DefaultRenderersFactory(this) {
            @Override
            protected AudioSink buildAudioSink(Context context, boolean enableFloatOutput,
                                               boolean enableAudioOutputPlaybackParams) {
                return new DefaultAudioSink.Builder(context)
                        .setEnableFloatOutput(false)
                        .setEnableAudioOutputPlaybackParameters(enableAudioOutputPlaybackParams)
                        .setAudioProcessors(new AudioProcessor[] {audioProcessor})
                        .build();
            }
        };
        player = new ExoPlayer.Builder(this, renderers).build();
        player.setVolume(0.20f);
        player.addListener(new Player.Listener() {
            @Override public void onPlaybackStateChanged(int state) { refreshStatus(); }
            @Override public void onPlayerError(PlaybackException error) {
                errorMessage = "The local sample could not be played.";
                audioProcessor.request(null);
                refreshStatus();
            }
        });

        ScrollView scroller = new ScrollView(this);
        scroller.setFillViewport(true);
        scroller.setBackgroundColor(BACKGROUND);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(36, 28, 36, 28);
        scroller.addView(layout);
        addText(layout, "Night Audio listening test", 25, TEAL);
        addText(layout, "Independent synthetic sound test. Not the Stremio app.", 17, Color.DKGRAY);
        addText(layout, "Set a comfortable TV volume before pressing Play. Playback starts at 20% of the app volume.",
                16, Color.DKGRAY);
        Button play = addButton(layout, "Play twelve second comparison", this::startComparison);
        addButton(layout, "Off", () -> setStrength(null, "Off"));
        addButton(layout, "Gentle", () -> setStrength(PcmDynamicsProcessor.Strength.GENTLE, "Gentle"));
        addButton(layout, "Balanced", () -> setStrength(PcmDynamicsProcessor.Strength.BALANCED, "Balanced"));
        addButton(layout, "Strong", () -> setStrength(PcmDynamicsProcessor.Strength.STRONG, "Strong"));
        addButton(layout, "Stop playback", () -> {
            player.stop();
            setStrength(null, "Off");
        });
        status = addText(layout, "Off. Nothing playing.", 17, TEAL);
        addText(layout, "Quiet and louder synthetic tones alternate. There is no speech, microphone, network connection or access to Stremio account data. Output hardware is not automatically identified.",
                14, Color.DKGRAY);
        setContentView(scroller);
        play.requestFocus();
        handler.post(statusTicker);
    }

    private TextView addText(LinearLayout parent, String text, int size, int colour) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextColor(colour);
        view.setTextSize(size);
        view.setPadding(0, 0, 0, 14);
        parent.addView(view);
        return view;
    }

    private Button addButton(LinearLayout parent, String text, Runnable action) {
        Button button = new Button(this);
        button.setText(text);
        button.setTextSize(18);
        button.setAllCaps(false);
        button.setOnClickListener(view -> action.run());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.bottomMargin = 7;
        parent.addView(button, params);
        return button;
    }

    private void setStrength(PcmDynamicsProcessor.Strength strength, String label) {
        mode = label;
        errorMessage = null;
        audioProcessor.request(strength);
        refreshStatus();
    }

    private void startComparison() {
        errorMessage = null;
        try {
            final File sample = new File(getCacheDir(), "local-night-audio-test.wav");
            if (!sample.isFile() || sample.length() != 44L + RATE * SECONDS * 4L) {
                writeSyntheticSample(sample);
            }
            player.setMediaItem(MediaItem.fromUri(Uri.fromFile(sample)));
            player.prepare();
            player.play();
        } catch (IOException e) {
            errorMessage = "Could not create the local sound sample.";
        }
        refreshStatus();
    }

    private void refreshStatus() {
        if (status == null || player == null) return;
        if (errorMessage != null) {
            status.setText(errorMessage);
        } else if (!player.isPlaying()) {
            status.setText(mode + ". No audio currently playing.");
        } else if ("Off".equals(mode)) {
            status.setText("Off. PCM signal passes through this feature unchanged.");
        } else if (audioProcessor.isPcmProcessingObserved()) {
            status.setText(mode + ". PCM frames processed in this test player. Physical output route unverified.");
        } else {
            status.setText(mode + ". Waiting for eligible PCM audio.");
        }
    }

    private static void writeInt(BufferedOutputStream output, int number) throws IOException {
        for (int i = 0; i < 4; i++) output.write((number >>> (8 * i)) & 255);
    }

    private static void writeShort(BufferedOutputStream output, int number) throws IOException {
        output.write(number & 255);
        output.write((number >>> 8) & 255);
    }

    private static void writeSyntheticSample(File file) throws IOException {
        final int frames = RATE * SECONDS;
        final int size = frames * 4;
        try (BufferedOutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            out.write(new byte[] {'R', 'I', 'F', 'F'});
            writeInt(out, 36 + size);
            out.write(new byte[] {'W', 'A', 'V', 'E', 'f', 'm', 't', ' '});
            writeInt(out, 16);
            writeShort(out, 1); // PCM
            writeShort(out, 2); // stereo
            writeInt(out, RATE);
            writeInt(out, RATE * 4);
            writeShort(out, 4);
            writeShort(out, 16);
            out.write(new byte[] {'d', 'a', 't', 'a'});
            writeInt(out, size);
            for (int index = 0; index < frames; index++) {
                // Quiet, loud, quiet, loud. Synthetic audio, no speech recording.
                final double amplitude = ((index / (RATE * 3)) % 2) == 0 ? 0.035 : 0.55;
                final int sample = (int) Math.round(32767 * amplitude *
                        Math.sin(2 * Math.PI * 330 * index / RATE));
                writeShort(out, sample);
                writeShort(out, sample);
            }
        }
    }

    @Override protected void onStop() {
        super.onStop();
        if (player != null) player.pause();
        audioProcessor.request(null);
        mode = "Off";
    }

    @Override protected void onDestroy() {
        handler.removeCallbacks(statusTicker);
        if (player != null) player.release();
        super.onDestroy();
    }
}
