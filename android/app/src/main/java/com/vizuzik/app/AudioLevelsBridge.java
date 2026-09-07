package com.vizuzik.app;

import android.util.Log;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-process singleton carrying the loudness spectrum from TrackedAudioCapture — the app's one
 * audio source — to everything that draws it. Mirrors DeezerMediaBridge's pattern: it all runs in
 * the app's default process, so a static holder is enough, no IPC needed.
 *
 * More than one listener at a time: DeezerMediaPlugin (streaming to the full-screen visualizer)
 * and OverlayEdgeGlowService (the edge effect over the other app) both need the same levels, in
 * parallel — the overlay exists precisely for the moments the web layer isn't in the foreground,
 * so neither can simply replace the other the way a single-listener field would. Sharing one
 * source between them is also what lets a single Visualizer serve both without either having to
 * know the other exists.
 */
final class AudioLevelsBridge {

    private static final String TAG = "AudioLevelsBridge";

    interface Listener {
        void onLevels(float[] levels);
        void onCaptureStopped();
    }

    private static final AudioLevelsBridge INSTANCE = new AudioLevelsBridge();

    static AudioLevelsBridge getInstance() {
        return INSTANCE;
    }

    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();

    private AudioLevelsBridge() {}

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    void publishLevels(float[] levels) {
        for (Listener listener : listeners) {
            // More than one listener means one listener's bug must never stop the capture
            // thread from reaching the other, or take the whole app down with it — see
            // DeezerMediaBridge.notifyListener() for the same reasoning.
            try {
                listener.onLevels(levels);
            } catch (Exception e) {
                Log.w(TAG, "onLevels", e);
            }
        }
    }

    void publishStopped() {
        for (Listener listener : listeners) {
            try {
                listener.onCaptureStopped();
            } catch (Exception e) {
                Log.w(TAG, "onCaptureStopped", e);
            }
        }
    }
}
