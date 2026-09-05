package com.vizuzik.app;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;

/**
 * Captures the phone's own microphone with a plain AudioRecord and turns it into the same
 * 32-band loudness spectrum AudioCaptureService produces for app-audio capture (same Goertzel
 * analysis, same 55 Hz-7000 Hz logarithmic band layout — mirrored here rather than shared, since
 * the two run in very different contexts: no MediaProjection here, and no consent dialog).
 *
 * Never instantiated directly by its consumers: MicCaptureCoordinator owns the single instance
 * and fans its levels out, so the plugin and the overlay service can't end up with one
 * AudioRecord each fighting over the same microphone.
 *
 * Deliberately AudioSource.MIC rather than routing through the WebView's own getUserMedia(): a
 * getUserMedia() audio stream is a WebRTC-shaped capture under the hood, and Chromium switches
 * Android's audio mode into its "in a call" state for the duration — which, over Bluetooth, is
 * exactly what tells a connected car head unit to switch into its own phone-call UI, cutting
 * media playback the way it would for an incoming call. A bare AudioRecord on AudioSource.MIC
 * never touches that mode at all, so nothing downstream has any reason to think a call started.
 */
final class MicCaptureThread extends Thread {

    interface Listener {
        void onLevels(float[] levels);
    }

    private static final int SAMPLE_RATE = 44100;
    private static final int CHUNK_SAMPLES = 2048;
    private static final int BAND_COUNT = 32;
    private static final double MIN_FREQ = 55;
    private static final double MAX_FREQ = 7000;

    private final Listener listener;
    private final double[] bandFrequencies = new double[BAND_COUNT];
    private final float[] smoothedBands = new float[BAND_COUNT];
    private volatile boolean running = true;
    private AudioRecord audioRecord;

    MicCaptureThread(Listener listener) {
        super("VizuzikMicCapture");
        this.listener = listener;
        double ratio = MAX_FREQ / MIN_FREQ;
        for (int i = 0; i < BAND_COUNT; i++) {
            bandFrequencies[i] = MIN_FREQ * Math.pow(ratio, i / (double) (BAND_COUNT - 1));
        }
    }

    /**
     * Builds the AudioRecord and reports whether it actually initialized. Called on the caller's
     * own thread, before start(): a device that refuses AudioSource.MIC (or has RECORD_AUDIO
     * denied underneath us) needs to be reported back synchronously rather than losing an
     * exception on a thread nothing is watching.
     */
    boolean prepare() {
        AudioFormat format = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build();
        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuffer, CHUNK_SAMPLES * 2) * 2;
        try {
            audioRecord = new AudioRecord.Builder()
                .setAudioSource(MediaRecorder.AudioSource.MIC)
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .build();
        } catch (Exception e) {
            return false;
        }
        return audioRecord.getState() == AudioRecord.STATE_INITIALIZED;
    }

    @Override
    public void run() {
        if (audioRecord == null) {
            return;
        }
        try {
            audioRecord.startRecording();
        } catch (IllegalStateException e) {
            return;
        }

        short[] buffer = new short[CHUNK_SAMPLES];
        while (running) {
            AudioRecord record = audioRecord;
            if (record == null) {
                break;
            }
            int read = record.read(buffer, 0, CHUNK_SAMPLES);
            if (read <= 0) {
                continue;
            }

            for (int i = 0; i < BAND_COUNT; i++) {
                double magnitude = goertzelMagnitude(buffer, read, bandFrequencies[i], SAMPLE_RATE);
                float level = clamp01((float) (Math.log10(1 + magnitude * 40) / Math.log10(41)));
                smoothedBands[i] = smoothedBands[i] * 0.5f + level * 0.5f;
            }
            listener.onLevels(smoothedBands.clone());
        }
    }

    /** Mirrors AudioCaptureService#onDestroy(): the mic keeps feeding read() continuously, so
     * the loop above notices `running` went false and returns well inside this join() window
     * even though stop()/release() only happen after it. */
    void stopCapture() {
        running = false;
        try {
            join(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        if (audioRecord != null) {
            try {
                audioRecord.stop();
            } catch (IllegalStateException ignored) {
                // Not recording — nothing to stop.
            }
            audioRecord.release();
            audioRecord = null;
        }
    }

    /** Single-frequency DFT magnitude via the Goertzel algorithm — cheap enough to run per band, per chunk. */
    private static double goertzelMagnitude(short[] samples, int numSamples, double targetFreq, double sampleRate) {
        double k = Math.round((numSamples * targetFreq) / sampleRate);
        double omega = (2 * Math.PI / numSamples) * k;
        double cosine = Math.cos(omega);
        double coeff = 2 * cosine;
        double q0;
        double q1 = 0;
        double q2 = 0;
        for (int i = 0; i < numSamples; i++) {
            double sample = samples[i] / 32768.0;
            q0 = coeff * q1 - q2 + sample;
            q2 = q1;
            q1 = q0;
        }
        double real = q1 - q2 * cosine;
        double imag = q2 * Math.sin(omega);
        return Math.sqrt(real * real + imag * imag) / (numSamples / 2.0);
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return value < 0 ? 0 : Math.min(value, 1);
    }
}
