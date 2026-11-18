package com.viral.shortsmaker;

import android.animation.ValueAnimator;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.view.animation.LinearInterpolator;

/**
 * v6 step indicator: the step's own circle outline IS the progress. Pending = dim ring, active = purple arc that
 * sweeps 0 -> 100 % around the circle with the step's real progress (no separate spinner on top), done = filled
 * green circle (the check icon sits above it). For a step with no measurable progress the arc creeps forward on a
 * time estimate and never closes until the step really finishes.
 */
public class StepRingView extends View {
    public static final int PENDING = 0, ACTIVE = 1, DONE = 2;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint arc = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF oval = new RectF();
    private int state = PENDING;
    private float shown = 0f;          // fraction currently drawn (0..1)
    private float target = 0f;
    private ValueAnimator anim;
    private float doneScale = 1f;
    // estimated mode
    private boolean estimated;
    private long estStart;
    private double estSeconds = 30;

    public StepRingView(Context c) { this(c, null); }
    public StepRingView(Context c, AttributeSet a) {
        super(c, a);
        float d = getResources().getDisplayMetrics().density;
        track.setStyle(Paint.Style.STROKE);
        track.setStrokeWidth(2.5f * d);
        arc.setStyle(Paint.Style.STROKE);
        arc.setStrokeWidth(2.5f * d);
        arc.setStrokeCap(Paint.Cap.ROUND);
        arc.setColor(0xFF7C5CFF);
        fill.setStyle(Paint.Style.FILL);
        fill.setColor(0xFF22C55E);
    }

    public int getState() { return state; }
    public float getShownFraction() { return shown; }

    public void setState(int s) {
        if (s == state) return;
        int old = state;
        state = s;
        if (s == DONE) {
            animateTo(1f, 220);
            if (old != DONE && isAttachedToWindow()) {
                ValueAnimator p = ValueAnimator.ofFloat(0.6f, 1f);
                p.setDuration(260);
                p.setInterpolator(new DecelerateInterpolator());
                p.addUpdateListener(v -> { doneScale = (float) v.getAnimatedValue(); invalidate(); });
                p.start();
            }
        } else if (s == PENDING) {
            stopAnim(); shown = target = 0f; estimated = false;
        }
        invalidate();
    }

    /** real progress of the active step, 0..1 */
    public void setProgress(double f) {
        estimated = false;
        float t = (float) Math.max(0, Math.min(1, f));
        if (t < target - 0.001f && target - t < 0.02f) return;   // ignore tiny backwards jitter
        animateTo(t, 450);
    }

    /**
     * The step has no progress signal: sweep on a time estimate (eased so it slows down near the end and stays below
     * 95 % until the step really finishes).
     */
    public void setEstimated(long startedAtMs, double expectedSeconds) {
        if (!estimated || estStart != startedAtMs) { estimated = true; estStart = startedAtMs; }
        estSeconds = Math.max(3, expectedSeconds);
        stopAnim();
        postInvalidateOnAnimation();
    }

    /** eased estimate: 1 - e^(-t/T) scaled to max 0.95 */
    public static float estimateFraction(double elapsedSec, double expectedSec) {
        double x = Math.max(0, elapsedSec) / Math.max(1, expectedSec);
        return (float) Math.min(0.95, 0.95 * (1 - Math.exp(-1.6 * x)));
    }

    private void animateTo(float t, long ms) {
        target = t;
        stopAnim();
        if (!isAttachedToWindow()) { shown = t; invalidate(); return; }
        anim = ValueAnimator.ofFloat(shown, t);
        anim.setDuration(ms);
        anim.setInterpolator(new LinearInterpolator());
        anim.addUpdateListener(v -> { shown = (float) v.getAnimatedValue(); invalidate(); });
        anim.start();
    }

    private void stopAnim() { if (anim != null) { anim.cancel(); anim = null; } }

    @Override protected void onDetachedFromWindow() { stopAnim(); super.onDetachedFromWindow(); }

    @Override
    protected void onDraw(Canvas c) {
        float w = getWidth(), h = getHeight();
        float sw = arc.getStrokeWidth();
        float r = Math.min(w, h) / 2f - sw / 2f - 0.5f;
        float cx = w / 2f, cy = h / 2f;
        oval.set(cx - r, cy - r, cx + r, cy + r);
        if (state == DONE) {
            c.drawCircle(cx, cy, (r + sw / 2f) * doneScale, fill);
            return;
        }
        fill.setColor(0xFF1C1C28);
        c.drawCircle(cx, cy, r, fill);
        fill.setColor(0xFF22C55E);
        track.setColor(state == ACTIVE ? 0xFF33294F : 0xFF2A2A3B);
        c.drawCircle(cx, cy, r, track);
        if (state != ACTIVE) return;
        float f = shown;
        if (estimated) {
            float e = estimateFraction((System.currentTimeMillis() - estStart) / 1000.0, estSeconds);
            shown = f = Math.max(shown, e);
            postInvalidateOnAnimation();
        }
        if (f > 0.004f) c.drawArc(oval, -90f, 360f * f, false, arc);
        else c.drawPoint(cx, cy - r, arc);
    }
}
