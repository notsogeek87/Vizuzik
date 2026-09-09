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

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private LockScreenVisualizerPreference() {}
}
