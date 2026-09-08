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
 * never launched this session. It listens to the same two bridges DeezerMediaPlugin does —
 * DeezerMediaBridge for the current track's artwork (turned into a glow color via OverlayPalette,
 * and handed to EdgeGlowView as-is too, for the "vinyl" style) and AudioLevelsBridge for the
 * loudness spectrum.
 *
 * Never opens the microphone, and never needs to: the levels come from TrackedAudioCapture, the
 * app's single audio source, owned process-wide rather than by this service. That ownership is the
 * point — one Visualizer serves both this overlay and Vizuzik's own full-screen player, so moving
 * between them never tears a capture down and builds another one up.
 */
public class OverlayEdgeGlowService extends Service
    implements DeezerMediaBridge.Listener, AudioLevelsBridge.Listener,
        SharedPreferences.OnSharedPreferenceChangeListener {

    private static final String TAG = "OverlayEdgeGlow";
    private static final String CHANNEL_ID = "vizuzik_overlay";
    private static final int NOTIFICATION_ID = 4243;
    /** Puts the album-art calibration handle on screen — see ArtCalibrationPuck. */
    static final String ACTION_CALIBRATE_ART = "com.vizuzik.app.CALIBRATE_ART";
    private static final long CALIBRATION_WATCH_MS = 10_000;

    /** Read by EdgeOverlayController, which would otherwise stop this service out from under a
     *  calibration in progress — that is exactly the moment Vizuzik itself is in the foreground,
     *  which is normally its cue that there is nothing worth drawing over. */
    private static volatile boolean calibrating;

    static boolean isCalibrating() {
        return calibrating;
    }

    private WindowManager windowManager;
    private NotificationManager notificationManager;
    private EdgeGlowView glowView;
    private ArtCalibrationPuck calibrationPuck;
    private final android.os.Handler calibrationHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final float[] anchor = new float[3];
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
     *
     * @return whether the service was actually asked to start — false means the caller's own
     *     "it's running now" bookkeeping must not be set, or it would never retry.
     */
    static boolean requestStart(Context context) {
        try {
            ContextCompat.startForegroundService(context, new Intent(context, OverlayEdgeGlowService.class));
            return true;
        } catch (Exception e) {
            // From Android 12, starting a foreground service while the process is in the
            // background throws ForegroundServiceStartNotAllowedException. EdgeOverlayController
            // calls this from DeezerMediaBridge callbacks, which fire on the tracked app's media
            // events — i.e. routinely while Vizuzik itself is fully backgrounded — so this is a
            // reachable path, and an uncaught exception on the main thread there takes down the
            // whole app rather than just the overlay. Same rule as startForegroundNotification()
            // below: never crash Vizuzik for the sake of a border effect.
            Log.w(TAG, "startForegroundService", e);
            return false;
        }
    }

    static void requestStop(Context context) {
        context.stopService(new Intent(context, OverlayEdgeGlowService.class));
    }

    /**
     * Puts the calibration handle on screen, starting the overlay first if it isn't already
     * running — someone can perfectly well go looking for this before ever having seen the
     * overlay, and a handle for an anchor nothing is drawing would be a strange thing to offer.
     *
     * @return whether the request could be made at all.
     */
    static boolean requestArtCalibration(Context context) {
        try {
            Intent intent = new Intent(context, OverlayEdgeGlowService.class);
            intent.setAction(ACTION_CALIBRATE_ART);
            ContextCompat.startForegroundService(context, intent);
            return true;
        } catch (Exception e) {
            // Same reachable failure as requestStart(): a foreground service can be refused when
            // the process is in the background, and it must never take the app down with it.
            Log.w(TAG, "requestArtCalibration", e);
            return false;
        }
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
            if (intent != null && ACTION_CALIBRATE_ART.equals(intent.getAction())) {
                showCalibrationPuck();
            }
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
        // From Android 12, the system *drops* the touches underneath a TYPE_APPLICATION_OVERLAY
        // window belonging to another app unless that window's opacity stays at or under a
        // threshold (0.8 by default) — and FLAG_NOT_TOUCHABLE does not exempt it, whatever it
        // happens to be drawing at the time. So the flag above was never the whole promise:
        // without this cap, scrolling in the app underneath simply stops working while the
        // overlay is up, which is not a trade any visualiser is worth. The threshold is asked for
        // rather than assumed, since a device is free to set its own; brightness is the panel's
        // to make up.
        //
        // Landed a hair under the threshold rather than exactly on it: Google's own write-up of
        // this feature calls the boundary itself passable ("at or below"), but that promise held
        // even less on a device that turned out to police overlays more strictly than stock
        // Android does — sitting exactly on a line a device is free to enforce as excluded is a
        // needless way to find out the hard way. Reported on a Z Fold, where nothing this narrow
        // was worth risking for two percent of brightness.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            float max = maxObscuringOpacityForTouch();
            OverlayDiagnostics.touchOpacityMax = max;
            params.alpha = Math.max(0f, max - 0.02f);
        }
        // Without this the window is laid out in the "default" cutout mode, which keeps it clear
        // of the notch and the status bar in portrait — so the bars stopped at the top of the app
        // rather than the top of the phone, with a band of nothing above them. FLAG_LAYOUT_NO_LIMITS
        // alone does not cover this; the cutout mode is its own decision.
        //
        // It does not put anything *over* the status bar: TYPE_APPLICATION_OVERLAY sits below
        // TYPE_STATUS_BAR in the window order, so the clock and system icons still draw on top.
        // What it buys is the band itself, which is transparent on both Deezer and the launcher.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
                ? WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_ALWAYS
                : WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        // Belt and braces: a view that fits system windows would be inset away from the very
        // edges this one exists to draw on.
        view.setFitsSystemWindows(false);
        try {
            windowManager.addView(view, params);
        } catch (Exception e) {
            Log.w(TAG, "Impossible d'ajouter la fenêtre du contour lumineux", e);
            return false;
        }
        glowView = view;
        touchSafeAlpha = params.alpha;
        OverlayDiagnostics.serviceRunning = true;
        OverlayDiagnostics.windowMode = "full";
        OverlayDiagnostics.windowAlphaWanted = params.alpha;
        OverlayDiagnostics.windowAlphaApplied = params.alpha;
        OverlayDiagnostics.windowX = params.x;
        OverlayDiagnostics.windowY = params.y;
        OverlayDiagnostics.windowWidth = params.width;
        OverlayDiagnostics.windowHeight = params.height;
        OverlayDiagnostics.windowError = "";
        view.setWindowBoundsListener(this::onWindowBoundsWanted);
        return true;
    }

    /** The alpha addOverlayView() capped the window at, kept rather than recomputed so the two
     *  places that set params.alpha (full screen at startup, shrunk-back-to-full-screen here)
     *  can never drift apart from each other. */
    private float touchSafeAlpha = 1f;

    /**
     * Answers EdgeGlowView.WindowBoundsListener — see updateWindowBounds() there for why "vinyl"
     * alone asks for something different: a small window sized to just the record, everything
     * else the same full-screen window as always. Both stay at the same touch-safe alpha and
     * NOT_TOUCHABLE — see touchSafeAlpha for why "small" cannot afford to go any more opaque than
     * that despite covering so much less of the screen.
     */
    private void onWindowBoundsWanted(boolean small, float screenCx, float screenCy, float outerHalf) {
        if (glowView == null || windowManager == null) return;
        WindowManager.LayoutParams params = (WindowManager.LayoutParams) glowView.getLayoutParams();
        if (small) {
            int size = Math.max(1, Math.round(outerHalf * 2f));
            params.width = size;
            params.height = size;
            params.x = Math.round(screenCx - outerHalf);
            params.y = Math.round(screenCy - outerHalf);
        } else {
            params.width = WindowManager.LayoutParams.MATCH_PARENT;
            params.height = WindowManager.LayoutParams.MATCH_PARENT;
            params.x = 0;
            params.y = 0;
        }
        // Same cap either way, and NOT_TOUCHABLE is never touched: a previous version made the
        // small window fully opaque and had it consume touches itself instead of passing them
        // through, on the theory that a window that opaque was already having its touches dropped
        // by the platform, flag or no flag — true for a tap, but a swipe (Deezer's own
        // left/right skip gesture, performed right over the cover the record sits on) needs a
        // down-move-up sequence to actually reach the app underneath, and a window that consumes
        // the down event kills the gesture just as dead as one the platform drops it under. Both
        // read identically to Deezer: nothing arrives. Staying under the threshold, record
        // included, is the only way that gesture is delivered at all — the record reads very
        // slightly translucent for it, the same couple of percent every other style already pays.
        params.alpha = touchSafeAlpha;
        OverlayDiagnostics.windowMode = small ? "small" : "full";
        OverlayDiagnostics.windowTouchable =
            (params.flags & WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0;
        OverlayDiagnostics.windowAlphaWanted = params.alpha;
        OverlayDiagnostics.windowX = params.x;
        OverlayDiagnostics.windowY = params.y;
        OverlayDiagnostics.windowWidth = params.width;
        OverlayDiagnostics.windowHeight = params.height;
        // Off this stack frame on purpose. This is reached from EdgeGlowView's own frame tick —
        // that is, from inside a callback the view hierarchy is in the middle of running — and
        // tearing the window down from there is exactly the kind of re-entrancy removeViewImmediate
        // refuses to do. It throwing here would be completely invisible: the window simply stays at
        // the alpha it already had, which is indistinguishable from the resize never having been
        // asked for. Posting it to the main looper runs the same work a moment later with nothing
        // of the view's own on the stack.
        calibrationHandler.post(() -> applyWindowParams(params));
    }

    /**
     * Re-lays-out the overlay window, remove-and-re-add rather than updateViewLayout().
     *
     * updateViewLayout() alone left the window at whatever alpha it was first added with, even
     * once its width/height/position had visibly taken effect — a stale value that happened to
     * read the same as the wanted one while "small" and "full" both asked for touchSafeAlpha, but
     * not safe to rely on now that either side could in principle ask for something else again.
     * Re-adding goes through
     * addOverlayView()'s own, already-proven path instead (ArtCalibrationPuck's window is never
     * anything but freshly added, and it has never shown this problem), at the cost of a one-frame
     * flicker on a transition that already isn't a quiet moment: a track changing, or the tracked
     * app itself coming or going.
     *
     * The failure that matters is the half-done one — removed, then the re-add throws — which
     * would leave the overlay gone for the rest of the session with nothing to bring it back. So
     * the re-add is retried once with the plainest possible params, and whatever went wrong is
     * kept for the diagnostics panel rather than only going to a logcat nobody here can read.
     */
    private void applyWindowParams(WindowManager.LayoutParams params) {
        if (glowView == null || windowManager == null) return;
        try {
            windowManager.removeViewImmediate(glowView);
        } catch (Exception e) {
            // Not attached, or refused: nothing was taken away, so nothing needs putting back.
            OverlayDiagnostics.windowError = "remove: " + e;
            Log.w(TAG, "applyWindowParams/remove", e);
            return;
        }
        try {
            windowManager.addView(glowView, params);
            OverlayDiagnostics.windowError = "";
            OverlayDiagnostics.windowAlphaApplied = params.alpha;
        } catch (Exception e) {
            OverlayDiagnostics.windowError = "add: " + e;
            Log.w(TAG, "applyWindowParams/add", e);
            try {
                params.width = WindowManager.LayoutParams.MATCH_PARENT;
                params.height = WindowManager.LayoutParams.MATCH_PARENT;
                params.x = 0;
                params.y = 0;
                params.alpha = touchSafeAlpha;
                windowManager.addView(glowView, params);
                OverlayDiagnostics.windowAlphaApplied = params.alpha;
                OverlayDiagnostics.windowMode = "full (repli)";
            } catch (Exception fatal) {
                OverlayDiagnostics.windowError = "add+repli: " + fatal;
                Log.w(TAG, "applyWindowParams/re-add", fatal);
            }
        }
    }

    /**
     * Adds the calibration handle: its own small window, deliberately not the overlay itself made
     * touchable — see ArtCalibrationPuck for why a full-screen touchable window would leave
     * someone unable to press play on the app they are calibrating against.
     */
    private void showCalibrationPuck() {
        if (calibrationPuck != null || glowView == null || windowManager == null) return;
        ArtCalibrationPuck puck = new ArtCalibrationPuck(this, new ArtCalibrationPuck.Listener() {
            @Override
            public void onCentreMoved(float screenX, float screenY) {
                moveCalibrationPuck(screenX, screenY);
            }

            @Override
            public void onScaleNudged(float factor) {
                if (glowView != null) glowView.nudgeArtScale(factor);
            }

            @Override
            public void onFinished() {
                finishCalibration();
            }
        });

        // Placed on the anchor it is about to correct, so the first thing it does is show where
        // Vizuzik currently thinks the cover is. Middle of the screen if the anchor cannot be
        // worked out at all — somewhere reachable, never the top-left corner.
        boolean placed = glowView.readArtAnchor(anchor);
        float centreX = placed ? anchor[0] : glowView.displayWidthPx() * 0.5f;
        float centreY = placed ? anchor[1] : glowView.displayHeightPx() * 0.5f;
        WindowManager.LayoutParams params = new WindowManager.LayoutParams(
            puck.widthPx(),
            puck.heightPx(),
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            // Touchable, unlike the overlay itself — that is the whole point of this window. Not
            // focusable, so the app underneath keeps the keyboard and the back gesture.
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        );
        params.gravity = Gravity.TOP | Gravity.START;
        params.x = clampPuckX(Math.round(centreX - puck.widthPx() * 0.5f), puck.widthPx());
        params.y = clampPuckY(Math.round(centreY - puck.heightPx() * 0.5f), puck.heightPx());
        try {
            windowManager.addView(puck, params);
        } catch (Exception e) {
            Log.w(TAG, "showCalibrationPuck", e);
            return;
        }
        calibrationPuck = puck;
        calibrating = true;
        puck.markTouched(android.os.SystemClock.elapsedRealtime());
        glowView.setCalibrating(true);
        calibrationHandler.postDelayed(calibrationWatchdog, CALIBRATION_WATCH_MS);
        // Said out here rather than in Vizuzik's own settings panel: by the time this matters the
        // music app is what's on screen, and an in-app toast would have gone with it.
        try {
            android.widget.Toast.makeText(
                this,
                "Placez la pastille au centre de la pochette, puis ✓",
                android.widget.Toast.LENGTH_LONG
            ).show();
        } catch (Exception e) {
            Log.w(TAG, "calibration toast", e);
        }
    }

    /** The opacity this window has to stay at or below for the app underneath to keep receiving
     *  touches — see addOverlayView(). Capped at the platform default as well as the device's own
     *  answer, so a device that raised the limit cannot talk this window into obscuring anything. */
    private float maxObscuringOpacityForTouch() {
        try {
            android.hardware.input.InputManager input =
                (android.hardware.input.InputManager) getSystemService(INPUT_SERVICE);
            if (input != null) {
                return Math.min(0.8f, input.getMaximumObscuringOpacityForTouch());
            }
        } catch (Exception e) {
            Log.w(TAG, "maxObscuringOpacityForTouch", e);
        }
        return 0.8f;
    }

    /** Keeps the handle wholly on screen: it is the only thing that can be dragged, so letting it
     *  be dragged (or placed) past an edge would make it unreachable. */
    private int clampPuckX(int x, int width) {
        float screen = glowView != null ? glowView.displayWidthPx() : 0f;
        if (screen <= 0) return Math.max(0, x);
        return Math.max(0, Math.min(Math.round(screen) - width, x));
    }

    private int clampPuckY(int y, int height) {
        float screen = glowView != null ? glowView.displayHeightPx() : 0f;
        if (screen <= 0) return Math.max(0, y);
        return Math.max(0, Math.min(Math.round(screen) - height, y));
    }

    /** Ends calibration on its own if the handle has been left untouched — see IDLE_TIMEOUT_MS. */
    private final Runnable calibrationWatchdog = new Runnable() {
        @Override
        public void run() {
            if (calibrationPuck == null) return;
            if (calibrationPuck.idleForMs(android.os.SystemClock.elapsedRealtime())
                >= ArtCalibrationPuck.IDLE_TIMEOUT_MS) {
                finishCalibration();
                return;
            }
            calibrationHandler.postDelayed(this, CALIBRATION_WATCH_MS);
        }
    };

    private void moveCalibrationPuck(float screenCentreX, float screenCentreY) {
        if (calibrationPuck == null || windowManager == null || glowView == null) return;
        try {
            WindowManager.LayoutParams params =
                (WindowManager.LayoutParams) calibrationPuck.getLayoutParams();
            int width = calibrationPuck.getWidth();
            int height = calibrationPuck.getHeight();
            params.x = clampPuckX(Math.round(screenCentreX - width * 0.5f), width);
            params.y = clampPuckY(Math.round(screenCentreY - height * 0.5f), height);
            windowManager.updateViewLayout(calibrationPuck, params);
            // From where the handle actually ended up, not from where the finger asked it to go,
            // so a drag stopped by the screen's edge leaves the anchor exactly under the handle.
            glowView.setArtCalibrationFromScreenCentre(
                params.x + width * 0.5f,
                params.y + height * 0.5f
            );
        } catch (Exception e) {
            Log.w(TAG, "moveCalibrationPuck", e);
        }
    }

    /** Takes the handle away and writes down where it was left. */
    private void finishCalibration() {
        calibrating = false;
        calibrationHandler.removeCallbacks(calibrationWatchdog);
        if (calibrationPuck != null && windowManager != null) {
            try {
                windowManager.removeView(calibrationPuck);
            } catch (Exception ignored) {
                // Already detached — nothing left to take away.
            }
        }
        calibrationPuck = null;
        if (glowView == null) return;
        glowView.setCalibrating(false);
        // Only the layout that was actually on screen during this drag — see
        // EdgeConfig.writeArtCalibration() for why the other one is never touched.
        EdgeConfig.writeArtCalibration(
            this, glowView.currentlyWide(), glowView.artOffsetX(), glowView.artOffsetY(), glowView.artScale()
        );
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
        // Whatever was believed about which app is in front does not survive a fold: every app
        // moves to another display, and the burst of activity that follows can leave a system
        // package as the last one resumed — see ForegroundApp.invalidate().
        ForegroundApp.invalidate();
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

        // Gates "vinyl"'s rotation — set on every update, a track change or a bare play/pause
        // alike, not only when a new track lands below.
        glowView.setPlaying(nowPlaying.isPlaying);

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
                glowView.setAlbumArt(nowPlaying.albumArt);
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
     * Called on the capture engine's own worker thread, never the main thread — an uncaught
     * exception there still takes down the whole app by default, so this gets the same guard as
     * the main-thread callbacks elsewhere in this class.
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

    // No onTaskRemoved() override, deliberately. This overlay is meant to run
    // precisely when Vizuzik isn't there: it starts on its own from NowPlayingListenerService the
    // first time a track plays, with the Activity never launched at all, so having a swipe out of
    // recents stop it made no sense. It also didn't work — NowPlayingListenerService survives task
    // removal and keeps publishing, so the very next playback event started the overlay straight
    // back up. What turns this off is the Edge Visualizer toggle, or playback stopping.

    @Override
    public void onDestroy() {
        // Before the view goes: the handle is a window of its own and would otherwise be left
        // behind on top of the music app, and what it was dragged to is worth keeping.
        finishCalibration();
        OverlayDiagnostics.serviceRunning = false;
        OverlayDiagnostics.windowMode = "arrêté";
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
        // Several paths above stop this service on their own (no overlay permission, addView
        // refused, startForeground failing). Without telling the controller, its "already started"
        // bookkeeping stays stuck on true and it never asks again for the rest of the process —
        // the overlay would just silently never come back.
        EdgeOverlayController.getInstance().onServiceStopped();
        super.onDestroy();
    }
}
