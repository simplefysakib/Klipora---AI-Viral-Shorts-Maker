package com.viral.shortsmaker;

import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionFonts;

import java.io.File;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.util.HashMap;
import java.util.Map;

/**
 * Desktop (AWT) version of the app's font measurer, for tests and the demo render. Uses reflection because Android
 * unit tests compile against android.jar (no java.awt), while they run on a desktop JVM that has it.
 */
public class AwtMeasurer implements CaptionBuilder.TextMeasurer {
    private final File dir;
    private final Map<String, Object> fonts = new HashMap<>();
    private final Map<String, Float> scale = new HashMap<>();
    private Object frc;
    private Method deriveFont, getAdvance;
    private Constructor<?> layoutCtor;

    public AwtMeasurer(File fontsDir) { dir = fontsDir; }

    @Override public float width(CaptionFonts.Font f, String text, float assSize) {
        try {
            Class<?> fontC = Class.forName("java.awt.Font");
            if (frc == null) {
                Class<?> frcC = Class.forName("java.awt.font.FontRenderContext");
                frc = frcC.getConstructor(Class.forName("java.awt.geom.AffineTransform"), boolean.class, boolean.class).newInstance(null, true, true);
                deriveFont = fontC.getMethod("deriveFont", float.class);
                Class<?> tl = Class.forName("java.awt.font.TextLayout");
                layoutCtor = tl.getConstructor(String.class, fontC, frcC);
                getAdvance = tl.getMethod("getAdvance");
            }
            Object base = fonts.get(f.asset);
            if (base == null) {
                byte[] b = Files.readAllBytes(new File(dir, f.asset).toPath());
                base = fontC.getMethod("createFont", int.class, java.io.InputStream.class).invoke(null, 0, new java.io.ByteArrayInputStream(b));
                fonts.put(f.asset, base);
                scale.put(f.asset, CaptionFonts.assEmScale(b));
            }
            Object d = deriveFont.invoke(base, assSize * scale.get(f.asset));
            return (float) getAdvance.invoke(layoutCtor.newInstance(text, d, frc));
        } catch (Exception e) {
            return -1;
        }
    }
}
