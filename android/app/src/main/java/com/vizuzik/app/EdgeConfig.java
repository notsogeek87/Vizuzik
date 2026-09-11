package com.vizuzik.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.graphics.Rect;
import android.os.Build;
import android.util.DisplayMetrics;
import android.view.WindowManager;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

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
    // See EdgeGlowView.drawVinyl() — the track's own artwork redrawn as a spinning record over
    // Deezer's own (static) album art, the same spot "cocoon" is centred on.
    static final String STYLE_VINYL = "vinyl";
    // See EdgeGlowView.drawParticles() — sparks spawned from the four screen edges, one per
    // spectrum band that just moved, rather than one continuous shape reacting to the whole
    // spectrum at once like the other edge-confined styles.
    static final String STYLE_PARTICLES = "particles";
    // See EdgeGlowView.drawCassette() — never offered in the general Edge Visualizer picker below
    // ("Style", edge-style in the settings panel): it has no Deezer layout to anchor itself
    // against, only the lock screen's own screen-centred rendering (see artRect()'s standalone
    // branch). Chosen instead from LockScreenVisualizerPreference's own, shorter style list — see
    // that class and LockScreenVisualizerActivity.
    static final String STYLE_CASSETTE = "cassette";
    // See EdgeGlowView.drawBaladeur() — same reasoning and the same shorter picker as
    // STYLE_CASSETTE above: a screen-centred portable-player case+screen illustration, the
    // native port of the web player's own "baladeur" display mode.
    static final String STYLE_BALADEUR = "baladeur";

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
    // Deliberately outside write()'s reach — see writeArtCalibration() for why this is the only
    // setting the panel is not allowed to overwrite. One triple per exact screen size the phone
    // has actually been calibrated in — see formatLayoutKey() — rather than per aspect-ratio
    // category, since a Fold's closed and open configurations can share an aspect ratio (both
    // "landscape") while being physically nothing alike, and a correction measured in one is not
    // a correction for the other.
    private static final String KEY_ART_CALIBRATIONS = "edgeArtCalibrations";
    // Two earlier, coarser storage shapes for the same calibration — see migrateArtCalibration().
    private static final String KEY_ART_OFFSET_X_LEGACY = "edgeArtOffsetX";
    private static final String KEY_ART_OFFSET_Y_LEGACY = "edgeArtOffsetY";
    private static final String KEY_ART_SCALE_LEGACY = "edgeArtScale";
    private static final String KEY_ART_OFFSET_X_TALL_LEGACY = "edgeArtOffsetXTall";
    private static final String KEY_ART_OFFSET_Y_TALL_LEGACY = "edgeArtOffsetYTall";
    private static final String KEY_ART_SCALE_TALL_LEGACY = "edgeArtScaleTall";
    private static final String KEY_ART_OFFSET_X_WIDE_LEGACY = "edgeArtOffsetXWide";
    private static final String KEY_ART_OFFSET_Y_WIDE_LEGACY = "edgeArtOffsetYWide";
    private static final String KEY_ART_SCALE_WIDE_LEGACY = "edgeArtScaleWide";
    private static final String KEY_HIDDEN_PACKAGES = "edgeHiddenPackages";
    // The one app hidden out of the box, without anyone having to find the picker first — see
    // DeezerMediaPlugin.listInstalledApps() for how they add more. GitHub's own app routinely
    // shows a page (a release, a long README) with a collapsing header — exactly the kind of
    // nested-scroll view that turned out fragile to the touch-occlusion workaround in
    // OverlayEdgeGlowService, on at least one device tested against.
    private static final String DEFAULT_HIDDEN_PACKAGES = "com.github.android";
    // On by default, and harmless while the grant it depends on is missing: EdgeGlowView's
    // activeStyle() only narrows anything once DeezerPlayerAccessibilityService is actually
    // connected, so with no grant this reads exactly as "off". It was off by default back when
    // nobody was ever asked for that grant; the permission sweep in main.js now asks for it on
    // every opening, and a record drawn over a playlist someone is browsing is the thing this
    // setting exists to stop.
    private static final String KEY_REQUIRE_PLAYER_SCREEN = "edgeRequirePlayerScreen";

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
        /** What "cocoon" and "vinyl" fall back to when the music app is not the one on screen:
         *  both are drawn against where that app's album art sits, so anywhere else there is
         *  nothing for either of them to be drawn against. */
        final String cocoonFallback;
        /** Where the album art really sits on *this* phone, as a correction to the layout model
         *  EdgeGlowView.artRect() estimates — see writeArtCalibration(). Offsets are fractions of
         *  the screen's width/height so they survive a resolution change; scale multiplies the
         *  estimated size. Keyed by formatLayoutKey() — the phone's exact current screen size —
         *  so a Fold's four configurations (closed/open x portrait/landscape) each keep their own
         *  entry; a size with no entry here uses the estimate untouched (0/0/1). Never null. */
        final Map<String, float[]> artCalibrations;
        /** Apps to hide over unconditionally, by package name — independent of onlyOverMusicApp,
         *  and honoured under the same rule as that setting: only once "usage access" tells this
         *  view what is actually on screen. Never null; empty when nothing is picked. */
        final Set<String> hiddenPackages;
        /** Restricts "cocoon"/"vinyl" further still: not just the tracked app in front, but its
         *  own full-screen player specifically — see DeezerPlayerAccessibilityService. Only ever
         *  acted on once that service is actually connected; see EdgeGlowView.activeStyle(). */
        final boolean requirePlayerScreen;

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
            String cocoonFallback,
            Map<String, float[]> artCalibrations,
            Set<String> hiddenPackages,
            boolean requirePlayerScreen
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
            this.artCalibrations = artCalibrations;
            this.hiddenPackages = hiddenPackages;
            this.requirePlayerScreen = requirePlayerScreen;
        }
    }

    static Snapshot read(Context context) {
        SharedPreferences prefs = prefs(context);
        migrateStyle(prefs);
        migrateArtCalibration(prefs);
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
            prefs.getString(KEY_COCOON_FALLBACK, STYLE_BARS),
            parseArtCalibrations(prefs.getString(KEY_ART_CALIBRATIONS, null)),
            parsePackages(prefs.getString(KEY_HIDDEN_PACKAGES, DEFAULT_HIDDEN_PACKAGES)),
            prefs.getBoolean(KEY_REQUIRE_PLAYER_SCREEN, true)
        );
    }

    /** "com.github.android,com.other.app" -> {"com.github.android", "com.other.app"}; blank
     *  entries dropped, order kept (LinkedHashSet) since it is all the settings panel has to show
     *  a stable list back from. */
    private static Set<String> parsePackages(String csv) {
        if (csv == null || csv.isEmpty()) return Collections.emptySet();
        Set<String> packages = new LinkedHashSet<>();
        for (String part : csv.split(",")) {
            String trimmed = part.trim();
            if (!trimmed.isEmpty()) packages.add(trimmed);
        }
        return packages;
    }

    /**
     * Where the album art actually sits on this particular phone, once someone has dragged the
     * calibration handle onto it (see ArtCalibrationPuck). Kept out of write() above on purpose:
     * that one mirrors the whole settings panel in one go, and the panel has no idea what the
     * calibration currently is — a slider moved after a calibration would silently throw it away.
     * This key therefore only ever changes from here.
     *
     * @param layoutKey the exact screen size this drag happened on (see formatLayoutKey()) —
     *     never any other entry: recalibrating one of a Fold's four configurations must never
     *     disturb whatever was separately measured in the other three.
     */
    static void writeArtCalibration(Context context, String layoutKey, float offsetX, float offsetY, float scale) {
        SharedPreferences prefs = prefs(context);
        Map<String, float[]> calibrations = parseArtCalibrations(prefs.getString(KEY_ART_CALIBRATIONS, null));
        calibrations.put(layoutKey, new float[] { offsetX, offsetY, scale });
        prefs.edit().putString(KEY_ART_CALIBRATIONS, serializeArtCalibrations(calibrations)).apply();
    }

    /** Back to the modelled position, for whichever exact screen size the phone reports right
     *  now — the settings panel's "Réinitialiser" button. Everyone else's own calibration, on
     *  any other screen size, is untouched. */
    static void resetArtCalibration(Context context) {
        SharedPreferences prefs = prefs(context);
        Map<String, float[]> calibrations = parseArtCalibrations(prefs.getString(KEY_ART_CALIBRATIONS, null));
        calibrations.remove(currentLayoutKey(context));
        prefs.edit().putString(KEY_ART_CALIBRATIONS, serializeArtCalibrations(calibrations)).apply();
    }

    /** "keyA:0.1,-0.2,1.05;keyB:0,0,1" -> {"keyA": [0.1, -0.2, 1.05], "keyB": [0, 0, 1]}; a
     *  malformed entry is skipped rather than failing every other one along with it. Order kept
     *  (LinkedHashMap) purely so a written-back file stays predictable to read by eye. */
    private static Map<String, float[]> parseArtCalibrations(String raw) {
        Map<String, float[]> calibrations = new LinkedHashMap<>();
        if (raw == null || raw.isEmpty()) return calibrations;
        for (String entry : raw.split(";")) {
            int colon = entry.indexOf(':');
            if (colon <= 0) continue;
            String[] parts = entry.substring(colon + 1).split(",", -1);
            if (parts.length != 3) continue;
            try {
                calibrations.put(entry.substring(0, colon), new float[] {
                    Float.parseFloat(parts[0]),
                    Float.parseFloat(parts[1]),
                    Float.parseFloat(parts[2]),
                });
            } catch (NumberFormatException ignored) {
                // One corrupted entry must not take the rest of the calibrations down with it.
            }
        }
        return calibrations;
    }

    private static String serializeArtCalibrations(Map<String, float[]> calibrations) {
        StringBuilder csv = new StringBuilder();
        for (Map.Entry<String, float[]> entry : calibrations.entrySet()) {
            if (csv.length() > 0) csv.append(';');
            float[] v = entry.getValue();
            csv.append(entry.getKey()).append(':').append(v[0]).append(',').append(v[1]).append(',').append(v[2]);
        }
        return csv.toString();
    }

    /** The key artCalibrations/writeArtCalibration() are keyed by: a screen's real width x height
     *  in pixels. Simply the phone's actual current size rather than a guess at "which of N named
     *  layouts is this" — a fold, a rotation, a multi-window resize, a future device shape with
     *  more than two hinges all just become a different width/height, and therefore automatically
     *  their own entry, with no per-shape logic anywhere needing to know how many there are. */
    static String formatLayoutKey(float widthPx, float heightPx) {
        return Math.round(widthPx) + "x" + Math.round(heightPx);
    }

    /** formatLayoutKey() for whatever the real display measures right now, asked of WindowManager
     *  directly — for callers like DeezerMediaPlugin.resetArtCalibration() that have a live
     *  Context but no already-running EdgeGlowView (with its own cached, tick-refreshed reading)
     *  to ask instead. */
    static String currentLayoutKey(Context context) {
        try {
            WindowManager wm = (WindowManager) context.getSystemService(Context.WINDOW_SERVICE);
            if (wm == null) return "?";
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                Rect bounds = wm.getCurrentWindowMetrics().getBounds();
                return formatLayoutKey(bounds.width(), bounds.height());
            }
            DisplayMetrics metrics = new DisplayMetrics();
            wm.getDefaultDisplay().getRealMetrics(metrics);
            return formatLayoutKey(metrics.widthPixels, metrics.heightPixels);
        } catch (Exception e) {
            return "?";
        }
    }

    /**
     * Two earlier, coarser shapes this calibration used to be stored in: first a single flat
     * triple, then briefly one triple per aspect-ratio category ("tall"/"wide") rather than per
     * exact screen size — which still conflated a Fold's closed and open configurations whenever
     * they happened to share an aspect ratio. Neither carries the actual screen size the
     * correction was measured at, so there is nothing to carry forward into the new keyed map;
     * both are simply dropped, once, and whoever had one recalibrates under the finer-grained
     * scheme. Silently discarding a calibration someone dragged into place is a real cost, but a
     * storage key with no size in it has no honest way to become one that has.
     */
    private static void migrateArtCalibration(SharedPreferences prefs) {
        if (!prefs.contains(KEY_ART_OFFSET_X_LEGACY) && !prefs.contains(KEY_ART_OFFSET_X_TALL_LEGACY)) return;
        prefs.edit()
            .remove(KEY_ART_OFFSET_X_LEGACY)
            .remove(KEY_ART_OFFSET_Y_LEGACY)
            .remove(KEY_ART_SCALE_LEGACY)
            .remove(KEY_ART_OFFSET_X_TALL_LEGACY)
            .remove(KEY_ART_OFFSET_Y_TALL_LEGACY)
            .remove(KEY_ART_SCALE_TALL_LEGACY)
            .remove(KEY_ART_OFFSET_X_WIDE_LEGACY)
            .remove(KEY_ART_OFFSET_Y_WIDE_LEGACY)
            .remove(KEY_ART_SCALE_WIDE_LEGACY)
            .apply();
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
        String cocoonFallback,
        String hiddenPackagesCsv,
        boolean requirePlayerScreen
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
            .putString(KEY_HIDDEN_PACKAGES, hiddenPackagesCsv != null ? hiddenPackagesCsv : "")
            .putBoolean(KEY_REQUIRE_PLAYER_SCREEN, requirePlayerScreen)
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
