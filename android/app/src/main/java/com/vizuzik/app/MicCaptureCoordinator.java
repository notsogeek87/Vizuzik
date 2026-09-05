package com.vizuzik.app;

import java.util.Set;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * The single owner of the phone's microphone for the whole app, and the reason it exists: two
 * different components want mic levels at two different moments — DeezerMediaPlugin while the
 * full-screen player is on screen, OverlayEdgeGlowService while it isn't — and the handoff
 * between them happens exactly at a backgrounding, with both native calls in flight at once.
 * Each opening its own AudioRecord meant one of the two losing that race and failing silently:
 * the glow going static on stale levels on the way out, the full-screen spectrum freezing on the
 * way back in.
 *
 * So neither of them opens an AudioRecord any more. Both just register here; the single
 * MicCaptureThread is started on the first listener and stopped after the last one leaves, and
 * every listener gets the same levels. Same in-process fan-out idea as AudioLevelsBridge, which
 * does this for the "real capture" source — this is its microphone counterpart.
 */
final class MicCaptureCoordinator {

    interface Listener {
        void onLevels(float[] levels);
    }

    private static final MicCaptureCoordinator INSTANCE = new MicCaptureCoordinator();

    static MicCaptureCoordinator getInstance() {
        return INSTANCE;
    }

    // Copy-on-write: publish() runs on the capture thread at ~20Hz and must never block on a
    // listener being added or removed.
    private final Set<Listener> listeners = new CopyOnWriteArraySet<>();
    private MicCaptureThread thread;

    private MicCaptureCoordinator() {}

    /**
     * Registers a listener, starting the shared capture if it isn't running yet. Callers must
     * hold RECORD_AUDIO already — this never prompts, having no Activity to prompt from.
     *
     * @return false when the microphone couldn't be opened at all (permission missing, or the
     *         device refused AudioSource.MIC); the listener is not registered in that case, so a
     *         caller that got false has nothing to unregister later.
     */
    synchronized boolean addListener(Listener listener) {
        if (thread != null) {
            listeners.add(listener);
            return true;
        }
        MicCaptureThread starting = new MicCaptureThread(this::publish);
        if (!starting.prepare()) {
            return false;
        }
        listeners.add(listener);
        thread = starting;
        starting.start();
        return true;
    }

    /** Stops the shared capture once the last listener leaves — the mic is never held idle. */
    synchronized void removeListener(Listener listener) {
        listeners.remove(listener);
        if (!listeners.isEmpty() || thread == null) {
            return;
        }
        MicCaptureThread stopping = thread;
        thread = null;
        stopping.stopCapture();
    }

    private void publish(float[] levels) {
        for (Listener listener : listeners) {
            listener.onLevels(levels);
        }
    }
}
