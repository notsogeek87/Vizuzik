package com.vizuzik.app;

import android.Manifest;
import android.app.Activity;
import android.app.UiModeManager;
import android.content.ActivityNotFoundException;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ActivityInfo;
import android.content.res.Configuration;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Base64;

import androidx.activity.result.ActivityResult;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;
import com.getcapacitor.annotation.Permission;
import com.getcapacitor.annotation.PermissionCallback;

import java.io.ByteArrayOutputStream;
import java.util.Set;

@CapacitorPlugin(
    name = "DeezerMedia",
    permissions = { @Permission(strings = { Manifest.permission.RECORD_AUDIO }, alias = "microphone") }
)
public class DeezerMediaPlugin extends Plugin implements DeezerMediaBridge.Listener, AudioLevelsBridge.Listener {

    // Owned directly (no Service, no singleton bridge): mic capture only ever runs while the
    // webview is alive and Vizuzik is in the foreground (see applyAudioSource() in main.js,
    // which stops it the moment the app is backgrounded), so its lifetime can just follow the
    // plugin's own.
    private MicCaptureThread micCaptureThread;

    @Override
    protected void handleOnStart() {
        DeezerMediaBridge.getInstance().addListener(this);
        AudioLevelsBridge.getInstance().addListener(this);
    }

    @Override
    protected void handleOnStop() {
        DeezerMediaBridge.getInstance().removeListener(this);
        AudioLevelsBridge.getInstance().removeListener(this);
        // Deliberately NOT stopping AudioCaptureService here: handleOnStop() fires on every
        // brief backgrounding (switching apps, checking a notification), not just on actually
        // closing Vizuzik. It's a real foreground service and is meant to keep running while
        // backgrounded — stopping it here meant returning to the app never showed live audio
        // again without redoing the whole consent flow, since nothing re-requested it. It's
        // stopped for real in AudioCaptureService#onTaskRemoved(), when the user removes
        // Vizuzik from recents.
        //
        // The mic thread has no such reason to survive: it isn't a Service, and the whole point
        // of it is that it's cheap to re-acquire, so any teardown of this plugin/webview takes
        // it down too rather than leaking a live AudioRecord.
        stopMicCaptureInternal();
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", isNotificationAccessGranted());
        call.resolve(result);
    }

    /**
     * Asks the system to rebind NowPlayingListenerService right now. Granting notification
     * access is supposed to do this on its own, but on at least one device — one whose Settings
     * app has no screen for ACTION_NOTIFICATION_LISTENER_SETTINGS at all, granted through
     * whatever alternate path its Settings app actually offers (see the requestPermission()
     * fallback above) — the service was left never bound, so nothing it tracks ever reached
     * DeezerMediaBridge even though access showed as granted. The web layer calls this once,
     * right after checkPermission() first reports granted this session (see refresh() in
     * main.js), rather than leaving a correctly-granted user stuck with no way back short of a
     * reboot.
     */
    @PluginMethod
    public void requestListenerRebind(PluginCall call) {
        NotificationListenerService.requestRebind(new ComponentName(getContext(), NowPlayingListenerService.class));
        call.resolve();
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        Intent listenerIntent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        listenerIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStartActivity(listenerIntent)) {
            call.resolve();
            return;
        }
        // Some Android TV builds (mainly generic/no-name boxes running a stripped-down Settings
        // app) don't have a screen for ACTION_NOTIFICATION_LISTENER_SETTINGS at all — that intent
        // resolves to nothing there. Falling back to the root Settings screen at least drops the
        // user somewhere they can look for it themselves instead of a dead button; the web layer
        // (see the click handler in main.js) tells them what to look for from there.
        Intent settingsIntent = new Intent(Settings.ACTION_SETTINGS);
        settingsIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStartActivity(settingsIntent)) {
            JSObject result = new JSObject();
            result.put("fallback", true);
            call.resolve(result);
            return;
        }
        // Neither intent resolved to anything: genuinely nothing left to open.
        call.reject("unavailable");
    }

    private boolean tryStartActivity(Intent intent) {
        try {
            getContext().startActivity(intent);
            return true;
        } catch (ActivityNotFoundException e) {
            return false;
        }
    }

    /**
     * Whether Deezer and/or Spotify are installed. The web layer uses this to pick which one to
     * track without asking: only one installed decides it outright, and asking is reserved for
     * the case where both are present.
     */
    @PluginMethod
    public void detectMusicApps(PluginCall call) {
        JSObject result = new JSObject();
        result.put("deezerInstalled", getContext().getPackageManager().getLaunchIntentForPackage(MusicApps.DEEZER_PACKAGE) != null);
        result.put("spotifyInstalled", getContext().getPackageManager().getLaunchIntentForPackage(MusicApps.SPOTIFY_PACKAGE) != null);
        call.resolve(result);
    }

    /**
     * Mirrors the web layer's choice of tracked app ("deezer" or "spotify") into
     * MusicAppPreference, so NowPlayingListenerService and AudioCaptureService — which don't
     * have access to localStorage — can read the same value.
     */
    @PluginMethod
    public void setMusicAppTarget(PluginCall call) {
        String packageName = MusicApps.packageForKey(call.getString("app"));
        if (packageName == null) {
            call.reject("app inconnu");
            return;
        }
        MusicAppPreference.setPackage(getContext(), packageName);
        call.resolve();
    }

    /**
     * Mirrors the web layer's chosen audio source ("mic" / "real" / "off") into
     * AudioSourcePreference, the same way setMusicAppTarget() mirrors the tracked app. Read by
     * OverlayEdgeGlowService to decide whether it's allowed to listen to the microphone on its
     * own while backgrounded — only when "mic" is what the user actually picked in the
     * full-screen player, never on its own initiative.
     */
    @PluginMethod
    public void setAudioSourcePreference(PluginCall call) {
        String source = call.getString("source");
        if (source == null) {
            call.reject("source manquante");
            return;
        }
        AudioSourcePreference.set(getContext(), source);
        call.resolve();
    }

    /**
     * Launches the given app ("deezer" or "spotify") directly instead of leaving the user to
     * find it themselves. Vizuzik is a companion display: called once per cold start, only when
     * no track is already active, so it never yanks focus away from an already-playing session
     * just to show a screen that's already where it should be. Resolves {launched:false} rather
     * than rejecting when the app isn't installed — that isn't an error the caller needs to
     * react to.
     */
    @PluginMethod
    public void openMusicApp(PluginCall call) {
        JSObject result = new JSObject();
        String packageName = MusicApps.packageForKey(call.getString("app"));
        Intent intent = packageName != null ? getContext().getPackageManager().getLaunchIntentForPackage(packageName) : null;
        if (intent == null) {
            result.put("launched", false);
            call.resolve(result);
            return;
        }
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        result.put("launched", true);
        call.resolve(result);
    }

    /**
     * Best-effort: brings Vizuzik's own task back to the foreground after openMusicApp() sent
     * the user to Deezer/Spotify because there was nothing to resume. Called once a track
     * actually starts, not on a timer, so it never interrupts someone still picking a song.
     * FLAG_ACTIVITY_REORDER_TO_FRONT reuses the existing task instead of recreating it. Not
     * guaranteed: Android's background-activity-start restrictions can block this outright on
     * some versions or if too much time has passed since Vizuzik itself last held the
     * foreground — in that case this silently does nothing, and the user is exactly where a
     * plain openMusicApp() would have left them anyway.
     */
    @PluginMethod
    public void bringToFront(PluginCall call) {
        Intent intent = new Intent(getContext(), MainActivity.class);
        intent.addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT | Intent.FLAG_ACTIVITY_NEW_TASK);
        try {
            getContext().startActivity(intent);
        } catch (Exception e) {
            // Background-activity-start restriction or similar: nothing more to do.
        }
        call.resolve();
    }

    /**
     * Cassette mode is drawn cassette-side up, i.e. landscape: forcing the activity into
     * landscape here — rather than leaving it to the CSS rotation trick alone — is what makes
     * the phone's own auto-rotate turn the screen for the user instead of asking them to fight
     * a portrait lock to see it the right way up. Deliberately plain LANDSCAPE rather than
     * SENSOR_LANDSCAPE: the sensor variant also accepts the *reversed* landscape orientation
     * (phone turned the other way round), and on this WebView that flips touch X but not the
     * rendered layout, which is exactly what made the next/previous swipe feel backwards in
     * this mode. A single fixed orientation has no such flip to get wrong.
     */
    @PluginMethod
    public void lockLandscape(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            Activity activity = getActivity();
            if (activity != null) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE);
            }
        });
        call.resolve();
    }

    /** Leaving cassette mode: back to whatever the system/device would normally allow. */
    @PluginMethod
    public void unlockOrientation(PluginCall call) {
        getBridge().executeOnMainThread(() -> {
            Activity activity = getActivity();
            if (activity != null) {
                activity.setRequestedOrientation(ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED);
            }
        });
        call.resolve();
    }

    /**
     * Whether this device is running as an Android TV (Leanback), so the web layer can switch to
     * a 10-foot layout and D-pad navigation instead of guessing from screen size or input
     * capability — both of which are unreliable (a TV remote's touchpad can still report a
     * pointer). UiModeManager is Android's own authority on this, the same check Google's own TV
     * samples use.
     */
    @PluginMethod
    public void getPlatformInfo(PluginCall call) {
        JSObject result = new JSObject();
        UiModeManager uiModeManager = (UiModeManager) getContext().getSystemService(Context.UI_MODE_SERVICE);
        boolean isTv = uiModeManager != null
            && uiModeManager.getCurrentModeType() == Configuration.UI_MODE_TYPE_TELEVISION;
        result.put("isTv", isTv);
        call.resolve(result);
    }

    @PluginMethod
    public void getNowPlaying(PluginCall call) {
        call.resolve(toJs(DeezerMediaBridge.getInstance().getLastNowPlaying()));
    }

    /**
     * Position and duration only — deliberately not the whole now-playing payload, because that
     * re-encodes the album art to base64 on every call and this one is polled every few seconds
     * to re-anchor the progress bar against drift.
     */
    @PluginMethod
    public void getPosition(PluginCall call) {
        JSObject result = new JSObject();
        DeezerMediaBridge bridge = DeezerMediaBridge.getInstance();
        DeezerMediaBridge.NowPlaying nowPlaying = bridge.getLastNowPlaying();
        if (nowPlaying == null) {
            result.put("active", false);
            call.resolve(result);
            return;
        }
        PlaybackState state = currentPlaybackState();
        result.put("active", true);
        result.put("duration", nowPlaying.durationMs);
        result.put("position", state != null ? DeezerMediaBridge.resolvePosition(state) : nowPlaying.positionMs);
        result.put("isPlaying", state != null ? state.getState() == PlaybackState.STATE_PLAYING : nowPlaying.isPlaying);
        result.put("canSeek", state != null ? DeezerMediaBridge.canSeek(state) : nowPlaying.canSeek);
        call.resolve(result);
    }

    @PluginMethod
    public void seek(PluginCall call) {
        // NOT call.getLong(): Capacitor only returns a value there when the bridged JSON object
        // is literally an instance of Long, and a JS number small enough to be a position in
        // milliseconds arrives as an Integer — so getLong() silently returned null and every
        // seek was rejected before it reached Deezer. optLong() coerces whatever numeric type
        // the bridge produced.
        long target = call.getData().optLong("position", -1);
        if (target < 0) {
            call.reject("position manquante ou invalide");
            return;
        }
        withTransportControls(call, controls -> controls.seekTo(target));
    }

    @PluginMethod
    public void play(PluginCall call) {
        withTransportControls(call, MediaController.TransportControls::play);
    }

    @PluginMethod
    public void pause(PluginCall call) {
        withTransportControls(call, MediaController.TransportControls::pause);
    }

    @PluginMethod
    public void next(PluginCall call) {
        withTransportControls(call, MediaController.TransportControls::skipToNext);
    }

    @PluginMethod
    public void previous(PluginCall call) {
        withTransportControls(call, MediaController.TransportControls::skipToPrevious);
    }

    @Override
    public void onNowPlayingChanged(DeezerMediaBridge.NowPlaying nowPlaying) {
        notifyListeners("nowPlayingChanged", toJs(nowPlaying));
    }

    /**
     * Whether real audio capture is possible on this device, and whether it is running right now.
     * The web layer asks on every resume: its own flags live in the webview and are wiped whenever
     * that is recreated, while AudioCaptureService keeps running across it. Without this, a
     * returning user was shown "activate real audio" for a capture that was already live, and
     * tapping it re-ran the system consent dialog for nothing.
     */
    @PluginMethod
    public void getCaptureState(PluginCall call) {
        JSObject result = new JSObject();
        boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q
            && getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE) != null;
        result.put("supported", supported);
        result.put("running", supported && AudioLevelsBridge.getInstance().isCapturing());
        call.resolve(result);
    }

    /**
     * Whether the edge-glow overlay (drawn over the tracked app itself, MuViz Edge-style) can run
     * on this device (Android 8+, TYPE_APPLICATION_OVERLAY) and whether the "display over other
     * apps" special permission is currently granted. The web layer checks this on every resume —
     * same reasoning as getCaptureState(): the grant is made in a system Settings screen the app
     * never sees the result of directly, so the only way to know is to ask again on return.
     */
    @PluginMethod
    public void checkOverlayPermission(PluginCall call) {
        JSObject result = new JSObject();
        boolean supported = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O;
        result.put("supported", supported);
        result.put("granted", supported && Settings.canDrawOverlays(getContext()));
        call.resolve(result);
    }

    /**
     * Opens the system "display over other apps" screen for Vizuzik specifically. Like
     * requestPermission() for notification access, this only opens the screen — there is no
     * result to await, so the web layer finds out what happened via checkOverlayPermission() the
     * next time it resumes.
     */
    @PluginMethod
    public void requestOverlayPermission(PluginCall call) {
        Intent intent = new Intent(
            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
            Uri.parse("package:" + getContext().getPackageName())
        );
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStartActivity(intent)) {
            call.resolve();
        } else {
            call.reject("unavailable");
        }
    }

    /**
     * Starts OverlayEdgeGlowService. Entirely orchestrated from the web layer (see
     * syncEdgeOverlay() in main.js): called only once Vizuzik itself is backgrounded, a track is
     * actually playing, and the overlay permission is already known to be granted — so a missing
     * grant here means the web layer's own state is stale rather than the normal case.
     */
    @PluginMethod
    public void startEdgeOverlay(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !Settings.canDrawOverlays(getContext())) {
            call.reject("permission");
            return;
        }
        ContextCompat.startForegroundService(getContext(), new Intent(getContext(), OverlayEdgeGlowService.class));
        call.resolve();
    }

    @PluginMethod
    public void stopEdgeOverlay(PluginCall call) {
        getContext().stopService(new Intent(getContext(), OverlayEdgeGlowService.class));
        call.resolve();
    }

    /**
     * Requests the system MediaProjection consent needed to capture the tracked app's own audio
     * output (Android 10+ only). Once granted, starts AudioCaptureService, which streams a
     * real-time loudness spectrum back via "audioLevels" events for as long as the service runs.
     */
    @PluginMethod
    public void startVisualizerCapture(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            call.reject("unsupported");
            return;
        }
        if (AudioLevelsBridge.getInstance().isCapturing()) {
            // Already granted and running: showing the system dialog again would be pure noise,
            // and accepting it would tear down the projection we're already using.
            JSObject result = new JSObject();
            result.put("alreadyRunning", true);
            call.resolve(result);
            return;
        }
        MediaProjectionManager manager =
            (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            call.reject("unsupported");
            return;
        }

        Intent captureIntent;
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            // Android 14's default createScreenCaptureIntent() shows a "share a single app"
            // picker first; on at least some devices, picking an app there just switches to it
            // and never returns a grant, leaving the user stuck having to navigate back
            // manually with nothing captured. Requesting the default display directly skips
            // that picker and goes straight to the "entire screen" consent, which does work —
            // and since we only ever read the audio track (no virtual display is ever created),
            // capturing the whole screen vs. a single app makes no difference to us.
            captureIntent = manager.createScreenCaptureIntent(MediaProjectionConfig.createConfigForDefaultDisplay());
        } else {
            captureIntent = manager.createScreenCaptureIntent();
        }
        startActivityForResult(call, captureIntent, "handleCaptureResult");
    }

    @ActivityCallback
    private void handleCaptureResult(PluginCall call, ActivityResult result) {
        if (call == null) {
            return;
        }
        if (result.getResultCode() != Activity.RESULT_OK || result.getData() == null) {
            call.reject("denied");
            return;
        }
        Intent serviceIntent = new Intent(getContext(), AudioCaptureService.class);
        serviceIntent.putExtra("resultCode", result.getResultCode());
        serviceIntent.putExtra("data", result.getData());
        ContextCompat.startForegroundService(getContext(), serviceIntent);
        JSObject granted = new JSObject();
        granted.put("alreadyRunning", false);
        call.resolve(granted);
    }

    @PluginMethod
    public void stopVisualizerCapture(PluginCall call) {
        stopAudioCaptureService();
        call.resolve();
    }

    private void stopAudioCaptureService() {
        getContext().stopService(new Intent(getContext(), AudioCaptureService.class));
    }

    /**
     * Starts the "micro" source: a plain AudioRecord on the phone's own microphone (see
     * MicCaptureThread for why it's deliberately not the WebView's getUserMedia()). Just the
     * ordinary RECORD_AUDIO runtime permission — requested here if not already granted — with no
     * system consent dialog and no MediaProjection, so unlike startVisualizerCapture() this is
     * safe to call from an automatic, no-user-gesture path.
     */
    @PluginMethod
    public void startMicCapture(PluginCall call) {
        if (micCaptureThread != null) {
            call.resolve();
            return;
        }
        if (getPermissionState("microphone") == PermissionState.GRANTED) {
            beginMicCapture(call);
        } else {
            requestPermissionForAlias("microphone", call, "handleMicPermissionResult");
        }
    }

    @PermissionCallback
    private void handleMicPermissionResult(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) {
            beginMicCapture(call);
        } else {
            call.reject("denied");
        }
    }

    private void beginMicCapture(PluginCall call) {
        MicCaptureThread thread = new MicCaptureThread(levels -> {
            JSArray array = new JSArray();
            for (float level : levels) {
                array.put((Object) level);
            }
            JSObject result = new JSObject();
            result.put("levels", array);
            notifyListeners("micLevels", result);
        });
        if (!thread.prepare()) {
            call.reject("unsupported");
            return;
        }
        micCaptureThread = thread;
        thread.start();
        call.resolve();
    }

    @PluginMethod
    public void stopMicCapture(PluginCall call) {
        stopMicCaptureInternal();
        call.resolve();
    }

    private void stopMicCaptureInternal() {
        if (micCaptureThread != null) {
            micCaptureThread.stopCapture();
            micCaptureThread = null;
        }
    }

    @Override
    public void onLevels(float[] levels) {
        JSArray array = new JSArray();
        for (float level : levels) {
            // put(Object) never throws, unlike put(double) — avoids a checked JSONException here.
            array.put((Object) level);
        }
        JSObject result = new JSObject();
        result.put("levels", array);
        notifyListeners("audioLevels", result);
    }

    @Override
    public void onCaptureStopped() {
        notifyListeners("audioCaptureStopped", new JSObject());
    }

    private interface TransportAction {
        void run(MediaController.TransportControls controls);
    }

    private void withTransportControls(PluginCall call, TransportAction action) {
        MediaController controller = DeezerMediaBridge.getInstance().getController();
        if (controller == null) {
            call.reject("Aucune lecture active");
            return;
        }
        action.run(controller.getTransportControls());
        call.resolve();
    }

    private boolean isNotificationAccessGranted() {
        Set<String> enabledPackages = NotificationManagerCompat.getEnabledListenerPackages(getContext());
        return enabledPackages.contains(getContext().getPackageName());
    }

    private JSObject toJs(DeezerMediaBridge.NowPlaying nowPlaying) {
        JSObject result = new JSObject();
        if (nowPlaying == null) {
            result.put("active", false);
            return result;
        }
        result.put("active", true);
        result.put("title", nowPlaying.title);
        result.put("artist", nowPlaying.artist);
        result.put("album", nowPlaying.album);
        result.put("isPlaying", nowPlaying.isPlaying);
        result.put("albumArt", encodeBitmap(nowPlaying.albumArt));
        result.put("duration", nowPlaying.durationMs);
        // Resolved against the session's *current* state rather than reusing the value captured
        // when the track was published: getNowPlaying() is also called on resume, potentially
        // minutes later, and a stale position would rewind the bar on every return to the app.
        PlaybackState state = currentPlaybackState();
        result.put("position", state != null ? DeezerMediaBridge.resolvePosition(state) : nowPlaying.positionMs);
        result.put("canSeek", state != null ? DeezerMediaBridge.canSeek(state) : nowPlaying.canSeek);
        return result;
    }

    private PlaybackState currentPlaybackState() {
        MediaController controller = DeezerMediaBridge.getInstance().getController();
        return controller == null ? null : controller.getPlaybackState();
    }

    private String encodeBitmap(Bitmap bitmap) {
        if (bitmap == null) {
            return null;
        }
        ByteArrayOutputStream stream = new ByteArrayOutputStream();
        bitmap.compress(Bitmap.CompressFormat.PNG, 90, stream);
        return "data:image/png;base64," + Base64.encodeToString(stream.toByteArray(), Base64.NO_WRAP);
    }
}
