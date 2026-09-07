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

/**
 * Notification-listener component whose sole purpose is to obtain "notification access", which
 * Android also requires in order to read other apps' active MediaSessions. Notifications
 * themselves are ignored; only the tracked app's media session (title/artist/art/playback state
 * and transport controls) is followed, via DeezerMediaBridge. Which app that is comes from
 * MusicAppPreference — Deezer or Spotify, whichever the web layer resolved at launch. Before
 * that choice is known, any session from either known app matches, so something still shows up
 * immediately on the (common) case where only one of the two is installed.
 */
public class NowPlayingListenerService extends NotificationListenerService {

    private static final String TAG = "NowPlayingListener";

    private MediaSessionManager mediaSessionManager;
    private MediaController activeController;
    private MediaController.Callback controllerCallback;

    private final MediaSessionManager.OnActiveSessionsChangedListener sessionsChangedListener = this::updateActiveSession;

    @Override
    public void onListenerConnected() {
        super.onListenerConnected();
        // This is the one component guaranteed to be alive whenever notification access is
        // granted, regardless of whether MainActivity has ever run this session (the system
        // binds it directly, e.g. right after a reboot) — the natural place to wire up
        // EdgeOverlayController so Edge Visualizer can start on its own the first time a track
        // plays, without the user ever having to open Vizuzik first.
        EdgeOverlayController.getInstance().init(getApplicationContext());
        DeezerMediaBridge.getInstance().addListener(EdgeOverlayController.getInstance());
        // Same reasoning, and the reason this has to happen *here* rather than when the overlay
        // starts: the music app announces its audio session once, when it opens it, so anything
        // that only starts listening after the user has switched to that app has already missed
        // it. See AudioSessionRegistry.
        AudioSessionRegistry.getInstance().start(getApplicationContext());
        // The app's one audio source, started here for the same reason: it must be capturing
        // before the user switches to the music app, whether the effect that consumes it ends up
        // being the overlay or Vizuzik's own player.
        TrackedAudioCapture.getInstance().start(getApplicationContext());
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
        // clear() before removeListener(): EdgeOverlayController needs this "nothing playing"
        // notification to stop OverlayEdgeGlowService — if it were unregistered first, it would
        // never learn playback stopped and could leave the overlay running indefinitely, with
        // nothing else around (MainActivity may never have run this session) to correct it.
        DeezerMediaBridge.getInstance().clear();
        DeezerMediaBridge.getInstance().removeListener(EdgeOverlayController.getInstance());
        // Symmetric with the start in onListenerConnected(). Without notification access there is
        // no tracked session left to follow, and an attached Visualizer would otherwise keep
        // RECORD_AUDIO in continuous use for the rest of the process's life.
        TrackedAudioCapture.getInstance().stop();
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
        String targetPackage = MusicAppPreference.getPackage(this);
        MediaController matched = null;
        if (controllers != null) {
            for (MediaController controller : controllers) {
                String packageName = controller.getPackageName();
                if (packageName == null) continue;
                boolean matches = targetPackage != null
                    ? packageName.equals(targetPackage)
                    : MusicApps.isKnownPackage(packageName);
                if (matches) {
                    matched = controller;
                    break;
                }
            }
        }

        if (matched == null) {
            detachController();
            DeezerMediaBridge.getInstance().clear();
            return;
        }

        if (
            activeController != null &&
            activeController.getSessionToken().equals(matched.getSessionToken())
        ) {
            return;
        }

        detachController();
        activeController = matched;
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
