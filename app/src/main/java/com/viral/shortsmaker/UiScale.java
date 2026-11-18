package com.viral.shortsmaker;

import android.content.Context;
import android.content.res.Configuration;

/**
 * v17 (2.8): the whole UI ~10% more compact (Sakib: "ui ux ko bas thoda sa chota karo saare buttons"). Every screen
 * gets the same slightly higher "display size" density, so buttons, cards, paddings, icons and text all shrink by the
 * same factor and stay consistent; the user's own font size setting still applies on top. Tap targets were raised to
 * 50 dp in the layouts, so they stay >= 45 real dp after the factor.
 */
public final class UiScale {
    public static final float FACTOR = 0.9f;

    private UiScale() {}

    public static Context wrap(Context base) {
        try {
            Configuration c = new Configuration(base.getResources().getConfiguration());
            if (c.densityDpi <= 0) return base;
            int dpi = Math.round(c.densityDpi * FACTOR);   // lower density = everything drawn ~10% smaller
            float k = (float) dpi / c.densityDpi;
            c.densityDpi = dpi;
            if (c.screenWidthDp > 0) c.screenWidthDp = Math.round(c.screenWidthDp / k);
            if (c.screenHeightDp > 0) c.screenHeightDp = Math.round(c.screenHeightDp / k);
            if (c.smallestScreenWidthDp > 0) c.smallestScreenWidthDp = Math.round(c.smallestScreenWidthDp / k);
            return base.createConfigurationContext(c);
        } catch (Throwable t) {
            return base;   // never block the app on a cosmetic setting
        }
    }
}
