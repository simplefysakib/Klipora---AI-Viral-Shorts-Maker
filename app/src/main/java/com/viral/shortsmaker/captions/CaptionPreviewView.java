package com.viral.shortsmaker.captions;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BlurMaskFilter;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.DashPathEffect;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.os.Build;
import android.os.SystemClock;
import android.util.AttributeSet;
import android.view.MotionEvent;
import android.view.View;

import com.viral.shortsmaker.core.FfmpegCommands;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Live caption preview on a 9:16 frame with a draggable / resizable transparent caption box.
 * Uses the same fitting + pagination as the ASS builder, so what you see is what gets burned in.
 */
public class CaptionPreviewView extends View {
    public interface OnLayoutChange { void onChanged(CaptionLayout l); }
    /** v12: the size changed from the preview (resize handle, pinch, double-tap reset); done = finger lifted */
    public interface OnSizeChange { void onSize(float sizeScale, boolean done); }
    private OnSizeChange sizeListener;
    public void setOnSizeChange(OnSizeChange l) { sizeListener = l; }

    private static final Map<String, Typeface> FONTS = new HashMap<>();
    private static final int[] CYCLE = {0xFF3DFF5A, 0xFFFFE500, 0xFFFF3B5C, 0xFF38BDF8};

    private CaptionStyle style = CaptionStyles.byId("hormozi");
    private CaptionLayout layout = new CaptionLayout();
    private List<Word> sample = defaultSample();
    private Bitmap frame, blurred;
    private FfmpegCommands.Frame frameMode = FfmpegCommands.Frame.BLUR;
    private boolean swatch = false, editable = true, animate = true;
    private OnLayoutChange listener;

    private final RectF fr = new RectF();
    private final RectF box = new RectF();
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.SUBPIXEL_TEXT_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint boxPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dash = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handle = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint bmp = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.ANTI_ALIAS_FLAG);
    private final long t0 = SystemClock.uptimeMillis();
    private List<CaptionBuilder.Page> pages = new ArrayList<>();
    private CaptionBuilder.Fit fit;
    private double total = 1;
    private java.util.Set<Word> emph = java.util.Collections.emptySet();
    private final Paint extra = new Paint(Paint.ANTI_ALIAS_FLAG);

    public CaptionPreviewView(Context c) { super(c); init(); }
    public CaptionPreviewView(Context c, AttributeSet a) { super(c, a); init(); }

    private void init() {
        stroke.setStyle(Paint.Style.STROKE);
        stroke.setStrokeJoin(Paint.Join.ROUND);
        stroke.setStrokeCap(Paint.Cap.ROUND);
        glow.setStyle(Paint.Style.STROKE);
        glow.setStrokeJoin(Paint.Join.ROUND);
        dash.setStyle(Paint.Style.STROKE);
        dash.setColor(0xE6FFFFFF);
        dash.setStrokeWidth(dp(1.5f));
        dash.setPathEffect(new DashPathEffect(new float[]{dp(7), dp(5)}, 0));
        handle.setColor(Color.WHITE);
        rebuild();
    }

    float dp(float v) { return v * getResources().getDisplayMetrics().density; }

    public static Typeface typeface(Context c, CaptionFonts.Font f) {
        Typeface t = FONTS.get(f.asset);
        if (t == null) {
            try { t = Typeface.createFromAsset(c.getAssets(), "fonts/" + f.asset); } catch (Exception e) { t = Typeface.DEFAULT_BOLD; }
            FONTS.put(f.asset, t);
        }
        return t;
    }

    static List<Word> defaultSample() {
        String[] w = {"This", "is", "how", "your", "captions", "will", "look", "on", "every", "short!"};
        List<Word> l = new ArrayList<>();
        double t = 0.2;
        for (String s : w) { double d = 0.22 + s.length() * 0.035; l.add(new Word(s, t, t + d)); t += d + 0.06; }
        return l;
    }

    // ------------------------------------------------------------------ API

    public void setSwatch(boolean s) { swatch = s; editable = !s; rebuild(); }
    public void setAnimate(boolean a) { animate = a; invalidate(); }
    public void setEditable(boolean e) { editable = e; invalidate(); }
    public void setOnLayoutChange(OnLayoutChange l) { listener = l; }
    public CaptionLayout getCaptionLayout() { return layout; }
    public CaptionStyle getStyle() { return style; }

    public void setStyle(CaptionStyle s) { style = s.copy(); rebuild(); }
    public void setCaptionLayout(CaptionLayout l) { layout = l.copy(); computeFrame(getWidth(), getHeight()); rebuild(); }

    public void setSampleWords(List<Word> words) {
        if (words == null || words.isEmpty()) { sample = defaultSample(); rebuild(); return; }
        List<Word> l = new ArrayList<>();
        double base = words.get(0).start;
        for (Word w : words) { if (w.start - base > 9) break; l.add(w.shift(-base + 0.2)); }
        sample = l;
        rebuild();
    }

    public void setFrame(Bitmap b, FfmpegCommands.Frame mode) {
        frame = b;
        frameMode = mode == null ? FfmpegCommands.Frame.BLUR : mode;
        blurred = null;
        if (b != null) {
            try {
                int w = 24, h = Math.max(1, Math.round(24f * b.getHeight() / Math.max(1, b.getWidth())));
                Bitmap small = Bitmap.createScaledBitmap(b, w, h, true);
                blurred = Bitmap.createScaledBitmap(small, w * 4, h * 4, true);
            } catch (Throwable ignored) {}
        }
        invalidate();
    }

    public void setFrameMode(FfmpegCommands.Frame m) { frameMode = m; invalidate(); }

    private void rebuild() {
        List<Word> words = CaptionBuilder.clean(swatch ? swatchWords() : sample, style.uppercase);
        CaptionLayout l = swatch ? new CaptionLayout(0.5f, 0.5f, 1f, 0.5f) : layout;
        fit = CaptionBuilder.fit(words, style, l);
        emph = style.keywords && !swatch ? CaptionBuilder.emphasis(words) : java.util.Collections.<Word>emptySet();
        pages = words.isEmpty() ? new ArrayList<>() : CaptionBuilder.paginate(words, style, fit);
        total = pages.isEmpty() ? 1 : pages.get(pages.size() - 1).end + 0.6;
        invalidate();
    }

    private List<Word> swatchWords() {
        List<Word> l = new ArrayList<>();
        l.add(new Word("Viral", 0, 0.5));
        l.add(new Word("vibes", 0.5, 1.0));
        return l;
    }

    // ------------------------------------------------------------------ drawing

    @Override
    protected void onDraw(Canvas c) {
        super.onDraw(c);
        float vw = getWidth(), vh = getHeight();
        if (vw <= 0 || vh <= 0) return;
        computeFrame(vw, vh);
        if (!swatch) drawFrame(c);
        float s = swatch ? Math.min(vw / 720f * 1.25f, vh / 260f) : fr.width() / layout.frameW();   // v14: canvas per format
        double t = swatch ? 0.75 : ((SystemClock.uptimeMillis() - t0) / 1000.0) % total;
        CaptionLayout l = swatch ? new CaptionLayout(0.5f, 0.5f, 1f, 0.5f) : layout;
        drawCaption(c, s, t, l);
        if (!swatch) drawExtras(c, s, t);
        if (!swatch && editable) { drawBox(c); drawGuides(c); }
        if (animate && !swatch && isShown()) postInvalidateOnAnimation();
    }

    /** the 9:16 frame rect inside the view (also computed on size change, so a touch before the first draw works) */
    private void computeFrame(float vw, float vh) {
        if (swatch) { fr.set(0, 0, vw, vh); return; }
        float pad = dp(8);
        float a = layout.aspect > 0 ? layout.aspect : 9f / 16f;   // v14: the frame has the project's format
        float h = vh - 2 * pad, w = h * a;
        if (w > vw - 2 * pad) { w = vw - 2 * pad; h = w / a; }
        fr.set((vw - w) / 2f, (vh - h) / 2f, (vw + w) / 2f, (vh + h) / 2f);
    }

    @Override protected void onSizeChanged(int w, int h, int ow, int oh) {
        super.onSizeChanged(w, h, ow, oh);
        computeFrame(w, h);
    }

    private void drawFrame(Canvas c) {
        c.save();
        c.clipRect(fr);
        if (frame == null) {
            Paint g = new Paint();
            g.setShader(new LinearGradient(fr.left, fr.top, fr.right, fr.bottom, 0xFF2B1F66, 0xFF0F0F18, Shader.TileMode.CLAMP));
            c.drawRect(fr, g);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
            p.setColor(0x22FFFFFF);
            c.drawCircle(fr.centerX(), fr.top + fr.height() * 0.36f, fr.width() * 0.16f, p);
            c.drawRoundRect(new RectF(fr.left + fr.width() * 0.22f, fr.top + fr.height() * 0.48f, fr.right - fr.width() * 0.22f, fr.bottom + fr.height() * 0.1f), fr.width() * 0.2f, fr.width() * 0.2f, p);
        } else {
            float ba = (float) frame.getWidth() / frame.getHeight();
            float fa = fr.width() / Math.max(1f, fr.height());
            boolean sameShape = Math.abs(ba - fa) / fa < 0.04f;   // v14: any format; the source already has its shape
            if (sameShape || frameMode == FfmpegCommands.Frame.FILL) {
                c.drawBitmap(frame, null, cover(frame.getWidth(), frame.getHeight()), bmp);
            } else {
                if (frameMode == FfmpegCommands.Frame.BLUR && blurred != null) {
                    c.drawBitmap(blurred, null, cover(blurred.getWidth(), blurred.getHeight()), bmp);
                    c.drawColor(0x22000000);
                } else c.drawColor(Color.BLACK);
                float sc = Math.min(fr.width() / frame.getWidth(), fr.height() / frame.getHeight());
                float w = frame.getWidth() * sc, h = frame.getHeight() * sc;
                c.drawBitmap(frame, null, new RectF(fr.centerX() - w / 2, fr.centerY() - h / 2, fr.centerX() + w / 2, fr.centerY() + h / 2), bmp);
            }
        }
        c.restore();
    }

    private RectF cover(int bw, int bh) {
        float scale = Math.max(fr.width() / bw, fr.height() / bh);
        float w = bw * scale, h = bh * scale;
        return new RectF(fr.centerX() - w / 2, fr.centerY() - h / 2, fr.centerX() + w / 2, fr.centerY() + h / 2);
    }

    private void updateBox() {
        box.set(fr.left + (layout.cx - layout.w / 2) * fr.width(), fr.top + (layout.cy - layout.h / 2) * fr.height(),
                fr.left + (layout.cx + layout.w / 2) * fr.width(), fr.top + (layout.cy + layout.h / 2) * fr.height());
    }

    private void drawBox(Canvas c) {
        updateBox();
        boxPaint.setColor(dragging ? 0x26FFFFFF : 0x14FFFFFF);
        c.drawRoundRect(box, dp(12), dp(12), boxPaint);
        c.drawRoundRect(box, dp(12), dp(12), dash);
        drawHandle(c, box.right, box.bottom);
    }

    /** v12: one CapCut-style resize handle glued to the box's bottom-right corner (white disc, diagonal two-way arrow) */
    private final Paint handleShadow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint handleIcon = new Paint(Paint.ANTI_ALIAS_FLAG);

    private void drawHandle(Canvas c, float hx, float hy) {
        float r = dp(mode == 2 ? 14 : 12);
        handleShadow.setColor(0x55000000);
        c.drawCircle(hx, hy + dp(1.5f), r + dp(1), handleShadow);
        c.drawCircle(hx, hy, r, handle);
        handleIcon.setColor(0xFF7C3AED);
        handleIcon.setStyle(Paint.Style.STROKE);
        handleIcon.setStrokeWidth(dp(2f));
        handleIcon.setStrokeCap(Paint.Cap.ROUND);
        handleIcon.setStrokeJoin(Paint.Join.ROUND);
        float a = r * 0.45f, h = r * 0.32f;
        c.drawLine(hx - a, hy - a, hx + a, hy + a, handleIcon);
        android.graphics.Path p = new android.graphics.Path();
        p.moveTo(hx - a + h, hy - a); p.lineTo(hx - a, hy - a); p.lineTo(hx - a, hy - a + h);
        p.moveTo(hx + a - h, hy + a); p.lineTo(hx + a, hy + a); p.lineTo(hx + a, hy + a - h);
        c.drawPath(p, handleIcon);
    }

    /** where the resize handle is (for tests and the touch code) */
    public float[] handleCenter() { updateBox(); return new float[]{box.right, box.bottom}; }

    private void drawCaption(Canvas c, float s, double t, CaptionLayout l) {
        if (pages.isEmpty() || fit == null) return;
        CaptionBuilder.Page page = null;
        int before = 0;
        for (CaptionBuilder.Page p : pages) {
            if (t >= p.start && t < p.end) { page = p; break; }
            before += p.allWords().size();
        }
        if (page == null) return;
        List<Word> ws = page.allWords();
        int active = 0;
        for (int i = 0; i < ws.size(); i++) if (t >= ws.get(i).start) active = i;

        float fs = fit.fontSize * s;
        Typeface tf = typeface(getContext(), fit.font);
        fill.setTypeface(tf); stroke.setTypeface(tf); glow.setTypeface(tf);
        fill.setTextSize(fs); stroke.setTextSize(fs); glow.setTextSize(fs);
        Paint.FontMetrics fm = fill.getFontMetrics();
        float lineH = fs * 1.18f;
        int nLines = page.lines.size();
        float cx = (swatch ? getWidth() / 2f : fr.left + l.cx * fr.width());
        float cy = (swatch ? getHeight() / 2f : fr.top + l.cy * fr.height());
        float top = cy - nLines * lineH / 2f;
        float space = Math.max(fill.measureText(" "), fs * 0.26f);
        float outline = style.outline * s * (fit.fontSize / Math.max(1f, style.fontSize * style.sizeScale));
        outline = Math.max(style.outline > 0 ? 1.5f * s : 0, Math.min(style.outline * s, outline + 0.5f * s));
        boolean entranceAnim = style.entrance && (t - page.start) < 0.11;
        float ent = entranceAnim ? (float) (0.94 + 0.06 * (t - page.start) / 0.11) : 1f;

        int idx = 0;
        c.save();
        if (ent != 1f) c.scale(ent, ent, cx, cy);
        for (int li = 0; li < nLines; li++) {
            List<Word> lw = page.lines.get(li).words;
            float[] widths = new float[lw.size()];
            float total = 0;
            for (int k = 0; k < lw.size(); k++) { widths[k] = fill.measureText(lw.get(k).text); total += widths[k] + (k > 0 ? space : 0); }
            float x = cx - total / 2f;
            float baseline = top + li * lineH + lineH / 2f - (fm.ascent + fm.descent) / 2f;
            if (style.lineBox) {
                float pad = Math.max(4f, style.outline) * s;
                boxPaint.setColor(style.boxColor);
                c.drawRoundRect(new RectF(x - pad, baseline + fm.ascent * 0.92f - pad * 0.4f, x + total + pad, baseline + fm.descent + pad * 0.4f), 6 * s, 6 * s, boxPaint);
            }
            for (int k = 0; k < lw.size(); k++, idx++) {
                String w = lw.get(k).text;
                boolean isActive = idx == active && style.anim != CaptionStyle.Anim.STATIC && style.anim != CaptionStyle.Anim.KARAOKE;
                boolean hidden = style.anim == CaptionStyle.Anim.REVEAL && idx > active;
                int hl = style.colorCycle ? CYCLE[(before + idx) % CYCLE.length] : style.highlightColor;
                if (!hidden) {
                    c.save();
                    if (isActive && (style.anim == CaptionStyle.Anim.POP || style.anim == CaptionStyle.Anim.REVEAL)) {
                        double since = t - ws.get(idx).start;
                        float sc = since < 0.09 ? (float) (0.88 + (1.12 - 0.88) * since / 0.09) : 1.12f;
                        c.scale(sc, sc, x + widths[k] / 2f, baseline + (fm.ascent + fm.descent) / 2f);
                    }
                    if (isActive && style.anim == CaptionStyle.Anim.BOX_WORD) {
                        // v12: rounded highlight box (radius ~28 % of its height), same shape as the burned-in one
                        android.graphics.Rect ib = new android.graphics.Rect();
                        String probe = CaptionFonts.containsDevanagari(w) ? "\u0915\u093F\u092A\u094D\u0930\u0947\u0902\u0915\u0941" : "Hg";
                        fill.getTextBounds(probe, 0, probe.length(), ib);
                        float padV = fs * 0.10f, padX = fs * 0.10f;
                        RectF hb = new RectF(x - padX, baseline + ib.top - padV, x + widths[k] + padX, baseline + ib.bottom + padV * 0.6f);
                        float rr = hb.height() * CaptionBuilder.BOX_RADIUS;
                        boxPaint.setColor(hl);
                        c.drawRoundRect(hb, rr, rr, boxPaint);
                    }
                    boolean boxActive = isActive && style.anim == CaptionStyle.Anim.BOX_WORD;
                    int color = isActive && style.anim != CaptionStyle.Anim.BOX_WORD ? hl
                            : emph.contains(lw.get(k)) ? CaptionBuilder.accent(style) : style.textColor;
                    plainWord = boxActive;
                    drawWord(c, w, x, baseline, s, boxActive ? 0f : outline, color);
                    plainWord = false;
                    if (style.anim == CaptionStyle.Anim.KARAOKE) {
                        Word wd = ws.get(idx);
                        double until = idx + 1 < ws.size() ? ws.get(idx + 1).start : wd.end;
                        float frac = (float) Math.max(0, Math.min(1, (t - wd.start) / Math.max(0.05, until - wd.start)));
                        if (frac > 0) {
                            c.save();
                            c.clipRect(x - outline, baseline + fm.ascent - outline, x + widths[k] * frac, baseline + fm.descent + outline);
                            fill.setColor(style.highlightColor);
                            c.drawText(w, x, baseline, fill);
                            c.restore();
                        }
                    }
                    c.restore();
                }
                x += widths[k] + space;
            }
        }
        c.restore();
    }

    // ------------------------------------------------------------------ code 33: offscreen painter for the backup video engine

    /**
     * code 33 (5.1): the backup video engine (no FFmpeg) burns the captions with THIS painter: the words of the short as
     * they went into its ASS file, the same fit / pages / colours / animation as here. clipDur > 0 ends captions there.
     */
    public void prepareOffscreen(List<Word> words, CaptionStyle st, CaptionLayout lay, double clipDur) {
        swatch = false; editable = false; animate = false;
        style = st.copy();
        layout = (lay == null ? new CaptionLayout() : lay).copy();
        List<Word> l = new ArrayList<>();
        for (Word w : words) {
            if (clipDur > 0 && w.start >= clipDur - 0.08) continue;
            l.add(clipDur > 0 ? w.retimed(w.start, Math.min(w.end, clipDur)) : w);
        }
        sample = l;
        rebuild();
        if (clipDur > 0) { for (CaptionBuilder.Page p : pages) p.end = Math.min(p.end, clipDur); total = clipDur; }
    }

    /** draws the captions of time t on a canvas of the output size; false = nothing visible was drawn */
    public boolean drawCaptionsAt(Canvas c, int w, int h, double t) {
        fr.set(0, 0, w, h);
        float s = w / (float) layout.frameW();
        drawCaption(c, s, t, layout);
        drawExtras(c, s, t);
        return pageAt(t) >= 0 || style.progressBar;
    }

    private int pageAt(double t) {
        for (int i = 0; i < pages.size(); i++) if (t >= pages.get(i).start && t < pages.get(i).end) return i;
        return -1;
    }

    /** changes whenever the drawn captions change (page, active word, a running animation step): skip identical redraws */
    public int captionSignature(double t) {
        int pi = pageAt(t);
        if (pi < 0) return style.progressBar ? (int) Math.floor(t * 30) * 31 - 7 : -1;
        CaptionBuilder.Page page = pages.get(pi);
        List<Word> ws = page.allWords();
        int active = 0;
        for (int i = 0; i < ws.size(); i++) if (t >= ws.get(i).start) active = i;
        boolean moving = style.progressBar || style.anim == CaptionStyle.Anim.KARAOKE || (style.entrance && t - page.start < 0.11)
                || ((style.anim == CaptionStyle.Anim.POP || style.anim == CaptionStyle.Anim.REVEAL) && t - ws.get(active).start < 0.1);
        int step = moving ? (int) Math.floor(t * 30) : -1;
        return ((pi * 997 + active) * 31 + step) * 2 + 1;
    }

    private boolean plainWord;   // v12: the word on a highlight box is drawn flat (no glow/shadow), like the render

    private void drawWord(Canvas c, String w, float x, float y, float s, float outline, int color) {
        if (style.glow > 0 && !plainWord) {
            glow.setStrokeWidth((style.outline + style.glow * 1.4f) * s * 2);
            glow.setColor(style.outlineColor);
            glow.setMaskFilter(new BlurMaskFilter(Math.max(1f, style.glow * s * 1.6f), BlurMaskFilter.Blur.NORMAL));
            c.drawText(w, x, y, glow);
        }
        if (style.shadow > 0 && !style.lineBox && !plainWord) {
            float d = style.shadow * s;
            int sc = style.shadowColor;
            if (outline > 0) { stroke.setStrokeWidth(outline * 2); stroke.setColor(sc); c.drawText(w, x + d, y + d, stroke); }
            fill.setColor(sc);
            c.drawText(w, x + d, y + d, fill);
        }
        if (outline > 0 && !style.lineBox) {
            stroke.setStrokeWidth(outline * 2);
            stroke.setColor(style.outlineColor);
            c.drawText(w, x, y, stroke);
        }
        fill.setColor(color);
        c.drawText(w, x, y, fill);
    }

    // ------------------------------------------------------------------ touch (v10: CapCut-style)
    // Drag the caption anywhere on the preview (no jump on tap) = position only; the bottom-right handle or a pinch = size only,
    // snap guides (centre line + top / middle / bottom safe lines) with a haptic tick, double-tap resets.
    // Positions are absolute from the gesture start (finger and box never drift apart) and moving never re-fits the
    // text (only a size change does), so dragging stays smooth.

    public static final float[] SNAP_Y = {0.18f, 0.5f, 0.70f};   // same as the Top / Middle / Low chips
    public static final float SNAP_X = 0.5f;

    private int mode = 0; // 0 none, 1 move, 2 resize (corner), 3 pinch
    private float downX, downY, anchorX, anchorY;
    private boolean dragging;
    private final CaptionLayout startLay = new CaptionLayout();
    private int snappedX = -1, snappedY = -1;   // -1 none; X: 0 centre; Y: index into SNAP_Y
    private android.view.ScaleGestureDetector scaler;
    private android.view.GestureDetector taps;
    private final Paint guide = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint unsafe = new Paint();

    private void initGestures() {
        if (scaler != null) return;
        guide.setColor(0xFFFFD60A);
        guide.setStrokeWidth(dp(1.2f));
        unsafe.setColor(0x33FF3B5C);
        scaler = new android.view.ScaleGestureDetector(getContext(), new android.view.ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override public boolean onScaleBegin(android.view.ScaleGestureDetector d) {
                mode = 3; dragging = true; beginSize();
                pinchSpan = Math.max(1f, d.getCurrentSpan());
                return true;
            }
            @Override public boolean onScale(android.view.ScaleGestureDetector d) {
                // v12: pinch changes the SIZE only (same as the corner handle), the position stays put
                applySize(startScale * d.getCurrentSpan() / pinchSpan, false);
                return true;
            }
        });
        if (Build.VERSION.SDK_INT >= 19) scaler.setQuickScaleEnabled(false);
        taps = new android.view.GestureDetector(getContext(), new android.view.GestureDetector.SimpleOnGestureListener() {
            @Override public boolean onDoubleTap(MotionEvent e) {
                CaptionLayout d = new CaptionLayout();
                layout.cx = d.cx; layout.cy = d.cy; layout.w = d.w; layout.h = d.h;
                style.sizeScale = 1f;
                tick();
                changed(true);
                if (sizeListener != null) sizeListener.onSize(1f, true);
                return true;
            }
        });
    }

    private void tick() {
        try { performHapticFeedback(Build.VERSION.SDK_INT >= 21 ? android.view.HapticFeedbackConstants.CLOCK_TICK : android.view.HapticFeedbackConstants.VIRTUAL_KEY); }
        catch (Throwable ignored) {}
    }

    /** pure snapping logic (unit-tested): returns the snapped value or the raw one, and which guide caught it (-1 none) */
    public static float[] snap(float raw, float[] guides, float catchDist, float releaseDist, int current) {
        if (current >= 0 && Math.abs(raw - guides[current]) <= releaseDist) return new float[]{guides[current], current};
        int best = -1; float bd = Float.MAX_VALUE;
        for (int i = 0; i < guides.length; i++) { float d = Math.abs(raw - guides[i]); if (d < bd) { bd = d; best = i; } }
        if (best >= 0 && bd <= catchDist) return new float[]{guides[best], best};
        return new float[]{raw, -1};
    }

    @Override
    public boolean onTouchEvent(MotionEvent e) {
        if (!editable || swatch) return super.onTouchEvent(e);
        initGestures();
        taps.onTouchEvent(e);
        scaler.onTouchEvent(e);
        float x = e.getX(), y = e.getY();
        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN: {
                mode = 0; dragging = false;
                updateBox();
                // v12: only the bottom-right handle resizes (size only); anywhere else on the frame moves the box
                if (Math.hypot(x - box.right, y - box.bottom) < dp(30)) {
                    mode = 2; beginSize();
                    startDist = Math.max(dp(24), (float) Math.hypot(x - box.centerX(), y - box.centerY()));
                    invalidate();
                }
                if (mode == 0 && fr.contains(x, y)) mode = 1;
                downX = x; downY = y;
                startLay.cx = layout.cx; startLay.cy = layout.cy; startLay.w = layout.w; startLay.h = layout.h;
                snappedX = -1; snappedY = -1;
                if (mode != 0 && getParent() != null) getParent().requestDisallowInterceptTouchEvent(true);
                return mode != 0;
            }
            case MotionEvent.ACTION_POINTER_DOWN:
                return true;
            case MotionEvent.ACTION_MOVE:
                if (mode == 3 || e.getPointerCount() > 1) return true;
                if (mode == 1) {
                    float slop = android.view.ViewConfiguration.get(getContext()).getScaledTouchSlop();
                    if (!dragging && Math.hypot(x - downX, y - downY) < slop) return true;
                    dragging = true;
                    CaptionLayout t = startLay.copy();
                    t.cx = startLay.cx + (x - downX) / fr.width();
                    t.cy = startLay.cy + (y - downY) / fr.height();
                    t.clamp();
                    float cX = dp(10) / fr.width(), rX = dp(18) / fr.width(), cY = dp(10) / fr.height(), rY = dp(18) / fr.height();
                    float[] sx = snap(t.cx, new float[]{SNAP_X}, cX, rX, snappedX);
                    float[] sy = snap(t.cy, SNAP_Y, cY, rY, snappedY);
                    int nx = (int) sx[1], ny = (int) sy[1];
                    if ((nx >= 0 && nx != snappedX) || (ny >= 0 && ny != snappedY)) tick();
                    snappedX = nx; snappedY = ny;
                    layout.cx = sx[0]; layout.cy = sy[0];
                    layout.clamp();
                    changed(false);
                } else if (mode == 2) {
                    dragging = true;
                    // distance from the box centre: drag out = bigger, drag in = smaller; the box stays centred where it is
                    float dist = (float) Math.hypot(x - startCx, y - startCy);
                    applySize(startScale * dist / startDist, false);
                }
                return true;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                boolean was = dragging;
                boolean sized = mode == 2 || mode == 3;
                mode = 0; dragging = false; snappedX = -1; snappedY = -1;
                invalidate();
                if (was && listener != null) listener.onChanged(layout);
                if (was && sized && sizeListener != null) sizeListener.onSize(style.sizeScale, true);
                performClick();
                return true;
        }
        return super.onTouchEvent(e);
    }

    /** live preview of the progress bar */
    private void drawExtras(Canvas c, float s, double t) {
        if (style.progressBar) {
            float h = 10 * s, y = fr.bottom - h;
            extra.setColor(0x66000000);
            c.drawRect(fr.left, y, fr.right, fr.bottom, extra);
            extra.setColor(style.highlightColor);
            c.drawRect(fr.left, y, fr.left + fr.width() * (float) Math.min(1, t / Math.max(0.1, total)), fr.bottom, extra);
        }
    }

    /** guides + unsafe bands, drawn over the preview while a gesture is running */
    private void drawGuides(Canvas c) {
        if (!dragging || fr.isEmpty()) return;
        // Shorts/Reels UI covers the bottom ~14% (title, buttons) and the right edge column; v14: square and 16:9
        // posts only lose a thin strip at the bottom (player controls / feed captions)
        if (layout.aspect < 0.8f) {
            c.drawRect(fr.left, fr.bottom - fr.height() * 0.14f, fr.right, fr.bottom, unsafe);
            c.drawRect(fr.right - fr.width() * 0.12f, fr.top + fr.height() * 0.45f, fr.right, fr.bottom - fr.height() * 0.14f, unsafe);
        } else c.drawRect(fr.left, fr.bottom - fr.height() * 0.08f, fr.right, fr.bottom, unsafe);
        if (snappedX >= 0) { float gx = fr.left + fr.width() * SNAP_X; c.drawLine(gx, fr.top, gx, fr.bottom, guide); }
        if (snappedY >= 0) { float gy = fr.top + fr.height() * SNAP_Y[snappedY]; c.drawLine(fr.left, gy, fr.right, gy, guide); }
    }


    // ------------------------------------------------------------------ v12 size (handle / pinch / slider)

    public static final float SIZE_MIN = 0.6f, SIZE_MAX = 1.6f;
    private float startScale = 1f, startDist = 1f, startCx, startCy, pinchSpan = 1f;

    private void beginSize() {
        startScale = style.sizeScale;
        startLay.cx = layout.cx; startLay.cy = layout.cy; startLay.w = layout.w; startLay.h = layout.h;
        startCx = box.centerX(); startCy = box.centerY();
    }

    /** pure maths (unit-tested): new size and the box scaled by the same ratio around its centre */
    public static float clampSize(float v) { return Math.max(SIZE_MIN, Math.min(SIZE_MAX, Math.round(v * 100f) / 100f)); }

    private void applySize(float want, boolean done) {
        float v = clampSize(want);
        float ratio = v / Math.max(0.01f, startScale);
        layout.w = Math.max(0.25f, Math.min(1f, startLay.w * ratio));
        layout.h = Math.max(0.05f, Math.min(0.6f, startLay.h * ratio));
        layout.cx = startLay.cx; layout.cy = startLay.cy;
        layout.clamp();
        boolean changedSize = v != style.sizeScale;
        style.sizeScale = v;
        if (changedSize) {
            if (v == SIZE_MIN || v == SIZE_MAX) tick();
            rebuild();
            if (sizeListener != null) sizeListener.onSize(v, done);
        } else invalidate();
    }

    /** the SIZE slider moved: same as dragging the handle (text and box scale together, box stays where it is) */
    public void setSizeScale(float v) {
        beginSize();
        applySize(v, true);
    }

    @Override public boolean performClick() { return super.performClick(); }

    private void changed(boolean sizeChanged) {
        if (sizeChanged) rebuild(); else invalidate();
        if (listener != null && mode != 1 && mode != 2 && mode != 3) listener.onChanged(layout);
    }
}
