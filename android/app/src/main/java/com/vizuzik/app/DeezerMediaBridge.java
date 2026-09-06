package com.vizuzik.app;

import android.graphics.Bitmap;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;

/**
 * In-process singleton shared between NowPlayingListenerService (which tracks the currently
 * targeted app's MediaSession — Deezer or Spotify, see MusicAppPreference) and DeezerMediaPlugin
 * (which exposes it to the web layer). Both run in the app's default process, so a static holder
 * is enough — no IPC needed.
 */
final class DeezerMediaBridge {

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

    private MediaController controller;
    private NowPlaying lastNowPlaying;
    private Listener listener;

    private DeezerMediaBridge() {}

    synchronized void setListener(Listener listener) {
        this.listener = listener;
        if (listener != null) {
            listener.onNowPlayingChanged(lastNowPlaying);
        }
    }

    synchronized void setController(MediaController controller) {
        this.controller = controller;
    }

    synchronized MediaController getController() {
        return controller;
    }

    synchronized NowPlaying getLastNowPlaying() {
        return lastNowPlaying;
    }

    synchronized void updateNowPlaying(NowPlaying nowPlaying) {
        this.lastNowPlaying = nowPlaying;
        if (listener != null) {
            listener.onNowPlayingChanged(nowPlaying);
        }
    }

    synchronized void clear() {
        this.controller = null;
        this.lastNowPlaying = null;
        if (listener != null) {
            listener.onNowPlayingChanged(null);
        }
    }
}
