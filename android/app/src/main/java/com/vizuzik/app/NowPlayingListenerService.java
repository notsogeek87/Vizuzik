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
 * MusicAppPreference: by default (auto) any music app, preferring the one currently playing;
 * a specific package only if one was explicitly chosen.
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
        // Same reasoning as EdgeOverlayController just above: the lock-screen visualizer has to
        // work even if Vizuzik's own Activity/webview never runs this session, and this service
        // is the one component guaranteed alive whenever notification access is granted.
        LockScreenVisualizerController.getInstance().init(getApplicationContext());
        DeezerMediaBridge.getInstance().addListener(LockScreenVisualizerController.getInstance());
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
        rewatch(null);
        detachController();
        // clear() before removeListener(): EdgeOverlayController needs this "nothing playing"
        // notification to stop OverlayEdgeGlowService — if it were unregistered first, it would
        // never learn playback stopped and could leave the overlay running indefinitely, with
        // nothing else around (MainActivity may never have run this session) to correct it.
        DeezerMediaBridge.getInstance().clear();
        DeezerMediaBridge.getInstance().removeListener(EdgeOverlayController.getInstance());
        DeezerMediaBridge.getInstance().removeListener(LockScreenVisualizerController.getInstance());
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

    // Sessions of non-active apps we watch so that one starting to play takes over (a session's
    // state change does not fire OnActiveSessionsChanged).
    private final java.util.Map<MediaController, MediaController.Callback> watchers = new java.util.HashMap<>();

    private void updateActiveSession(List<MediaController> controllers) {
        boolean auto = MusicAppPreference.isAuto(this);
        String targetPackage = MusicAppPreference.getPackage(this);
        MediaController matched = null;
        if (controllers != null) {
            if (auto) {
                // Any music app: the one playing wins, then the one already followed, then the
                // first other session.
                MediaController fallback = null;
                for (MediaController controller : controllers) {
                    if (!MusicApps.isTrackable(this, controller.getPackageName())) continue;
                    PlaybackState state = controller.getPlaybackState();
                    if (state != null && state.getState() == PlaybackState.STATE_PLAYING) {
                        matched = controller;
                        break;
                    }
                    if (activeController != null && controller.getSessionToken().equals(activeController.getSessionToken())) {
                        fallback = controller;
                    } else if (fallback == null) {
                        fallback = controller;
                    }
                }
                if (matched == null) matched = fallback;
            } else {
                for (MediaController controller : controllers) {
                    String packageName = controller.getPackageName();
                    if (packageName != null && packageName.equals(targetPackage)) {
                        matched = controller;
                        break;
                    }
                }
            }
            rewatch(auto ? controllers : null);
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
        if (auto) MusicAppPreference.setPackage(this, matched.getPackageName());
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

    private void rewatch(List<MediaController> controllers) {
        for (java.util.Map.Entry<MediaController, MediaController.Callback> e : watchers.entrySet()) {
            e.getKey().unregisterCallback(e.getValue());
        }
        watchers.clear();
        if (controllers == null || mediaSessionManager == null) return;
        for (MediaController controller : controllers) {
            if (!MusicApps.isTrackable(this, controller.getPackageName())) continue;
            MediaController.Callback cb = new MediaController.Callback() {
                @Override
                public void onPlaybackStateChanged(PlaybackState state) {
                    if (state == null || state.getState() != PlaybackState.STATE_PLAYING) return;
                    if (activeController != null && controller.getSessionToken().equals(activeController.getSessionToken())) return;
                    try {
                        updateActiveSession(mediaSessionManager.getActiveSessions(
                            new ComponentName(NowPlayingListenerService.this, NowPlayingListenerService.class)));
                    } catch (SecurityException ignored) {
                    }
                }
            };
            controller.registerCallback(cb);
            watchers.put(controller, cb);
        }
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
