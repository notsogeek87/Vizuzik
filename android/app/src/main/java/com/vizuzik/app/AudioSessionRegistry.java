package com.vizuzik.app;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.media.audiofx.AudioEffect;
import android.os.Build;
import android.util.Log;

import java.util.LinkedHashSet;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Remembers which audio session the tracked music app currently has open, so that whoever wants to
 * capture from it can ask at any moment rather than having to be listening at exactly the right
 * one.
 *
 * ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION is a one-shot announcement: a player broadcasts it the
 * moment it opens its audio session, and never repeats it. TrackedSessionAudioSource originally
 * registered for it itself, from inside OverlayEdgeGlowService — a service that only starts once
 * the user has *already* switched away to the music app, long after that broadcast went out. It
 * therefore never heard one on a normal switch, never attached a Visualizer, and the overlay spent
 * its whole life in EdgeGlowView's ambient regime: a border breathing on three slow sine waves,
 * which is exactly the "it grows and shrinks but not in time with anything" that kept being
 * reported. The bars style has no ambient regime to hide behind, so the same bug reads as a
 * completely blank overlay — which is what confirmed it.
 *
 * The receiver therefore has to outlive any one consumer. This registry is started from
 * NowPlayingListenerService, which the system keeps bound for as long as notification access is
 * granted — so it is already listening while the user is still in Vizuzik, well before Deezer is
 * ever opened. It is deliberately never unregistered: it holds only a couple of ints, and the whole
 * point is to still know the session id whenever a consumer next starts.
 *
 * Known limit: starting early narrows the window but cannot close it. If this process comes up
 * while the tracked app is *already* playing — killed and restarted mid-playback, or notification
 * access granted for the first time during a track — the open broadcast is already gone and there
 * is no API to ask another app what session id it is using, so there is nothing to attach to until
 * the next track starts. EdgeGlowView treats that as "not live", the same as any other silence.
 */
final class AudioSessionRegistry {

    interface Listener {
        /** @param sessionId the tracked app's open session, or -1 once it closes. */
        void onAudioSessionChanged(int sessionId);
    }

    private static final String TAG = "AudioSessionRegistry";
    private static final AudioSessionRegistry INSTANCE = new AudioSessionRegistry();

    static AudioSessionRegistry getInstance() {
        return INSTANCE;
    }

    private final CopyOnWriteArraySet<Listener> listeners = new CopyOnWriteArraySet<>();
    // Every session a tracked app currently has open, oldest first — not just the newest one. A
    // player can hold more than one at a time (an ad or a preview alongside the music, a route
    // switch opening the next before closing the last); keeping only the most recent would mean
    // the short-lived one's close event reports "nothing playing" and, because the open broadcast
    // is one-shot, the still-playing original could never be found again.
    private final LinkedHashSet<Integer> openSessions = new LinkedHashSet<>();
    private volatile int sessionId = -1;
    private boolean started;

    private final BroadcastReceiver receiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            onSessionEvent(intent);
        }
    };

    private AudioSessionRegistry() {}

    /** Idempotent — called from whichever component happens to come up first in this process. */
    synchronized void start(Context context) {
        if (started) return;
        IntentFilter filter = new IntentFilter();
        filter.addAction(AudioEffect.ACTION_OPEN_AUDIO_EFFECT_CONTROL_SESSION);
        filter.addAction(AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION);
        try {
            // Broadcast by the music app's own process rather than the system, so API 33+ requires
            // saying so explicitly or registerReceiver() throws outright on an API 34+ target.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.getApplicationContext().registerReceiver(receiver, filter, Context.RECEIVER_EXPORTED);
            } else {
                context.getApplicationContext().registerReceiver(receiver, filter);
            }
            started = true;
        } catch (Exception e) {
            // Leaves started false so a later caller retries; capture simply stays unavailable
            // until then, which EdgeGlowView already handles as "not live".
            Log.w(TAG, "registerReceiver", e);
        }
    }

    /** The tracked app's currently open session, or -1 if none is known right now. */
    int currentSessionId() {
        return sessionId;
    }

    void addListener(Listener listener) {
        listeners.add(listener);
    }

    void removeListener(Listener listener) {
        listeners.remove(listener);
    }

    private void onSessionEvent(Intent intent) {
        int id = intent.getIntExtra(AudioEffect.EXTRA_AUDIO_SESSION, -1);
        String packageName = intent.getStringExtra(AudioEffect.EXTRA_PACKAGE_NAME);
        if (id <= 0 || packageName == null || !MusicApps.isKnownPackage(packageName)) return;

        int updated;
        synchronized (this) {
            if (AudioEffect.ACTION_CLOSE_AUDIO_EFFECT_CONTROL_SESSION.equals(intent.getAction())) {
                openSessions.remove(id);
            } else {
                // Re-inserted at the end so the newest session is the one handed out, which is
                // what a track change or a route switch should follow.
                openSessions.remove(id);
                openSessions.add(id);
            }
            updated = newestOpenSession();
            if (updated == sessionId) return;
            sessionId = updated;
        }
        for (Listener listener : listeners) {
            listener.onAudioSessionChanged(updated);
        }
    }

    /** Caller must hold this object's monitor. */
    private int newestOpenSession() {
        int newest = -1;
        for (int open : openSessions) newest = open;
        return newest;
    }
}
