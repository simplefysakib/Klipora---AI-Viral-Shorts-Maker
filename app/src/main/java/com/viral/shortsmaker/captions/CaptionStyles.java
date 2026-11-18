package com.viral.shortsmaker.captions;

import java.util.ArrayList;
import java.util.List;

/** Built-in caption presets, inspired by the looks that dominate Shorts/Reels. */
public final class CaptionStyles {
    private CaptionStyles() {}

    public static List<CaptionStyle> all() {
        List<CaptionStyle> l = new ArrayList<>();

        CaptionStyle s = base("hormozi", "Hormozi", "montserrat", CaptionStyle.Anim.POP);
        s.fontSize = 70; s.highlightColor = 0xFFFFE500; s.outline = 6; s.shadow = 3; s.maxWordsPerLine = 3; s.maxLines = 1;
        l.add(s);

        s = base("beast", "Beast", "anton", CaptionStyle.Anim.POP);
        s.fontSize = 92; s.highlightColor = 0xFF3DFF5A; s.outline = 7; s.shadow = 5; s.maxWordsPerLine = 2; s.maxLines = 1;
        s.colorCycle = true;
        l.add(s);

        s = base("karaoke", "Karaoke", "poppins", CaptionStyle.Anim.KARAOKE);
        s.fontSize = 58; s.highlightColor = 0xFF22D3EE; s.outline = 4; s.shadow = 2; s.maxWordsPerLine = 4; s.maxLines = 2;
        s.uppercase = false;
        l.add(s);

        s = base("boxword", "Highlight Box", "poppins", CaptionStyle.Anim.BOX_WORD);
        s.fontSize = 60; s.highlightColor = 0xFF7C3AED; s.outline = 4; s.shadow = 0; s.maxWordsPerLine = 3; s.maxLines = 2;
        l.add(s);

        s = base("pill", "Pill", "poppins_bold", CaptionStyle.Anim.HIGHLIGHT);
        s.fontSize = 50; s.lineBox = true; s.boxColor = 0xCC111111; s.highlightColor = 0xFFFACC15; s.outline = 9; s.shadow = 0;
        s.uppercase = false; s.maxWordsPerLine = 5; s.maxLines = 2;
        l.add(s);

        s = base("neon", "Neon Glow", "poppins", CaptionStyle.Anim.HIGHLIGHT);
        s.fontSize = 60; s.textColor = 0xFFE0FCFF; s.highlightColor = 0xFFFF2BD6; s.outlineColor = 0xFF00E5FF; s.outline = 3;
        s.glow = 6; s.shadow = 0; s.maxWordsPerLine = 3; s.maxLines = 2;
        l.add(s);

        s = base("clean", "Clean", "poppins_bold", CaptionStyle.Anim.STATIC);
        s.fontSize = 50; s.outline = 0; s.shadow = 3; s.shadowColor = 0xCC000000; s.uppercase = false;
        s.maxWordsPerLine = 6; s.maxLines = 2; s.highlightColor = 0xFFFFFFFF;
        l.add(s);

        s = base("subtitle", "Minimal Subtitle", "noto_deva", CaptionStyle.Anim.STATIC);
        s.fontSize = 42; s.lineBox = true; s.boxColor = 0x99000000; s.outline = 6; s.shadow = 0; s.uppercase = false;
        s.maxWordsPerLine = 8; s.maxLines = 2; s.entrance = false;
        l.add(s);

        s = base("wordpop", "One Word", "bebas", CaptionStyle.Anim.POP);
        s.fontSize = 150; s.highlightColor = 0xFFFFFFFF; s.outline = 7; s.shadow = 4; s.maxWordsPerLine = 1; s.maxLines = 1;
        l.add(s);

        s = base("desi", "Desi Bold", "baloo", CaptionStyle.Anim.REVEAL);
        s.fontSize = 66; s.textColor = 0xFFFFD60A; s.highlightColor = 0xFFFFFFFF; s.outline = 6; s.shadow = 3;
        s.uppercase = false; s.maxWordsPerLine = 3; s.maxLines = 2;
        l.add(s);

        return l;
    }

    private static CaptionStyle base(String id, String name, String font, CaptionStyle.Anim anim) {
        CaptionStyle s = new CaptionStyle();
        s.id = id; s.name = name; s.fontKey = font; s.anim = anim;
        return s;
    }

    public static CaptionStyle byId(String id) {
        for (CaptionStyle s : all()) if (s.id.equals(id)) return s;
        return all().get(0);
    }
}
