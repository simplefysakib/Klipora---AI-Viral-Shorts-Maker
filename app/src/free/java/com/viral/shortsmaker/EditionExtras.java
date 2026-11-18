package com.viral.shortsmaker;

import android.app.Activity;

/** code 33 (5.1): the Free edition adds no extra settings (the backup engine test switch is Premium only). */
public final class EditionExtras {
    private EditionExtras() {}

    public static final boolean HAS_BACKUP_ENGINE_SWITCH = false;

    public static void addSettings(Activity a) { }
}
