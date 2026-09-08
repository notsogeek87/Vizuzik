package com.vizuzik.app;

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

    private static volatile boolean onPlayerScreen;
    private static volatile boolean serviceConnected;

    /** True only when the service is bound *and* it last reported the player screen. Never true
     *  on a guess: see isServiceConnected() below for the field this leans on. */
    static boolean isOnPlayerScreen() {
        return serviceConnected && onPlayerScreen;
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

    /** Also clears onPlayerScreen on disconnect: a stale "yes" surviving past the service that
     *  reported it is worse than losing the answer, since nothing is left to ever correct it. */
    static void setServiceConnected(boolean value) {
        serviceConnected = value;
        if (!value) onPlayerScreen = false;
    }

    private NowPlayerScreenState() {}
}
