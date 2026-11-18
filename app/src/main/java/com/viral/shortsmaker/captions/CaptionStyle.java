package com.viral.shortsmaker.captions;

import org.json.JSONObject;

/** A fully resolved caption look. Colours are ARGB ints. */
public class CaptionStyle {
    public enum Anim {
        /** whole line appears, no per-word change (classic subtitle) */
        STATIC,
        /** spoken word changes colour */
        HIGHLIGHT,
        /** spoken word changes colour and pops bigger */
        POP,
        /** colour sweep fill across each word (karaoke) */
        KARAOKE,
        /** spoken word sits on a coloured box */
        BOX_WORD,
        /** words appear one by one as they are spoken */
        REVEAL
    }

    public String id = "hormozi";
    public String name = "Hormozi";
    public String fontKey = "montserrat";
    /** font size in px on a 720x1280 frame, before box fitting */
    public float fontSize = 68f;
    public float sizeScale = 1f;
    public int textColor = 0xFFFFFFFF;
    public int highlightColor = 0xFFFFE500;
    public int outlineColor = 0xFF000000;
    public float outline = 5f;
    public float shadow = 2f;
    public int shadowColor = 0x99000000;
    /** semi-opaque box behind the whole line */
    public boolean lineBox = false;
    public int boxColor = 0xB3000000;
    public boolean uppercase = true;
    public int maxWordsPerLine = 3;
    public int maxLines = 1;
    public Anim anim = Anim.POP;
    /** soft glow (blurred outline) */
    public float glow = 0f;
    /** cycle highlight colours word by word (MrBeast look) */
    public boolean colorCycle = false;
    /** fade/slide the page in */
    public boolean entrance = true;
    /** v10: key words (numbers, money, strong words) stand out in an accent colour */
    public boolean keywords = true;
    /** v10: thin progress bar along the bottom edge */
    public boolean progressBar = false;
    /** v44: the caption language's own font (CaptionFonts key); null = the font above (or the script's font when needed) */
    public String scriptFont = null;
    /** v44: words are joined by a space (false for Japanese, Chinese, Thai: their words run together) */
    public boolean spaced = true;
    /** v44: locale for ALL CAPS ("tr" makes i into İ); null = the root locale */
    public String upperLocale = null;
    /** v44: right-to-left script: the word-by-word looks lay the words out from the right */
    public boolean rtl = false;

    public CaptionStyle copy() { return fromJson(toJson()); }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("id", id); o.put("name", name); o.put("fontKey", fontKey); o.put("fontSize", fontSize);
            o.put("sizeScale", sizeScale); o.put("textColor", textColor); o.put("highlightColor", highlightColor);
            o.put("outlineColor", outlineColor); o.put("outline", outline); o.put("shadow", shadow);
            o.put("shadowColor", shadowColor); o.put("lineBox", lineBox); o.put("boxColor", boxColor);
            o.put("uppercase", uppercase); o.put("maxWordsPerLine", maxWordsPerLine); o.put("maxLines", maxLines);
            o.put("anim", anim.name()); o.put("glow", glow); o.put("colorCycle", colorCycle); o.put("entrance", entrance);
            o.put("keywords", keywords); o.put("progressBar", progressBar);
            if (scriptFont != null) o.put("scriptFont", scriptFont);
            o.put("spaced", spaced);
            if (upperLocale != null) o.put("upperLocale", upperLocale);
            o.put("rtl", rtl);
        } catch (Exception ignored) {}
        return o;
    }

    public static CaptionStyle fromJson(JSONObject o) {
        CaptionStyle s = new CaptionStyle();
        if (o == null) return s;
        s.id = o.optString("id", s.id); s.name = o.optString("name", s.name);
        s.fontKey = o.optString("fontKey", s.fontKey); s.fontSize = (float) o.optDouble("fontSize", s.fontSize);
        s.sizeScale = (float) o.optDouble("sizeScale", s.sizeScale);
        s.textColor = o.optInt("textColor", s.textColor); s.highlightColor = o.optInt("highlightColor", s.highlightColor);
        s.outlineColor = o.optInt("outlineColor", s.outlineColor); s.outline = (float) o.optDouble("outline", s.outline);
        s.shadow = (float) o.optDouble("shadow", s.shadow); s.shadowColor = o.optInt("shadowColor", s.shadowColor);
        s.lineBox = o.optBoolean("lineBox", s.lineBox); s.boxColor = o.optInt("boxColor", s.boxColor);
        s.uppercase = o.optBoolean("uppercase", s.uppercase);
        s.maxWordsPerLine = o.optInt("maxWordsPerLine", s.maxWordsPerLine); s.maxLines = o.optInt("maxLines", s.maxLines);
        try { s.anim = Anim.valueOf(o.optString("anim", s.anim.name())); } catch (Exception ignored) {}
        s.glow = (float) o.optDouble("glow", s.glow); s.colorCycle = o.optBoolean("colorCycle", s.colorCycle);
        s.entrance = o.optBoolean("entrance", s.entrance);
        s.keywords = o.optBoolean("keywords", s.keywords);
        s.progressBar = o.optBoolean("progressBar", s.progressBar);
        s.scriptFont = o.has("scriptFont") ? o.optString("scriptFont", null) : null;
        s.spaced = o.optBoolean("spaced", s.spaced);
        s.upperLocale = o.has("upperLocale") ? o.optString("upperLocale", null) : null;
        s.rtl = o.optBoolean("rtl", false);
        return s;
    }
}
