package com.vizuzik.app;

import android.content.Context;
import android.content.SharedPreferences;

/**
 * Which music app's MediaSession to track and whose audio to capture, mirrored here so that
 * NowPlayingListenerService and AudioCaptureService — native components that don't have access
 * to the webview's localStorage, where the web layer keeps its own copy of this choice — can
 * read the same value. DeezerMediaPlugin.setMusicAppTarget() is the only writer.
 */
final class MusicAppPreference {

    private static final String PREFS_NAME = "vizuzik";
    private static final String KEY_PACKAGE = "musicAppPackage";

    static String getPackage(Context context) {
        return prefs(context).getString(KEY_PACKAGE, null);
    }

    static void setPackage(Context context, String packageName) {
        prefs(context).edit().putString(KEY_PACKAGE, packageName).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private MusicAppPreference() {}
}
