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
 * Unlike EdgeOverlayPreference's original reasoning, this no longer defaults to off: the feature
 * ships on by default (style "vinyl"/Disque), same as Edge Visualizer — see the 2026-09-10 update
 * to docs/architecture/2026-09-09-visualiseur-ecran-verrouille.md. It still costs meaningfully
 * more battery than a real AOD, which is why it's the one setting the first-launch flow always
 * asks the required grants for up front (see runFirstLaunchSetup()/askLockScreenVisualizerPermissions()
 * in main.js) rather than leaving it to be discovered in the settings panel.
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
    static final String STYLE_BALADEUR = EdgeConfig.STYLE_BALADEUR;

    // Default style: "vinyl" ("Disque"), not "bars" — see the class doc.
    private static final String DEFAULT_STYLE = STYLE_VINYL;

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, true);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    /** Read once by LockScreenVisualizerActivity.onStart(), the same moment it reads the rest of
     *  EdgeConfig — see EdgeGlowView.setStandaloneStyle(). An unrecognised or missing value falls
     *  back to DEFAULT_STYLE rather than being passed through, since activeStyle() trusts this
     *  value completely (unlike EdgeConfig.style, it is never checked against a fallback there). */
    static String getStyle(Context context) {
        String stored = prefs(context).getString(KEY_STYLE, DEFAULT_STYLE);
        return isKnownStyle(stored) ? stored : DEFAULT_STYLE;
    }

    static void setStyle(Context context, String style) {
        prefs(context).edit().putString(KEY_STYLE, isKnownStyle(style) ? style : DEFAULT_STYLE).apply();
    }

    private static boolean isKnownStyle(String style) {
        return STYLE_BARS.equals(style) || STYLE_CASSETTE.equals(style) || STYLE_VINYL.equals(style)
            || STYLE_BALADEUR.equals(style);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private LockScreenVisualizerPreference() {}
}
