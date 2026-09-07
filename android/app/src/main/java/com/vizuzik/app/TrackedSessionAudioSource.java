package com.vizuzik.app;

import android.Manifest;
import android.content.Context;
import android.content.pm.PackageManager;
import android.media.audiofx.Visualizer;
import android.os.Handler;
import android.os.HandlerThread;
import android.util.Log;

import androidx.core.content.ContextCompat;

/**
 * The app's audio source: a real-time loudness spectrum taken straight from the tracked app's own
 * audio session via android.media.audiofx.Visualizer — no MediaProjection consent dialog, only
 * RECORD_AUDIO (asked for once by requestAudioPermission() in DeezerMediaPlugin).
 *
 * How the session id is found: AudioSessionRegistry, which listens process-wide for the
 * ACTION_OPEN/CLOSE_AUDIO_EFFECT_CONTROL_SESSION broadcasts Android's standard playback stacks
 * send and caches the tracked app's currently open session. This class deliberately does not
 * register for those broadcasts itself — see AudioSessionRegistry's class doc: the open broadcast
 * fires once, when the player opens its session, which is always well before this source starts,
 * so listening from here caught nothing and left the overlay permanently in its ambient regime.
 * Validated on-device before writing this (see VisualizerProbe, the throwaway prototype this class
 * replaces for production use): on the test device, attaching to the "output mix" (session 0) is
 * refused outright by the OS (ERROR_INVALID_OPERATION), but attaching to the tracked app's own
 * session works and returns real, moving FFT data.
 *
 * Produces a 32-band, 55 Hz-7000 Hz logarithmic loudness spectrum, published through
 * AudioLevelsBridge (see TrackedAudioCapture) to both the full-screen visualizer and the edge
 * overlay. Two earlier sources — a microphone thread and a MediaProjection service — produced the
 * same band layout and have since been removed; this is now the only one.
 *
 * Everything Visualizer-related (construction, capture callbacks, release) runs on a dedicated
 * background thread with its own Looper, never the main thread: an AudioEffect subclass delivers
 * its callbacks on whichever thread constructed it if that thread has a Looper, so constructing
 * it from a plain BroadcastReceiver callback (delivered on the main thread by default) would mean
 * up to 30 FFT-to-band conversions a second competing with the overlay's own rendering and window
 * management for main-thread time.
 *
 * On how updateBands() scales a raw FFT magnitude into the 0-1 level EdgeGlowView draws: three
 * earlier attempts got this wrong, and the last one is worth recording because it failed in a way
 * that looked like success. Two fixed constants were guessed first and were wrong in opposite
 * directions (one left the glow barely reactive, the other pinned it at maximum). The third tried
 * an adaptive ceiling per band — each band continuously renormalized against its own recent peak,
 * with that peak decaying over roughly a second. That is an automatic gain control, and an AGC
 * whose time constant sits on top of the beat period is precisely a rhythm remover: a loud beat
 * pulls its band's ceiling up, the ceiling then decays into the gap after it, and the next beat
 * divides by a ceiling the previous beat just set. Every band ends up hovering near its own recent
 * average no matter how loud the music actually is, so the border still moved — it just moved
 * with no relation to the beat, which is exactly what it looked like on the device.
 *
 * There is no need to infer a reference level at all: Visualizer hands the FFT back as *signed
 * bytes* (see updateBands()'s layout note), so a bin's magnitude is bounded by sqrt(128² + 128²) ≈
 * 180 by construction. That bound comes from the API's own documented data type rather than from
 * device gain staging, which is what made the first two constants unguessable. updateBands() maps
 * against it directly, with no memory of previous frames — so a loud passage reads loud, a quiet
 * one reads quiet, and the beat survives.
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
    // The largest magnitude a bin can hold given the FFT arrives as signed bytes — see the class
    // doc. Fixed, frame-independent, and derived from the data type rather than guessed.
    private static final float MAGNITUDE_MAX = 180f;
    // Below this a bin is barely above the quantisation noise of a byte-resolution FFT. Gating it
    // to zero keeps a silent passage looking silent instead of having its noise floor stretched
    // into visible movement by the curve below.
    private static final float NOISE_GATE = 4f;
    // Music leaves most bins far below MAGNITUDE_MAX, so mapping linearly would render nearly
    // everything dark and flat. This lifts quiet-but-real content into view without letting loud
    // content saturate: 0.05 of the range becomes 0.26, 0.25 becomes 0.54, 1.0 stays 1.0.
    private static final double LEVEL_GAMMA = 0.45;

    private final Context appContext;
    private final Listener listener;
    private final double[] bandFrequencies = new double[BAND_COUNT];
    private final float[] smoothedBands = new float[BAND_COUNT];

    private final AudioSessionRegistry.Listener registryListener = this::onSessionChanged;

    private final Visualizer.OnDataCaptureListener captureListener = new Visualizer.OnDataCaptureListener() {
        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
            // Not requested (see setDataCaptureListener() below) — the FFT alone is enough to
            // build the band spectrum the visualizers expect.
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

    /** Attaches straight away to whatever session the registry already knows about — the normal
     *  case, since the tracked app started playing before this source ever ran — and follows any
     *  later change from there. */
    void start() {
        if (workerThread != null) return;
        workerThread = new HandlerThread("VizuzikTrackedSessionAudio");
        workerThread.start();
        workerHandler = new Handler(workerThread.getLooper());

        AudioSessionRegistry registry = AudioSessionRegistry.getInstance();
        registry.start(appContext);
        registry.addListener(registryListener);
        onSessionChanged(registry.currentSessionId());
    }

    /** Re-runs the attach decision for whatever session is open right now — see
     *  TrackedAudioCapture.onAudioPermissionGranted(), the only caller. */
    void retryAttach() {
        onSessionChanged(AudioSessionRegistry.getInstance().currentSessionId());
    }

    void stop() {
        AudioSessionRegistry.getInstance().removeListener(registryListener);
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

    /** Called on the registry's thread (or this one's caller, at start()) — hands every decision
     *  straight to workerHandler, since everything below it touches the Visualizer. */
    private void onSessionChanged(int sessionId) {
        Handler handler = workerHandler;
        if (handler == null) return; // stop() already ran.
        if (sessionId <= 0) {
            handler.post(this::onSessionClosed);
        } else {
            handler.post(() -> attach(sessionId));
        }
    }

    /** Runs on workerHandler's thread. */
    private void onSessionClosed() {
        if (attachedSessionId == -1) return; // Nothing was attached in the first place.
        releaseVisualizer();
        listener.onSourceLost();
    }

    /** Runs on workerHandler's thread — so does every callback the Visualizer built here ever
     *  fires, since delivery follows the creating thread's Looper. */
    private void attach(int sessionId) {
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
            Log.w(TAG, "Impossible d'attacher le Visualizer à la session " + sessionId, e);
            listener.onSourceLost();
        }
    }

    /**
     * Buckets the FFT capture into 32 log-spaced bands. Android's Visualizer FFT byte layout:
     * fft[0]=Re(0),
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

            float level = magnitude <= NOISE_GATE
                ? 0f
                : (float) Math.pow(
                    clamp01((float) ((magnitude - NOISE_GATE) / (MAGNITUDE_MAX - NOISE_GATE))),
                    LEVEL_GAMMA
                );
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
