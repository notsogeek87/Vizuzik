package com.vizuzik.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.View;

/**
 * Draws a thin, colored glow along the four screen edges, breathing with the music underneath —
 * the one visual this view is allowed to show, since OverlayEdgeGlowService adds it as a
 * touch-transparent window on top of whatever app is in front (see the service for why it's
 * edges only, not a full-screen scene: MuViz Edge's namesake feature, chosen over a full overlay
 * so the app underneath — Deezer, typically — stays completely legible and usable).
 *
 * Two regimes, same rule as src/visualizer.js's ambient/live split (see
 * docs/architecture/2026-09-03-rythme-hors-capture.md): with real audio levels flowing in from
 * AudioLevelsBridge, the glow's thickness and a beat pulse follow the actual bass; without it,
 * the glow just breathes on slow independent oscillators and never invents a beat. In both
 * regimes the color itself drifts slowly across the current track's three accent colors — that
 * part isn't rhythm, so it's always on.
 */
final class EdgeGlowView extends View implements Choreographer.FrameCallback {

    private static final String TAG = "EdgeGlowView";
    private static final int[][] FALLBACK_PALETTE = {
        { 124, 92, 255 },
        { 236, 72, 153 },
        { 56, 189, 248 },
    };
    private static final long PALETTE_BLEND_MS = 700;
    private static final long COLOR_TRAVEL_MS = 26_000;
    private static final int BEAT_HISTORY = 48;

    private final Paint paint = new Paint();
    private final float density;

    private int[][] fromPalette = FALLBACK_PALETTE;
    private int[][] toPalette = FALLBACK_PALETTE;
    private long paletteBlendStartMs;

    private float colorShift;
    private long lastFrameMs;

    // Written from the audio-capture thread, read from the UI thread on every frame: plain
    // volatile fields rather than a lock, same tradeoff as AudioLevelsBridge.capturing — a
    // decorative glow can tolerate a one-frame-old value, but must never block the capture loop.
    private volatile boolean live;
    private volatile float level;
    private volatile float bass;
    private volatile boolean pendingBeat;

    private final float[] bassHistory = new float[BEAT_HISTORY];
    private int bassCursor;
    private long lastBeatAtMs;
    private float beatEnergy;

    // Ambient-only breathing: three periods with no common multiple, so the glow never seems to
    // loop — same idea as _updateAmbient() in visualizer.js, just three oscillators instead of
    // per-band ones since this view has no spectrum to speak of, only a border.
    private float ambientPhase;

    EdgeGlowView(Context context) {
        super(context);
        density = context.getResources().getDisplayMetrics().density;
        paint.setStyle(Paint.Style.FILL);
    }

    void setPalette(int[][] palette) {
        fromPalette = currentPalette();
        toPalette = palette != null ? palette : FALLBACK_PALETTE;
        paletteBlendStartMs = SystemClock.elapsedRealtime();
    }

    void setLive(boolean live) {
        this.live = live;
        if (!live) {
            level = 0f;
            bass = 0f;
            beatEnergy = 0f;
        }
    }

    /**
     * A real, honest impulse — a track change or a play/pause — the only kind ambient mode is
     * allowed to show (see docs/architecture/2026-09-03-rythme-hors-capture.md: no invented
     * beat, but a real event may still land visibly). Called from OverlayEdgeGlowService.
     * Harmless in live mode too: real beats already drive beatEnergy just as strongly.
     */
    void pulse(float strength) {
        beatEnergy = Math.max(beatEnergy, clamp01(strength));
    }

    /** Called from AudioCaptureService's capture thread via AudioLevelsBridge. */
    void pushLevels(float[] bands) {
        if (bands == null || bands.length == 0) return;
        int bassBands = Math.min(6, bands.length);
        float bassSum = 0f;
        for (int i = 0; i < bassBands; i++) bassSum += bands[i];
        float newBass = bassSum / bassBands;
        float levelSum = 0f;
        for (float v : bands) levelSum += v;
        float newLevel = levelSum / bands.length;

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

        live = true;
        level = newLevel;
        bass = newBass;
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        lastFrameMs = SystemClock.elapsedRealtime();
        Choreographer.getInstance().postFrameCallback(this);
    }

    @Override
    protected void onDetachedFromWindow() {
        Choreographer.getInstance().removeFrameCallback(this);
        super.onDetachedFromWindow();
    }

    @Override
    public void doFrame(long frameTimeNanos) {
        // This callback and onDraw() below both run on the app's single main thread — the same
        // one the whole webview and every Activity run on. An exception escaping either of them
        // would crash the entire app, not just this decorative overlay, so both are defensive
        // about anything unexpected (a null palette entry, a transient view-detach race) rather
        // than ever letting that happen for the sake of a border glow.
        try {
            long now = SystemClock.elapsedRealtime();
            long dtMs = Math.max(0, Math.min(200, now - lastFrameMs));
            lastFrameMs = now;

            colorShift = (colorShift + dtMs / (float) COLOR_TRAVEL_MS * 3f) % 3f;

            if (pendingBeat) {
                pendingBeat = false;
                beatEnergy = 1f;
            }
            // Same 0.9-per-~16.7ms decay as visualizer.js's beatEnergy *= 0.9, scaled to whatever
            // this loop's actual frame interval turns out to be.
            beatEnergy *= (float) Math.pow(0.9, dtMs / 16.7);

            ambientPhase += dtMs;

            invalidate();
        } catch (Exception e) {
            Log.w(TAG, "doFrame", e);
        } finally {
            // Roughly 24fps: plenty smooth for a soft border glow, a third of the redraw work of
            // a full 60fps loop for something that runs for as long as the music plays underneath.
            // Kept outside the try body so one bad frame doesn't also kill every frame after it.
            Choreographer.getInstance().postFrameCallback(this);
        }
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            drawGlow(canvas);
        } catch (Exception e) {
            Log.w(TAG, "onDraw", e);
        }
    }

    private void drawGlow(Canvas canvas) {
        int width = getWidth();
        int height = getHeight();
        if (width <= 0 || height <= 0) return;

        int color = displayColor();
        // beatEnergy carries either a real detected beat (live) or a real event's pulse() —
        // track change, play/pause — in both regimes; see pulse() above for why ambient mode is
        // still allowed this much.
        float pulse = clamp01(beatEnergy);
        float strength;
        if (live) {
            strength = clamp01(level);
        } else {
            // Slow, symmetric breathing — no beat, no invented tempo, just three incommensurate
            // waves summed and rescaled into a gentle 0.25..0.55 band.
            double a = Math.sin(ambientPhase / 30_000.0 * Math.PI * 2);
            double b = Math.sin(ambientPhase / 48_000.0 * Math.PI * 2 + 1.7);
            double c = Math.sin(ambientPhase / 82_000.0 * Math.PI * 2 + 3.1);
            strength = (float) (0.4 + (a + b + c) / 3.0 * 0.15);
        }

        float thicknessDp = 10f + strength * 30f + pulse * 16f;
        float thickness = thicknessDp * density;
        int alpha = clamp255((int) (110 + strength * 90 + pulse * 55));

        int edgeColor = (color & 0x00FFFFFF) | (alpha << 24);
        int transparent = color & 0x00FFFFFF;

        drawEdge(canvas, 0, 0, width, thickness, edgeColor, transparent, true); // top
        drawEdge(canvas, 0, height - thickness, width, thickness, edgeColor, transparent, false); // bottom
        drawEdgeVertical(canvas, 0, 0, thickness, height, edgeColor, transparent, true); // left
        drawEdgeVertical(canvas, width - thickness, 0, thickness, height, edgeColor, transparent, false); // right
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

    private int displayColor() {
        int[][] palette = currentPalette();
        int index = (int) Math.floor(colorShift) % 3;
        int next = (index + 1) % 3;
        float frac = colorShift - (float) Math.floor(colorShift);
        int[] a = palette[index];
        int[] b = palette[next];
        int r = Math.round(a[0] + (b[0] - a[0]) * frac);
        int g = Math.round(a[1] + (b[1] - a[1]) * frac);
        int bl = Math.round(a[2] + (b[2] - a[2]) * frac);
        return Color.rgb(clamp255(r), clamp255(g), clamp255(bl));
    }

    /** Blends fromPalette toward toPalette over PALETTE_BLEND_MS — see setPalette(). */
    private int[][] currentPalette() {
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
