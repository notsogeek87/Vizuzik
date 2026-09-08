package com.vizuzik.app;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Color;

import java.util.Collections;
import java.util.LinkedHashSet;
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
    // Deliberately outside write()'s reach — see writeArtCalibration() for why these are the
    // only settings the panel is not allowed to overwrite. One triple per layout the model in
    // EdgeGlowView.artRect() distinguishes (folded/portrait "tall", unfolded/landscape "wide") —
    // see the field comment on EdgeGlowView.artOffsetXTall for why a correction measured in one
    // cannot simply be reused in the other.
    private static final String KEY_ART_OFFSET_X_TALL = "edgeArtOffsetXTall";
    private static final String KEY_ART_OFFSET_Y_TALL = "edgeArtOffsetYTall";
    private static final String KEY_ART_SCALE_TALL = "edgeArtScaleTall";
    private static final String KEY_ART_OFFSET_X_WIDE = "edgeArtOffsetXWide";
    private static final String KEY_ART_OFFSET_Y_WIDE = "edgeArtOffsetYWide";
    private static final String KEY_ART_SCALE_WIDE = "edgeArtScaleWide";
    // Where the single, unsplit calibration used to live, before it became one triple per layout
    // — see migrateArtCalibration().
    private static final String KEY_ART_OFFSET_X_LEGACY = "edgeArtOffsetX";
    private static final String KEY_ART_OFFSET_Y_LEGACY = "edgeArtOffsetY";
    private static final String KEY_ART_SCALE_LEGACY = "edgeArtScale";
    private static final String KEY_HIDDEN_PACKAGES = "edgeHiddenPackages";
    // The one app hidden out of the box, without anyone having to find the picker first — see
    // DeezerMediaPlugin.listInstalledApps() for how they add more. GitHub's own app routinely
    // shows a page (a release, a long README) with a collapsing header — exactly the kind of
    // nested-scroll view that turned out fragile to the touch-occlusion workaround in
    // OverlayEdgeGlowService, on at least one device tested against.
    private static final String DEFAULT_HIDDEN_PACKAGES = "com.github.android";
    // Off by default: unlike everything else this file stores, honouring it at all depends on a
    // grant nobody has unless they went looking for it — see DeezerPlayerAccessibilityService.
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
         *  estimated size. 0/0/1 means "the estimate, untouched". One triple per layout the model
         *  distinguishes — "Tall" folded/portrait, "Wide" unfolded/landscape — since they don't
         *  even share a centre fraction and a correction for one says nothing about the other. */
        final float artOffsetXTall;
        final float artOffsetYTall;
        final float artScaleTall;
        final float artOffsetXWide;
        final float artOffsetYWide;
        final float artScaleWide;
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
            float artOffsetXTall,
            float artOffsetYTall,
            float artScaleTall,
            float artOffsetXWide,
            float artOffsetYWide,
            float artScaleWide,
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
            this.artOffsetXTall = artOffsetXTall;
            this.artOffsetYTall = artOffsetYTall;
            this.artScaleTall = artScaleTall;
            this.artOffsetXWide = artOffsetXWide;
            this.artOffsetYWide = artOffsetYWide;
            this.artScaleWide = artScaleWide;
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
            prefs.getFloat(KEY_ART_OFFSET_X_TALL, 0f),
            prefs.getFloat(KEY_ART_OFFSET_Y_TALL, 0f),
            prefs.getFloat(KEY_ART_SCALE_TALL, 1f),
            prefs.getFloat(KEY_ART_OFFSET_X_WIDE, 0f),
            prefs.getFloat(KEY_ART_OFFSET_Y_WIDE, 0f),
            prefs.getFloat(KEY_ART_SCALE_WIDE, 1f),
            parsePackages(prefs.getString(KEY_HIDDEN_PACKAGES, DEFAULT_HIDDEN_PACKAGES)),
            prefs.getBoolean(KEY_REQUIRE_PLAYER_SCREEN, false)
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
     * These keys therefore only ever change from here.
     *
     * @param wide which of the two stored triples to overwrite — the layout that was actually on
     *     screen for this drag (see EdgeGlowView.currentlyWide()), never both: recalibrating
     *     folded must never disturb whatever was separately measured unfolded.
     */
    static void writeArtCalibration(Context context, boolean wide, float offsetX, float offsetY, float scale) {
        SharedPreferences.Editor editor = prefs(context).edit();
        if (wide) {
            editor.putFloat(KEY_ART_OFFSET_X_WIDE, offsetX)
                .putFloat(KEY_ART_OFFSET_Y_WIDE, offsetY)
                .putFloat(KEY_ART_SCALE_WIDE, scale);
        } else {
            editor.putFloat(KEY_ART_OFFSET_X_TALL, offsetX)
                .putFloat(KEY_ART_OFFSET_Y_TALL, offsetY)
                .putFloat(KEY_ART_SCALE_TALL, scale);
        }
        editor.apply();
    }

    /** Back to the modelled position for *both* layouts at once — the settings panel's "Réinitialiser"
     *  button, which has no way to know which layout the phone is even in right now, unlike a
     *  calibration drag (see writeArtCalibration()) which always happens in one specific layout. */
    static void resetArtCalibration(Context context) {
        prefs(context)
            .edit()
            .putFloat(KEY_ART_OFFSET_X_TALL, 0f)
            .putFloat(KEY_ART_OFFSET_Y_TALL, 0f)
            .putFloat(KEY_ART_SCALE_TALL, 1f)
            .putFloat(KEY_ART_OFFSET_X_WIDE, 0f)
            .putFloat(KEY_ART_OFFSET_Y_WIDE, 0f)
            .putFloat(KEY_ART_SCALE_WIDE, 1f)
            .apply();
    }

    /**
     * An install from before the calibration was split one-per-layout carries a single flat
     * correction under the legacy keys, measured in whichever layout the phone happened to be in
     * at the time — there is no way to tell which after the fact. Seeding both new buckets from
     * it, once, keeps that calibration in effect exactly as before until either layout is
     * recalibrated on its own; discarding it outright would have thrown away a correction someone
     * actually dragged into place for no better reason than a storage format change.
     */
    private static void migrateArtCalibration(SharedPreferences prefs) {
        if (!prefs.contains(KEY_ART_OFFSET_X_LEGACY)) return;
        float offsetX = prefs.getFloat(KEY_ART_OFFSET_X_LEGACY, 0f);
        float offsetY = prefs.getFloat(KEY_ART_OFFSET_Y_LEGACY, 0f);
        float scale = prefs.getFloat(KEY_ART_SCALE_LEGACY, 1f);
        prefs.edit()
            .putFloat(KEY_ART_OFFSET_X_TALL, offsetX)
            .putFloat(KEY_ART_OFFSET_Y_TALL, offsetY)
            .putFloat(KEY_ART_SCALE_TALL, scale)
            .putFloat(KEY_ART_OFFSET_X_WIDE, offsetX)
            .putFloat(KEY_ART_OFFSET_Y_WIDE, offsetY)
            .putFloat(KEY_ART_SCALE_WIDE, scale)
            .remove(KEY_ART_OFFSET_X_LEGACY)
            .remove(KEY_ART_OFFSET_Y_LEGACY)
            .remove(KEY_ART_SCALE_LEGACY)
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
