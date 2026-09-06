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
    // Diagnostic/alternate style: see EdgeGlowView.drawBars() — each band drawn on its own
    // instead of averaged into the glow's single scalar.
    static final String STYLE_BARS = "bars";

    static final String BAND_FULL = "full";
    static final String BAND_BASS = "bass";
    static final String BAND_MID = "mid";
    static final String BAND_TREBLE = "treble";

    static final String COLOR_AUTO = "auto";
    static final String COLOR_CUSTOM = "custom";

    private static final String PREFS_NAME = "vizuzik";
    private static final String KEY_STYLE = "edgeStyle";
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

    /** Immutable snapshot handed to EdgeGlowView — read once per change rather than hitting
     *  SharedPreferences on every one of its ~24 ticks per second. */
    static final class Snapshot {
        final String style;
        final float intensity;
        final float thickness;
        final float brightness;
        final float sensitivity;
        final String band;
        final int[][] customPalette; // null when colorMode is "auto"
        final boolean top;
        final boolean bottom;
        final boolean left;
        final boolean right;

        Snapshot(
            String style,
            float intensity,
            float thickness,
            float brightness,
            float sensitivity,
            String band,
            int[][] customPalette,
            boolean top,
            boolean bottom,
            boolean left,
            boolean right
        ) {
            this.style = style;
            this.intensity = intensity;
            this.thickness = thickness;
            this.brightness = brightness;
            this.sensitivity = sensitivity;
            this.band = band;
            this.customPalette = customPalette;
            this.top = top;
            this.bottom = bottom;
            this.left = left;
            this.right = right;
        }
    }

    static Snapshot read(Context context) {
        SharedPreferences prefs = prefs(context);
        String colorMode = prefs.getString(KEY_COLOR_MODE, COLOR_AUTO);
        int[][] customPalette = COLOR_CUSTOM.equals(colorMode)
            ? parseColors(prefs.getString(KEY_CUSTOM_COLORS, null))
            : null;
        return new Snapshot(
            prefs.getString(KEY_STYLE, STYLE_GLOW),
            prefs.getFloat(KEY_INTENSITY, 1f),
            prefs.getFloat(KEY_THICKNESS, 1f),
            prefs.getFloat(KEY_BRIGHTNESS, 1f),
            prefs.getFloat(KEY_SENSITIVITY, 1f),
            prefs.getString(KEY_BAND, BAND_FULL),
            customPalette,
            prefs.getBoolean(KEY_TOP, true),
            prefs.getBoolean(KEY_BOTTOM, true),
            prefs.getBoolean(KEY_LEFT, true),
            prefs.getBoolean(KEY_RIGHT, true)
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
        String colorMode,
        String customColorsCsv,
        boolean top,
        boolean bottom,
        boolean left,
        boolean right
    ) {
        prefs(context)
            .edit()
            .putString(KEY_STYLE, style)
            .putFloat(KEY_INTENSITY, intensity)
            .putFloat(KEY_THICKNESS, thickness)
            .putFloat(KEY_BRIGHTNESS, brightness)
            .putFloat(KEY_SENSITIVITY, sensitivity)
            .putString(KEY_BAND, band)
            .putString(KEY_COLOR_MODE, colorMode)
            .putString(KEY_CUSTOM_COLORS, customColorsCsv)
            .putBoolean(KEY_TOP, top)
            .putBoolean(KEY_BOTTOM, bottom)
            .putBoolean(KEY_LEFT, left)
            .putBoolean(KEY_RIGHT, right)
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

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
    }

    private EdgeConfig() {}
}
