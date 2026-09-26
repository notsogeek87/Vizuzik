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
    private static final String KEY_TRIGGER = "lockScreenVisualizerTrigger";
    private static final String KEY_DURATION_SEC = "lockScreenVisualizerDurationSec";

    /** The default: shown once, for getDurationSec() seconds, when the screen goes off while
     *  music plays (the user locking, or the screen timing out), then handed back to the real
     *  lock screen, which goes to sleep on its own — see
     *  LockScreenVisualizerController.maybeShowOnSleep(). */
    static final String TRIGGER_SLEEP = "sleep";
    /** Shown for getDurationSec() seconds when the user fully wakes the screen (side button,
     *  double tap to wake) onto the lock screen while music plays — see
     *  LockScreenVisualizerController.maybeShowOnWake(). */
    static final String TRIGGER_WAKE = "wake";
    /** The original behaviour: relight the screen the moment it goes off, and keep it lit for as
     *  long as the music plays. */
    static final String TRIGGER_CONTINUOUS = "continuous";
    private static final String DEFAULT_TRIGGER = TRIGGER_SLEEP;

    /** The only durations the settings panel offers — anything else stored falls back to the
     *  default rather than being trusted, same as getStyle(). */
    private static final int[] DURATIONS_SEC = {5, 10, 15, 30, 60};
    private static final int DEFAULT_DURATION_SEC = 10;

    // A shorter list than EdgeConfig's own "style" (see there): no "glow"/"particles" — this
    // screen has nothing else on it worth outlining an edge around — and no "cocoon", which stays
    // out for the same reason it stayed out of the standalone recentring "vinyl" and "cassette"
    // get (see EdgeGlowView.artRect()'s standalone branch and the ADR's "Pistes non retenues").
    static final String STYLE_BARS = EdgeConfig.STYLE_BARS;
    static final String STYLE_CASSETTE = EdgeConfig.STYLE_CASSETTE;
    static final String STYLE_VINYL = EdgeConfig.STYLE_VINYL;
    static final String STYLE_BALADEUR = EdgeConfig.STYLE_BALADEUR;
    static final String STYLE_K7_ETIQUETTE = EdgeConfig.STYLE_K7_ETIQUETTE;
    static final String STYLE_K7_CLASSIQUE = EdgeConfig.STYLE_K7_CLASSIQUE;

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

    static String getTrigger(Context context) {
        String stored = prefs(context).getString(KEY_TRIGGER, DEFAULT_TRIGGER);
        return isKnownTrigger(stored) ? stored : DEFAULT_TRIGGER;
    }

    /** Every trigger but "continuous" shows the visualizer for getDurationSec() seconds only. */
    static boolean isTimedTrigger(Context context) {
        return !TRIGGER_CONTINUOUS.equals(getTrigger(context));
    }

    static void setTrigger(Context context, String trigger) {
        prefs(context).edit()
            .putString(KEY_TRIGGER, isKnownTrigger(trigger) ? trigger : DEFAULT_TRIGGER).apply();
    }

    /** How long LockScreenVisualizerActivity stays up in TRIGGER_SLEEP/TRIGGER_WAKE mode before
     *  handing back to the real lock screen. Unused in TRIGGER_CONTINUOUS mode. */
    static int getDurationSec(Context context) {
        int stored = prefs(context).getInt(KEY_DURATION_SEC, DEFAULT_DURATION_SEC);
        return isKnownDuration(stored) ? stored : DEFAULT_DURATION_SEC;
    }

    static void setDurationSec(Context context, int seconds) {
        prefs(context).edit()
            .putInt(KEY_DURATION_SEC, isKnownDuration(seconds) ? seconds : DEFAULT_DURATION_SEC).apply();
    }

    private static boolean isKnownTrigger(String trigger) {
        return TRIGGER_SLEEP.equals(trigger) || TRIGGER_WAKE.equals(trigger)
            || TRIGGER_CONTINUOUS.equals(trigger);
    }

    private static boolean isKnownDuration(int seconds) {
        for (int allowed : DURATIONS_SEC) {
            if (allowed == seconds) return true;
        }
        return false;
    }

    private static boolean isKnownStyle(String style) {
        return STYLE_BARS.equals(style) || STYLE_CASSETTE.equals(style) || STYLE_VINYL.equals(style)
            || STYLE_BALADEUR.equals(style) || STYLE_K7_ETIQUETTE.equals(style)
            || STYLE_K7_CLASSIQUE.equals(style);
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private LockScreenVisualizerPreference() {}
}
