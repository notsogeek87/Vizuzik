package com.vizuzik.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Mirrors the web layer's Edge Visualizer on/off toggle (localStorage "vizuzik:edgeOverlay") into
 * native SharedPreferences — same reasoning as MusicAppPreference/EdgeConfig: EdgeOverlayController
 * decides whether to start OverlayEdgeGlowService from native code that runs whenever
 * NowPlayingListenerService is alive, which is whenever notification access is granted,
 * independently of whether Vizuzik's own Activity/webview has ever been launched this session.
 * DeezerMediaPlugin.setEdgeOverlayEnabled() is the only writer.
 */
final class EdgeOverlayPreference {

    private static final String PREFS_NAME = "vizuzik";
    private static final String KEY_ENABLED = "edgeOverlayEnabled";

    static boolean isEnabled(Context context) {
        return prefs(context).getBoolean(KEY_ENABLED, false);
    }

    static void setEnabled(Context context, boolean enabled) {
        prefs(context).edit().putBoolean(KEY_ENABLED, enabled).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private EdgeOverlayPreference() {}
}
