package com.viral.shortsmaker.captions;

import org.json.JSONObject;

/**
 * The caption area the user draws on the 9:16 frame, normalised to 0..1.
 * cx/cy = centre of the box, w/h = size of the box. The box size drives the font size and line width.
 */
public class CaptionLayout {
    public float cx = 0.5f;
    public float cy = 0.72f;
    public float w = 0.86f;
    public float h = 0.15f;
    /**
     * v14: output frame width / height (9:16 = 0.5625 default, 1:1, 16:9). Not stored in the JSON: it comes from the
     * project's format, so a layout drawn in Caption Studio renders exactly where it was drawn in that format.
     */
    public float aspect = 9f / 16f;

    public CaptionLayout() {}

    /** default caption box for a format: lower third, narrower on 16:9 */
    public static CaptionLayout defaultFor(float aspect) {
        CaptionLayout l = aspect > 1.2f ? new CaptionLayout(0.5f, 0.82f, 0.72f, 0.2f)
                : aspect > 0.8f ? new CaptionLayout(0.5f, 0.78f, 0.86f, 0.17f) : new CaptionLayout();
        l.aspect = aspect;
        return l;
    }

    /** ASS canvas of this format: short side 720 (720x1280, 720x720, 1280x720) */
    public int frameW() { return aspect >= 1f ? Math.round(720 * aspect / 2f) * 2 : 720; }
    public int frameH() { return aspect >= 1f ? 720 : Math.round(720 / aspect / 2f) * 2; }
    /** font sizes are designed on the 720x1280 frame; landscape text is drawn a little smaller */
    public float fontScale() { return aspect > 1.2f ? 0.8f : 1f; }

    public CaptionLayout(float cx, float cy, float w, float h) {
        this.cx = cx; this.cy = cy; this.w = w; this.h = h;
        clamp();
    }

    public CaptionLayout copy() { CaptionLayout c = new CaptionLayout(cx, cy, w, h); c.aspect = aspect; return c; }

    public void clamp() {
        w = Math.max(0.25f, Math.min(1f, w));
        h = Math.max(0.05f, Math.min(0.6f, h));
        cx = Math.max(w / 2f, Math.min(1f - w / 2f, cx));
        cy = Math.max(h / 2f, Math.min(1f - h / 2f, cy));
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try { o.put("cx", cx); o.put("cy", cy); o.put("w", w); o.put("h", h); } catch (Exception ignored) {}
        return o;
    }

    public static CaptionLayout fromJson(JSONObject o) {
        CaptionLayout l = new CaptionLayout();
        if (o == null) return l;
        l.cx = (float) o.optDouble("cx", l.cx);
        l.cy = (float) o.optDouble("cy", l.cy);
        l.w = (float) o.optDouble("w", l.w);
        l.h = (float) o.optDouble("h", l.h);
        l.clamp();
        return l;
    }
}
