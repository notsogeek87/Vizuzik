package com.vizuzik.app;

import android.content.Context;

/**
 * The app's single audio source, and the only one: a Visualizer attached to the tracked music
 * app's own audio session (TrackedSessionAudioSource), published into AudioLevelsBridge where both
 * consumers already listen — DeezerMediaPlugin streaming to the full-screen visualizer, and
 * OverlayEdgeGlowService drawing the edge effect over the other app.
 *
 * There used to be three selectable sources, cycled from a badge in the player: a real microphone
 * (MicCaptureThread), the tracked app's output via MediaProjection (AudioCaptureService), and
 * "off", which left the visualizer running its own invented ambient animation. All three are gone.
 * Two of them were poor substitutes for hearing the music — the microphone hears the room, and
 * MediaProjection made Android show its alarming "start recording your screen" dialog on every
 * single launch, with no memory of past grants. The third answered "is this reacting to the music?"
 * with a convincing animation that had nothing to do with the music at all. Attaching to the
 * player's own session needs neither a dialog nor a compromise: only RECORD_AUDIO, granted once.
 *
 * Started from NowPlayingListenerService, so it is running for as long as notification access is
 * granted — including when Vizuzik's own Activity has never been launched. Nothing is captured
 * until the tracked app actually opens an audio session, so idling here costs nothing.
 */
final class TrackedAudioCapture implements TrackedSessionAudioSource.Listener {

    private static final TrackedAudioCapture INSTANCE = new TrackedAudioCapture();

    static TrackedAudioCapture getInstance() {
        return INSTANCE;
    }

    private TrackedSessionAudioSource source;

    private TrackedAudioCapture() {}

    /** Idempotent — called from whichever component comes up first in this process. */
    synchronized void start(Context context) {
        if (source != null) return;
        source = new TrackedSessionAudioSource(context, this);
        source.start();
    }

    /**
     * Stops capturing for good. Called when notification access goes away, which is the honest end
     * of this source's usefulness: without it there is no tracked session to follow and no way to
     * learn about the next one. An attached Visualizer holds RECORD_AUDIO in continuous use and
     * runs ~30 FFT callbacks a second, so it must not simply be left running for the life of the
     * process.
     */
    synchronized void stop() {
        if (source == null) return;
        source.stop();
        source = null;
    }

    /**
     * RECORD_AUDIO has just been granted. The source silently declines to attach without it (it
     * runs in a background service with no Activity to ask from), so without this the effect would
     * stay dead until the tracked app happened to open its *next* session — which, for someone who
     * just granted the permission while a track was already playing, reads as the grant having
     * done nothing.
     */
    synchronized void onAudioPermissionGranted() {
        if (source != null) source.retryAttach();
    }

    @Override
    public void onLevels(float[] levels) {
        AudioLevelsBridge.getInstance().publishLevels(levels);
    }

    @Override
    public void onSourceLost() {
        AudioLevelsBridge.getInstance().publishStopped();
    }
}
