package com.vizuzik.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Mirrors the web layer's "Visualiseur écran verrouillé" toggle (localStorage
 * "vizuzik:lockScreenVisualizer") into native SharedPreferences — same reasoning as
 * EdgeOverlayPreference: LockScreenVisualizerController decides whether to show
 * LockScreenVisualizerActivity from native code that runs whenever NowPlayingListenerService is
 * alive, independently of whether Vizuzik's own Activity/webview has ever been launched this
 * session. DeezerMediaPlugin.setLockScreenVisualizerEnabled() is the only writer.
 *
 * Unlike EdgeOverlayPreference, absent means *off*: this feature takes over the lock screen and
 * keeps the display lit instead of letting it sleep, a materially bigger cost and a more
 * intrusive thing to show than a border drawn over another app — it only ever runs once someone
 * has explicitly turned it on.
 */
final class LockScreenVisualizerPreference {

    private static final String PREFS_NAME = "vizuzik";
    private static final String KEY_ENABLED = "lockScreenVisualizerEnabled";
    private static final String KEY_STYLE = "lockScreenVisualizerStyle";

    // A shorter list than EdgeConfig's own "style" (see there): no "glow"/"particles" — this
    // screen has nothing else on it worth outlining an edge around — and no "cocoon", which stays
    // out for the same reason it stayed out of the standalone recentring "vinyl" and "cassette"
    // get (see EdgeGlowView.artRect()'s standalone branch and the ADR's "Pistes non retenues").
    static final String STYLE_BARS = EdgeConfig.STYLE_BARS;
    static final String STYLE_CASSETTE = EdgeConfig.STYLE_CASSETTE;
    static final String STYLE_VINYL = EdgeConfig.STYLE_VINYL;

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Read once by LockScreenVisualizerActivity.onStart(), the same moment it reads the rest of
     *  EdgeConfig — see EdgeGlowView.setStandaloneStyle(). An unrecognised or missing value falls
     *  back to "bars" rather than being passed through, since activeStyle() trusts this value
     *  completely (unlike EdgeConfig.style, it is never checked against a fallback there). */
    static String getStyle(Context context) {
        String stored = prefs(context).getString(KEY_STYLE, STYLE_BARS);
        return isKnownStyle(stored) ? stored : STYLE_BARS;
    }

    static void setStyle(Context context, String style) {
        prefs(context).edit().putString(KEY_STYLE, isKnownStyle(style) ? style : STYLE_BARS).apply();
    }

    private static boolean isKnownStyle(String style) {
        return STYLE_BARS.equals(style) || STYLE_CASSETTE.equals(style) || STYLE_VINYL.equals(style);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private LockScreenVisualizerPreference() {}
}
