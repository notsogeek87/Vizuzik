package com.vizuzik.app;

import android.graphics.Bitmap;
import android.media.session.MediaController;
import android.media.session.PlaybackState;
import android.os.SystemClock;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-process singleton shared between NowPlayingListenerService (which tracks the currently
 * targeted app's MediaSession — Deezer or Spotify, see MusicAppPreference) and its two
 * consumers, DeezerMediaPlugin (exposes it to the web layer) and OverlayEdgeGlowService (drives
 * the edge-glow overlay drawn over the tracked app itself). All run in the app's default
 * process, so a static holder is enough — no IPC needed.
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
    // A Set, not a single field: the plugin (web layer) and the overlay service both need every
    // update, at the same time, without either displacing the other the way a single mutable
    // field would. Copy-on-write because updates are rare (a track or state change) next to how
    // often a listener set membership is merely iterated.
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private DeezerMediaBridge() {}

    synchronized void addListener(Listener listener) {
        listeners.add(listener);
        listener.onNowPlayingChanged(lastNowPlaying);
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

    synchronized NowPlaying getLastNowPlaying() {
        return lastNowPlaying;
    }

    synchronized void updateNowPlaying(NowPlaying nowPlaying) {
        this.lastNowPlaying = nowPlaying;
        for (Listener listener : listeners) {
            listener.onNowPlayingChanged(nowPlaying);
        }
    }

    synchronized void clear() {
        this.controller = null;
        this.lastNowPlaying = null;
        for (Listener listener : listeners) {
            listener.onNowPlayingChanged(null);
        }
    }
}
