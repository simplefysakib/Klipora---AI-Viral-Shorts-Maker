package com.viral.shortsmaker;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

public class WindowSlider extends View {

    private Paint trackPaint;
    private Paint boxPaint;
    private RectF trackRect;
    private RectF boxRect;

    private float totalDuration = 100f;
    private float windowDuration = 10f;
    private float currentStart = 0f;

    private float boxX = 0f;
    private float lastTouchX = 0f;
    private boolean isDragging = false;

    private OnWindowChangeListener listener;

    public interface OnWindowChangeListener {
        void onWindowChanged(float start, float end);
    }

    public WindowSlider(Context context, AttributeSet attrs) {
        super(context, attrs);
        init();
    }

    private void init() {
        trackPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        trackPaint.setColor(Color.parseColor("#E2E8F0")); // Light Gray Track
        trackPaint.setStyle(Paint.Style.FILL);

        boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
        boxPaint.setColor(Color.parseColor("#4F46E5")); // Premium Indigo Box
        boxPaint.setStyle(Paint.Style.FILL);

        trackRect = new RectF();
        boxRect = new RectF();
    }

    public void setListener(OnWindowChangeListener listener) {
        this.listener = listener;
    }

    public void setDurations(float total, float window) {
        this.totalDuration = Math.max(total, 1f);
        this.windowDuration = Math.min(window, this.totalDuration);
        this.currentStart = 0f;
        this.boxX = 0f;
        invalidate();
        notifyListener();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        updateRects();
    }

    private void updateRects() {
        int width = getWidth();
        int height = getHeight();
        float trackHeight = height * 0.4f;
        float trackTop = (height - trackHeight) / 2;

        trackRect.set(0, trackTop, width, trackTop + trackHeight);

        float boxWidth = (windowDuration / totalDuration) * width;
        boxWidth = Math.max(boxWidth, 50f); // Minimum visual width

        boxX = (currentStart / totalDuration) * width;
        boxRect.set(boxX, 0, boxX + boxWidth, height);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        super.onDraw(canvas);
        // Draw background track
        canvas.drawRoundRect(trackRect, trackRect.height() / 2, trackRect.height() / 2, trackPaint);
        // Draw draggable box
        canvas.drawRoundRect(boxRect, 16f, 16f, boxPaint);
    }

    @Override
    public boolean onTouchEvent(MotionEvent event) {
        float x = event.getX();
        float boxWidth = boxRect.width();
        float width = getWidth();

        switch (event.getAction()) {
            case MotionEvent.ACTION_DOWN:
                if (x >= boxRect.left && x <= boxRect.right) {
                    isDragging = true;
                    lastTouchX = x;
                    return true;
                }
                break;
            case MotionEvent.ACTION_MOVE:
                if (isDragging) {
                    float dx = x - lastTouchX;
                    boxX += dx;

                    // Clamp to bounds
                    if (boxX < 0) boxX = 0;
                    if (boxX + boxWidth > width) boxX = width - boxWidth;

                    boxRect.offsetTo(boxX, 0);
                    lastTouchX = x;

                    currentStart = (boxX / width) * totalDuration;
                    invalidate();
                    notifyListener();
                    return true;
                }
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                isDragging = false;
                break;
        }
        return super.onTouchEvent(event);
    }

    private void notifyListener() {
        if (listener != null) {
            listener.onWindowChanged(currentStart, currentStart + windowDuration);
        }
    }
}

