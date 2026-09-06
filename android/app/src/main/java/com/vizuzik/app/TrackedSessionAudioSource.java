package com.vizuzik.app;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * Feeds OverlayEdgeGlowService a real-time loudness spectrum straight from the tracked app's own
 * audio session, via android.media.audiofx.Visualizer — no MediaProjection consent dialog, only
 * RECORD_AUDIO (already requested by "mic" mode in the full-screen player; see
 * MicCaptureThread/startMicCapture()).
 *
 * How the session id is found: Android's standard playback stacks (MediaPlayer, ExoPlayer's
 * AudioTrack path) send AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION whenever a new
 * *non-zero* audio session opens (and ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION when it goes
 * away), carrying that session's id and the playing app's package name — the traditional way a
 * third-party equalizer/visualizer app attaches to whatever's currently playing without knowing
 * in advance which app or session that will be. Validated on-device before writing this (see
 * VisualizerProbe, the throwaway prototype this class replaces for production use): on the test
 * device, attaching to the "output mix" (session 0) is refused outright by the OS
 * (ERROR_INVALID_OPERATION), but attaching to the tracked app's own session via this broadcast
 * works and returns real, moving FFT data.
 *
 * Produces the same 32-band, 55 Hz-7000 Hz logarithmic loudness spectrum as
 * AudioCaptureService/MicCaptureThread, so EdgeGlowView.pushLevels() takes levels from this
 * source exactly as it already does from AudioLevelsBridge — no changes needed there. The band
 * table and smoothing formula are mirrored here rather than shared, same as MicCaptureThread
 * mirrors AudioCaptureService's — this source runs in yet another context (its own worker
 * thread, driven by FFT bins rather than raw PCM), and the project's own precedent is to keep
 * each capture path's copy independent rather than couple three different contexts to one
 * shared helper.
 *
 * Everything Visualizer-related (construction, capture callbacks, release) runs on a dedicated
 * background thread with its own Looper, never the main thread: an AudioEffect subclass delivers
 * its callbacks on whichever thread constructed it if that thread has a Looper, so constructing
 * it from a plain BroadcastReceiver callback (delivered on the main thread by default) would mean
 * up to 30 FFT-to-band conversions a second competing with the overlay's own rendering and window
 * management for main-thread time.
 *
 * The magnitude-to-level scaling below is a first approximation (Visualizer's raw FFT magnitudes
 * have no fixed reference level to calibrate against without more on-device tuning) — expect to
 * adjust the compression constant once someone's actually looked at the glow reacting to music.
 */
final class TrackedSessionAudioSource {

    interface Listener {
        void onLevels(float[] levels);
        void onSourceLost();
    }

    private static final String TAG = "TrackedSessionAudio";
    private static final int BAND_COUNT = 32;
    private static final double MIN_FREQ = 55;
    private static final double MAX_FREQ = 7000;
    private static final int CAPTURE_RATE_HZ = 30;
    // First-approximation log compression — see the class doc above.
    private static final double MAGNITUDE_SCALE = 6.0;
    private static final double MAGNITUDE_CEILING = 600.0;

    private final Context appContext;
    private final Listener listener;
    private final double[] bandFrequencies = new double[BAND_COUNT];
    private final float[] smoothedBands = new float[BAND_COUNT];

    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onAudioEffectSessionEvent(intent);
        }
    };

    private final Visualizer.OnDataCaptureListener captureListener = new Visualizer.OnDataCaptureListener() {
        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
            // Not requested (see setDataCaptureListener() below) — the FFT alone is enough to
            // build the band spectrum EdgeGlowView expects.
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
            updateBands(fft, samplingRate / 1000.0);
        }
    };

    // Everything below is only ever touched on workerHandler's thread (start()/stop() hop onto
    // it via post() before reading or writing any of it), so no additional synchronization is
    // needed despite start()/stop() themselves being called from the service's main thread.
    private HandlerThread workerThread;
    private Handler workerHandler;
    private Visualizer visualizer;
    private int attachedSessionId = -1;

    TrackedSessionAudioSource(Context context, Listener listener) {
        this.appContext = context.getApplicationContext();
        this.listener = listener;
        double ratio = MAX_FREQ / MIN_FREQ;
        for (int i = 0; i < BAND_COUNT; i++) {
            bandFrequencies[i] = MIN_FREQ * Math.pow(ratio, i / (double) (BAND_COUNT - 1));
        }
    }

    /** Starts listening for a session to attach to. Attaching itself only happens once a
     *  matching broadcast arrives — see onAudioEffectSessionEvent(). */
    void start() {
        if (workerThread != null) return;
        workerThread = new HandlerThread("VizuzikTrackedSessionAudio");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        // This broadcast comes from another app's process (the tracked app's player), not the
        // system, so on API 33+ it needs to be declared explicitly or registerReceiver() throws
        // on API 34+ targets. Delivered on the main thread (no Handler passed) — fine, since
        // onAudioEffectSessionEvent() only reads two Intent extras before handing the real work
        // to workerHandler.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(sessionReceiver, filter);
        }
    }

    void stop() {
        try {
            appContext.unregisterReceiver(sessionReceiver);
        } catch (IllegalArgumentException ignored) {
            // Already unregistered, or start() was never called.
        }
        if (workerHandler != null) {
            workerHandler.post(this::releaseVisualizer);
        }
        if (workerThread != null) {
            // Lets the release() posted above run first, then stops the thread for good.
            workerThread.quitSafely();
            workerThread = null;
            workerHandler = null;
        }
    }

    private void onAudioEffectSessionEvent(Intent intent) {
        int sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
        String packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        if (sessionId <= 0 || packageName == null || !MusicApps.isKnownPackage(packageName)) {
            return;
        }
        Handler handler = workerHandler;
        if (handler == null) return; // stop() already ran.
        if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction())) {
            handler.post(() -> onSessionClosed(sessionId));
        } else {
            handler.post(() -> attach(sessionId, packageName));
        }
    }

    /** Runs on workerHandler's thread. */
    private void onSessionClosed(int sessionId) {
        if (sessionId != attachedSessionId) return; // Some other session closing, not ours.
        releaseVisualizer();
        listener.onSourceLost();
    }

    /** Runs on workerHandler's thread — so does every callback the Visualizer built here ever
     *  fires, since delivery follows the creating thread's Looper. */
    private void attach(int sessionId, String packageName) {
        // Some players resend ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION for the same session on a
        // focus change or route switch, without the session actually closing — tearing down and
        // recreating the Visualizer for that would just be a brief, avoidable glow dropout.
        if (sessionId == attachedSessionId && visualizer != null) {
            return;
        }
        // A background service has no Activity to show a runtime permission dialog from — same
        // reasoning as the old mic-in-overlay code this replaces, just without ever opening a
        // second AudioRecord: if RECORD_AUDIO isn't already granted (e.g. only "son réel" was
        // ever used, never "mic"), this silently stays off rather than prompting for a grant it
        // can't ask for. AudioLevelsBridge (MediaProjection) remains the fallback either way.
        if (ContextCompat.checkSelfPermission(appContext, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return;
        }
        releaseVisualizer();
        try {
            Visualizer v = new Visualizer(sessionId);
            int captureSize = Visualizer.getCaptureSizeRange()[1];
            try {
                v.setCaptureSize(captureSize);
            } catch (Exception e) {
                Log.w(TAG, "setCaptureSize failed, using default", e);
            }
            int rate = Math.min(CAPTURE_RATE_HZ * 1000, Visualizer.getMaxCaptureRate());
            v.setDataCaptureListener(captureListener, rate, false, true);
            v.setEnabled(true);
            visualizer = v;
            attachedSessionId = sessionId;
        } catch (Exception e) {
            Log.w(TAG, "Impossible d'attacher le Visualizer à la session de " + packageName, e);
            listener.onSourceLost();
        }
    }

    /**
     * Buckets the FFT capture into the same 32 log-spaced bands AudioCaptureService/
     * MicCaptureThread produce. Android's Visualizer FFT byte layout: fft[0]=Re(0),
     * fft[1]=Re(captureSize/2), and for bin i in 1..captureSize/2-1: fft[2i]=Re(i), fft[2i+1]=Im(i)
     * — frequency resolution is sampleRateHz/captureSize, so each target frequency maps to
     * bin = round(freq * captureSize / sampleRateHz).
     */
    private void updateBands(byte[] fft, double sampleRateHz) {
        if (fft == null || fft.length < 4 || sampleRateHz <= 0) return;
        int bins = fft.length / 2;
        for (int i = 0; i < BAND_COUNT; i++) {
            int bin = (int) Math.round(bandFrequencies[i] * fft.length / sampleRateHz);
            bin = Math.max(1, Math.min(bins - 1, bin));
            double re;
            double im;
            if (bin == bins - 1) {
                re = fft[1]; // Re(captureSize/2) is packed here, no matching imaginary part.
                im = 0;
            } else {
                re = fft[2 * bin];
                im = fft[2 * bin + 1];
            }
            double magnitude = Math.sqrt(re * re + im * im);
            float level = clamp01((float) (
                Math.log10(1 + magnitude * MAGNITUDE_SCALE) / Math.log10(1 + MAGNITUDE_CEILING * MAGNITUDE_SCALE)
            ));
            smoothedBands[i] = smoothedBands[i] * 0.5f + level * 0.5f;
        }
        listener.onLevels(smoothedBands.clone());
    }

    /** Runs on workerHandler's thread. */
    private void releaseVisualizer() {
        attachedSessionId = -1;
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (Exception e) {
            Log.w(TAG, "release", e);
        }
        visualizer = null;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return value < 0 ? 0 : Math.min(value, 1);
    }
}
