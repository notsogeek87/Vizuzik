package com.vizuzik.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.ServiceInfo;
import android.content.res.Configuration;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.util.Log;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;
import androidx.core.content.ContextCompat;

/**
 * Draws EdgeGlowView as a touch-transparent window on top of whatever app is in front — Deezer,
 * Spotify, YouTube Music, a local player — so the Edge Visualizer is visible without ever having
 * to switch back to Vizuzik's own full-screen player.
 *
 * Started and stopped through requestStart()/requestStop() below, never by any opinion of its
 * own — it just renders for as long as it's alive. Two independent callers decide when: the web
 * layer (startEdgeOverlay()/stopEdgeOverlay() in DeezerMediaPlugin, driven by syncEdgeOverlay()
 * in main.js) while the webview is running, and EdgeOverlayController natively (via
 * NowPlayingListenerService) so the same thing happens even if Vizuzik's own Activity/webview has
 * never launched this session. It listens to the same
 * two bridges DeezerMediaPlugin does — DeezerMediaBridge for the current track's artwork (turned
 * into a glow color via OverlayPalette) and AudioLevelsBridge for real audio levels, the same
 * AudioPlaybackCapture pipeline the full-screen visualizer already uses.
 *
 * Deliberately does not touch the microphone. An earlier version of this exact feature also ran
 * its own mic capture in the background so the glow could react even when the user's chosen
 * audio source was "mic" rather than "real audio" — that turned out to be the one part of the
 * feature that never worked reliably: two AudioRecords racing over the same microphone (this
 * service opening one the instant the full-screen player released it), a foreground-service type
 * that has to be declared exactly right or the whole app crashes, and a "live" flag that could get
 * stuck. Those are all consequences of sharing a mic between two independent components, not of
 * drawing an overlay — so this version simply never opens a second mic stream. When "mic" is the
 * chosen source (or capture isn't running at all), the glow falls back to its ambient regime
 * below, exactly like the full-screen player does before real audio is granted.
 *
 * Live reactivity primarily comes from TrackedSessionAudioSource (android.media.audiofx.
 * Visualizer attached to the tracked app's own audio session, found via a system broadcast —
 * validated on-device before being wired in here, see its class doc) rather than AudioLevelsBridge:
 * it needs only RECORD_AUDIO, never the MediaProjection consent dialog AudioCaptureService
 * requires. AudioLevelsBridge stays wired in alongside it as a fallback, per the same reasoning
 * that already justifies keeping the ambient regime below — a device/Android build where the
 * broadcast never arrives (or RECORD_AUDIO isn't granted) should still light up if "son réel" is
 * already running, rather than silently doing less than the previous version could.
 */
public class OverlayEdgeGlowService extends Service
    implements DeezerMediaBridge.Listener, AudioLevelsBridge.Listener, SharedPreferences.OnSharedPreferenceChangeListener,
        TrackedSessionAudioSource.Listener {

    private static final String TAG = "OverlayEdgeGlow";
    private static final String CHANNEL_ID = "vizuzik_overlay";
    private static final int NOTIFICATION_ID = 4243;

    private WindowManager windowManager;
    private NotificationManager notificationManager;
    private EdgeGlowView glowView;
    private TrackedSessionAudioSource trackedSessionAudioSource;
    private String lastTrackKey;
    private boolean lastIsPlaying;
    private boolean hasLastIsPlaying;

    /**
     * Starts/stops this service — the one place both DeezerMediaPlugin (driven by the web
     * layer's syncEdgeOverlay(), while the webview is alive) and EdgeOverlayController (driven
     * natively by NowPlayingListenerService, so it works even if Vizuzik's own Activity/webview
     * has never run this session) go through, so the two orchestrators can never disagree on
     * how starting/stopping actually happens — only on when to do it, and they compute that from
     * the same real-world conditions (enabled setting, overlay permission, isPlaying, whether
     * Vizuzik itself is in the foreground), so in practice they always agree anyway. Both calls
     * are idempotent on the receiving end (onStartCommand() no-ops if the view already exists;
     * Android no-ops stopService() on an already-stopped service).
     */
    static void requestStart(Context context) {
        ContextCompat.startForegroundService(context, new Intent(context, OverlayEdgeGlowService.class));
    }

    static void requestStop(Context context) {
        context.stopService(new Intent(context, OverlayEdgeGlowService.class));
    }

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
        notificationManager = getSystemService(NotificationManager.class);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        try {
            startForegroundNotification();
        } catch (Exception e) {
            // Defensive: startForeground() throwing here runs on the main thread with nothing
            // above it to catch it, which would take down the entire app, not just this service.
            // Never again for the sake of a border glow — stop cleanly instead.
            Log.w(TAG, "startForegroundNotification", e);
            stopSelf();
            return START_NOT_STICKY;
        }

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !Settings.canDrawOverlays(this)) {
            // Below Android 8 TYPE_APPLICATION_OVERLAY doesn't exist; without the "display over
            // other apps" grant, addView() below would just throw. Either way there is nothing
            // this service can usefully do.
            stopSelf();
            return START_NOT_STICKY;
        }

        if (glowView == null && !addOverlayView()) {
            // addView() can still throw even with the permission granted — some OEM skins gate
            // TYPE_APPLICATION_OVERLAY further, or the grant hasn't fully propagated yet. This
            // runs on the app's main thread like everything else in the process: an uncaught
            // exception here would crash Vizuzik itself, not just this service, so failing to
            // add the view is treated as "can't run right now" rather than left to propagate.
            stopSelf();
            return START_NOT_STICKY;
        }
        if (glowView != null) {
            glowView.applyConfig(EdgeConfig.read(this));
            EdgeConfig.registerListener(this, this);
            // addListener() immediately replays the current now-playing state to this listener
            // (see DeezerMediaBridge), so there's no need to also ask for it here.
            DeezerMediaBridge.getInstance().addListener(this);
            AudioLevelsBridge.getInstance().addListener(this);
            if (trackedSessionAudioSource == null) {
                trackedSessionAudioSource = new TrackedSessionAudioSource(this, this);
            }
            trackedSessionAudioSource.start();
        }
        return START_STICKY;
    }

    /** @return whether the view was actually added. */
    private boolean addOverlayView() {
        EdgeGlowView view = new EdgeGlowView(this);
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // NOT_TOUCHABLE is the one flag this feature can't do without: the overlay is
            // supposed to be looked at, never touched — every gesture must reach the app
            // underneath exactly as if this window weren't there at all.
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        try {
            windowManager.addView(view, params);
        } catch (Exception e) {
            Log.w(TAG, "Impossible d'ajouter la fenêtre du contour lumineux", e);
            return false;
        }
        glowView = view;
        return true;
    }

    /**
     * A fold/unfold on a device like the Z Fold (or any display-size configuration change — a
     * rotation, an external-display switch) resizes the default display the window is attached
     * to. MATCH_PARENT + FLAG_LAYOUT_NO_LIMITS already makes WindowManager itself follow that
     * automatically, and EdgeGlowView reads getWidth()/getHeight() fresh on every frame — but
     * updateViewLayout() is called explicitly anyway to force an immediate relayout rather than
     * wait for whatever triggers the next one, so the glow doesn't sit at the old screen's
     * dimensions for a visible moment after unfolding.
     */
    @Override
    public void onConfigurationChanged(Configuration newConfig) {
        super.onConfigurationChanged(newConfig);
        if (glowView == null || windowManager == null) return;
        try {
            windowManager.updateViewLayout(glowView, glowView.getLayoutParams());
        } catch (Exception e) {
            Log.w(TAG, "onConfigurationChanged", e);
        }
    }

    private void startForegroundNotification() {
        if (notificationManager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Effets par-dessus l'app de musique",
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Contour lumineux affiché par-dessus l'app de musique en cours.");
            notificationManager.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vizuzik")
            .setContentText("Edge Visualizer actif")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_MIN)
            .build();
        int type = Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE
            ? ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
            : 0;
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type);
    }

    @Override
    public void onNowPlayingChanged(DeezerMediaBridge.NowPlaying nowPlaying) {
        if (glowView == null || nowPlaying == null) return;

        // A real event, same two the full-screen player pulses on: a new track landing, or
        // play/pause toggling. Without AudioLevelsBridge running (no "real audio" capture
        // granted), these are the *only* honest impulses the glow is allowed — ambient mode
        // breathes on its own otherwise, but never invents a beat.
        String trackKey = nowPlaying.title + "::" + nowPlaying.artist;
        boolean isNewTrack = !trackKey.equals(lastTrackKey);
        if (isNewTrack) {
            lastTrackKey = trackKey;
            // Runs on the main thread (MediaController.Callback dispatch) like the rest of this
            // service — same reasoning as EdgeGlowView's own try/catch: a bad frame of artwork
            // must never be able to bring down the whole app.
            try {
                glowView.setPalette(OverlayPalette.extract(nowPlaying.albumArt));
            } catch (Exception e) {
                Log.w(TAG, "onNowPlayingChanged", e);
            }
            glowView.pulse(1f);
        } else if (hasLastIsPlaying && nowPlaying.isPlaying != lastIsPlaying) {
            glowView.pulse(0.55f);
        }
        lastIsPlaying = nowPlaying.isPlaying;
        hasLastIsPlaying = true;
    }

    /**
     * Serves both AudioLevelsBridge.Listener and TrackedSessionAudioSource.Listener — they
     * declare the identical onLevels(float[]) signature, and both feed the glow the exact same
     * way, so one implementation satisfies both interfaces. Called on whichever capture engine's
     * own thread produced the levels, never the main thread — an uncaught exception there still
     * takes down the whole app by default, so this gets the same guard as the main-thread
     * callbacks elsewhere in this class.
     */
    @Override
    public void onLevels(float[] levels) {
        try {
            if (glowView != null) glowView.pushLevels(levels);
        } catch (Exception e) {
            Log.w(TAG, "onLevels", e);
        }
    }

    @Override
    public void onCaptureStopped() {
        if (glowView != null) glowView.clearLevels();
    }

    /** The tracked app's session closed, or attaching to it failed outright — falls back to
     *  whatever AudioLevelsBridge is still providing (possibly nothing, in which case
     *  EdgeGlowView's own liveness timeout drops it to ambient on its own). */
    @Override
    public void onSourceLost() {
        // No explicit glowView.clearLevels() here: unlike AudioLevelsBridge.onCaptureStopped()
        // above (a clean, deliberate stop), this can fire while AudioLevelsBridge is still live —
        // clearing levels here would incorrectly blank out a still-working fallback.
    }

    /** A settings-panel edit while the overlay is already running — applied live, no restart. */
    @Override
    public void onSharedPreferenceChanged(SharedPreferences sharedPreferences, String key) {
        if (glowView != null) {
            glowView.applyConfig(EdgeConfig.read(this));
        }
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Same reasoning as AudioCaptureService: swiping Vizuzik out of recents is the real "stop"
     *  moment, as opposed to the tracked app merely being what's in front right now. */
    @Override
    public void onTaskRemoved(Intent rootIntent) {
        stopSelf();
        super.onTaskRemoved(rootIntent);
    }

    @Override
    public void onDestroy() {
        if (glowView != null && windowManager != null) {
            try {
                windowManager.removeView(glowView);
            } catch (Exception ignored) {
                // Already detached, or some other teardown quirk — either way, nothing left to
                // clean up here, and onDestroy() must never itself be the thing that crashes.
            }
            glowView = null;
        }
        EdgeConfig.unregisterListener(this, this);
        DeezerMediaBridge.getInstance().removeListener(this);
        AudioLevelsBridge.getInstance().removeListener(this);
        if (trackedSessionAudioSource != null) {
            trackedSessionAudioSource.stop();
        }
        super.onDestroy();
    }
}
