package com.viral.shortsmaker.captions;

import android.graphics.Paint;
import android.graphics.Typeface;

import java.io.File;
import java.io.FileInputStream;
import java.util.HashMap;
import java.util.Map;

/** Real font metrics on the device: Android's text shaper measures exactly what libass will draw. */
public final class AndroidMeasurer implements CaptionBuilder.TextMeasurer {
    private final File dir;
    private final Map<String, Typeface> faces = new HashMap<>();
    private final Map<String, Float> scales = new HashMap<>();
    private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);

    public AndroidMeasurer(File fontsDir) { dir = fontsDir; }

    @Override public synchronized float width(CaptionFonts.Font f, String text, float assSize) {
        Typeface tf = faces.get(f.asset);
        if (tf == null) {
            File file = new File(dir, f.asset);
            if (!file.exists()) return -1;
            tf = Typeface.createFromFile(file);
            faces.put(f.asset, tf);
            float sc = 1f;
            try (FileInputStream in = new FileInputStream(file)) {
                byte[] b = new byte[(int) file.length()];
                int off = 0, r;
                while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
                sc = CaptionFonts.assEmScale(b);
            } catch (Exception ignored) {}
            scales.put(f.asset, sc);
        }
        paint.setTypeface(tf);
        paint.setTextSize(assSize * scales.get(f.asset));
        return paint.measureText(text);
    }
}
