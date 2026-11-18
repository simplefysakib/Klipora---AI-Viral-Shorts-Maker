package com.viral.shortsmaker;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.viral.shortsmaker.core.Prefs;

/**
 * Legal notice (Nov 1 2026): a one-time "use only content you own or may use" notice with links to the Privacy Policy
 * and Terms of Use, shown on first start until the user taps "I Agree" (stored in SharedPreferences, never shown
 * again), plus the same links in Settings. It only informs - no feature is gated, changed or removed by it.
 */
public final class LegalNotice {
    private LegalNotice() {}

    public static final String PRIVACY_URL = "https://klipora.sites.tab.bot/privacy";
    public static final String TERMS_URL = "https://klipora.sites.tab.bot/terms";
    public static final String KEY_ACCEPTED = "legal_notice_accepted_v1";
    public static final String KEY_ACCEPTED_AT = "legal_notice_accepted_at";

    public static final String TITLE = "Before you start";
    public static final String BODY = "Use Klipora only with videos you own or have permission to use. "
            + "You are responsible for the content you create and share. "
            + "Klipora is not affiliated with YouTube or Google.";
    public static final String DISCLAIMER = "Klipora is not affiliated with YouTube or Google. "
            + "Only use content you own or have permission to use.";

    /** tests: Robolectric screens skip the notice unless a test asks for it */
    static boolean showInTests = false;
    /** the notice currently on screen (tests) */
    static AlertDialog current;

    public static boolean accepted(Context c) { return Prefs.sp(c).getBoolean(KEY_ACCEPTED, false); }

    public static void accept(Context c) {
        Prefs.sp(c).edit().putBoolean(KEY_ACCEPTED, true).putLong(KEY_ACCEPTED_AT, System.currentTimeMillis()).apply();
    }

    /** shows the one-time notice if it was never accepted; the app keeps working underneath exactly as before */
    public static void showIfNeeded(Activity a) {
        if (a == null || a.isFinishing() || accepted(a)) return;
        if ("robolectric".equals(Build.FINGERPRINT) && !showInTests) return;
        // code 32: only a notice on THIS screen counts (a re-created screen - font size / dark mode change - showed none,
        // because the old screen's dead dialog still said isShowing())
        if (current != null && current.isShowing() && current.getOwnerActivity() == a) return;
        View v = LayoutInflater.from(a).inflate(R.layout.dialog_legal, null);
        v.findViewById(R.id.btnLegalPrivacy).setOnClickListener(x -> open(a, PRIVACY_URL));
        v.findViewById(R.id.btnLegalTerms).setOnClickListener(x -> open(a, TERMS_URL));
        AlertDialog d = new MaterialAlertDialogBuilder(a)
                .setTitle(TITLE)
                .setView(v)
                .setCancelable(false)
                .setPositiveButton("I Agree", (dlg, w) -> { accept(a); current = null; })
                .create();
        d.setCanceledOnTouchOutside(false);
        d.setOwnerActivity(a);
        d.setOnDismissListener(x -> { if (current == d) current = null; });
        current = d;
        try { d.show(); } catch (Exception ignored) { current = null; }
    }

    public static void open(Context c, String url) {
        try {
            Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            if (!(c instanceof Activity)) i.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            c.startActivity(i);
        } catch (Exception e) {
            Toast.makeText(c, "Could not open browser. Visit " + url, Toast.LENGTH_LONG).show();
        }
    }
}
