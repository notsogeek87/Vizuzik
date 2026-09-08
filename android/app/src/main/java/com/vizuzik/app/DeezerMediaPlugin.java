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
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.net.Uri;
import android.os.Build;
import android.provider.Settings;
import android.service.notification.NotificationListenerService;
import android.util.Base64;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.PermissionState;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
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

    @Override
    protected void handleOnStart() {
        DeezerMediaBridge.getInstance().addListener(this);
        AudioLevelsBridge.getInstance().addListener(this);
    }

    @Override
    protected void handleOnStop() {
        DeezerMediaBridge.getInstance().removeListener(this);
        AudioLevelsBridge.getInstance().removeListener(this);
        // Nothing to tear down beyond these two listeners: the audio source itself
        // (TrackedAudioCapture) is owned process-wide and deliberately outlives this webview —
        // the edge overlay needs it precisely when Vizuzik is backgrounded.
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
     * MusicAppPreference, so NowPlayingListenerService and AudioSessionRegistry — which don't
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
     * Requests RECORD_AUDIO, the one permission the visualizer needs. Android names it
     * "microphone" in its dialog, but nothing here ever opens the microphone: it is what
     * android.media.audiofx.Visualizer requires to attach to the music app's own audio session
     * (see TrackedSessionAudioSource). Asked from here because that source runs in a background
     * service, which has no Activity to show a permission dialog from.
     */
    @PluginMethod
    public void requestAudioPermission(PluginCall call) {
        if (getPermissionState("microphone") == PermissionState.GRANTED) {
            resolveAudioPermission(call, true);
            return;
        }
        requestPermissionForAlias("microphone", call, "handleAudioPermission");
    }

    /** The same answer without ever showing a dialog — asked on every resume, since the grant can
     *  be made (or taken back) from Android's own Settings while Vizuzik is in the background. */
    @PluginMethod
    public void getAudioPermission(PluginCall call) {
        resolveAudioPermission(call, getPermissionState("microphone") == PermissionState.GRANTED);
    }

    @PermissionCallback
    private void handleAudioPermission(PluginCall call) {
        resolveAudioPermission(call, getPermissionState("microphone") == PermissionState.GRANTED);
    }

    private void resolveAudioPermission(PluginCall call, boolean granted) {
        if (granted) {
            // The source declines to attach while ungranted, and only learns of a new session
            // when the player opens one — so without this nudge a grant made mid-track appears
            // to do nothing until the next track.
            TrackedAudioCapture.getInstance().onAudioPermissionGranted();
        }
        JSObject result = new JSObject();
        result.put("granted", granted);
        call.resolve(result);
    }

    /**
     * Whether the edge-glow overlay (drawn over the tracked app itself, MuViz Edge-style) can run
     * on this device (Android 8+, TYPE_APPLICATION_OVERLAY) and whether the "display over other
     * apps" special permission is currently granted. The web layer checks this on every resume —
     * same reasoning as getAudioPermission(): the grant is made in a system Settings screen the
     * app never sees the result of directly, so the only way to know is to ask again on return.
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
     * Whether Vizuzik currently holds "usage access" — the special permission Edge Visualizer's
     * "only over the music app" setting needs, since knowing which app is on screen is otherwise
     * impossible from an overlay window (see ForegroundApp). Read on every resume, same reason as
     * the two permission checks above: it is granted in a system Settings screen whose result the
     * app never sees directly.
     */
    @PluginMethod
    public void checkUsageAccess(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", ForegroundApp.hasUsageAccess(getContext()));
        call.resolve(result);
    }

    /** Opens the system "usage access" screen. Like the other two, it only opens the screen. */
    @PluginMethod
    public void requestUsageAccess(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        if (tryStartActivity(intent)) {
            call.resolve();
        } else {
            call.reject("unavailable");
        }
    }

    /**
     * Starts OverlayEdgeGlowService. Orchestrated from the web layer (see syncEdgeOverlay() in
     * main.js) whenever the webview is alive — called only once Vizuzik itself is backgrounded, a
     * track is actually playing, and the overlay permission is already known to be granted, so a
     * missing grant here means the web layer's own state is stale rather than the normal case.
     * EdgeOverlayController reaches the same conclusion independently from native state, for the
     * case where the webview isn't running at all yet — both go through
     * OverlayEdgeGlowService.requestStart()/requestStop(), so the two can never disagree on how
     * starting/stopping actually happens.
     */
    @PluginMethod
    public void startEdgeOverlay(PluginCall call) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !Settings.canDrawOverlays(getContext())) {
            call.reject("permission");
            return;
        }
        OverlayEdgeGlowService.requestStart(getContext());
        call.resolve();
    }

    @PluginMethod
    public void stopEdgeOverlay(PluginCall call) {
        OverlayEdgeGlowService.requestStop(getContext());
        call.resolve();
    }

    /**
     * Mirrors the web layer's Edge Visualizer on/off toggle into EdgeOverlayPreference — the only
     * way EdgeOverlayController (running natively, independent of this plugin/the webview) can
     * find out the user turned it on, so Edge Visualizer can start the first time a track plays
     * even if Vizuzik itself is never opened this session. Re-syncs immediately in case a track
     * is already playing when the setting changes.
     */
    @PluginMethod
    public void setEdgeOverlayEnabled(PluginCall call) {
        EdgeOverlayPreference.setEnabled(getContext(), call.getBoolean("enabled", false));
        EdgeOverlayController.getInstance().init(getContext().getApplicationContext());
        EdgeOverlayController.getInstance().sync();
        call.resolve();
    }

    /**
     * Reads the Edge Visualizer settings panel's current values back out of EdgeConfig — used to
     * restore the native-held state (there is no localStorage on this side) if the web layer's own
     * copy is ever missing, e.g. a fresh install of a newer version that added a field.
     */
    @PluginMethod
    public void getEdgeConfig(PluginCall call) {
        EdgeConfig.Snapshot config = EdgeConfig.read(getContext());
        JSObject result = new JSObject();
        result.put("style", config.style);
        result.put("intensity", config.intensity);
        result.put("thickness", config.thickness);
        result.put("brightness", config.brightness);
        result.put("sensitivity", config.sensitivity);
        result.put("band", config.band);
        result.put("barSize", config.barSize);
        result.put("colorMode", config.customPalette != null ? EdgeConfig.COLOR_CUSTOM : EdgeConfig.COLOR_AUTO);
        result.put("top", config.top);
        result.put("bottom", config.bottom);
        result.put("left", config.left);
        result.put("right", config.right);
        result.put("onlyOverMusicApp", config.onlyOverMusicApp);
        result.put("cocoonFallback", config.cocoonFallback);
        // Read-only here: setEdgeConfig() deliberately cannot write these back, so a slider moved
        // after a calibration can never throw it away. See EdgeConfig.writeArtCalibration().
        result.put("artOffsetX", config.artOffsetX);
        result.put("artOffsetY", config.artOffsetY);
        result.put("artScale", config.artScale);
        call.resolve(result);
    }

    /**
     * Puts the album-art calibration handle on screen and brings up the music app it is meant to
     * be lined up against — see ArtCalibrationPuck. Doing both from here is the point: the anchor
     * describes where *Deezer* keeps its cover, so leaving the handle sitting on top of Vizuzik's
     * own screen gives someone nothing to align it with.
     *
     * The order matters. The handle is asked for while Vizuzik is still the app in front, since a
     * foreground service started from the background can be refused outright; the music app is
     * only brought up once that has gone through.
     */
    @PluginMethod
    public void startArtCalibration(PluginCall call) {
        // Same guard as startEdgeOverlay(): without the "display over other apps" grant the
        // service would start and immediately stop itself, and the panel would have sent someone
        // off to Deezer to look for a handle that was never going to appear.
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || !Settings.canDrawOverlays(getContext())) {
            call.reject("permission");
            return;
        }
        boolean started = OverlayEdgeGlowService.requestArtCalibration(getContext());
        JSObject result = new JSObject();
        result.put("started", started);
        result.put("launched", started && openTrackedMusicApp());
        call.resolve(result);
    }

    /** Brings up whichever app is currently being tracked (see MusicAppPreference), defaulting to
     *  Deezer for an install where nothing has been chosen yet. */
    private boolean openTrackedMusicApp() {
        try {
            String packageName = MusicAppPreference.getPackage(getContext());
            if (packageName == null) packageName = MusicApps.DEEZER_PACKAGE;
            Intent intent = getContext().getPackageManager().getLaunchIntentForPackage(packageName);
            if (intent == null) return false;
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            getContext().startActivity(intent);
            return true;
        } catch (Exception e) {
            // Not installed any more, or the launch was refused: the handle is already up, so the
            // user can still get there themselves, and the panel says so. Never worth failing the
            // whole call over.
            return false;
        }
    }

    /** Back to the modelled position — the way out of a calibration dragged somewhere silly. */
    @PluginMethod
    public void resetArtCalibration(PluginCall call) {
        EdgeConfig.writeArtCalibration(getContext(), 0f, 0f, 1f);
        call.resolve();
    }

    /**
     * Mirrors the whole settings panel state into EdgeConfig (SharedPreferences), the only way
     * OverlayEdgeGlowService — a background Service with no access to the webview's localStorage
     * — can read what the user picked. Applied live via EdgeConfig's SharedPreferences listener
     * if the overlay is already running, no restart needed.
     */
    @PluginMethod
    public void setEdgeConfig(PluginCall call) {
        // optDouble()/optBoolean() on the raw JSONObject rather than call.getDouble()/
        // getBoolean(): the same issue seek() works around for getLong() above applies here —
        // Capacitor's typed getters only return a value when the bridged JSON number is exactly
        // the type they expect, and a whole-number slider value (e.g. intensity at its default
        // of 1) can arrive as a plain JSON integer rather than a double.
        org.json.JSONObject data = call.getData();
        EdgeConfig.write(
            getContext(),
            call.getString("style", EdgeConfig.STYLE_BARS),
            (float) data.optDouble("intensity", 1.0),
            (float) data.optDouble("thickness", 1.0),
            (float) data.optDouble("brightness", 1.0),
            (float) data.optDouble("sensitivity", 1.0),
            call.getString("band", EdgeConfig.BAND_FULL),
            (float) data.optDouble("barSize", 1.0),
            call.getString("colorMode", EdgeConfig.COLOR_AUTO),
            call.getString("customColors", null),
            data.optBoolean("top", true),
            data.optBoolean("bottom", false),
            data.optBoolean("left", false),
            data.optBoolean("right", false),
            data.optBoolean("onlyOverMusicApp", false),
            call.getString("cocoonFallback", EdgeConfig.STYLE_BARS)
        );
        call.resolve();
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
