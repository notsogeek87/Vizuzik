package com.vizuzik.app;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.os.Build;
import android.os.IBinder;
import android.provider.Settings;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;
import androidx.core.app.ServiceCompat;

/**
 * Draws EdgeGlowView as a touch-transparent window on top of whatever app is in front — Deezer,
 * typically — so the visualizer is visible without ever having to switch back to Vizuzik's own
 * full-screen player. See docs/architecture/2026-09-04-contour-lumineux-par-dessus-deezer.md for
 * why an edge glow rather than a full-screen overlay, and why it's entirely non-interactive.
 *
 * Started and stopped only by the web layer (startEdgeOverlay()/stopEdgeOverlay() in
 * DeezerMediaPlugin, driven by syncEdgeOverlay() in main.js): the service itself has no opinion
 * on when it should be running, it just renders for as long as it's alive. It listens to the
 * same two bridges DeezerMediaPlugin does — DeezerMediaBridge for the current track's artwork
 * (turned into a glow color via OverlayPalette) and AudioLevelsBridge for real audio levels —
 * which is why both bridges had to grow support for more than one listener at a time.
 */
public class OverlayEdgeGlowService extends Service implements DeezerMediaBridge.Listener, AudioLevelsBridge.Listener {

    private static final String CHANNEL_ID = "vizuzik_overlay";
    private static final int NOTIFICATION_ID = 4243;

    private WindowManager windowManager;
    private EdgeGlowView glowView;
    private String lastTrackKey;

    @Override
    public void onCreate() {
        super.onCreate();
        windowManager = (WindowManager) getSystemService(WINDOW_SERVICE);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundNotification();

        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !Settings.canDrawOverlays(this)) {
            // Below Android 8 TYPE_APPLICATION_OVERLAY doesn't exist; without the "display over
            // other apps" grant, addView() below would just throw. Either way there is nothing
            // this service can usefully do.
            stopSelf();
            return START_NOT_STICKY;
        }

        if (glowView == null) {
            addOverlayView();
            DeezerMediaBridge.getInstance().addListener(this);
            AudioLevelsBridge.getInstance().addListener(this);
            onNowPlayingChanged(DeezerMediaBridge.getInstance().getLastNowPlaying());
        }
        return START_STICKY;
    }

    private void addOverlayView() {
        glowView = new EdgeGlowView(this);
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
        windowManager.addView(glowView, params);
    }

    private void startForegroundNotification() {
        NotificationManager manager = getSystemService(NotificationManager.class);
        if (manager.getNotificationChannel(CHANNEL_ID) == null) {
            NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "Effets par-dessus l'app de musique",
                NotificationManager.IMPORTANCE_MIN
            );
            channel.setDescription("Contour lumineux affiché par-dessus Deezer ou Spotify.");
            manager.createNotificationChannel(channel);
        }
        Notification notification = new NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Vizuzik")
            .setContentText("Effets visuels actifs par-dessus l'app de musique")
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
        String trackKey = nowPlaying.title + "::" + nowPlaying.artist;
        if (trackKey.equals(lastTrackKey)) return;
        lastTrackKey = trackKey;
        glowView.setPalette(OverlayPalette.extract(nowPlaying.albumArt));
    }

    @Override
    public void onLevels(float[] levels) {
        if (glowView != null) glowView.pushLevels(levels);
    }

    @Override
    public void onCaptureStopped() {
        if (glowView != null) glowView.setLive(false);
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }

    /** Same reasoning as AudioCaptureService: swiping Vizuzik out of recents is the real "stop"
     *  moment, as opposed to Deezer merely being what's in front right now. */
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
            } catch (IllegalArgumentException ignored) {
                // Already detached.
            }
            glowView = null;
        }
        DeezerMediaBridge.getInstance().removeListener(this);
        AudioLevelsBridge.getInstance().removeListener(this);
        super.onDestroy();
    }
}
