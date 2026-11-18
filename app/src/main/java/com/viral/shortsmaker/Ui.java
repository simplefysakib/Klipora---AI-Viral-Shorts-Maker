package com.viral.shortsmaker;

import android.app.Activity;
import android.os.Build;
import android.view.View;
import android.view.ViewGroup;

import androidx.core.graphics.Insets;
import androidx.core.view.ViewCompat;
import androidx.core.view.WindowInsetsCompat;

/** v11: small UI helpers shared by the screens. */
final class Ui {
    private Ui() {}

    /**
     * Keeps a screen's content clear of the status bar, navigation bar / gesture area, display cut-out and keyboard
     * when Android draws the app edge-to-edge (Android 15+ with targetSdk 35+). The app targets 34 today, where Android
     * itself keeps content above the bars, so this is a no-op there; it is wired in now so a future target bump cannot
     * put buttons under the gesture bar.
     */
    static void fitSystemBars(Activity a) {
        if (!edgeToEdge(a)) return;
        View root = ((ViewGroup) a.findViewById(android.R.id.content)).getChildAt(0);
        if (root == null) return;
        final int l = root.getPaddingLeft(), t = root.getPaddingTop(), r = root.getPaddingRight(), b = root.getPaddingBottom();
        ViewCompat.setOnApplyWindowInsetsListener(root, (v, in) -> {
            Insets s = in.getInsets(WindowInsetsCompat.Type.systemBars() | WindowInsetsCompat.Type.displayCutout());
            Insets ime = in.getInsets(WindowInsetsCompat.Type.ime());
            v.setPadding(l + s.left, t + s.top, r + s.right, b + Math.max(s.bottom, ime.bottom));
            return WindowInsetsCompat.CONSUMED;
        });
        ViewCompat.requestApplyInsets(root);
    }

    static boolean edgeToEdge(Activity a) {
        return Build.VERSION.SDK_INT >= 35 && a.getApplicationInfo().targetSdkVersion >= 35;
    }
}
