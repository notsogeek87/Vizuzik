package com.vizuzik.app;

import android.content.Context;
import android.os.Build;
import android.os.SystemClock;
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

    /**
     * How long playback is allowed to look stopped before the overlay is taken down for it.
     *
     * A track change is not a clean handover: players routinely report a pause, or a moment of
     * nothing at all, between one track and the next. Acted on immediately, that tears the
     * overlay window down and builds another one a moment later — the effect blinks out and back
     * on every track change, and on the way back in it briefly paints over whatever app is in
     * front. Every other condition here is a deliberate act (a setting, a permission, opening
     * Vizuzik) and is still acted on at once; only this one waits to see if it meant it.
     */
    private static final long PAUSE_GRACE_MS = 1_500;

    private Context appContext;
    private boolean lastStarted;
    /** When playback was first seen to have stopped, or 0 while it is running. */
    private long pausedSinceMs;
    private final android.os.Handler handler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable recheck = this::sync;

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

    /** The service is gone — including when it stopped itself rather than being asked to, which is
     *  why this can't be inferred from requestStop() alone. Re-syncs rather than just clearing the
     *  flag: the destroy can arrive *after* a newer start (foregrounding Vizuzik then going
     *  straight back to Deezer destroys the old instance last), and leaving the flag false while
     *  the overlay is in fact running would make the next stop a no-op and strand it on screen. */
    void onServiceStopped() {
        lastStarted = false;
        sync();
    }

    /** Re-evaluates and acts — called on every now-playing change (this class's own listener
     *  callback above) and on every MainActivity foreground/background transition. */
    void sync() {
        if (appContext == null) return;

        DeezerMediaBridge.NowPlaying nowPlaying = DeezerMediaBridge.getInstance().getLastNowPlaying();
        boolean isPlaying = nowPlaying != null && nowPlaying.isPlaying;
        boolean overlaySupported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;

        // Everything except playback itself: those are all deliberate acts, and none of them
        // flickers the way a track change does.
        boolean allowed = EdgeOverlayPreference.isEnabled(appContext)
            && overlaySupported
            && Settings.canDrawOverlays(appContext)
            && !MainActivity.isForeground();
        boolean shouldRun = allowed && isPlaying;

        // Never stopped out from under the album-art calibration handle. That handle is put up
        // from Vizuzik's own settings panel — i.e. exactly when Vizuzik is in the foreground,
        // which is normally this class's cue that there is nothing worth drawing over — and it is
        // a short, explicit thing the user is in the middle of.
        if (!shouldRun && OverlayEdgeGlowService.isCalibrating()) return;

        // A gap in playback where nothing else has changed is given a moment to turn out to be a
        // track change rather than a stop — see PAUSE_GRACE_MS. Anything that resumes within it
        // finds the overlay still up, and never has to be built again from nothing. Measured from
        // when the gap started, not from this call, so that a stream of updates during it cannot
        // keep pushing the decision away for ever.
        long now = SystemClock.elapsedRealtime();
        if (isPlaying) pausedSinceMs = 0;
        else if (pausedSinceMs == 0) pausedSinceMs = now;

        handler.removeCallbacks(recheck);
        if (lastStarted && allowed && !isPlaying && now - pausedSinceMs < PAUSE_GRACE_MS) {
            handler.postDelayed(recheck, PAUSE_GRACE_MS - (now - pausedSinceMs));
            return;
        }

        if (shouldRun == lastStarted) return;
        if (shouldRun) {
            // Only counts as started if the request actually went through — a foreground-service
            // start refused from the background must leave this false so the next event retries.
            lastStarted = OverlayEdgeGlowService.requestStart(appContext);
        } else {
            OverlayEdgeGlowService.requestStop(appContext);
            lastStarted = false;
        }
    }
}
