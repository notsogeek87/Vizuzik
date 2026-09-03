package com.vizuzik.app;

import android.content.ComponentName;
import android.graphics.Bitmap;
import android.media.MediaMetadata;
import android.media.session.MediaController;
import android.media.session.MediaSessionManager;
import android.media.session.PlaybackState;
import android.service.notification.NotificationListenerService;
import android.service.notification.StatusBarNotification;
import android.util.Log;

import java.util.List;
import java.util.Locale;

/**
 * Notification-listener component whose sole purpose is to obtain "notification access", which
 * Android also requires in order to read other apps' active MediaSessions. Notifications
 * themselves are ignored; only Deezer's media session (title/artist/art/playback state and
 * transport controls) is tracked, via DeezerMediaBridge.
 */
public class NowPlayingListenerService extends NotificationListenerService {

    private static final String TAG = "NowPlayingListener";
    private static final String DEEZER_PACKAGE = "deezer";

    private MediaSessionManager mediaSessionManager;
    private MediaController activeController;
    private MediaController.Callback controllerCallback;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener = this::updateActiveSession;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        mediaSessionManager = (MediaSessionManager) getSystemService(MEDIA_SESSION_SERVICE);
        ComponentName component = new ComponentName(this, NowPlayingListenerService.class);
        try {
            mediaSessionManager.addOnActiveSessionsChangedListener(sessionsChangedListener, component);
            updateActiveSession(mediaSessionManager.getActiveSessions(component));
        } catch (SecurityException e) {
            Log.w(TAG, "Notification access not granted yet", e);
        }
    }

    @Override
    public void onListenerDisconnected() {
        super.onListenerDisconnected();
        if (mediaSessionManager != null) {
            mediaSessionManager.removeOnActiveSessionsChangedListener(sessionsChangedListener);
        }
        detachController();
        DeezerMediaBridge.getInstance().clear();
    }

    @Override
    public void onNotificationPosted(StatusBarNotification sbn) {
        // Notifications themselves are not used; only media sessions are.
    }

    @Override
    public void onNotificationRemoved(StatusBarNotification sbn) {
        // Notifications themselves are not used; only media sessions are.
    }

    private void updateActiveSession(List<MediaController> controllers) {
        MediaController deezerController = null;
        if (controllers != null) {
            for (MediaController controller : controllers) {
                if (controller.getPackageName() != null && controller.getPackageName().toLowerCase(Locale.ROOT).contains(DEEZER_PACKAGE)) {
                    deezerController = controller;
                    break;
                }
            }
        }

        if (deezerController == null) {
            detachController();
            DeezerMediaBridge.getInstance().clear();
            return;
        }

        if (
            activeController != null &&
            activeController.getSessionToken().equals(deezerController.getSessionToken())
        ) {
            return;
        }

        detachController();
        activeController = deezerController;
        DeezerMediaBridge.getInstance().setController(activeController);

        controllerCallback = new MediaController.Callback() {
            @Override
            public void onMetadataChanged(MediaMetadata metadata) {
                publish(metadata, activeController.getPlaybackState());
            }

            @Override
            public void onPlaybackStateChanged(PlaybackState state) {
                publish(activeController.getMetadata(), state);
            }

            @Override
            public void onSessionDestroyed() {
                detachController();
                DeezerMediaBridge.getInstance().clear();
            }
        };
        activeController.registerCallback(controllerCallback);
        publish(activeController.getMetadata(), activeController.getPlaybackState());
    }

    private void publish(MediaMetadata metadata, PlaybackState state) {
        if (metadata == null) {
            return;
        }
        boolean isPlaying = state != null && state.getState() == PlaybackState.STATE_PLAYING;
        Bitmap art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ALBUM_ART);
        if (art == null) {
            art = metadata.getBitmap(MediaMetadata.METADATA_KEY_ART);
        }
        DeezerMediaBridge.NowPlaying nowPlaying = new DeezerMediaBridge.NowPlaying(
            metadata.getString(MediaMetadata.METADATA_KEY_TITLE),
            metadata.getString(MediaMetadata.METADATA_KEY_ARTIST),
            metadata.getString(MediaMetadata.METADATA_KEY_ALBUM),
            art,
            isPlaying,
            Math.max(0, metadata.getLong(MediaMetadata.METADATA_KEY_DURATION)),
            DeezerMediaBridge.resolvePosition(state),
            DeezerMediaBridge.canSeek(state)
        );
        DeezerMediaBridge.getInstance().updateNowPlaying(nowPlaying);
    }

    private void detachController() {
        if (activeController != null && controllerCallback != null) {
            activeController.unregisterCallback(controllerCallback);
        }
        activeController = null;
        controllerCallback = null;
        DeezerMediaBridge.getInstance().setController(null);
    }
}
