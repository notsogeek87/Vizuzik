package com.vizuzik.app;

import android.graphics.Bitmap;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;
import android.util.Log;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-process singleton shared between NowPlayingListenerService (which tracks the currently
 * targeted app's MediaSession — Deezer or Spotify, see MusicAppPreference) and its listeners.
 * Both run in the app's default process, so a static holder is enough — no IPC needed.
 *
 * More than one listener at a time: DeezerMediaPlugin (exposing it to the web layer) and
 * OverlayEdgeGlowService (the Edge Visualizer background overlay, which needs the track's artwork
 * for its glow color) both need the same now-playing state, in parallel.
 */
final class DeezerMediaBridge {

    private static final String TAG = "DeezerMediaBridge";

    interface Listener {
        void onNowPlayingChanged(NowPlaying nowPlaying);
    }

    static final class NowPlaying {
        final String title;
        final String artist;
        final String album;
        final Bitmap albumArt;
        final boolean isPlaying;
        /** Track length in ms, or 0 when the app's metadata doesn't report one (live streams). */
        final long durationMs;
        /** Playback position in ms, resolved to the instant this object was built. */
        final long positionMs;
        final boolean canSeek;

        NowPlaying(
            String title,
            String artist,
            String album,
            Bitmap albumArt,
            boolean isPlaying,
            long durationMs,
            long positionMs,
            boolean canSeek
        ) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.albumArt = albumArt;
            this.isPlaying = isPlaying;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
            this.canSeek = canSeek;
        }
    }

    /**
     * A PlaybackState carries the position as of the last time the session updated it, not as of
     * now — so while playing it has to be extrapolated forward from that timestamp, otherwise the
     * progress bar would sit still between the session's occasional updates.
     */
    static long resolvePosition(PlaybackState state) {
        if (state == null) {
            return 0;
        }
        long position = state.getPosition();
        long updatedAt = state.getLastPositionUpdateTime();
        if (state.getState() == PlaybackState.STATE_PLAYING && updatedAt > 0) {
            float speed = state.getPlaybackSpeed();
            if (speed <= 0) {
                speed = 1f;
            }
            position += (long) ((SystemClock.elapsedRealtime() - updatedAt) * speed);
        }
        return Math.max(0, position);
    }

    static boolean canSeek(PlaybackState state) {
        return state != null && (state.getActions() & PlaybackState.ACTION_SEEK_TO) != 0;
    }

    private static final DeezerMediaBridge INSTANCE = new DeezerMediaBridge();

    static DeezerMediaBridge getInstance() {
        return INSTANCE;
    }

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private MediaController controller;
    private volatile NowPlaying lastNowPlaying;

    private DeezerMediaBridge() {}

    /** Immediately replays the last known state to a freshly-added listener — same behavior the
     *  old single-listener setListener() had — so a service that starts after a track is already
     *  playing doesn't have to wait for the next change to find out about it. */
    void addListener(Listener listener) {
        listeners.add(listener);
        notifyListener(listener, lastNowPlaying);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    synchronized void setController(MediaController controller) {
        this.controller = controller;
    }

    synchronized MediaController getController() {
        return controller;
    }

    NowPlaying getLastNowPlaying() {
        return lastNowPlaying;
    }

    void updateNowPlaying(NowPlaying nowPlaying) {
        this.lastNowPlaying = nowPlaying;
        for (Listener listener : listeners) {
            notifyListener(listener, nowPlaying);
        }
    }

    synchronized void clear() {
        this.controller = null;
        this.lastNowPlaying = null;
        for (Listener listener : listeners) {
            notifyListener(listener, null);
        }
    }

    /**
     * More than one listener means one listener's bug must never stop another from hearing
     * about a track change — DeezerMediaPlugin (the web bridge) and OverlayEdgeGlowService (the
     * Edge Visualizer) are both registered here and neither owns the other's reliability. This
     * runs on the main thread (MediaController.Callback dispatch), so an uncaught exception here
     * would otherwise crash the whole app over one bad listener.
     */
    private void notifyListener(Listener listener, NowPlaying nowPlaying) {
        try {
            listener.onNowPlayingChanged(nowPlaying);
        } catch (Exception e) {
            Log.w(TAG, "onNowPlayingChanged", e);
        }
    }
}
