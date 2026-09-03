package com.vizuzik.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ServiceInfo;
import android.media.AudioAttributes;
import android.media.AudioFormat;
import android.media.AudioPlaybackCaptureConfiguration;
import android.media.AudioRecord;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.media.session.MediaController;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;

import androidx.annotation.Nullable;
import androidx.annotation.RequiresApi;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Captures Deezer's own audio output (Android's AudioPlaybackCapture API, requires API 29+) and
 * turns it into a per-band loudness spectrum for the visualizer, instead of a simulated beat.
 *
 * Requires a MediaProjection consent grant (the system "start recording or casting" dialog),
 * obtained by DeezerMediaPlugin beforehand and passed in as this service's start intent extras.
 * If the currently playing app opts out of playback capture, or the device is below API 29, the
 * web layer simply stops receiving "audioLevels" events and falls back to its own animation.
 */
@RequiresApi(api = Build.VERSION_CODES.Q)
public class AudioCaptureService extends Service {

    private static final String TAG = "AudioCaptureService";
    private static final String CHANNEL_ID = "vizuzik_visualizer";
    private static final int NOTIFICATION_ID = 4242;

    private static final int SAMPLE_RATE = 44100;
    private static final int CHUNK_SAMPLES = 2048;
    private static final int BAND_COUNT = 32;
    private static final double MIN_FREQ = 55;
    private static final double MAX_FREQ = 7000;

    private MediaProjectionManager projectionManager;
    private MediaProjection mediaProjection;
    private AudioRecord audioRecord;
    private Thread captureThread;
    private volatile boolean running;
    private double[] bandFrequencies;
    private float[] smoothedBands;

    @Override
    public void onCreate() {
        super.onCreate();
        projectionManager = (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        bandFrequencies = new double[BAND_COUNT];
        smoothedBands = new float[BAND_COUNT];
        double ratio = MAX_FREQ / MIN_FREQ;
        for (int i = 0; i < BAND_COUNT; i++) {
            bandFrequencies[i] = MIN_FREQ * Math.pow(ratio, i / (double) (BAND_COUNT - 1));
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
        );

        if (running) {
            return START_NOT_STICKY;
        }

        if (intent == null || !intent.hasExtra("resultCode") || !intent.hasExtra("data")) {
            stopSelf();
            return START_NOT_STICKY;
        }

        int resultCode = intent.getIntExtra("resultCode", 0);
        Intent projectionData = intent.getParcelableExtra("data");
        if (projectionData == null) {
            stopSelf();
            return START_NOT_STICKY;
        }

        mediaProjection = projectionManager.getMediaProjection(resultCode, projectionData);
        if (mediaProjection == null) {
            stopSelf();
            return START_NOT_STICKY;
        }
        mediaProjection.registerCallback(
            new MediaProjection.Callback() {
                @Override
                public void onStop() {
                    stopSelf();
                }
            },
            null
        );

        if (!startCapture()) {
            stopSelf();
        }

        return START_NOT_STICKY;
    }

    private boolean startCapture() {
        AudioPlaybackCaptureConfiguration.Builder configBuilder = new AudioPlaybackCaptureConfiguration.Builder(mediaProjection)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME);

        Integer deezerUid = resolveDeezerUid();
        if (deezerUid != null) {
            // Restrict capture to Deezer specifically instead of "whatever app is playing
            // media", so e.g. a notification sound from another app can't feed the visualizer.
            configBuilder.addMatchingUid(deezerUid);
        } else {
            Log.w(TAG, "UID Deezer introuvable, capture non restreinte à un paquet précis");
        }
        AudioPlaybackCaptureConfiguration config = configBuilder.build();

        AudioFormat format = new AudioFormat.Builder()
            .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setSampleRate(SAMPLE_RATE)
            .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
            .build();

        int minBuffer = AudioRecord.getMinBufferSize(SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT);
        int bufferSize = Math.max(minBuffer, CHUNK_SAMPLES * 2) * 2;

        try {
            audioRecord = new AudioRecord.Builder()
                .setAudioFormat(format)
                .setBufferSizeInBytes(bufferSize)
                .setAudioPlaybackCaptureConfig(config)
                .build();
        } catch (Exception e) {
            Log.w(TAG, "Impossible de créer AudioRecord pour la capture de lecture", e);
            return false;
        }

        if (audioRecord.getState() != AudioRecord.STATE_INITIALIZED) {
            return false;
        }

        running = true;
        AudioLevelsBridge.getInstance().markCapturing();
        audioRecord.startRecording();
        captureThread = new Thread(this::captureLoop, "VizuzikAudioCapture");
        captureThread.start();
        return true;
    }

    private void captureLoop() {
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

            AudioLevelsBridge.getInstance().publishLevels(smoothedBands.clone());
        }
    }

    /**
     * Prefers the live Deezer package name we already know from the active MediaSession
     * (DeezerMediaBridge, populated via NowPlayingListenerService's notification-listener
     * access — not subject to Android 11+ package-visibility filtering); falls back to the
     * known Play Store package id in case the session isn't tracked yet at this exact moment.
     */
    private Integer resolveDeezerUid() {
        MediaController controller = DeezerMediaBridge.getInstance().getController();
        String packageName = controller != null ? controller.getPackageName() : "deezer.android.app";
        try {
            return getPackageManager().getApplicationInfo(packageName, 0).uid;
        } catch (PackageManager.NameNotFoundException e) {
            return null;
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

    private Notification buildNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Visualiseur audio",
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Analyse le son en cours de lecture pour animer le visualiseur.");
            manager.createNotificationChannel(channel);
        }
        return new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vizuzik")
            .setContentText("Visualiseur audio actif")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Fires when the user actually removes Vizuzik from recents — the real "stop capturing" moment, as opposed to merely backgrounding the app for a bit. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        running = false;
        if (captureThread != null) {
            try {
                captureThread.join(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            captureThread = null;
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
        if (mediaProjection != null) {
            mediaProjection.stop();
            mediaProjection = null;
        }
        AudioLevelsBridge.getInstance().publishStopped();
        super.onDestroy();
    }
}
