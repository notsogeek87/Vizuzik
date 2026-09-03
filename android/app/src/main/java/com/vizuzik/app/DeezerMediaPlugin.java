package com.vizuzik.app;

import android.content.Intent;
import android.graphics.Bitmap;
import android.media.session.MediaController;
import android.provider.Settings;
import android.util.Base64;

import androidx.core.app.NotificationManagerCompat;

import com.getcapacitor.JSObject;
import com.getcapacitor.Plugin;
import com.getcapacitor.PluginCall;
import com.getcapacitor.PluginMethod;
import com.getcapacitor.annotation.CapacitorPlugin;

import java.io.ByteArrayOutputStream;
import java.util.Set;

@CapacitorPlugin(name = "DeezerMedia")
public class DeezerMediaPlugin extends Plugin implements DeezerMediaBridge.Listener {

    @Override
    protected void handleOnStart() {
        DeezerMediaBridge.getInstance().setListener(this);
    }

    @Override
    protected void handleOnStop() {
        DeezerMediaBridge.getInstance().setListener(null);
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
