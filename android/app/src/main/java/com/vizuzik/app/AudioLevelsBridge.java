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

    private AudioLevelsBridge() {}

    synchronized void setListener(Listener listener) {
        this.listener = listener;
    }

    synchronized void publishLevels(float[] levels) {
        if (listener != null) {
            listener.onLevels(levels);
        }
    }

    synchronized void publishStopped() {
        if (listener != null) {
            listener.onCaptureStopped();
        }
    }
}
