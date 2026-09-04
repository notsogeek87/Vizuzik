package com.vizuzik.app;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * In-process singleton shared between AudioCaptureService (which analyzes Deezer's own audio
 * output) and its two consumers, DeezerMediaPlugin (streams the spectrum to the web layer) and
 * OverlayEdgeGlowService (drives the edge-glow overlay). Mirrors DeezerMediaBridge's pattern:
 * all run in the app's default process, so a static holder is enough — no IPC needed.
 */
final class AudioLevelsBridge {

    interface Listener {
        void onLevels(float[] levels);
        void onCaptureStopped();
    }

    private static final AudioLevelsBridge INSTANCE = new AudioLevelsBridge();

    static AudioLevelsBridge getInstance() {
        return INSTANCE;
    }

    // Both consumers need every update as it happens, not whichever registered last — see
    // DeezerMediaBridge.listeners for the same reasoning. Copy-on-write: publishLevels() runs on
    // the capture thread at ~20Hz and must never block on a listener being added/removed.
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
            listener.onLevels(levels);
        }
    }

    void publishStopped() {
        capturing = false;
        for (Listener listener : listeners) {
            listener.onCaptureStopped();
        }
    }
}
