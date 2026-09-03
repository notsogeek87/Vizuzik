package com.vizuzik.app;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.media.projection.MediaProjectionManager;
import android.media.session.MediaController;
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
        stopAudioCaptureService();
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
        MediaProjectionManager manager =
            (MediaProjectionManager) getContext().getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        if (manager == null) {
            call.reject("unsupported");
            return;
        }
        startActivityForResult(call, manager.createScreenCaptureIntent(), "handleCaptureResult");
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
        call.resolve();
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
        return result;
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
