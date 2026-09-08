package com.vizuzik.app;

import android.accessibilityservice.AccessibilityService;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
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
    /** A pathological view tree must never turn one accessibility event into an unbounded walk on
     *  the main thread — real screens are nowhere near this deep. */
    private static final int MAX_DEPTH = 40;

    @Override
    public void onAccessibilityEvent(AccessibilityEvent event) {
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
     * resource ID. This app's own view IDs are private to it and change across versions, but a
     * player screen and a browse screen differ in a way that holds across most music apps' UI
     * regardless of naming: the player carries a scrubbable SeekBar for the track's position, and
     * a browse screen — even one with a mini-player docked at the bottom — essentially never
     * does, since scrubbing from a list would be a strange thing to offer there.
     *
     * Written without the ability to inspect Deezer's actual layout on a real device — if it
     * turns out wrong in practice (the player not detected, or a browse screen wrongly read as
     * one), the fix belongs here, in what counts as "looks like the player", not in anything else
     * this rests on.
     */
    private boolean looksLikeNowPlayingScreen() {
        AccessibilityNodeInfo root = getRootInActiveWindow();
        if (root == null) return false;
        try {
            return containsSeekBar(root, 0);
        } finally {
            root.recycle();
        }
    }

    private boolean containsSeekBar(AccessibilityNodeInfo node, int depth) {
        if (node == null || depth > MAX_DEPTH) return false;
        CharSequence className = node.getClassName();
        if (className != null && className.toString().contains("SeekBar")) return true;
        int count = node.getChildCount();
        for (int i = 0; i < count; i++) {
            AccessibilityNodeInfo child = node.getChild(i);
            if (child == null) continue;
            try {
                if (containsSeekBar(child, depth + 1)) return true;
            } finally {
                child.recycle();
            }
        }
        return false;
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
