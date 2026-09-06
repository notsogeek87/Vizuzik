package com.vizuzik.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.audiofx.AudioEffect;
import android.media.audiofx.Visualizer;
import android.os.Build;
import android.util.Log;

/**
 * Isolated diagnostic prototype. NOT wired into the production audio pipeline
 * (AudioLevelsBridge, MicCaptureThread, EdgeGlowView) — it only exposes a status snapshot (see
 * getStatus()) and logs to Logcat, so it can be started and stopped freely while iterating
 * without touching anything the app actually renders.
 *
 * The question it exists to answer experimentally: can android.media.audiofx.Visualizer see the
 * tracked app's (Deezer's) own audio output using only RECORD_AUDIO — no MediaProjection consent
 * dialog — the way MuViz Edge's own documentation describes ("system-level music data")? Our
 * current AudioCaptureService already proves AudioPlaybackCapture works for this app's audio, so
 * Deezer isn't the kind of app that opts out of being captured (AudioAttributes'
 * setAllowedCapturePolicy()); the open question is only whether the OS/OEM build still allows a
 * non-privileged app to attach a Visualizer effect to *another app's* audio at all.
 *
 * Two independent, simultaneously-run attempts to get "the right audioSessionId" — tracked
 * separately so a single test run on a real device shows which (if either) actually works:
 *
 * 1. Visualizer(0) — session 0 is Android's historical "output mix": attaching there used to let
 *    any app see whatever was playing system-wide, gated only by RECORD_AUDIO, and is plausibly
 *    what a global-visualizer app like MuViz Edge relies on. Whether this still works for a
 *    normal (non-system) app on a modern Android/OEM build is exactly what needs observing here
 *    — Google has progressively restricted it, and some OEM skins go further still.
 *
 * 2. AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION — a broadcast the standard Android
 *    playback stacks (MediaPlayer, ExoPlayer's AudioTrack path) send automatically whenever a new
 *    *non-zero* audio session opens, carrying that session's id and the playing app's package
 *    name. This is the traditional way a third-party equalizer/visualizer app attaches to
 *    whatever's currently playing without knowing in advance which app or session that will be.
 *    It needs no permission beyond RECORD_AUDIO to receive; whether Deezer's player actually
 *    sends it is, again, only knowable on-device.
 *
 * How to run this: no device/adb/remote-inspector required — see the temporary "Test Visualizer"
 * debug panel wired into index.html/main.js (getVisualizerProbeStatus() in DeezerMediaPlugin
 * polls getStatus() below). Logcat (filter "VizuzikVisualizerProbe") still gets the same
 * information for anyone who does have adb handy.
 */
final class VisualizerProbe {

    private static final String TAG = "VizuzikVisualizerProbe";
    // Visualizer capture callbacks can fire dozens of times per second; logging every one would
    // flood Logcat without helping anyone read it (the status snapshot itself is always kept
    // fresh regardless — this only throttles the Logcat lines).
    private static final int LOG_EVERY_N_CAPTURES = 8;
    private static final int CAPTURE_RATE_HZ = 20;

    private final Context appContext;
    private final BroadcastReceiver sessionReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onAudioEffectSessionOpened(intent);
        }
    };

    private final Attempt globalMix = new Attempt("global-mix(session=0)");
    // null label (rather than a placeholder string) until a broadcast actually names a tracked
    // package: the web panel treats a falsy label as "nothing tracked yet" (see main.js).
    private final Attempt trackedSession = new Attempt(null);
    // Most recent ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION seen at all, matched or not — proves
    // whether the broadcast fires in the first place, independently of whether it was Deezer.
    private volatile String lastBroadcastPackage;
    private volatile int lastBroadcastSessionId = -1;

    private Visualizer globalMixVisualizer;
    private Visualizer trackedSessionVisualizer;
    private boolean receiverRegistered;

    VisualizerProbe(Context context) {
        this.appContext = context.getApplicationContext();
    }

    /** Starts both attempts. Safe to call again while already running — releases the previous
     *  global-mix visualizer first, so a repeated start() from a manual test session never leaks
     *  a native effect instance. */
    void start() {
        registerSessionReceiver();
        releaseVisualizer(globalMixVisualizer);
        globalMixVisualizer = null;
        globalMix.reset();
        globalMixVisualizer = attachVisualizer(0, globalMix);
    }

    void stop() {
        if (receiverRegistered) {
            try {
                appContext.unregisterReceiver(sessionReceiver);
            } catch (IllegalArgumentException ignored) {
                // Already unregistered.
            }
            receiverRegistered = false;
        }
        releaseVisualizer(globalMixVisualizer);
        globalMixVisualizer = null;
        releaseVisualizer(trackedSessionVisualizer);
        trackedSessionVisualizer = null;
        globalMix.reset();
        trackedSession.reset();
        // reset() deliberately leaves label alone (globalMix's label is fixed, never reset) —
        // trackedSession's is the one that actually changes, so clear it back to "nothing
        // tracked yet" explicitly here.
        trackedSession.label = null;
        lastBroadcastPackage = null;
        lastBroadcastSessionId = -1;
    }

    /** A plain snapshot the plugin hands back to the web layer as JSON — see
     *  DeezerMediaPlugin.getVisualizerProbeStatus(). Every field is a primitive/String so the
     *  caller doesn't need to know about Attempt at all. */
    static final class Status {
        final boolean globalMixInitialized;
        final String globalMixError;
        final double globalMixAmplitude;
        final double globalMixFft;

        final boolean trackedSessionInitialized;
        final String trackedSessionLabel;
        final String trackedSessionError;
        final double trackedSessionAmplitude;
        final double trackedSessionFft;

        final String lastBroadcastPackage;
        final int lastBroadcastSessionId;

        Status(Attempt globalMix, Attempt trackedSession, String lastBroadcastPackage, int lastBroadcastSessionId) {
            this.globalMixInitialized = globalMix.initialized;
            this.globalMixError = globalMix.error;
            this.globalMixAmplitude = globalMix.amplitude;
            this.globalMixFft = globalMix.fftMagnitude;

            this.trackedSessionInitialized = trackedSession.initialized;
            this.trackedSessionLabel = trackedSession.label;
            this.trackedSessionError = trackedSession.error;
            this.trackedSessionAmplitude = trackedSession.amplitude;
            this.trackedSessionFft = trackedSession.fftMagnitude;

            this.lastBroadcastPackage = lastBroadcastPackage;
            this.lastBroadcastSessionId = lastBroadcastSessionId;
        }
    }

    Status getStatus() {
        return new Status(globalMix, trackedSession, lastBroadcastPackage, lastBroadcastSessionId);
    }

    private void registerSessionReceiver() {
        if (receiverRegistered) return;
        IntentFilter filter = new IntentFilter(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        // Registered at runtime, not manifest-declared: implicit broadcasts like this one are
        // blocked from reaching manifest-declared receivers since Android 8, but still reach one
        // registered while the process is alive — fine for a probe that only runs during a
        // manual test, started and stopped from the foreground app.
        //
        // This broadcast isn't one of Android's "protected" system broadcasts (Deezer's own
        // player process is what sends it, not the system), so on API 33+ it needs an explicit
        // RECEIVER_EXPORTED — apps targeting API 34+ get a SecurityException from the plain
        // 2-arg registerReceiver() otherwise, since Android 14 requires every dynamically
        // registered receiver to say whether other apps' broadcasts should reach it.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            appContext.registerReceiver(sessionReceiver, filter, Context.RECEIVER_EXPORTED);
        } else {
            appContext.registerReceiver(sessionReceiver, filter);
        }
        receiverRegistered = true;
        Log.i(TAG, "Listening for ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION broadcasts");
    }

    private void onAudioEffectSessionOpened(Intent intent) {
        int sessionId = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
        String packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        Log.i(TAG, "ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION: package=" + packageName + " audioSessionId=" + sessionId);
        // Recorded even when not the tracked app: seeing *any* broadcast at all, from *any*
        // package, is itself useful — its total absence is what tells us Deezer's player (or
        // this Android build) never sends this broadcast in the first place.
        lastBroadcastPackage = packageName;
        lastBroadcastSessionId = sessionId;
        if (sessionId <= 0 || packageName == null || !MusicApps.isKnownPackage(packageName)) {
            return;
        }
        releaseVisualizer(trackedSessionVisualizer);
        trackedSessionVisualizer = null;
        trackedSession.reset();
        trackedSession.label = "tracked-session[" + packageName + "]";
        trackedSessionVisualizer = attachVisualizer(sessionId, trackedSession);
    }

    private Visualizer attachVisualizer(int sessionId, Attempt attempt) {
        Visualizer visualizer;
        try {
            visualizer = new Visualizer(sessionId);
        } catch (Exception e) {
            // RuntimeException/UnsupportedOperationException here (permission missing, no audio
            // effect library, or the session refusing attachment) is exactly the negative result
            // this probe is trying to observe — recorded, not swallowed silently.
            String message = "construction failed: " + e;
            Log.w(TAG, attempt.label + ": Visualizer construction failed", e);
            attempt.error = message;
            return null;
        }

        Log.i(TAG, attempt.label + ": Visualizer initialized");
        // Visualizer has no getter to read the session back — it's whatever was just passed to
        // the constructor above, so log that directly instead.
        Log.i(TAG, attempt.label + ": audio session id = " + sessionId);
        attempt.initialized = true;

        int captureSize = Visualizer.getCaptureSizeRange()[1];
        try {
            visualizer.setCaptureSize(captureSize);
        } catch (Exception e) {
            Log.w(TAG, attempt.label + ": setCaptureSize failed, using default", e);
        }

        int rate = Math.min(CAPTURE_RATE_HZ * 1000, Visualizer.getMaxCaptureRate());
        try {
            visualizer.setDataCaptureListener(new CaptureLogger(attempt), rate, true, true);
            visualizer.setEnabled(true);
        } catch (Exception e) {
            String message = "enabling capture failed: " + e;
            Log.w(TAG, attempt.label + ": enabling capture failed", e);
            attempt.error = message;
            visualizer.release();
            return null;
        }
        return visualizer;
    }

    private void releaseVisualizer(Visualizer visualizer) {
        if (visualizer == null) return;
        try {
            visualizer.setEnabled(false);
            visualizer.release();
        } catch (Exception e) {
            Log.w(TAG, "release failed", e);
        }
    }

    /** Mutable per-attempt state, updated from capture callbacks (main thread for the tracked
     *  session's broadcast-triggered attach, the capture engine's own thread for callbacks) and
     *  read from the plugin's getVisualizerProbeStatus() (a different thread again) — plain
     *  volatile fields rather than a lock, same tradeoff as AudioLevelsBridge.capturing: a
     *  diagnostic snapshot can tolerate a one-frame-old value. */
    private static final class Attempt {
        volatile String label;
        volatile boolean initialized;
        volatile String error;
        volatile double amplitude;
        volatile double fftMagnitude;

        Attempt(String label) {
            this.label = label;
        }

        void reset() {
            initialized = false;
            error = null;
            amplitude = 0;
            fftMagnitude = 0;
        }
    }

    /** One instance per Visualizer attachment (global-mix and tracked-session run at the same
     *  time), so each throttles its own Logcat cadence independently while always keeping its
     *  Attempt's status fields current for polling. */
    private static final class CaptureLogger implements Visualizer.OnDataCaptureListener {
        private final Attempt attempt;
        private int captureCount;

        CaptureLogger(Attempt attempt) {
            this.attempt = attempt;
        }

        @Override
        public void onWaveFormDataCapture(Visualizer visualizer, byte[] waveform, int samplingRate) {
            double amplitude = waveformAmplitude(waveform);
            attempt.amplitude = amplitude;
            if (captureCount % LOG_EVERY_N_CAPTURES == 0) {
                Log.i(TAG, attempt.label + ": waveform amplitude = " + amplitude);
            }
        }

        @Override
        public void onFftDataCapture(Visualizer visualizer, byte[] fft, int samplingRate) {
            double magnitude = fftMagnitude(fft);
            attempt.fftMagnitude = magnitude;
            if (captureCount % LOG_EVERY_N_CAPTURES == 0) {
                Log.i(TAG, attempt.label + ": FFT magnitude = " + magnitude);
            }
            captureCount++;
        }

        /** Waveform bytes are 8-bit unsigned PCM centered at 128 (silence) — RMS deviation from
         *  that midpoint is a simple, cheap "is anything moving at all" signal for a diagnostic
         *  log, not a properly weighted loudness measure. */
        private static double waveformAmplitude(byte[] waveform) {
            if (waveform == null || waveform.length == 0) return 0;
            double sumSquares = 0;
            for (byte b : waveform) {
                double centered = (b & 0xFF) - 128.0;
                sumSquares += centered * centered;
            }
            return Math.sqrt(sumSquares / waveform.length);
        }

        /** Android's Visualizer FFT byte layout: fft[0]=Re(0), fft[1]=Re(n/2), and for
         *  i in 1..n/2-1: fft[2i]=Re(i), fft[2i+1]=Im(i). Summing magnitudes across bins gives a
         *  single number that should visibly track loudness/brightness for this diagnostic. */
        private static double fftMagnitude(byte[] fft) {
            if (fft == null || fft.length < 2) return 0;
            double sum = Math.abs(fft[0]) + Math.abs(fft[1]);
            for (int i = 2; i + 1 < fft.length; i += 2) {
                double re = fft[i];
                double im = fft[i + 1];
                sum += Math.sqrt(re * re + im * im);
            }
            return sum;
        }
    }
}
