package com.vizuzik.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Rect;
import android.os.SystemClock;
import android.provider.Settings;
import android.text.TextUtils;
import android.util.Log;
import android.view.accessibility.AccessibilityEvent;
import android.view.accessibility.AccessibilityNodeInfo;

/**
 * Off by default, and asked for separately from every other permission this app uses: the one way
 * Vizuzik can tell Deezer's own full-screen "now playing" player apart from every other screen the
 * app has — search, home, a playlist, all of which can be showing while a track keeps playing
 * behind a docked mini-player. Nothing else this app has access to can make that distinction: the
 * usage-event stream (see ForegroundApp) only says which *app* is in front, and the media session
 * only reports playback state, neither knows what the app is actually showing. Scoped as tightly
 * as the accessibility config resource allows — see player_screen_accessibility_config.xml — to
 * just the two tracked music apps, and reports a single boolean (NowPlayerScreenState), nothing
 * about the content of any screen is kept or sent anywhere.
 *
 * Purely additive: with this off (the default, and all it takes to keep it off is never granting
 * it in Réglages > Accessibilité), "vinyl"/"cocoon" behave exactly as before — anchored to
 * whichever app is in front, not to which of its screens.
 */
public final class DeezerPlayerAccessibilityService extends AccessibilityService {

    private static final String TAG = "DeezerPlayerA11y";
    /** A pathological view tree must never turn one walk into an unbounded one — real screens are
     *  nowhere near either limit. Nodes, not just depth: a long flat list (search results, a
     *  playlist) can be enormous without being deep, and the expensive case is exactly the one
     *  with no match at all — every browse screen — since nothing short-circuits that walk early. */
    private static final int MAX_DEPTH = 40;
    // Raised from 400 once the diagnostics could say how far a walk actually got: a budget that
    // stops the walk before it reaches the scrubber answers "not the player" for the same reason
    // an empty screen does, and 400 is not much of a margin over a Compose player's own tree.
    private static final int MAX_NODES = 1500;
    // How often onAccessibilityEvent() is actually allowed to walk the tree. typeWindowContentChanged
    // is in the config alongside typeWindowStateChanged because a single-Activity app's own
    // in-app navigation (Deezer's search/home/player are very likely destinations in one
    // Compose/Fragment host, not separate Activities) may never fire a window-state change at
    // all — but content-changed fires on every scroll frame and list update too, and querying the
    // node tree is a cross-process call into Deezer's own window. Walking it unthrottled is what
    // made Deezer itself stutter; this bounds the rate without dropping the event type outright.
    private static final long MIN_CHECK_INTERVAL_MS = 400;
    // A scrubber has to span most of the window's width to count — a docked mini-player's own
    // progress line is very often *also* a real SeekBar widget (just styled thin), and without
    // this the player screen and a browse screen with music still going underneath a mini-player
    // were indistinguishable, which was the whole "still shows the disc" report.
    private static final float MIN_SEEKBAR_WIDTH_FRACTION = 0.55f;
    // ...but that 0.55 was measured off a one-column *portrait* screenshot, where the scrubber
    // runs close to the full window width. Confirmed wrong on a real landscape scan (diagnostics
    // read "barre 0.45✗ · pochette 0.60 décalée 0.00✓" — the artwork check passed, the seekbar
    // one alone failed): Deezer keeps its player in a centred column there rather than spreading
    // it across the whole width, the same reason the artwork centring got its own wide tolerance
    // below (WIDE_ARTWORK_CENTER_X_FRACTION). Set with real margin under that 0.45 rather than
    // exactly on it, on the same "if it's still wrong, the fix belongs here" basis as everything
    // else in this heuristic — this is still paired with the artwork check below, which a docked
    // mini-player's own smaller cover keeps failing regardless of how loose this one is.
    private static final float WIDE_MIN_SEEKBAR_WIDTH_FRACTION = 0.38f;
    // An image has to cover a real fraction of the window's height to count as the player's own
    // full-size cover art rather than a list thumbnail or a mini-player's small icon.
    //
    // Set against a real measurement rather than a guess: EdgeGlowView's own art-position model
    // (ART_TALL_MAX_HEIGHT_FRACTION) puts Deezer's actual full-player cover at ~40% of the
    // screen's height, measured directly off a real Deezer screenshot. A promoted card on the
    // home feed — confirmed against a screenshot of Deezer's own "Accueil" tab, the concrete case
    // that slipped past the first version of this heuristic — ran to about 19-29% there, large
    // enough to have cleared the previous, lower threshold. This sits with real margin under the
    // cover's own ~40% and over what a feed card measured at.
    private static final float MIN_ARTWORK_HEIGHT_FRACTION = 0.32f;
    // The player's own cover sits centred on the screen (see EdgeGlowView's ART_TALL_CENTER_X_FRACTION,
    // 0.5) — every other large image on a Deezer screen is feed or carousel content, which is
    // laid out in a row and essentially never centred. Measured on that same "Accueil" screenshot:
    // the promoted card's own centre sat 23% of the screen's width off-centre, comfortably outside
    // this tolerance, while an actually-centred cover sits inside it by construction.
    private static final float MAX_ARTWORK_CENTER_OFFSET_FRACTION = 0.12f;
    // ...but "centred" is only true of the one-column layout. Unfolded, Deezer lays the player out
    // in two panes with the cover in the left one — EdgeGlowView's own model puts it at a quarter
    // of the width (ART_WIDE_CENTER_X_FRACTION), which is 0.25 off-centre and so twice outside the
    // tolerance above. Testing against the middle alone would have answered "not the player" for
    // every single screen of an unfolded Fold, which is the one device this was written for.
    private static final float WIDE_ARTWORK_CENTER_X_FRACTION = 0.25f;

    // A docked mini-player sits pinned to the very bottom of the screen, its own progress hairline
    // included — that hairline is exactly the "styled thin" SeekBar the WIDE_MIN_SEEKBAR_WIDTH_FRACTION
    // comment above already knew could pass the width floor. What it didn't yet guard against: a
    // *browse* screen (a playlist, an album) whose header carries a large, centred cover of its own —
    // clearing MIN_ARTWORK_HEIGHT_FRACTION on a completely different widget than the one clearing the
    // seekbar floor, the two meeting only because both thresholds were checked in isolation. Reported
    // directly (a Bibliothèque playlist screen, full-width mini-player bar, disc drawn over the
    // playlist's own header): both fractions passed, the player screen was not showing. The real
    // full-screen player never has this shape — its scrubber sits with the transport controls, still
    // well above the screen's bottom edge (see EdgeGlowView's ART_TALL_TOP_FRACTION/
    // ART_TALL_MAX_HEIGHT_FRACTION: the cover alone already reaches ~50% down, and the scrubber comes
    // after it) — so a scrubber found in the bottom band reserved for a docked bar is that docked
    // bar's, never the full player's, however wide it measures.
    private static final float DOCKED_BAR_ZONE_TOP_FRACTION = 0.82f;

    private long lastCheckAtMs;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
        long now = SystemClock.elapsedRealtime();
        if (now - lastCheckAtMs < MIN_CHECK_INTERVAL_MS) return;
        lastCheckAtMs = now;
        try {
            NowPlayerScreenState.setOnPlayerScreen(looksLikeNowPlayingScreen());
        } catch (Exception e) {
            // Runs on events from another app's window, which this app doesn't control the shape
            // of — a node tree that throws on a walk must never be allowed to crash Vizuzik.
            Log.w(TAG, "onAccessibilityEvent", e);
        }
    }

    /**
     * Whether the window currently on screen, in one of the tracked apps, looks like their
     * full-screen "now playing" player rather than a browse screen — a heuristic, not a lookup by
     * resource ID, since this app's own view IDs are private to it and change across versions.
     * Requires *both* a wide scrubber and a large piece of artwork together: either alone can
     * belong to a docked mini-player too (see the two MIN_* fractions above for why), but a
     * browse screen showing both at once — full-width scrubber, large cover — essentially never
     * happens.
     *
     * Written without the ability to inspect Deezer's actual layout on a real device — if it
     * still turns out wrong in practice, the fix belongs here, in what counts as "looks like the
     * player", not in anything else this rests on.
     */
    private boolean looksLikeNowPlayingScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) {
            // Worth its own mark: "the service is running but never gets a tree" and "it gets a
            // tree and doesn't recognise it" are different problems with different fixes, and
            // without this they look identical from the settings panel.
            OverlayDiagnostics.markScan();
            OverlayDiagnostics.scanNodesVisited = 0;
            OverlayDiagnostics.scanSawWideSeekBar = false;
            OverlayDiagnostics.scanSawLargeArtwork = false;
            OverlayDiagnostics.scanWidestSeekBarFraction = -1f;
            OverlayDiagnostics.scanTallestImageFraction = -1f;
            OverlayDiagnostics.scanTallestImageOffsetFraction = -1f;
            return false;
        }
        try {
            // The window that is *active* is not necessarily the app the event came from. Deezer
            // goes on firing content-changed events from behind — a progress bar ticking, a list
            // settling — while something else entirely is on screen, and getRootInActiveWindow()
            // answers with whatever is in front, which for these purposes was Vizuzik's own
            // webview. That is how the first reading off a device came back "23 nœuds, aucune
            // barre, une pochette parfaitement centrée": a real answer about the wrong window.
            CharSequence packageName = root.getPackageName();
            String scanned = packageName != null ? packageName.toString() : "";
            OverlayDiagnostics.scanPackage = scanned;
            if (!MusicApps.isKnownPackage(scanned)) {
                // Not the music app in front, so its player screen is definitionally not showing.
                OverlayDiagnostics.markScan();
                OverlayDiagnostics.scanNodesVisited = 0;
                return false;
            }
            Rect window = new Rect();
            root.getBoundsInScreen(window);
            if (window.width() <= 0 || window.height() <= 0) return false;
            Scan scan = new Scan(window.width(), window.height(), window.left, window.top);
            scan.walk(root, 0);
            // What the walk actually saw, thresholds aside — a heuristic that is merely mis-tuned
            // (a cover at 30% against a 32% floor) and one looking at a tree with no SeekBar and no
            // Image in it at all are indistinguishable from the booleans alone, and telling them
            // apart is the difference between moving a number and rewriting the whole approach.
            OverlayDiagnostics.markScan();
            OverlayDiagnostics.scanNodesVisited = scan.nodesVisited;
            OverlayDiagnostics.scanSawWideSeekBar = scan.hasWideSeekBar;
            OverlayDiagnostics.scanSawLargeArtwork = scan.hasLargeArtwork;
            OverlayDiagnostics.scanWidestSeekBarFraction = scan.widestSeekBarFraction;
            OverlayDiagnostics.scanTallestImageFraction = scan.tallestImageFraction;
            OverlayDiagnostics.scanTallestImageOffsetFraction = scan.tallestImageOffsetFraction;
            OverlayDiagnostics.scanBudgetExhausted = scan.nodesVisited >= MAX_NODES;
            return scan.hasWideSeekBar && scan.hasLargeArtwork;
        } finally {
            root.recycle();
        }
    }

    /** One walk's findings and its own node budget — a plain object rather than instance fields,
     *  since nothing here should ever depend on only one walk running at a time. */
    private static final class Scan {
        final int windowWidth;
        final int windowHeight;
        final int windowTop;
        /** Where the cover is allowed to sit: the middle always, plus the left pane's own centre
         *  when the window is wide enough to be the two-pane layout. */
        final int[] artworkCenterXs;
        /** MIN_SEEKBAR_WIDTH_FRACTION, or the looser WIDE_ one for a landscape window — see there
         *  for why the portrait-measured threshold doesn't hold once the player sits in a
         *  centred column rather than spanning the screen. */
        final float minSeekBarWidthFraction;
        int nodesVisited;
        boolean hasWideSeekBar;
        boolean hasLargeArtwork;
        // The best candidate seen for each, threshold or no threshold — reported, never tested on.
        float widestSeekBarFraction = -1f;
        float tallestImageFraction = -1f;
        float tallestImageOffsetFraction = -1f;

        Scan(int windowWidth, int windowHeight, int windowLeft, int windowTop) {
            this.windowWidth = windowWidth;
            this.windowHeight = windowHeight;
            this.windowTop = windowTop;
            int middle = windowLeft + windowWidth / 2;
            boolean wide = windowWidth > windowHeight;
            this.artworkCenterXs = wide
                ? new int[] { middle, windowLeft + Math.round(windowWidth * WIDE_ARTWORK_CENTER_X_FRACTION) }
                : new int[] { middle };
            this.minSeekBarWidthFraction = wide ? WIDE_MIN_SEEKBAR_WIDTH_FRACTION : MIN_SEEKBAR_WIDTH_FRACTION;
        }

        /** Whether bounds sit inside the bottom band reserved for a docked bar — see
         *  DOCKED_BAR_ZONE_TOP_FRACTION. Tested on the top edge, not the centre: a scrubber that
         *  merely reaches into the band from above (the real player's, sitting close to it on a
         *  short screen) must not be excluded — only one that starts inside it, which a docked bar's
         *  own hairline always does. */
        boolean startsInDockedBarZone(Rect bounds) {
            float topFraction = (bounds.top - windowTop) / (float) windowHeight;
            return topFraction >= DOCKED_BAR_ZONE_TOP_FRACTION;
        }

        /** How far the given centre sits from the nearest allowed one, as a fraction of the
         *  window's width. */
        float centerOffsetFraction(int centerX) {
            float best = Float.MAX_VALUE;
            for (int allowed : artworkCenterXs) {
                best = Math.min(best, Math.abs(centerX - allowed) / (float) windowWidth);
            }
            return best;
        }

        /**
         * Whether a node is a playback scrubber, asked three ways rather than by class name alone.
         *
         * The class name alone was wrong, and the diagnostics said so outright: the widest thing
         * matching "SeekBar" on a real scan was *nothing at all*, not something merely too narrow.
         * A Compose slider does not have to report itself as android.widget.SeekBar — what it does
         * carry is range semantics (a current value between a minimum and a maximum) and, when it
         * can be dragged, the "set progress" action. Those are what a scrubber actually is;
         * the class name is just the most fragile way of noticing one.
         */
        private static boolean isScrubber(AccessibilityNodeInfo node, String className) {
            if (className.contains("SeekBar") || className.contains("Slider")
                || className.contains("ProgressBar")) {
                return true;
            }
            if (node.getRangeInfo() != null) return true;
            return node.getActionList().contains(
                AccessibilityNodeInfo.AccessibilityAction.ACTION_SET_PROGRESS);
        }

        void walk(AccessibilityNodeInfo node, int depth) {
            if (node == null || depth > MAX_DEPTH) return;
            if (hasWideSeekBar && hasLargeArtwork) return; // both found — nothing left to learn
            if (++nodesVisited > MAX_NODES) return;

            if (node.isVisibleToUser()) {
                CharSequence className = node.getClassName();
                String name = className != null ? className.toString() : "";
                if (!name.isEmpty()) {
                    Rect bounds = new Rect();
                    node.getBoundsInScreen(bounds);
                    if (isScrubber(node, name)) {
                        float widthFraction = bounds.width() / (float) windowWidth;
                        if (widthFraction > widestSeekBarFraction) widestSeekBarFraction = widthFraction;
                        if (widthFraction >= minSeekBarWidthFraction && !startsInDockedBarZone(bounds)) {
                            hasWideSeekBar = true;
                        }
                    }
                    if (name.contains("Image")) {
                        float heightFraction = bounds.height() / (float) windowHeight;
                        float offsetFraction = centerOffsetFraction(bounds.centerX());
                        if (heightFraction > tallestImageFraction) {
                            tallestImageFraction = heightFraction;
                            tallestImageOffsetFraction = offsetFraction;
                        }
                        if (heightFraction >= MIN_ARTWORK_HEIGHT_FRACTION
                            && offsetFraction <= MAX_ARTWORK_CENTER_OFFSET_FRACTION) {
                            hasLargeArtwork = true;
                        }
                    }
                }
            }

            int count = node.getChildCount();
            for (int i = 0; i < count && !(hasWideSeekBar && hasLargeArtwork); i++) {
                AccessibilityNodeInfo child = node.getChild(i);
                if (child == null) continue;
                try {
                    walk(child, depth + 1);
                } finally {
                    child.recycle();
                }
            }
        }
    }

    @Override
    public void onInterrupt() {
        // Nothing held here beyond what the framework already tears down on interrupt.
    }

    @Override
    protected void onServiceConnected() {
        super.onServiceConnected();
        NowPlayerScreenState.setServiceConnected(true);
    }

    @Override
    public boolean onUnbind(Intent intent) {
        NowPlayerScreenState.setServiceConnected(false);
        return super.onUnbind(intent);
    }

    /** Whether this service is currently enabled in system Settings — how the settings panel
     *  knows without waiting for onServiceConnected() to have fired yet this session. */
    static boolean isEnabled(Context context) {
        String enabled = Settings.Secure.getString(
            context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (TextUtils.isEmpty(enabled)) return false;
        ComponentName self = new ComponentName(context, DeezerPlayerAccessibilityService.class);
        for (String piece : enabled.split(":")) {
            if (self.equals(ComponentName.unflattenFromString(piece))) return true;
        }
        return false;
    }
}
