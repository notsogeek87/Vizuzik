package com.vizuzik.app;

import android.util.Log;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-process singleton shared between AudioCaptureService (which analyzes the tracked app's own
 * audio output) and its listeners. Mirrors DeezerMediaBridge's pattern: both run in the app's
 * default process, so a static holder is enough — no IPC needed.
 *
 * More than one listener at a time: DeezerMediaPlugin (streaming to the web layer) and
 * OverlayEdgeGlowService (the Edge Visualizer background overlay) both need the same levels, in
 * parallel — the overlay exists precisely for the moments the web layer isn't in the foreground,
 * so neither can simply replace the other the way a single-listener field would.
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
    // Written by AudioCaptureService, read by DeezerMediaPlugin from the web layer's thread:
    // volatile rather than synchronized so a state query can never block on a capture callback.
    private volatile boolean capturing;

    private AudioLevelsBridge() {}

    /**
     * Whether AudioCaptureService currently holds a live MediaProjection. The web layer asks on
     * every resume: the answer is what lets it skip re-requesting a consent it already has, since
     * its own JS state is lost whenever the webview is recreated but the service isn't.
     */
    boolean isCapturing() {
        return capturing;
    }

    void markCapturing() {
        capturing = true;
    }

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
        capturing = false;
        for (Listener listener : listeners) {
            try {
                listener.onCaptureStopped();
            } catch (Exception e) {
                Log.w(TAG, "onCaptureStopped", e);
            }
        }
    }
}
