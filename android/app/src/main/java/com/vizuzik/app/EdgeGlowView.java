package com.vizuzik.app;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Shader;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
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
 * the glow breathes on its own — quickly and visibly enough to read as alive on a glance, since
 * unlike the full-screen player this is meant to be glimpsed rather than watched continuously —
 * but still never claims to be following an actual beat it never captured. In both regimes the
 * color itself drifts across the current track's three accent colors, and a real event (a track
 * change, a play/pause) is still allowed an honest pulse — see pulse() below.
 */
final class EdgeGlowView extends View {

    private static final String TAG = "EdgeGlowView";
    // A plain Handler loop rather than Choreographer.postFrameCallback(): this view belongs to a
    // Service's overlay window, not an Activity, and on-device testing showed Choreographer's
    // vsync-driven callbacks never firing a second time for it on at least one device/Android
    // build — leaving the very first frame on screen forever, a border that looked "on" but
    // never moved. A Handler tied to the main Looper's own message queue has no such dependency
    // on the window being considered for vsync by the system; a plain border glow doesn't need
    // frame-perfect vsync timing anyway.
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
    // visualizer.js decays its own beatEnergy at 0.9 per ~16.7ms because it shares the screen
    // with a whole scene reacting alongside it; this view's border is the *only* thing carrying
    // that impulse, so it needs to linger for roughly a second and a half to actually register on
    // a glance instead of flashing for a couple of frames.
    private static final float PULSE_DECAY_MS = 55f;

    private final Paint paint = new Paint();
    private final float density;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Runnable tick = this::onTick;
    // Diagnostic only: lets OverlayEdgeGlowService prove the loop is actually still running (by
    // surfacing a live counter in its own notification) without needing a logcat capture from
    // the person testing it — see the service for why this was worth adding.
    private Runnable tickListener;
    // Diagnostic only, temporary: a plain tick counter drawn directly on the glow itself, in case
    // the notification counter turns out to be hidden behind "silent notifications" collapsing on
    // some devices (IMPORTANCE_MIN notifications are collapsed there by default) — this can't be
    // missed since it's part of the exact thing being watched. Remove alongside the notification
    // counter once the freeze reports are resolved.
    private int debugTickCount;
    private final Paint debugPaint = new Paint();

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
        debugPaint.setColor(Color.WHITE);
        debugPaint.setTextSize(28f * density);
        debugPaint.setShadowLayer(6f * density, 0, 0, Color.BLACK);
        debugPaint.setAntiAlias(true);
    }

    void setPalette(int[][] palette) {
        fromPalette = currentPalette();
        toPalette = palette != null ? palette : FALLBACK_PALETTE;
        paletteBlendStartMs = SystemClock.elapsedRealtime();
    }

    void setTickListener(Runnable listener) {
        this.tickListener = listener;
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
            debugTickCount++;

            invalidate();
            if (tickListener != null) tickListener.run();
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
            // Symmetric breathing on three incommensurate waves — no beat, no invented tempo,
            // but fast and wide enough (7s/11s/17s, ±0.4 around a 0.5 mid-point) to actually read
            // as "alive" within a few seconds' glance, unlike the slower wash the full-screen
            // player uses for something meant to be watched continuously, not glanced at.
            double a = Math.sin(ambientPhase / 7_000.0 * Math.PI * 2);
            double b = Math.sin(ambientPhase / 11_000.0 * Math.PI * 2 + 1.7);
            double c = Math.sin(ambientPhase / 17_000.0 * Math.PI * 2 + 3.1);
            strength = (float) (0.5 + (a + b + c) / 3.0 * 0.4);
        }

        float thicknessDp = 16f + strength * 46f + pulse * 26f;
        float thickness = thicknessDp * density;
        int alpha = clamp255((int) (150 + strength * 105 + pulse * 60));

        int edgeColor = (color & 0x00FFFFFF) | (alpha << 24);
        int transparent = color & 0x00FFFFFF;

        drawEdge(canvas, 0, 0, width, thickness, edgeColor, transparent, true); // top
        drawEdge(canvas, 0, height - thickness, width, thickness, edgeColor, transparent, false); // bottom
        drawEdgeVertical(canvas, 0, 0, thickness, height, edgeColor, transparent, true); // left
        drawEdgeVertical(canvas, width - thickness, 0, thickness, height, edgeColor, transparent, false); // right

        // Diagnostic only, temporary — see the field comment above.
        canvas.drawText("tick " + debugTickCount, 24f * density, 60f * density, debugPaint);
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
