package com.vizuzik.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

/**
 * Edge Visualizer settings, mirrored here (same "vizuzik" SharedPreferences file as
 * MusicAppPreference) so that OverlayEdgeGlowService — a background Service with no access to
 * the webview's localStorage — can read the same choices the web layer's settings panel writes.
 * DeezerMediaPlugin.setEdgeConfig() is the only writer; the web layer keeps its own copy in
 * localStorage purely to redraw its own settings UI instantly, without waiting on a round trip.
 */
final class EdgeConfig {

    static final String STYLE_GLOW = "glow";
    // See EdgeGlowView.drawBars() — each band drawn on its own instead of averaged into the
    // glow's single scalar. The default since STYLE_VERSION 2.
    static final String STYLE_BARS = "bars";
    // See EdgeGlowView.drawCocoon() — a woven ribbon centred on Deezer's own album art rather
    // than the four screen edges the other two styles are confined to.
    static final String STYLE_COCOON = "cocoon";

    static final String BAND_FULL = "full";
    static final String BAND_BASS = "bass";
    static final String BAND_MID = "mid";
    static final String BAND_TREBLE = "treble";

    static final String COLOR_AUTO = "auto";
    static final String COLOR_CUSTOM = "custom";

    private static final String PREFS_NAME = "vizuzik";
    private static final String KEY_STYLE = "edgeStyle";
    // Bumped when a stored KEY_STYLE should be dropped rather than honoured. Version 2 made bars
    // the default; an install predating it carries "glow" only because that was the single value
    // the panel could ever write, which is not the same as having chosen it.
    private static final String KEY_STYLE_VERSION = "edgeStyleVersion";
    private static final int STYLE_VERSION = 2;
    private static final String KEY_INTENSITY = "edgeIntensity";
    private static final String KEY_THICKNESS = "edgeThickness";
    private static final String KEY_BRIGHTNESS = "edgeBrightness";
    private static final String KEY_SENSITIVITY = "edgeSensitivity";
    private static final String KEY_BAND = "edgeBand";
    private static final String KEY_COLOR_MODE = "edgeColorMode";
    private static final String KEY_CUSTOM_COLORS = "edgeCustomColors";
    private static final String KEY_TOP = "edgeTop";
    private static final String KEY_BOTTOM = "edgeBottom";
    private static final String KEY_LEFT = "edgeLeft";
    private static final String KEY_RIGHT = "edgeRight";
    private static final String KEY_ONLY_OVER_MUSIC_APP = "edgeOnlyOverMusicApp";
    private static final String KEY_BAR_SIZE = "edgeBarSize";
    private static final String KEY_COCOON_FALLBACK = "edgeCocoonFallback";

    /** Immutable snapshot handed to EdgeGlowView — read once per change rather than hitting
     *  SharedPreferences on every one of its ~24 ticks per second. */
    static final class Snapshot {
        final String style;
        final float intensity;
        final float thickness;
        final float brightness;
        final float sensitivity;
        final String band;
        /** How tall the "bars" style may grow, as a multiplier — see EdgeGlowView.barLimit(). */
        final float barSize;
        final int[][] customPalette; // null when colorMode is "auto"
        final boolean top;
        final boolean bottom;
        final boolean left;
        final boolean right;
        /** Hide the overlay unless the tracked music app is the one on screen — see
         *  ForegroundApp, and note it can only be honoured once "usage access" is granted. */
        final boolean onlyOverMusicApp;
        /** What "cocoon" falls back to when the music app is not the one on screen: it is drawn
         *  around where that app's album art sits, so anywhere else it frames nothing. */
        final String cocoonFallback;

        Snapshot(
            String style,
            float intensity,
            float thickness,
            float brightness,
            float sensitivity,
            String band,
            float barSize,
            int[][] customPalette,
            boolean top,
            boolean bottom,
            boolean left,
            boolean right,
            boolean onlyOverMusicApp,
            String cocoonFallback
        ) {
            this.style = style;
            this.intensity = intensity;
            this.thickness = thickness;
            this.brightness = brightness;
            this.sensitivity = sensitivity;
            this.band = band;
            this.barSize = barSize;
            this.customPalette = customPalette;
            this.top = top;
            this.bottom = bottom;
            this.left = left;
            this.right = right;
            this.onlyOverMusicApp = onlyOverMusicApp;
            this.cocoonFallback = cocoonFallback;
        }
    }

    static Snapshot read(Context context) {
        SharedPreferences prefs = prefs(context);
        migrateStyle(prefs);
        String colorMode = prefs.getString(KEY_COLOR_MODE, COLOR_AUTO);
        int[][] customPalette = COLOR_CUSTOM.equals(colorMode)
            ? parseColors(prefs.getString(KEY_CUSTOM_COLORS, null))
            : null;
        return new Snapshot(
            prefs.getString(KEY_STYLE, STYLE_BARS),
            prefs.getFloat(KEY_INTENSITY, 1f),
            prefs.getFloat(KEY_THICKNESS, 1f),
            prefs.getFloat(KEY_BRIGHTNESS, 1f),
            prefs.getFloat(KEY_SENSITIVITY, 1f),
            prefs.getString(KEY_BAND, BAND_FULL),
            prefs.getFloat(KEY_BAR_SIZE, 1f),
            customPalette,
            // A fresh install gets one row of bars along the top edge: the whole spectrum on all
            // four sides at once is a lot to meet an app with, and the top is the edge that reads
            // as belonging to the phone rather than to whatever is on screen. Anyone who had the
            // overlay before keeps the edges they had — these are only the absent-value defaults.
            prefs.getBoolean(KEY_TOP, true),
            prefs.getBoolean(KEY_BOTTOM, false),
            prefs.getBoolean(KEY_LEFT, false),
            prefs.getBoolean(KEY_RIGHT, false),
            prefs.getBoolean(KEY_ONLY_OVER_MUSIC_APP, false),
            prefs.getString(KEY_COCOON_FALLBACK, STYLE_BARS)
        );
    }

    /**
     * Writes every field at once from the web layer's settings panel state — simpler than one
     * SharedPreferences.Editor per field, and this is only ever called on a user edit (a slider
     * released, a toggle flipped), never per frame.
     */
    static void write(
        Context context,
        String style,
        float intensity,
        float thickness,
        float brightness,
        float sensitivity,
        String band,
        float barSize,
        String colorMode,
        String customColorsCsv,
        boolean top,
        boolean bottom,
        boolean left,
        boolean right,
        boolean onlyOverMusicApp,
        String cocoonFallback
    ) {
        prefs(context)
            .edit()
            .putString(KEY_STYLE, style)
            // Stamped alongside the value so a style picked here is a real choice, left alone by
            // migrateStyle() from now on.
            .putInt(KEY_STYLE_VERSION, STYLE_VERSION)
            .putFloat(KEY_INTENSITY, intensity)
            .putFloat(KEY_THICKNESS, thickness)
            .putFloat(KEY_BRIGHTNESS, brightness)
            .putFloat(KEY_SENSITIVITY, sensitivity)
            .putString(KEY_BAND, band)
            .putFloat(KEY_BAR_SIZE, barSize)
            .putString(KEY_COLOR_MODE, colorMode)
            .putString(KEY_CUSTOM_COLORS, customColorsCsv)
            .putBoolean(KEY_TOP, top)
            .putBoolean(KEY_BOTTOM, bottom)
            .putBoolean(KEY_LEFT, left)
            .putBoolean(KEY_RIGHT, right)
            .putBoolean(KEY_ONLY_OVER_MUSIC_APP, onlyOverMusicApp)
            .putString(KEY_COCOON_FALLBACK, cocoonFallback)
            .apply();
    }

    /** Registers a listener fired whenever any of the above keys changes — how
     *  OverlayEdgeGlowService picks up a settings-panel edit while it's already running, without
     *  needing a restart. Same SharedPreferences instance must be kept alive by the caller, since
     *  the framework only holds a weak reference to the listener. */
    static void registerListener(Context context, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs(context).registerOnSharedPreferenceChangeListener(listener);
    }

    static void unregisterListener(Context context, SharedPreferences.OnSharedPreferenceChangeListener listener) {
        prefs(context).unregisterOnSharedPreferenceChangeListener(listener);
    }

    /** "#7C5CFF,#EC4899,#38BDF8" -> 3 RGB triples; null/malformed input falls back to the same
     *  default accents EdgeGlowView/OverlayPalette already use when there's no cover art. */
    private static int[][] parseColors(String csv) {
        if (csv == null) return null;
        String[] parts = csv.split(",");
        if (parts.length == 0) return null;
        int[][] colors = new int[Math.min(3, parts.length)][];
        for (int i = 0; i < colors.length; i++) {
            try {
                int color = Color.parseColor(parts[i].trim());
                colors[i] = new int[] { Color.red(color), Color.green(color), Color.blue(color) };
            } catch (IllegalArgumentException e) {
                return null;
            }
        }
        if (colors.length == 1) {
            colors = new int[][] { colors[0], colors[0], colors[0] };
        } else if (colors.length == 2) {
            colors = new int[][] { colors[0], colors[1], colors[0] };
        }
        return colors;
    }

    /** Drops a KEY_STYLE stored before the current STYLE_VERSION, once, so the new default takes
     *  effect on an existing install. write() stamps the current version, so a style the user
     *  actually picks in the settings panel is never touched by this. */
    private static void migrateStyle(SharedPreferences prefs) {
        if (prefs.getInt(KEY_STYLE_VERSION, 1) >= STYLE_VERSION) return;
        prefs.edit().remove(KEY_STYLE).putInt(KEY_STYLE_VERSION, STYLE_VERSION).apply();
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private EdgeConfig() {}
}
