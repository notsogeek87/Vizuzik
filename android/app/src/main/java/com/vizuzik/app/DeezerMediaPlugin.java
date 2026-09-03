package com.vizuzik.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionConfig;
import android.media.projection.MediaProjectionManager;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.Build;
import android.provider.Settings;
import android.util.Base64;

import androidx.activity.result.ActivityResult;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.getcapacitor.JSArray;
import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.ActivityCallback;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.util.Set;

@CapacitorPlugin(name = "DeezerMedia")
public class DeezerMediaPlugin extends Plugin implements DeezerMediaBridge.Listener, AudioLevelsBridge.Listener {

    @Override
    protected void handleOnStart() {
        DeezerMediaBridge.getInstance().setListener(this);
        AudioLevelsBridge.getInstance().setListener(this);
    }

    @Override
    protected void handleOnStop() {
        DeezerMediaBridge.getInstance().setListener(null);
        AudioLevelsBridge.getInstance().setListener(null);
        // Deliberately NOT stopping AudioCaptureService here: handleOnStop() fires on every
        // brief backgrounding (switching apps, checking a notification), not just on actually
        // closing Vizuzik. It's a real foreground service and is meant to keep running while
        // backgrounded — stopping it here meant returning to the app never showed live audio
        // again without redoing the whole consent flow, since nothing re-requested it. It's
        // stopped for real in AudioCaptureService#onTaskRemoved(), when the user removes
        // Vizuzik from recents.
    }

    @PluginMethod
    public void checkPermission(PluginCall call) {
        JSObject result = new JSObject();
        result.put("granted", isNotificationAccessGranted());
        call.resolve(result);
    }

    @PluginMethod
    public void requestPermission(PluginCall call) {
        Intent intent = new Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS);
        intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
        getContext().startActivity(intent);
        call.resolve();
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
        Long position = call.getLong("position");
        if (position == null || position < 0) {
            call.reject("position manquante");
            return;
        }
        final long target = position;
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
     * Requests the system MediaProjection consent needed to capture Deezer's own audio output
     * (Android 10+ only). Once granted, starts AudioCaptureService, which streams a real-time
     * loudness spectrum back via "audioLevels" events for as long as the service runs.
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
            call.reject("Deezer n'a pas de lecture active");
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
