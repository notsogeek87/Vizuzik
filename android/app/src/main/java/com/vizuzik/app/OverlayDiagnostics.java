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

    /** What the last window layout asked for: "full" (whole screen, capped alpha) or "small" (just
     *  the record, opaque). See EdgeGlowView.updateWindowBounds() for why those are the two. */
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

    // --- Written by EdgeGlowView, once per tick ---

    static volatile String styleSelected = "?";
    static volatile String styleActive = "?";
    static volatile boolean suppressed;
    static volatile boolean foregroundKnown;
    static volatile boolean trackedAppOnScreen;
    static volatile String foregroundPackage = "";
    static volatile boolean viewVisible;

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

    private OverlayDiagnostics() {}
}
