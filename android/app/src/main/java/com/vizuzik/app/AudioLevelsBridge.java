package com.vizuzik.app;

/**
 * In-process singleton shared between AudioCaptureService (which analyzes Deezer's own audio
 * output) and DeezerMediaPlugin (which streams the resulting spectrum to the web layer). Mirrors
 * DeezerMediaBridge's pattern: both run in the app's default process, so a static holder is
 * enough — no IPC needed.
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

    private Listener listener;
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

    synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    synchronized void publishLevels(float[] levels) {
        if (listener != null) {
            listener.onLevels(levels);
        }
    }

    synchronized void publishStopped() {
        capturing = false;
        if (listener != null) {
            listener.onCaptureStopped();
        }
    }
}
