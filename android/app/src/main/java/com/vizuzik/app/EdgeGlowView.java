package com.vizuzik.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.View;

/**
 * Draws a thin, colored glow along the screen edges, breathing with the music underneath — the
 * one visual this view is allowed to show, since OverlayEdgeGlowService adds it as a
 * touch-transparent window on top of whatever app is in front (Deezer/Spotify/a local player,
 * typically). A full-screen overlay was deliberately rejected (it would make the app underneath
 * illegible); an edge-only glow, MuViz Edge-style, keeps it completely usable.
 *
 * Two regimes, same rule as src/visualizer.js's ambient/live split: with real audio levels
 * flowing in from AudioLevelsBridge, the glow's thickness and a beat pulse follow the actual
 * bass; without it (capture not granted, or momentarily stalled), the glow breathes on its own —
 * quickly and visibly enough to read as alive on a glance — but never claims to follow a beat it
 * never captured. In both regimes the color drifts across the current track's three accent
 * colors (or a fixed custom palette, see EdgeConfig), and a real event (track change, play/pause)
 * is still allowed an honest pulse.
 *
 * Three rendering styles, picked in the settings panel (EdgeConfig): "bars", the default, drawing
 * each of the 32 bands on its own; "glow", the border that averages them into one scalar; and
 * "cocoon", the one style that isn't edge-only — see drawCocoon() below for why and how.
 */
final class EdgeGlowView extends View {

    private static final String TAG = "EdgeGlowView";
    // A plain Handler loop rather than Choreographer.postFrameCallback(): this view belongs to a
    // Service's overlay window, not an Activity, and Choreographer's vsync-driven callback can
    // simply never fire a second time for such a window on some devices/Android builds — leaving
    // the very first frame on screen forever. A Handler tied to the main Looper's own message
    // queue has no such dependency on the window being considered for vsync by the system; a
    // plain border glow doesn't need frame-perfect vsync timing anyway.
    private static final long FRAME_INTERVAL_MS = 42; // ~24fps
    private static final int[][] FALLBACK_PALETTE = {
        { 124, 92, 255 },
        { 236, 72, 153 },
        { 56, 189, 248 },
    };
    private static final long PALETTE_BLEND_MS = 700;
    private static final long COLOR_TRAVEL_MS = 9_000;
    private static final int BEAT_HISTORY = 48;
    // How long a pulse (a real beat, or pulse() from a track change / play-pause) stays visible.
    // This view's border is the only thing carrying that impulse, so it needs to linger for
    // roughly a second and a half to actually register on a glance instead of flashing briefly.
    private static final float PULSE_DECAY_MS = 55f;
    /** Past this long without a single level, the capture counts as gone and ambient takes over. */
    private static final long LIVE_LEVELS_TIMEOUT_MS = 1_200;

    // 32 bands, 55 Hz-7000 Hz logarithmic — the layout TrackedSessionAudioSource produces.
    // Rough index ranges for EdgeConfig's frequency choice: bass ~55-190 Hz (0-7),
    // mid ~190-1700 Hz (8-21), treble ~1700-7000 Hz (22-31).
    private static final int BASS_END = 8;
    private static final int MID_END = 22;
    // How far a bar may reach inward, as a fraction of the screen dimension it grows along.
    // Deliberately well under half: opposite edges are both enabled by default, so anything more
    // lets two rows meet on a loud passage and cover the middle of whatever is underneath — this
    // overlay is meant to frame the tracked app, not hide it.
    private static final float BAR_MAX_FRACTION = 0.3f;

    // Where Deezer's own now-playing album art sits. This view has no way to read another app's
    // actual view bounds — there is no accessibility hook wired up for that — so "cocoon", the
    // one style drawn around a point rather than along the four edges, works from measurements
    // taken off screenshots.
    //
    // Deezer lays that screen out two ways, and which one is up can be told from this window's
    // own shape, without asking Deezer anything. Both were measured on a Z Fold:
    //   folded   1248x1823 — one column, the cover a square 0.583 of the width, top edge 0.105
    //                        of the height down, centred horizontally
    //   unfolded 2448x1575 — two panes, the cover moved into the left one: vertically centred,
    //                        centred on the first quarter of the width, and sized off the
    //                        *height* (0.619) since height is what constrains a wide layout
    //
    // A screen shape or a Deezer version far from either of those drifts, and nothing here can
    // correct for that short of real layout inspection.
    private static final float ART_TALL_CENTER_X_FRACTION = 0.5f;
    private static final float ART_TALL_TOP_FRACTION = 0.105f;
    private static final float ART_TALL_WIDTH_FRACTION = 0.583f;
    private static final float ART_WIDE_CENTER_X_FRACTION = 0.25f;
    private static final float ART_WIDE_CENTER_Y_FRACTION = 0.5f;
    private static final float ART_WIDE_HEIGHT_FRACTION = 0.619f;

    // The cocoon bundle, in multiples of the artwork's half-size. What makes it read as a ribbon
    // of light rather than a few loops is the density: two dozen hairlines packed into a narrow
    // band. Three thick translucent lines — the first attempt — just looked like three lines.
    //
    // The path is a superellipse, not a circle: the thing it frames is a square cover, and a
    // circle around a square leaves gaps at the edge midpoints and crowds the corners.
    private static final int COCOON_STRANDS = 24;
    private static final int COCOON_SPOKES = 110;
    private static final float COCOON_INNER = 1.04f;
    private static final float COCOON_BAND = 0.17f;
    private static final float COCOON_SWING = 0.11f;
    private static final float COCOON_SHEAR = 0.42f;
    private static final float COCOON_SQUIRCLE = 3.4f;
    // Sum of the three lobe amplitudes below, used to bias the wave into 0..1 so it can only
    // ever push a strand outward: this view draws on top of the music app, so anything that
    // dipped inward would crawl across the album art it is supposed to be framing.
    private static final float COCOON_WAVE_MAX = 0.085f + 0.042f + 0.055f;

    private final Paint paint = new Paint();
    // Reused across strands and frames: drawCocoon() rebuilds it 30 times per frame, and
    // allocating a Path each time would hand the collector ~700 of them a second.
    private final Path cocoonPath = new Path();
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;

    private int[][] fromPalette = FALLBACK_PALETTE;
    private int[][] toPalette = FALLBACK_PALETTE;
    private long paletteBlendStartMs;

    private float colorShift;
    private long lastFrameMs;

    // Written from the audio-capture thread, read from the UI thread on every frame: plain
    // volatile fields rather than a lock: a decorative glow can tolerate a one-frame-old value,
    // but must never block the capture loop.
    //
    // "Live" is a timestamp rather than a boolean on purpose: a boolean set on the first level
    // and never cleared is exactly how the glow used to freeze — one silent death of the capture
    // and the last received level stayed on screen forever. Levels going stale now falls back to
    // the ambient regime on its own.
    private volatile long lastLevelsAtMs;
    private volatile float level;
    private volatile boolean pendingBeat;

    private final float[] bassHistory = new float[BEAT_HISTORY];
    private int bassCursor;
    private long lastBeatAtMs;
    private float beatEnergy;

    // Ambient-only breathing: three periods with no common multiple, so the glow never seems to
    // loop — same idea as _updateAmbient() in visualizer.js, just three oscillators instead of
    // per-band ones since this view has no spectrum to speak of, only a border.
    private float ambientPhase;

    // Last full 32-band array received (whichever source fed it) — kept separately from the
    // single-scalar `level` above so the "bars" diagnostic style can show each band's own value
    // instead of the one number the border glow reduces the whole spectrum to. A plain volatile
    // reference swap, not a copy: TrackedSessionAudioSource/AudioLevelsBridge already hand over a
    // freshly cloned array on every call that's never mutated again afterward, so publishing the
    // reference itself is safe without an extra copy here.
    private volatile float[] lastBands;

    // EdgeConfig-driven knobs, applied by OverlayEdgeGlowService — see applyConfig().
    private String style = EdgeConfig.STYLE_GLOW;
    private String band = EdgeConfig.BAND_FULL;
    private float intensity = 1f;
    private float thicknessMul = 1f;
    private float brightnessMul = 1f;
    private float sensitivity = 1f;
    private int[][] customPalette; // non-null only in "custom" color mode
    private boolean edgeTop = true;
    private boolean edgeBottom = true;
    private boolean edgeLeft = true;
    private boolean edgeRight = true;
    private boolean onlyOverMusicApp = true;

    // Whether the tracked app is currently something other than what's on screen, so this window
    // should paint nothing. Re-evaluated on a slow timer rather than per frame: answering it
    // costs a query to UsageStatsManager (see ForegroundApp), and a second of lag when leaving
    // the music app is not worth paying for it 24 times a second.
    private static final long FOREGROUND_CHECK_MS = 1_000;
    private boolean suppressed;
    private long lastForegroundCheckAtMs;

    EdgeGlowView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
    }

    /** Applied once at startup and again whenever the settings panel changes something while the
     *  overlay is already running (see OverlayEdgeGlowService's SharedPreferences listener). */
    void applyConfig(EdgeConfig.Snapshot config) {
        style = config.style;
        band = config.band;
        intensity = config.intensity;
        thicknessMul = config.thickness;
        brightnessMul = config.brightness;
        sensitivity = config.sensitivity;
        customPalette = config.customPalette;
        edgeTop = config.top;
        edgeBottom = config.bottom;
        edgeLeft = config.left;
        edgeRight = config.right;
        onlyOverMusicApp = config.onlyOverMusicApp;
        // Answer again on the next tick rather than keep a verdict reached under the old setting.
        lastForegroundCheckAtMs = 0;
    }

    void setPalette(int[][] palette) {
        fromPalette = currentAutoPalette();
        toPalette = palette != null ? palette : FALLBACK_PALETTE;
        paletteBlendStartMs = SystemClock.elapsedRealtime();
    }

    /** The capture feeding this view stopped for good — drop straight back to ambient. */
    void clearLevels() {
        lastLevelsAtMs = 0;
        level = 0f;
        beatEnergy = 0f;
        lastBands = null;
    }

    /**
     * A real, honest impulse — a track change or a play/pause — the only kind ambient mode is
     * allowed to show. Called from OverlayEdgeGlowService. Harmless in live mode too: real beats
     * already drive beatEnergy just as strongly.
     */
    void pulse(float strength) {
        beatEnergy = Math.max(beatEnergy, clamp01(strength));
    }

    /**
     * Called from the capture engine's worker thread rather than the main one, and the view can
     * be re-fed by a new capture while an old one's last callback is still in flight.
     * Synchronized because bassHistory/bassCursor below are a plain
     * ring buffer with no other protection: two unsynchronized writers could tear a value or
     * lose an increment, corrupting the beat detector. The critical section is a fixed handful of
     * float operations on a 48-element array, called at most a few dozen times a second — never
     * enough contention for a lock to be worth avoiding.
     */
    synchronized void pushLevels(float[] bands) {
        if (bands == null || bands.length == 0) return;
        lastBands = bands;

        int bassBands = Math.min(BASS_END, bands.length);
        float bassSum = 0f;
        for (int i = 0; i < bassBands; i++) bassSum += bands[i];
        float newBass = bassBands > 0 ? bassSum / bassBands : 0f;

        float newLevel = averageForBand(bands);

        bassHistory[bassCursor] = newBass;
        bassCursor = (bassCursor + 1) % BEAT_HISTORY;
        float avg = 0f;
        for (float v : bassHistory) avg += v;
        avg /= BEAT_HISTORY;

        long now = SystemClock.elapsedRealtime();
        boolean isPeak = newBass > avg * 1.32f + 0.015f && newBass > 0.06f;
        if (isPeak && now - lastBeatAtMs > 190) {
            lastBeatAtMs = now;
            pendingBeat = true;
        }

        level = clamp01(newLevel * sensitivity);
        lastLevelsAtMs = now;
    }

    /** Averages whichever slice of the 32-band spectrum EdgeConfig's frequency choice selects —
     *  "full" (the whole spectrum, same as the full-screen visualizer) or one of bass/mid/treble
     *  for someone who wants the border to track a narrower range than the beat detector does. */
    private float averageForBand(float[] bands) {
        int from = bandFrom(bands.length);
        int to = bandTo(bands.length);
        if (to <= from) return 0f;
        float sum = 0f;
        for (int i = from; i < to; i++) sum += bands[i];
        return sum / (to - from);
    }

    /** First band of the [from, to) slice EdgeConfig's frequency choice selects — shared with
     *  drawBars(), so the panel's Fréquences setting narrows the spectrum in both styles rather
     *  than only in the glow's average. */
    private int bandFrom(int length) {
        if (EdgeConfig.BAND_MID.equals(band)) return Math.min(BASS_END, length);
        if (EdgeConfig.BAND_TREBLE.equals(band)) return Math.min(MID_END, length);
        return 0;
    }

    private int bandTo(int length) {
        if (EdgeConfig.BAND_BASS.equals(band)) return Math.min(BASS_END, length);
        if (EdgeConfig.BAND_MID.equals(band)) return Math.min(MID_END, length);
        return length;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameMs = SystemClock.elapsedRealtime();
        handler.post(tick);
    }

    @Override
    protected void onDetachedFromWindow() {
        handler.removeCallbacks(tick);
        super.onDetachedFromWindow();
    }

    private void onTick() {
        // This and onDraw() below both run on the app's single main thread — the same one the
        // whole webview and every Activity run on. An exception escaping either of them would
        // crash the entire app, not just this decorative overlay, so both are defensive about
        // anything unexpected (a null palette entry, a transient view-detach race) rather than
        // ever letting that happen for the sake of a border glow.
        try {
            long now = SystemClock.elapsedRealtime();
            long dtMs = Math.max(0, Math.min(200, now - lastFrameMs));
            lastFrameMs = now;

            colorShift = (colorShift + dtMs / (float) COLOR_TRAVEL_MS * 3f) % 3f;

            if (pendingBeat) {
                pendingBeat = false;
                beatEnergy = 1f;
            }
            beatEnergy *= (float) Math.pow(0.9, dtMs / PULSE_DECAY_MS);

            ambientPhase += dtMs;
            updateSuppression(now);

            invalidate();
        } catch (Exception e) {
            Log.w(TAG, "onTick", e);
        } finally {
            // Kept outside the try body so one bad tick doesn't also kill every tick after it.
            handler.postDelayed(tick, FRAME_INTERVAL_MS);
        }
    }

    /**
     * Decides whether this window should be showing anything at all right now. Only ever hides
     * on a definite answer — the setting is on, the permission is granted, and the app on screen
     * is something else; anything it cannot establish leaves the overlay visible, since a
     * decoration that silently refuses to appear is a far worse failure than one that appears
     * over an app it needn't have.
     */
    private void updateSuppression(long now) {
        if (lastForegroundCheckAtMs != 0 && now - lastForegroundCheckAtMs < FOREGROUND_CHECK_MS) return;
        lastForegroundCheckAtMs = now;
        if (!onlyOverMusicApp) {
            suppressed = false;
            return;
        }
        Context context = getContext();
        if (context == null || !ForegroundApp.hasUsageAccess(context)) {
            suppressed = false;
            return;
        }
        suppressed = !ForegroundApp.isTrackedAppInForeground(context);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            // Drawing nothing empties this window's display list, so the overlay disappears
            // without the service having to be torn down and rebuilt every time someone glances
            // at another app.
            if (suppressed) return;
            if (EdgeConfig.STYLE_BARS.equals(style)) {
                drawBars(canvas);
            } else if (EdgeConfig.STYLE_COCOON.equals(style)) {
                drawCocoon(canvas);
            } else {
                drawGlow(canvas);
            }
        } catch (Exception e) {
            Log.w(TAG, "onDraw", e);
        }
    }

    /**
     * The default style: each of the 32 bands drawn on its own rather than reduced to the single
     * scalar drawGlow() works from. Started as a way to answer "is the data actually moving, or is
     * the border just not showing it" — an average can sit fairly still while the individual bands
     * swing a lot — and stayed the default because it shows what the capture delivers directly.
     */
    private void drawBars(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        // Same liveness check drawGlow() uses to fall back to ambient: without it, a capture that
        // dies silently (no explicit clearLevels() call) would leave the last frame's bars lit on
        // screen forever, exactly the frozen-glow bug this whole timeout mechanism exists to
        // prevent — bars have no ambient regime to fall back to, so "not live" just means blank.
        boolean live = lastLevelsAtMs != 0
            && SystemClock.elapsedRealtime() - lastLevelsAtMs < LIVE_LEVELS_TIMEOUT_MS;
        float[] bands = lastBands;
        if (!live || bands == null || bands.length == 0) {
            return;
        }

        int from = bandFrom(bands.length);
        int to = bandTo(bands.length);
        if (to <= from) return;

        int color = displayColor();
        paint.setShader(null);
        // Reset from whatever drawCocoon() may have left the shared Paint in — style can change
        // live, mid-overlay, from a settings-panel edit.
        paint.setStyle(Paint.Style.FILL);

        // Every edge the panel enables, not the bottom alone: leaving only Gauche/Droite checked
        // would otherwise render nothing at all, which looks exactly like a capture that died.
        if (edgeBottom) drawBarRow(canvas, bands, from, to, color, width, height, false);
        if (edgeTop) drawBarRow(canvas, bands, from, to, color, width, height, true);
        if (edgeLeft) drawBarColumn(canvas, bands, from, to, color, width, height, false);
        if (edgeRight) drawBarColumn(canvas, bands, from, to, color, width, height, true);
    }

    /** Peak length for one bar, honouring the thickness slider but never past the point where two
     *  opposite rows would collide. */
    private float barLimit(int extent) {
        return Math.min(extent * BAR_MAX_FRACTION * thicknessMul, extent * 0.45f);
    }

    private float barLength(float bandLevel, int extent) {
        float level = clamp01(bandLevel * intensity * sensitivity);
        return Math.max(2f * density, level * barLimit(extent));
    }

    private void applyBarPaint(float bandLevel, int color) {
        float level = clamp01(bandLevel * intensity * sensitivity);
        int alpha = clamp255((int) ((120 + level * 135f) * brightnessMul));
        paint.setColor((color & 0x00FFFFFF) | (alpha << 24));
    }

    /** One row of bars along a horizontal edge, growing inward from it. */
    private void drawBarRow(Canvas canvas, float[] bands, int from, int to, int color, int width, int height, boolean fromTop) {
        int n = to - from;
        float slot = (float) width / n;
        for (int i = 0; i < n; i++) {
            float length = barLength(bands[from + i], height);
            applyBarPaint(bands[from + i], color);
            float left = i * slot;
            canvas.drawRect(
                left + slot * 0.15f,
                fromTop ? 0 : height - length,
                left + slot * 0.85f,
                fromTop ? length : height,
                paint
            );
        }
    }

    /** One column of bars along a vertical edge, growing inward from it. */
    private void drawBarColumn(Canvas canvas, float[] bands, int from, int to, int color, int width, int height, boolean fromRight) {
        int n = to - from;
        float slot = (float) height / n;
        for (int i = 0; i < n; i++) {
            float length = barLength(bands[from + i], width);
            applyBarPaint(bands[from + i], color);
            float top = i * slot;
            canvas.drawRect(
                fromRight ? width - length : 0,
                top + slot * 0.15f,
                fromRight ? width : length,
                top + slot * 0.85f,
                paint
            );
        }
    }

    private void drawGlow(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        // Reset from whatever drawCocoon() may have left the shared Paint in — style can change
        // live, mid-overlay, from a settings-panel edit. The alpha matters as much as the style
        // here: this method carries its own alpha inside the gradient's colours, so a leftover
        // per-strand alpha would silently scale the whole glow down.
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        int color = displayColor();
        // beatEnergy carries either a real detected beat (live) or a real event's pulse() —
        // track change, play/pause — in both regimes.
        float pulse = clamp01(beatEnergy);
        float strength;
        boolean live = lastLevelsAtMs != 0
            && SystemClock.elapsedRealtime() - lastLevelsAtMs < LIVE_LEVELS_TIMEOUT_MS;
        if (live) {
            strength = clamp01(level);
        } else {
            // Symmetric breathing on three incommensurate waves — no beat, no invented tempo,
            // but fast and wide enough (7s/11s/17s, ±0.4 around a 0.5 mid-point) to actually read
            // as "alive" within a few seconds' glance, unlike the slower wash the full-screen
            // player uses for something meant to be watched continuously, not glanced at.
            double a = Math.sin(ambientPhase / 7_000.0 * Math.PI * 2);
            double b = Math.sin(ambientPhase / 11_000.0 * Math.PI * 2 + 1.7);
            double c = Math.sin(ambientPhase / 17_000.0 * Math.PI * 2 + 3.1);
            strength = (float) (0.5 + (a + b + c) / 3.0 * 0.4);
        }
        strength = clamp01(strength * intensity);

        // Bolder than a typical "glow" on purpose: this is meant to be noticeable at a glance
        // across a room, over whatever app is in front, not a subtle edge highlight — a first
        // pass at these constants read as too discreet on-device.
        float thicknessDp = (22f + strength * 70f + pulse * 40f) * thicknessMul;
        float thickness = Math.max(0f, thicknessDp) * density;
        int alpha = clamp255((int) ((190 + strength * 120f + pulse * 70f) * brightnessMul));

        int edgeColor = (color & 0x00FFFFFF) | (alpha << 24);
        int transparent = color & 0x00FFFFFF;

        if (edgeTop) drawEdge(canvas, 0, 0, width, thickness, edgeColor, transparent, true);
        if (edgeBottom) drawEdge(canvas, 0, height - thickness, width, thickness, edgeColor, transparent, false);
        if (edgeLeft) drawEdgeVertical(canvas, 0, 0, thickness, height, edgeColor, transparent, true);
        if (edgeRight) drawEdgeVertical(canvas, width - thickness, 0, thickness, height, edgeColor, transparent, false);
    }

    private void drawEdge(Canvas canvas, float left, float top, float w, float h, int from, int to, boolean fromTop) {
        float startY = fromTop ? top : top + h;
        float endY = fromTop ? top + h : top;
        paint.setShader(new LinearGradient(0, startY, 0, endY, from, to, Shader.TileMode.CLAMP));
        canvas.drawRect(left, top, left + w, top + h, paint);
    }

    private void drawEdgeVertical(Canvas canvas, float left, float top, float w, float h, int from, int to, boolean fromLeft) {
        float startX = fromLeft ? left : left + w;
        float endX = fromLeft ? left + w : left;
        paint.setShader(new LinearGradient(startX, 0, endX, 0, from, to, Shader.TileMode.CLAMP));
        canvas.drawRect(left, top, left + w, top + h, paint);
    }

    /**
     * The one style that isn't confined to the four screen edges: a ribbon of light wrapped
     * around where Deezer's own album art sits on screen — see ART_* above for where that
     * estimate comes from and its limits.
     *
     * The ribbon is one wave swept through the bundle: every strand rides the same lobed shape,
     * pushed a little further out and sheared a little further along in phase than the one
     * inside it. That shear is the whole trick — it fans the bundle open on one side of a lobe
     * and pinches it shut on the other. Hairlines, not thick bands: the light comes from how
     * many of them there are, never from any one being bright.
     *
     * Two details exist because this draws over *another app*, not over Vizuzik's own dark
     * screen, and Deezer tints its now-playing page from the cover — so the backdrop is
     * regularly pale. A diagonal gradient gives the bundle a bright crest instead of one flat
     * milky tone, and each strand is laid over a darker, wider copy of itself, the same reason
     * light UI text carries a shadow. Additive blending was tried first and is what a pale
     * backdrop defeats completely: screening light onto an already-bright green page changes
     * almost nothing.
     *
     * Reuses the beat/palette/ambient state the other two styles maintain; the geometry, the
     * sweep and the per-point spectrum sampling are what's specific here.
     */
    private void drawCocoon(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        // Landscape means Deezer's two-pane layout, portrait its one-column one — see the ART_*
        // constants. Read fresh every frame, so folding or unfolding the device moves the ribbon
        // with the cover instead of needing anything to be told about it.
        boolean wide = width > height;
        float half = wide
            ? height * ART_WIDE_HEIGHT_FRACTION * 0.5f
            : width * ART_TALL_WIDTH_FRACTION * 0.5f;
        float cx = width * (wide ? ART_WIDE_CENTER_X_FRACTION : ART_TALL_CENTER_X_FRACTION);
        float cy = wide ? height * ART_WIDE_CENTER_Y_FRACTION : height * ART_TALL_TOP_FRACTION + half;
        if (half <= 0) return;

        boolean live = lastLevelsAtMs != 0
            && SystemClock.elapsedRealtime() - lastLevelsAtMs < LIVE_LEVELS_TIMEOUT_MS;
        float[] bands = live ? lastBands : null;
        float loud = live ? clamp01(level) : 0.22f;
        float pulse = clamp01(beatEnergy);
        float t = SystemClock.elapsedRealtime() / 1000f;

        // Clamped rather than left to the slider alone: past roughly the cover's own size the
        // ribbon stops framing it and starts burying the app around it.
        float band = Math.max(half * 0.08f, Math.min(half * COCOON_BAND * thicknessMul, half * 0.45f));
        float swing = half * COCOON_SWING;

        // How much room there actually is between the cover and the nearest screen edge. On the
        // unfolded layout the cover sits barely a tenth of the width from the left edge, so
        // without this the bundle just runs off it and the ribbon reads as cut in half.
        float room = Math.min(Math.min(cx, width - cx), Math.min(cy, height - cy)) * 0.98f
            - half * COCOON_INNER;
        if (room > 0 && band + swing > room) {
            float squeeze = room / (band + swing);
            band *= squeeze;
            swing *= squeeze;
        }
        float outer = half * COCOON_INNER + band + swing;

        drawCocoonHalo(canvas, cx, cy, outer, loud, pulse);

        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);
        paint.setAntiAlias(true);

        float lineWidth = Math.max(1f, 0.55f * density * thicknessMul);
        float phaseA = t * 0.23f;
        float phaseB = t * 0.17f;
        int alpha = clamp255((int) ((150 + loud * 40f + pulse * 26f) * brightnessMul));
        Shader sweep = buildCocoonSweep(cx, cy, half, t);

        for (int s = 0; s < COCOON_STRANDS; s++) {
            float u = (float) s / (COCOON_STRANDS - 1); // 0 against the artwork .. 1 outermost
            float shear = u * COCOON_SHEAR;

            cocoonPath.reset();
            for (int i = 0; i <= COCOON_SPOKES; i++) {
                float f = (float) i / COCOON_SPOKES;
                float angle = (float) (f * Math.PI * 2);
                float wave = (float) (
                    Math.sin(angle * 3 + phaseA + shear) * 0.085
                        + Math.sin(angle * 5 - phaseB * 1.3 + shear * 1.7) * 0.042
                        + Math.sin(angle * 2 - phaseB * 0.7 - shear) * 0.055
                );
                float wave01 = (wave + COCOON_WAVE_MAX) / (2 * COCOON_WAVE_MAX);
                float react = (sampleLevel(bands, f) * 0.09f + pulse * 0.03f) * intensity;
                float radius = half * squircle(angle) * COCOON_INNER
                    + band * u
                    + swing * wave01
                    + half * react * (0.25f + u * 0.5f);
                float px = cx + (float) Math.cos(angle) * radius;
                float py = cy + (float) Math.sin(angle) * radius;
                if (i == 0) cocoonPath.moveTo(px, py);
                else cocoonPath.lineTo(px, py);
            }
            cocoonPath.close();

            paint.setShader(null);
            paint.setColor(0);
            paint.setAlpha(clamp255((int) (46 * (1 - 0.5f * u))));
            paint.setStrokeWidth(lineWidth * 2.4f);
            canvas.drawPath(cocoonPath, paint);

            // Dense and bright against the cover, dissolving outward: a flat bundle reads as a
            // ring, a fading one reads as a ribbon with a spine.
            paint.setShader(sweep);
            paint.setAlpha(clamp255((int) (alpha * (1 - 0.6f * u))));
            paint.setStrokeWidth(lineWidth);
            canvas.drawPath(cocoonPath, paint);
        }

        paint.setShader(null);
        drawCocoonSparks(canvas, cx, cy, half, band + swing, t, loud, pulse);
        paint.setAntiAlias(false);
    }

    /**
     * The diagonal sweep every strand is stroked with: near-white where the light "falls", back
     * into the album's own colour away from it, turning slowly so the crest travels around the
     * ribbon. Built once per frame and shared — per-strand brightness comes from Paint's alpha,
     * which multiplies a shader rather than replacing it.
     */
    private Shader buildCocoonSweep(float cx, float cy, float half, float t) {
        float angle = t * 0.11f;
        float reach = half * 1.6f;
        float dx = (float) Math.cos(angle) * reach;
        float dy = (float) Math.sin(angle) * reach;
        int[] colors = {
            withAlpha(lit(paletteColorAt(0.35f), 0.15f), 140),
            withAlpha(lit(paletteColorAt(0.35f), 0.92f), 255),
            withAlpha(lit(paletteColorAt(0.95f), 0.45f), 230),
            withAlpha(lit(paletteColorAt(1.35f), 0.20f), 128),
        };
        float[] stops = { 0f, 0.35f, 0.62f, 1f };
        return new LinearGradient(cx - dx, cy - dy, cx + dx, cy + dy, colors, stops, Shader.TileMode.CLAMP);
    }

    /** The soft bloom the strands sit in. A ring around the bundle, transparent in the middle:
     *  a plain disc would wash out the album art it is supposed to be framing. */
    private void drawCocoonHalo(Canvas canvas, float cx, float cy, float outer, float loud, float pulse) {
        float radius = outer * 1.35f;
        if (radius <= 0) return;
        int glow = lit(paletteColorAt(0.35f), 0.4f) & 0x00FFFFFF;
        int peak = glow | (clamp255((int) ((16 + loud * 18f + pulse * 26f) * brightnessMul)) << 24);
        int[] colors = { glow, glow, peak, glow };
        float[] stops = { 0f, 0.6f, 0.82f, 1f };
        paint.setStyle(Paint.Style.FILL);
        paint.setAlpha(255);
        paint.setShader(new RadialGradient(cx, cy, radius, colors, stops, Shader.TileMode.CLAMP));
        canvas.drawCircle(cx, cy, radius, paint);
        paint.setShader(null);
    }

    /** Motes drifting in the ribbon. Deterministic from the index alone — no per-frame state to
     *  keep, and the field stays put across a rotation instead of reshuffling. */
    private void drawCocoonSparks(Canvas canvas, float cx, float cy, float half, float bandWidth,
                                  float t, float loud, float pulse) {
        paint.setStyle(Paint.Style.FILL);
        for (int i = 0; i < 16; i++) {
            float seed = i * 2.399963f; // golden angle: spreads them without a visible pattern
            float angle = seed + t * (0.05f + (i % 5) * 0.012f);
            float radius = half * squircle(angle) * COCOON_INNER
                + bandWidth * (0.15f + 0.9f * frac01((float) Math.sin(seed * 12.9898f) * 43758.547f));
            float twinkle = 0.35f + 0.65f * (float) Math.abs(Math.sin(t * 1.7 + seed));
            paint.setColor(withAlpha(
                lit(paletteColorAt(i % 3), 0.55f),
                clamp255((int) ((70 + loud * 90f + pulse * 60f) * twinkle * brightnessMul))
            ));
            float size = (1f + (i % 3) * 0.55f) * density * (0.7f + loud);
            canvas.drawCircle(
                cx + (float) Math.cos(angle) * radius,
                cy + (float) Math.sin(angle) * radius,
                size,
                paint
            );
        }
    }

    /** Radius of a rounded square (superellipse) of half-size 1 at this angle. */
    private static float squircle(float angle) {
        double c = Math.abs(Math.cos(angle));
        double s = Math.abs(Math.sin(angle));
        double d = Math.pow(Math.pow(c, COCOON_SQUIRCLE) + Math.pow(s, COCOON_SQUIRCLE), 1.0 / COCOON_SQUIRCLE);
        return d <= 0 ? 1f : (float) (1.0 / d);
    }

    /** Lifts a colour towards white. The ribbon has to stay legible over a pale app background,
     *  and its crests read as light rather than as paint. */
    private static int lit(int color, float amount) {
        int r = Color.red(color);
        int g = Color.green(color);
        int b = Color.blue(color);
        return Color.rgb(
            Math.round(r + (255 - r) * amount),
            Math.round(g + (255 - g) * amount),
            Math.round(b + (255 - b) * amount)
        );
    }

    private static int withAlpha(int color, int alpha) {
        return (color & 0x00FFFFFF) | (clamp255(alpha) << 24);
    }

    /** Fractional part, always positive — the mote hash relies on it to spread them. */
    private static float frac01(float value) {
        float f = value - (float) Math.floor(value);
        return Float.isNaN(f) ? 0f : f;
    }

    /**
     * Spectrum level for one point around the ribbon, mirrored the same way the full-screen
     * player's _mirroredBand() is: bass in the middle of the ring, treble at the seam. Without
     * live bands (capture not granted, or momentarily stalled) this falls back to the same
     * three-incommensurate-wave breathing drawGlow() uses in ambient mode, phase-shifted by
     * position around the ring so it reads as a slow current rather than one uniform pulse — a
     * gentle "still alive" without ever claiming to have heard a beat it didn't.
     */
    private float sampleLevel(float[] bands, float frac) {
        if (bands != null && bands.length > 0) {
            float d = Math.abs(frac - 0.5f) * 2f;
            int idx = Math.round(d * (bands.length - 1));
            idx = Math.max(0, Math.min(bands.length - 1, idx));
            return clamp01(bands[idx] * sensitivity);
        }
        double a = Math.sin((ambientPhase + frac * 4000) / 7_000.0 * Math.PI * 2);
        double b = Math.sin((ambientPhase + frac * 6000) / 11_000.0 * Math.PI * 2 + 1.7);
        double c = Math.sin((ambientPhase + frac * 3000) / 17_000.0 * Math.PI * 2 + 3.1);
        return clamp01((float) (0.15 + (a + b + c) / 3.0 * 0.12));
    }

    private int displayColor() {
        return paletteColorAt(0f);
    }

    /**
     * Palette colour at a floating index, offset by the same ambient colour travel displayColor()
     * rides on — lets several elements each sit at their own fixed offset into the travelling
     * palette instead of all showing the exact same colour at once. drawCocoon()'s three strands
     * use this at 0/1/2 so they read as distinct threads rather than one flat ring.
     */
    private int paletteColorAt(float floatIndex) {
        int[][] palette = currentPalette();
        float p = floatIndex + colorShift;
        int base = (int) Math.floor(p);
        int index = ((base % 3) + 3) % 3;
        int next = (index + 1) % 3;
        float frac = p - (float) Math.floor(p);
        int[] a = palette[index];
        int[] b = palette[next];
        int r = Math.round(a[0] + (b[0] - a[0]) * frac);
        int g = Math.round(a[1] + (b[1] - a[1]) * frac);
        int bl = Math.round(a[2] + (b[2] - a[2]) * frac);
        return Color.rgb(clamp255(r), clamp255(g), clamp255(bl));
    }

    /** Custom palette (fixed, user-chosen colors) bypasses the cover-driven blend entirely — see
     *  currentAutoPalette() for the "auto" behavior this replaces. */
    private int[][] currentPalette() {
        return customPalette != null ? customPalette : currentAutoPalette();
    }

    /** Blends fromPalette toward toPalette over PALETTE_BLEND_MS — see setPalette(). */
    private int[][] currentAutoPalette() {
        long elapsed = SystemClock.elapsedRealtime() - paletteBlendStartMs;
        if (elapsed >= PALETTE_BLEND_MS) return toPalette;
        float t = Math.max(0f, elapsed / (float) PALETTE_BLEND_MS);
        int[][] blended = new int[3][3];
        for (int i = 0; i < 3; i++) {
            int[] from = fromPalette[i];
            int[] to = toPalette[i];
            for (int c = 0; c < 3; c++) {
                blended[i][c] = Math.round(from[c] + (to[c] - from[c]) * t);
            }
        }
        return blended;
    }

    private static float clamp01(float value) {
        if (Float.isNaN(value)) return 0f;
        return value < 0 ? 0 : Math.min(value, 1);
    }

    private static int clamp255(int value) {
        return value < 0 ? 0 : Math.min(value, 255);
    }
}
