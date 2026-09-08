package com.vizuzik.app;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.SystemClock;
import android.util.Log;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.WindowManager;

/**
 * The handle used to tell Vizuzik where this phone's music app actually keeps its album art.
 *
 * EdgeGlowView can only *model* that position (see its ART_* constants): there is no accessibility
 * hook to read another app's layout, and screen capture was dropped from this app on purpose,
 * since MediaProjection made Android show its "start recording your screen" dialog every time. So
 * the model is a good guess, and this is how it gets corrected on a phone it guessed wrong for —
 * the user drags this handle onto their own cover and the anchor follows.
 *
 * It is a window of its own, a couple of hundred dp across, rather than making the overlay itself
 * touchable. That is the whole reason it exists in this shape: the overlay spans the entire
 * screen, and a full-screen window that accepts touches would swallow every tap meant for the app
 * underneath — someone calibrating over Deezer could no longer press play. Only this small pill
 * takes touches; everywhere else the app underneath goes on working normally.
 *
 * Drag it to move the anchor, use the two side buttons to size it, and the tick to finish.
 * OverlayEdgeGlowService owns it and is what actually persists the result.
 */
@SuppressLint("ViewConstructor")
final class ArtCalibrationPuck extends View {

    private static final String TAG = "ArtCalibrationPuck";

    interface Listener {
        /** The handle's centre moved to this point on screen. */
        void onCentreMoved(float screenX, float screenY);
        /** One of the size buttons was tapped; the factor is per tap. */
        void onScaleNudged(float factor);
        /** The tick was tapped, or the handle timed out. */
        void onFinished();
    }

    /** Untouched for this long, calibration ends on its own. Someone who wandered off must never
     *  be left with a stray window sitting on top of their music app. */
    static final long IDLE_TIMEOUT_MS = 120_000;

    private static final float WIDTH_DP = 232f;
    private static final float HEIGHT_DP = 64f;
    private static final float SCALE_STEP = 1.06f;

    private final Listener listener;
    private final float density;
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF pill = new RectF();

    /** Where the finger went down, and where this window was then — a drag is the difference. */
    private float touchStartRawX;
    private float touchStartRawY;
    private int windowStartX;
    private int windowStartY;
    private boolean dragging;
    private long lastTouchAtMs;

    ArtCalibrationPuck(Context context, Listener listener) {
        super(context);
        this.listener = listener;
        this.density = context.getResources().getDisplayMetrics().density;
    }

    int widthPx() {
        return Math.round(WIDTH_DP * density);
    }

    int heightPx() {
        return Math.round(HEIGHT_DP * density);
    }

    long idleForMs(long nowMs) {
        return lastTouchAtMs == 0 ? 0 : nowMs - lastTouchAtMs;
    }

    void markTouched(long nowMs) {
        lastTouchAtMs = nowMs;
    }

    @Override
    protected void onMeasure(int widthMeasureSpec, int heightMeasureSpec) {
        setMeasuredDimension(widthPx(), heightPx());
    }

    @Override
    protected void onDraw(Canvas canvas) {
        try {
            float w = getWidth();
            float h = getHeight();
            if (w <= 0 || h <= 0) return;
            float inset = density * 2f;
            pill.set(inset, inset, w - inset, h - inset);
            float radius = pill.height() * 0.5f;

            paint.setStyle(Paint.Style.FILL);
            paint.setColor(0xE60B0B12);
            canvas.drawRoundRect(pill, radius, radius, paint);
            paint.setStyle(Paint.Style.STROKE);
            paint.setStrokeWidth(Math.max(1f, density));
            paint.setColor(0x66FFFFFF);
            canvas.drawRoundRect(pill, radius, radius, paint);

            float third = w / 3f;
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(Color.WHITE);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(h * 0.42f);
            float baseline = h * 0.5f - (paint.descent() + paint.ascent()) * 0.5f;
            // Three zones, in the order the hand expects them: smaller, move, bigger — with the
            // tick folded into the middle as a long press would be less discoverable.
            canvas.drawText("−", third * 0.5f, baseline, paint);
            canvas.drawText("✓", third * 1.5f, baseline, paint);
            canvas.drawText("+", third * 2.5f, baseline, paint);

            paint.setStrokeWidth(Math.max(1f, density * 0.8f));
            paint.setStyle(Paint.Style.STROKE);
            paint.setColor(0x33FFFFFF);
            canvas.drawLine(third, h * 0.24f, third, h * 0.76f, paint);
            canvas.drawLine(third * 2f, h * 0.24f, third * 2f, h * 0.76f, paint);
        } catch (Exception e) {
            // Same rule as EdgeGlowView: this runs on the app's one main thread, and a decorative
            // handle must never be the thing that takes the whole app down.
            Log.w(TAG, "onDraw", e);
        }
    }

    /**
     * A tap on a side button resizes, a tap in the middle finishes, and anything that travels
     * further than a tap is a drag of the whole handle. Dragging is reported as an absolute screen
     * position rather than a delta so that a dropped move event can't accumulate into a drift.
     */
    @Override
    public boolean onTouchEvent(MotionEvent event) {
        try {
            lastTouchAtMs = SystemClock.elapsedRealtime();
            switch (event.getActionMasked()) {
                case MotionEvent.ACTION_DOWN: {
                    touchStartRawX = event.getRawX();
                    touchStartRawY = event.getRawY();
                    WindowManager.LayoutParams params = layoutParams();
                    if (params != null) {
                        windowStartX = params.x;
                        windowStartY = params.y;
                    }
                    dragging = false;
                    return true;
                }
                case MotionEvent.ACTION_MOVE: {
                    float dx = event.getRawX() - touchStartRawX;
                    float dy = event.getRawY() - touchStartRawY;
                    if (!dragging && Math.hypot(dx, dy) < density * 8f) return true;
                    dragging = true;
                    listener.onCentreMoved(
                        windowStartX + dx + getWidth() * 0.5f,
                        windowStartY + dy + getHeight() * 0.5f
                    );
                    return true;
                }
                case MotionEvent.ACTION_UP: {
                    if (!dragging) {
                        float third = getWidth() / 3f;
                        float x = event.getX();
                        if (x < third) listener.onScaleNudged(1f / SCALE_STEP);
                        else if (x > third * 2f) listener.onScaleNudged(SCALE_STEP);
                        else listener.onFinished();
                    }
                    dragging = false;
                    return true;
                }
                case MotionEvent.ACTION_CANCEL: {
                    dragging = false;
                    return true;
                }
                default:
                    return true;
            }
        } catch (Exception e) {
            Log.w(TAG, "onTouchEvent", e);
            return true;
        }
    }

    private WindowManager.LayoutParams layoutParams() {
        ViewGroup.LayoutParams params = getLayoutParams();
        return params instanceof WindowManager.LayoutParams
            ? (WindowManager.LayoutParams) params
            : null;
    }
}
