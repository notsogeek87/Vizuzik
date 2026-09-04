package com.vizuzik.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Mirrors the web layer's chosen audio source ("mic" / "real" / "off", see AUDIO_SOURCES in
 * main.js) so that OverlayEdgeGlowService — a native component with no access to the webview's
 * localStorage — knows whether it's allowed to listen to the microphone on its own while
 * Vizuzik is backgrounded. Same pattern as MusicAppPreference. DeezerMediaPlugin's
 * setAudioSourcePreference() is the only writer.
 */
final class AudioSourcePreference {

    static final String MIC = "mic";
    static final String REAL = "real";
    static final String OFF = "off";

    private static final String PREFS_NAME = "vizuzik";
    private static final String KEY_SOURCE = "audioSource";

    static String get(Context context) {
        return prefs(context).getString(KEY_SOURCE, OFF);
    }

    static void set(Context context, String source) {
        prefs(context).edit().putString(KEY_SOURCE, source).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private AudioSourcePreference() {}
}
