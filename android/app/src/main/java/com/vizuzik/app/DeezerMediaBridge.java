package com.vizuzik.app;

import android.graphics.Bitmap;
import android.media.session.MediaController;

/**
 * In-process singleton shared between NowPlayingListenerService (which tracks Deezer's
 * MediaSession) and DeezerMediaPlugin (which exposes it to the web layer). Both run in the
 * app's default process, so a static holder is enough — no IPC needed.
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

        NowPlaying(String title, String artist, String album, Bitmap albumArt, boolean isPlaying) {
            this.title = title;
            this.artist = artist;
            this.album = album;
            this.albumArt = albumArt;
            this.isPlaying = isPlaying;
        }
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
