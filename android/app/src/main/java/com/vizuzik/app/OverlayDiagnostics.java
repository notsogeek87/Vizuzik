package com.vizuzik.app;

import android.os.SystemClock;

/**
 * What the overlay is actually doing right now, as opposed to what the code that wrote it believes
 * it should be doing.
 *
 * This exists because three rounds of "the disc is still see-through" and "it still doesn't notice
 * I left the player" were answered with fixes made blind — from screenshots, without a device, an
 * emulator, or a logcat — and none of them moved the pixels. There is no way to tell, from here,
 * whether the window really did shrink to full opacity, whether the accessibility service is even
 * bound, or whether the build being tested contains the change at all; every one of those questions
 * has a definite answer on the phone, and none of them was reachable. So the phone answers them:
 * the three pieces that know (the overlay service, the view, the accessibility service) each drop
 * their state here as they run, and the settings panel shows it, verbatim, to be screenshotted.
 *
 * Deliberately plain volatile fields rather than anything synchronised: every writer is on the main
 * thread or the accessibility service's own callback thread, every reader is the settings panel
 * asking once a second, and a snapshot that mixes two moments' values is not a problem for
 * something whose whole purpose is to be read by a person. Nothing here is persisted, sent
 * anywhere, or used to make a decision — it is only ever displayed.
 */
final class OverlayDiagnostics {

    // --- Written by OverlayEdgeGlowService, on every window change it asks WindowManager for ---

    /** What the last window layout asked for: "full" (whole screen) or "small" (just the record) —
     *  both at the same capped alpha. See EdgeGlowView.updateWindowBounds() for why those are the
     *  two, and OverlayEdgeGlowService.touchSafeAlpha for why "small" doesn't get to be any more
     *  opaque than "full" is. */
    static volatile String windowMode = "?";
    /** The alpha the service *asked* for, and the one actually on the view's params afterwards —
     *  kept apart because the whole "still translucent" question is precisely whether those two
     *  ever differ. */
    static volatile float windowAlphaWanted = Float.NaN;
    static volatile float windowAlphaApplied = Float.NaN;
    static volatile int windowX;
    static volatile int windowY;
    static volatile int windowWidth;
    static volatile int windowHeight;
    /** The cap the platform itself reports for touch-through opacity — asked rather than assumed,
     *  so a device that sets its own is visible here instead of being guessed at from a pixel. */
    static volatile float touchOpacityMax = Float.NaN;
    /** Whatever went wrong in the last attempt to re-lay-out the window, or "" if nothing did.
     *  A remove/add pair that throws leaves the window at its previous alpha and is otherwise
     *  completely silent — which is exactly the failure that would look like "nothing changed". */
    static volatile String windowError = "";
    static volatile boolean serviceRunning;
    /** Whether the window currently consumes touches in its own area. Always false: the small
     *  vinyl window used to briefly try this, and it also meant Deezer's own left/right
     *  skip-track swipe over the cover never reached Deezer — see onWindowBoundsWanted(). */
    static volatile boolean windowTouchable;

    // --- Written by EdgeGlowView: what the system actually did, not what was asked for ---

    /**
     * The view's own measured size and position on screen.
     *
     * Everything in the group above is an echo: fields this app set on a LayoutParams and then
     * read back off the same object, which will agree with itself whether or not WindowManager
     * ever acted on them. "Opacité du vinyle 1.00" proves only that 1.00 was asked for. These four
     * are measured after layout, by the view, from the window the system actually gave it — so a
     * resize that was requested and silently ignored reads here as the old full-screen size, and
     * one that really happened reads as the record's own square.
     */
    static volatile int viewWidth;
    static volatile int viewHeight;
    static volatile int viewLeft;
    static volatile int viewTop;

    // --- Written by EdgeGlowView, once per tick ---

    static volatile String styleSelected = "?";
    static volatile String styleActive = "?";
    static volatile boolean suppressed;
    static volatile boolean foregroundKnown;
    static volatile boolean trackedAppOnScreen;
    static volatile String foregroundPackage = "";
    static volatile boolean viewVisible;
    /** The two settings that decide when these styles are allowed at all. "Le disque est visible
     *  hors lecture" has two completely different causes — the restriction is off, or it is on and
     *  the scan says yes anyway — and nothing in this panel could tell them apart. */
    static volatile boolean requirePlayerScreen;
    static volatile boolean onlyOverMusicApp;

    // --- Written by DeezerPlayerAccessibilityService, once per scan it is allowed to run ---

    /** Rising count, not a boolean: "0" says the service never ran a scan (never granted, or
     *  granted but never sent an event for these packages), which is a completely different
     *  problem from a scan that runs and answers "no". */
    static volatile int scanCount;
    static volatile boolean scanSawWideSeekBar;
    static volatile boolean scanSawLargeArtwork;
    static volatile int scanNodesVisited;
    /** The widest SeekBar and tallest centred Image the last scan saw, as fractions of the window,
     *  whether or not they cleared their thresholds — so a heuristic that is merely mis-tuned can
     *  be told apart from one looking at a tree that has neither. */
    static volatile float scanWidestSeekBarFraction;
    static volatile float scanTallestImageFraction;
    static volatile float scanTallestImageOffsetFraction;
    /** Which app's window the last scan actually walked. The first real reading off a device said
     *  "23 nœuds" — a tree far too small to be Deezer's player — which is how it came out that a
     *  scan can be handed Vizuzik's own window instead of the one the event came from. */
    static volatile String scanPackage = "";
    /** Whether the walk ran out of its node budget before finishing. A walk that stopped early and
     *  a walk that finished and found nothing answer "no" identically. */
    static volatile boolean scanBudgetExhausted;
    private static volatile long lastScanAtMs;

    /** Milliseconds since the last scan, or -1 if there has never been one. */
    static long msSinceLastScan() {
        long at = lastScanAtMs;
        return at == 0 ? -1 : SystemClock.elapsedRealtime() - at;
    }

    static void markScan() {
        scanCount++;
        lastScanAtMs = SystemClock.elapsedRealtime();
    }

    // --- Latched: the last moment "vinyl" was really the style being drawn ---

    /**
     * Everything above describes right now — and right now, whenever anyone can read it, Vizuzik
     * itself is in the foreground, which is exactly when the overlay service is stopped and the
     * style has fallen back to bars. The first reading off a device said "Service surcouche:
     * arrêté / vinyl → bars", which is correct and completely useless: the state worth seeing only
     * exists while the phone is showing Deezer and nobody is looking at this panel.
     *
     * So it is kept. These are copies of the window's state taken at the last tick where "vinyl"
     * was genuinely what was being drawn, and they survive the overlay stopping — which makes
     * "was the record's window actually opaque?" answerable after the fact, from the settings
     * screen, which is the only place it can ever be asked from.
     */
    static volatile String vinylWindowMode = "";
    static volatile float vinylWindowAlpha = Float.NaN;
    static volatile int vinylWindowWidth;
    static volatile int vinylWindowHeight;
    static volatile int vinylWindowX;
    static volatile int vinylWindowY;
    static volatile boolean vinylWindowTouchable;
    static volatile int vinylViewWidth;
    static volatile int vinylViewHeight;
    static volatile int vinylViewLeft;
    static volatile int vinylViewTop;
    private static volatile long lastVinylAtMs;

    static long msSinceVinyl() {
        long at = lastVinylAtMs;
        return at == 0 ? -1 : SystemClock.elapsedRealtime() - at;
    }

    static void latchVinyl() {
        vinylWindowMode = windowMode;
        vinylWindowAlpha = windowAlphaApplied;
        vinylWindowWidth = windowWidth;
        vinylWindowHeight = windowHeight;
        vinylWindowX = windowX;
        vinylWindowY = windowY;
        vinylWindowTouchable = windowTouchable;
        vinylViewWidth = viewWidth;
        vinylViewHeight = viewHeight;
        vinylViewLeft = viewLeft;
        vinylViewTop = viewTop;
        lastVinylAtMs = SystemClock.elapsedRealtime();
    }

    private OverlayDiagnostics() {}
}
