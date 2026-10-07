package com.vizuzik.app;

import android.graphics.Rect;
import android.os.SystemClock;

/**
 * Whether the tracked music app's own full-screen "now playing" player is currently what's on
 * screen — the one thing DeezerPlayerAccessibilityService exists to answer.
 *
 * Kept here, separate from the service itself, so EdgeGlowView can read the answer without caring
 * whether a live instance of the service happens to be bound at this exact moment — accessibility
 * services are rebound by the system for all sorts of reasons unrelated to anything this app does
 * (the accessibility settings screen being opened at all, for instance), and a decorative style
 * falling back because of that churn rather than a real "not on the player screen" answer would
 * be exactly the kind of guess this app otherwise refuses to hide anything on.
 */
final class NowPlayerScreenState {

    /** How long a published rectangle stays believable. The service re-scans about once a second
     *  while the player is up; past this, nothing is left to say the window is still where it
     *  was (events from a tracked app that has left the screen are never delivered), so a stale
     *  rectangle must read as no rectangle rather than as a place to draw. */
    private static final long BOUNDS_MAX_AGE_MS = 3000;

    private static volatile boolean onPlayerScreen;
    private static volatile boolean serviceConnected;
    // Never mutated once published — replaced wholesale, so a reader on another thread always
    // sees a complete rectangle.
    private static volatile Rect windowBounds;
    private static volatile Rect artworkBounds;
    private static volatile long boundsAtMs;

    /** True only when the service is bound *and* it last reported the player screen. Never true
     *  on a guess: see isServiceConnected() below for the field this leans on. */
    static boolean isOnPlayerScreen() {
        return serviceConnected && onPlayerScreen && !isStale();
    }

    /** Whether there is a live instance to have answered at all — the settings panel's
     *  "requirePlayerScreen" only ever restricts anything once this is true; see EdgeGlowView's
     *  activeStyle(). */
    static boolean isServiceConnected() {
        return serviceConnected;
    }

    static void setOnPlayerScreen(boolean value) {
        onPlayerScreen = value;
    }

    /**
     * One scan's complete answer. {@code window} is where the tracked app's own window sits on
     * screen (null: none was found) — in split-screen that is only a pane, not the display — and
     * {@code artwork} the cover measured inside it when the player is showing (null: not measured,
     * fall back to modelling it within {@code window}).
     */
    static void publish(boolean onPlayer, Rect window, Rect artwork) {
        windowBounds = window != null ? new Rect(window) : null;
        artworkBounds = onPlayer && artwork != null ? new Rect(artwork) : null;
        boundsAtMs = SystemClock.elapsedRealtime();
        onPlayerScreen = onPlayer;
    }

    /** The tracked app's window on screen, or null when unknown or out of date. */
    static Rect trackedWindowBounds() {
        Rect r = windowBounds;
        if (!serviceConnected || r == null || isStale()) return null;
        return r;
    }

    /** The cover as measured on screen while the player is showing, or null — never a guess. */
    static Rect measuredArtworkBounds() {
        Rect r = artworkBounds;
        if (!isOnPlayerScreen() || r == null || isStale()) return null;
        return r;
    }

    private static boolean isStale() {
        return SystemClock.elapsedRealtime() - boundsAtMs > BOUNDS_MAX_AGE_MS;
    }

    /** Also clears onPlayerScreen on disconnect: a stale "yes" surviving past the service that
     *  reported it is worse than losing the answer, since nothing is left to ever correct it. */
    static void setServiceConnected(boolean value) {
        serviceConnected = value;
        if (!value) {
            onPlayerScreen = false;
            windowBounds = null;
            artworkBounds = null;
        }
    }

    private NowPlayerScreenState() {}
}
