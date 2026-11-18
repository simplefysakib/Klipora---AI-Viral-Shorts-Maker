package com.viral.shortsmaker.captions;

/** Fonts bundled in assets/fonts. family = the exact family name inside the TTF (used by libass). */
public final class CaptionFonts {
    public static final class Font {
        public final String key, label, family, asset;
        public final boolean devanagari;
        /** average glyph advance as a fraction of the font size (bold caps), used to fit lines into the box */
        public final float widthFactor;
        /** v44: script fonts draw smaller at the same point size; the caption size is raised by this factor */
        public final float sizeBoost;
        Font(String key, String label, String family, String asset, boolean devanagari, float widthFactor) {
            this(key, label, family, asset, devanagari, widthFactor, 1f);
        }
        Font(String key, String label, String family, String asset, boolean devanagari, float widthFactor, float sizeBoost) {
            this.key = key; this.label = label; this.family = family; this.asset = asset;
            this.devanagari = devanagari; this.widthFactor = widthFactor; this.sizeBoost = sizeBoost;
        }
    }

    public static final Font[] ALL = {
            new Font("montserrat", "Montserrat XB", "KL Montserrat", "kl_montserrat.ttf", false, 0.66f),
            new Font("poppins", "Poppins Black", "KL Poppins", "kl_poppins.ttf", true, 0.64f),
            new Font("poppins_bold", "Poppins Bold", "KL Poppins Medium", "kl_poppins_medium.ttf", true, 0.60f),
            new Font("anton", "Anton", "KL Anton", "kl_anton.ttf", false, 0.46f),
            new Font("bebas", "Bebas Neue", "KL Bebas", "kl_bebas.ttf", false, 0.42f),
            new Font("baloo", "Baloo (Hindi)", "KL Baloo", "kl_baloo.ttf", true, 0.58f),
            new Font("noto_deva", "Noto Devanagari", "KL Noto Deva", "kl_noto_deva.ttf", true, 0.58f),
            // v44 (5.7): the caption languages' own scripts (Noto Sans, OFL), bold, subset to the script's letters
            new Font("noto_sans", "Noto Sans", "KL Noto Sans", "kl_noto_sans.ttf", false, 0.62f, 1f),
            new Font("noto_bengali", "Noto Bengali", "KL Noto Bengali", "kl_noto_bengali.ttf", false, 0.60f, 1.12f),
            new Font("noto_tamil", "Noto Tamil", "KL Noto Tamil", "kl_noto_tamil.ttf", false, 0.60f, 1.12f),
            new Font("noto_telugu", "Noto Telugu", "KL Noto Telugu", "kl_noto_telugu.ttf", false, 0.60f, 1.12f),
            new Font("noto_gujarati", "Noto Gujarati", "KL Noto Gujarati", "kl_noto_gujarati.ttf", false, 0.60f, 1.12f),
            new Font("noto_kannada", "Noto Kannada", "KL Noto Kannada", "kl_noto_kannada.ttf", false, 0.60f, 1.12f),
            new Font("noto_malayalam", "Noto Malayalam", "KL Noto Malayalam", "kl_noto_malayalam.ttf", false, 0.60f, 1.12f),
            new Font("noto_gurmukhi", "Noto Gurmukhi", "KL Noto Gurmukhi", "kl_noto_gurmukhi.ttf", false, 0.60f, 1.12f),
            new Font("noto_arabic", "Noto Arabic", "KL Noto Arabic", "kl_noto_arabic.ttf", false, 0.52f, 1.12f),
            new Font("noto_thai", "Noto Thai", "KL Noto Thai", "kl_noto_thai.ttf", false, 0.60f, 1.10f),
            new Font("noto_jp", "Noto Sans JP", "KL Noto JP", "kl_noto_jp.ttf", false, 0.95f, 0.95f),
            new Font("noto_kr", "Noto Sans KR", "KL Noto KR", "kl_noto_kr.ttf", false, 0.95f, 0.95f),
            new Font("noto_sc", "Noto Sans SC", "KL Noto SC", "kl_noto_sc.ttf", false, 0.95f, 0.95f),
    };

    private CaptionFonts() {}

    public static Font get(String key) {
        for (Font f : ALL) if (f.key.equals(key)) return f;
        return ALL[1];
    }

    public static boolean containsDevanagari(CharSequence s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c >= 0x0900 && c <= 0x097F) return true;
        }
        return false;
    }

    /**
     * v44: the bundled font that draws the first non-Latin script found in the text (Cyrillic, Bengali ... Thai, kana,
     * Hangul), or null when the text is Latin / Devanagari / Han only (Han is picked by the caption language).
     */
    public static String scriptKeyFor(CharSequence s) {
        if (s == null) return null;
        for (int i = 0; i < s.length(); i++) {
            int c = s.charAt(i);
            if (c >= 0x0400 && c <= 0x04FF) return "noto_sans";
            if (c >= 0x0980 && c <= 0x09FF) return "noto_bengali";
            if (c >= 0x0A00 && c <= 0x0A7F) return "noto_gurmukhi";
            if (c >= 0x0A80 && c <= 0x0AFF) return "noto_gujarati";
            if (c >= 0x0B80 && c <= 0x0BFF) return "noto_tamil";
            if (c >= 0x0C00 && c <= 0x0C7F) return "noto_telugu";
            if (c >= 0x0C80 && c <= 0x0CFF) return "noto_kannada";
            if (c >= 0x0D00 && c <= 0x0D7F) return "noto_malayalam";
            if ((c >= 0x0600 && c <= 0x06FF) || (c >= 0x0750 && c <= 0x077F) || (c >= 0xFB50 && c <= 0xFDFF) || (c >= 0xFE70 && c <= 0xFEFF)) return "noto_arabic";
            if (c >= 0x0E00 && c <= 0x0E7F) return "noto_thai";
            if (c >= 0x3040 && c <= 0x30FF) return "noto_jp";
            if (c >= 0xAC00 && c <= 0xD7AF) return "noto_kr";
        }
        return null;
    }

    /** Font actually used for a given text: Latin-only fonts are swapped for a Devanagari-capable one. */
    public static Font resolve(String key, CharSequence sampleText) {
        Font f = get(key);
        if (!f.devanagari && sampleText != null && containsDevanagari(sampleText)) {
            return (f.key.equals("anton") || f.key.equals("bebas")) ? get("baloo") : get("poppins");
        }
        // v44: any other script the chosen font cannot draw (Bengali under Bebas, Cyrillic under Montserrat ...) uses its own font
        String need = scriptKeyFor(sampleText);
        if (need != null && !f.key.equals(need)) return get(need);
        return f;
    }

    /**
     * libass sizes a font so that usWinAscent+usWinDescent (OS/2) spans the ASS font size, while Android/AWT
     * sizes by the em square. Returns unitsPerEm / (winAscent + winDescent) so a measurer can convert. 1 on error.
     */
    public static float assEmScale(byte[] ttf) {
        try {
            java.nio.ByteBuffer b = java.nio.ByteBuffer.wrap(ttf);
            int num = b.getShort(4) & 0xFFFF;
            int upem = 0, winA = 0, winD = 0, hA = 0, hD = 0;
            for (int i = 0; i < num; i++) {
                int rec = 12 + i * 16;
                String tag = new String(ttf, rec, 4, "ISO-8859-1");
                int off = b.getInt(rec + 8);
                if (tag.equals("head")) upem = b.getShort(off + 18) & 0xFFFF;
                else if (tag.equals("OS/2")) { winA = b.getShort(off + 74) & 0xFFFF; winD = b.getShort(off + 76) & 0xFFFF; }
                else if (tag.equals("hhea")) { hA = b.getShort(off + 4); hD = b.getShort(off + 6); }
            }
            int h = winA + winD;
            if (h <= 0) h = hA - hD;
            if (upem <= 0 || h <= 0) return 1f;
            return (float) upem / h;
        } catch (Exception e) {
            return 1f;
        }
    }
}
