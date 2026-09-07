package com.vizuzik.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
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

    // Where Deezer's own now-playing album art sits, measured from a reference screenshot:
    // centred horizontally, its top edge a little below Deezer's own top bar, sized as a
    // fraction of the screen's width (it reads as square on the device that screenshot came
    // from). This view has no way to read another app's actual view bounds — there is no
    // accessibility hook wired up for that — so "cocoon" (the one style drawn around a point
    // rather than along the four edges) works from this fixed estimate rather than a real
    // measurement. It will drift on a device or Deezer layout the screenshot doesn't match;
    // there is nothing to correct that against short of adding real layout inspection.
    private static final float ART_CENTER_X_FRACTION = 0.5f;
    private static final float ART_TOP_FRACTION = 0.095f;
    private static final float ART_WIDTH_FRACTION = 0.64f;

    private final Paint paint = new Paint();
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

            invalidate();
        } catch (Exception e) {
            Log.w(TAG, "onTick", e);
        } finally {
            // Kept outside the try body so one bad tick doesn't also kill every tick after it.
            handler.postDelayed(tick, FRAME_INTERVAL_MS);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
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
        // live, mid-overlay, from a settings-panel edit.
        paint.setStyle(Paint.Style.FILL);
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
     * The one style that isn't confined to the four screen edges: a woven ribbon of light (three
     * strands, same idea as src/visualizer.js's "cocoon" scene on Vizuzik's own full-screen
     * player) wrapped around where Deezer's own album art sits on screen — see
     * ART_CENTER_X_FRACTION/ART_TOP_FRACTION/ART_WIDTH_FRACTION above for where that estimate
     * comes from and its limits. Reuses the same beat/palette/ambient state the other two styles
     * already maintain; only the geometry and per-point spectrum sampling are new.
     */
    private void drawCocoon(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        float fx = width * ART_CENTER_X_FRACTION;
        float artHalf = width * ART_WIDTH_FRACTION * 0.5f;
        float fy = height * ART_TOP_FRACTION + artHalf;

        boolean live = lastLevelsAtMs != 0
            && SystemClock.elapsedRealtime() - lastLevelsAtMs < LIVE_LEVELS_TIMEOUT_MS;
        float[] bands = live ? lastBands : null;

        float pulse = clamp01(beatEnergy);
        float restR = artHalf * (1.16f + pulse * 0.05f);
        float t = SystemClock.elapsedRealtime() / 1000f;

        paint.setShader(null);
        paint.setStyle(Paint.Style.STROKE);
        paint.setStrokeJoin(Paint.Join.ROUND);
        paint.setStrokeCap(Paint.Cap.ROUND);

        int strands = 3;
        int spokes = 96;
        Path path = new Path();
        for (int s = 0; s < strands; s++) {
            float phase = t * (0.22f + s * 0.09f) + s * 2.4f;
            float petals = 3 + s;
            float drift = t * (0.16f + s * 0.06f) * (s % 2 == 0 ? 1 : -1);

            path.reset();
            for (int i = 0; i <= spokes; i++) {
                float frac = (float) i / spokes;
                float angle = (float) (frac * Math.PI * 2) + drift;
                float level = sampleLevel(bands, frac);
                float wobble = (float) (
                    Math.sin(angle * petals + phase) * 0.15
                        + Math.sin(angle * petals * 1.6 - phase * 1.3) * 0.06
                );
                float rad = restR * (1 + wobble + level * 0.3f * intensity + pulse * 0.06f * intensity)
                    + s * artHalf * 0.05f;
                float px = fx + (float) Math.cos(angle) * rad;
                float py = fy + (float) Math.sin(angle) * rad;
                if (i == 0) path.moveTo(px, py);
                else path.lineTo(px, py);
            }
            path.close();

            int mainColor = paletteColorAt(s);
            int filamentColor = paletteColorAt(s + 1);

            paint.setColor((mainColor & 0x00FFFFFF) | (clamp255((int) ((36 + pulse * 30f) * brightnessMul)) << 24));
            paint.setStrokeWidth((8f + s * 2f) * thicknessMul * density);
            canvas.drawPath(path, paint);

            // A crisp filament riding the same curve, same trick as the full-screen player's
            // aurora/cocoon scenes: keeps the soft band from reading as fog.
            paint.setColor((filamentColor & 0x00FFFFFF) | (clamp255((int) ((120 + pulse * 90f) * brightnessMul)) << 24));
            paint.setStrokeWidth(1.4f * density);
            canvas.drawPath(path, paint);
        }
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
