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
    private static final int MAX_NODES = 400;
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
    // An image has to cover a real fraction of the window's height to count as the player's own
    // full-size cover art rather than a list thumbnail or a mini-player's small icon.
    private static final float MIN_ARTWORK_HEIGHT_FRACTION = 0.22f;

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
        if (root == null) return false;
        try {
            Rect window = new Rect();
            root.getBoundsInScreen(window);
            if (window.width() <= 0 || window.height() <= 0) return false;
            Scan scan = new Scan(window.width(), window.height());
            scan.walk(root, 0);
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
        int nodesVisited;
        boolean hasWideSeekBar;
        boolean hasLargeArtwork;

        Scan(int windowWidth, int windowHeight) {
            this.windowWidth = windowWidth;
            this.windowHeight = windowHeight;
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
                    if (!hasWideSeekBar && name.contains("SeekBar")
                        && bounds.width() >= windowWidth * MIN_SEEKBAR_WIDTH_FRACTION) {
                        hasWideSeekBar = true;
                    }
                    if (!hasLargeArtwork && name.contains("Image")
                        && bounds.height() >= windowHeight * MIN_ARTWORK_HEIGHT_FRACTION) {
                        hasLargeArtwork = true;
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
