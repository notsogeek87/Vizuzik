package com.vizuzik.app;

import android.content.Context;
import android.os.Build;
import android.provider.Settings;

/**
 * Decides whether OverlayEdgeGlowService should be running — the native counterpart to
 * syncEdgeOverlay() in main.js, so the effect works even if Vizuzik's own Activity/webview has
 * never launched this session (e.g. the phone rebooted, notification access is already granted,
 * and the user goes straight to Deezer without ever opening Vizuzik itself). Registered as a
 * DeezerMediaBridge.Listener from NowPlayingListenerService.onCreate() — that service is bound by
 * the system and alive whenever notification access is granted, independently of MainActivity.
 *
 * Same four conditions as the web layer's version, computed from purely native state:
 * - the user turned Edge Visualizer on (EdgeOverlayPreference, mirrored from the web layer's
 *   toggle);
 * - the "display over other apps" permission is granted (Settings.canDrawOverlays(), no need to
 *   ask the web layer — this is a system permission query, always answerable);
 * - a track is actually playing (from DeezerMediaBridge, which this class listens to directly);
 * - Vizuzik's own full-screen player isn't already the thing on screen (MainActivity.isForeground()
 *   — false whenever the app's process is running only to host NowPlayingListenerService, which
 *   is exactly the "never launched" case this class exists for).
 *
 * The two orchestrators (this one and the web layer's) can end up both deciding the same thing at
 * slightly different times — harmless, since OverlayEdgeGlowService.requestStart()/requestStop()
 * are idempotent either way.
 */
final class EdgeOverlayController implements DeezerMediaBridge.Listener {

    private static final EdgeOverlayController INSTANCE = new EdgeOverlayController();

    static EdgeOverlayController getInstance() {
        return INSTANCE;
    }

    private Context appContext;
    private boolean lastStarted;

    private EdgeOverlayController() {}

    /** Idempotent — safe to call from both NowPlayingListenerService and MainActivity, whichever
     *  happens to run first in this process. */
    void init(Context context) {
        if (appContext == null) {
            appContext = context.getApplicationContext();
        }
    }

    @Override
    public void onNowPlayingChanged(DeezerMediaBridge.NowPlaying nowPlaying) {
        sync();
    }

    /** Re-evaluates and acts — called on every now-playing change (this class's own listener
     *  callback above) and on every MainActivity foreground/background transition. */
    void sync() {
        if (appContext == null) return;

        DeezerMediaBridge.NowPlaying nowPlaying = DeezerMediaBridge.getInstance().getLastNowPlaying();
        boolean isPlaying = nowPlaying != null && nowPlaying.isPlaying;
        boolean overlaySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;

        boolean shouldRun = EdgeOverlayPreference.isEnabled(appContext)
            && overlaySupported
            && Settings.canDrawOverlays(appContext)
            && isPlaying
            && !MainActivity.isForeground();

        if (shouldRun == lastStarted) return;
        lastStarted = shouldRun;
        if (shouldRun) {
            OverlayEdgeGlowService.requestStart(appContext);
        } else {
            OverlayEdgeGlowService.requestStop(appContext);
        }
    }
}
