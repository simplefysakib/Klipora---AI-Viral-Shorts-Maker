package com.viral.shortsmaker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.view.accessibility.AccessibilityNodeInfo;

import java.util.ArrayList;
import java.util.List;

/**
 * v12 premium slider for Caption Studio: thick rounded track, purple-to-pink gradient on the active part, a round
 * white thumb with a soft shadow and a purple ring that grows while held. Steps snap with a light haptic tick.
 * API mirrors the bits of Material's Slider the studio used (value range, step, change listener with fromUser).
 */
public class PremiumSlider extends View {

    public interface OnChangeListener { void onValueChange(PremiumSlider s, float value, boolean fromUser); }

    private float from = 0f, to = 1f, step = 0f, value = 0f;
    private final List<OnChangeListener> listeners = new ArrayList<>();
    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint active = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint thumb = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint shadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF r = new RectF();
    private boolean held;
    private int gradW = -1;
    private int colorStart = 0xFF7C3AED, colorEnd = 0xFFEC4899;

    public PremiumSlider(Context c) { super(c); init(); }
    public PremiumSlider(Context c, AttributeSet a) { super(c, a); init(); }
    public PremiumSlider(Context c, AttributeSet a, int s) { super(c, a, s); init(); }

    private void init() {
        track.setColor(0xFF2A2740);
        thumb.setColor(0xFFFFFFFF);
        ring.setStyle(Paint.Style.STROKE);
        shadow.setColor(0x66000000);
        setFocusable(true);
        setClickable(true);
        setMinimumHeight(dp(40));
    }

    public void setValueFrom(float f) { from = f; value = clamp(value); invalidate(); }
    public void setValueTo(float t) { to = t; value = clamp(value); invalidate(); }
    public void setStepSize(float s) { step = Math.max(0f, s); }
    public float getValueFrom() { return from; }
    public float getValueTo() { return to; }
    public float getValue() { return value; }
    public void setColors(int start, int end) { colorStart = start; colorEnd = end; gradW = -1; invalidate(); }

    public void setValue(float v) { set(v, false); }
    public void addOnChangeListener(OnChangeListener l) { listeners.add(l); }

    private float clamp(float v) {
        if (to <= from) return from;
        v = Math.max(from, Math.min(to, v));
        if (step > 0) v = from + Math.round((v - from) / step) * step;
        return Math.max(from, Math.min(to, Math.round(v * 1000f) / 1000f));
    }

    private void set(float v, boolean user) {
        float nv = clamp(v);
        if (nv == value) return;
        value = nv;
        if (user && (step >= 1f || nv == from || nv == to)) try { performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK); } catch (Throwable ignored) {}
        invalidate();
        for (OnChangeListener l : listeners) l.onValueChange(this, value, user);
    }

    private int dp(float v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    @Override protected void onMeasure(int wSpec, int hSpec) {
        int h = resolveSize(dp(40), hSpec);
        setMeasuredDimension(MeasureSpec.getSize(wSpec), Math.max(h, dp(40)));
    }

    private float pad() { return dp(13); }

    private float frac() { return to <= from ? 0 : (value - from) / (to - from); }

    @Override protected void onDraw(Canvas c) {
        float pad = pad(), w = getWidth() - 2 * pad, cy = getHeight() / 2f, th = dp(8);
        r.set(pad, cy - th / 2, pad + w, cy + th / 2);
        c.drawRoundRect(r, th / 2, th / 2, track);
        float tx = pad + w * frac();
        if (gradW != getWidth()) {
            active.setShader(new LinearGradient(pad, 0, pad + w, 0, colorStart, colorEnd, Shader.TileMode.CLAMP));
            gradW = getWidth();
        }
        r.set(pad, cy - th / 2, Math.max(pad + th, tx), cy + th / 2);
        c.drawRoundRect(r, th / 2, th / 2, active);
        float tr = dp(held ? 12 : 11);
        c.drawCircle(tx, cy + dp(1.5f), tr + dp(0.5f), shadow);
        c.drawCircle(tx, cy, tr, thumb);
        ring.setColor(colorStart);
        ring.setStrokeWidth(dp(held ? 4 : 3));
        c.drawCircle(tx, cy, tr - dp(held ? 2 : 1.5f), ring);
    }

    @Override public boolean onTouchEvent(MotionEvent e) {
        if (!isEnabled()) return false;
        float pad = pad(), w = Math.max(1, getWidth() - 2 * pad);
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                held = true;
                if (getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                set(from + (to - from) * (e.getX() - pad) / w, true);
                invalidate();
                return true;
            case MotionEvent.ACTION_MOVE:
                set(from + (to - from) * (e.getX() - pad) / w, true);
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                held = false;
                invalidate();
                if (e.getActionMasked() == MotionEvent.ACTION_UP) performClick();
                return true;
        }
        return super.onTouchEvent(e);
    }

    @Override public boolean performClick() { return super.performClick(); }

    @Override public void onInitializeAccessibilityNodeInfo(AccessibilityNodeInfo info) {
        super.onInitializeAccessibilityNodeInfo(info);
        info.setClassName("android.widget.SeekBar");
        if (android.os.Build.VERSION.SDK_INT >= 24)
            info.setRangeInfo(AccessibilityNodeInfo.RangeInfo.obtain(AccessibilityNodeInfo.RangeInfo.RANGE_TYPE_FLOAT, from, to, value));
    }
}
