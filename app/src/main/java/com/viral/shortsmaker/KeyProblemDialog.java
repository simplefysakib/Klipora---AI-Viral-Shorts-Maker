package com.viral.shortsmaker;

import android.app.Activity;
import android.content.Intent;
import android.net.Uri;
import android.widget.Toast;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.viral.shortsmaker.core.KeyCheck;

/**
 * The popup shown when the user taps Generate and Google / Groq say the saved API key is not valid (checked just
 * before the job starts). Same Material dialog style as the rest of the app; exactly three buttons: "Get new key" (opens
 * the provider's key page), "Open Settings" (straight to the key field) and "Cancel" (the job does not start).
 */
public final class KeyProblemDialog {
    private KeyProblemDialog() {}

    public static final String GEMINI_KEYS_URL = "https://aistudio.google.com/app/apikey";
    public static final String GROQ_KEYS_URL = "https://console.groq.com/keys";

    public static final String GEMINI_TITLE = "Your Gemini API key is not valid";
    public static final String GROQ_TITLE = "Your Groq API key is not valid";
    public static final String BTN_GET = "Get new key";
    public static final String BTN_SETTINGS = "Open Settings";
    public static final String BTN_CANCEL = "Cancel";

    /** which key the popup is about (BOTH shows the Gemini popup and names the Groq key too) */
    public static KeyCheck.Service service(KeyCheck.Preflight p) {
        return p == KeyCheck.Preflight.GROQ_INVALID ? KeyCheck.Service.GROQ : KeyCheck.Service.GEMINI;
    }

    public static String title(KeyCheck.Preflight p) {
        return service(p) == KeyCheck.Service.GROQ ? GROQ_TITLE : GEMINI_TITLE;
    }

    public static String url(KeyCheck.Preflight p) {
        return service(p) == KeyCheck.Service.GROQ ? GROQ_KEYS_URL : GEMINI_KEYS_URL;
    }

    public static String message(KeyCheck.Preflight p) {
        if (service(p) == KeyCheck.Service.GROQ) {
            return "Groq says the API key saved in Klipora doesn't work (it may have a typo, or it was deleted). "
                    + "Groq makes the transcript, so shorts can't be made until it's fixed.\n\n"
                    + "How to fix it:\n"
                    + "1) Open the GroqCloud console\n"
                    + "2) Create API key and copy it\n"
                    + "3) Paste it in Klipora Settings";
        }
        String m = "Google says the API key saved in Klipora doesn't work (it may have a typo, or it was deleted).\n\n"
                + "How to fix it:\n"
                + "1) Open Google AI Studio\n"
                + "2) Create API key and copy it\n"
                + "3) Paste it in Klipora Settings";
        if (p == KeyCheck.Preflight.BOTH_INVALID)
            m += "\n\nYour Groq API key is not valid either. Create a new one in the GroqCloud console (console.groq.com/keys) and paste it in Settings too.";
        return m;
    }

    /** the intent "Get new key" starts */
    public static Intent keyPageIntent(KeyCheck.Preflight p) {
        return new Intent(Intent.ACTION_VIEW, Uri.parse(url(p)));
    }

    /**
     * Shows the popup. {@code openSettings} receives the key the popup is about (Settings opens with that field focused).
     */
    public static AlertDialog show(Activity a, KeyCheck.Preflight p, java.util.function.Consumer<KeyCheck.Service> openSettings) {
        AlertDialog d = new MaterialAlertDialogBuilder(a)
                .setTitle(title(p))
                .setMessage(message(p))
                .setPositiveButton(BTN_GET, (x, w) -> {
                    try { a.startActivity(keyPageIntent(p)); }
                    catch (Exception e) { Toast.makeText(a, "Could not open browser", Toast.LENGTH_SHORT).show(); }
                })
                .setNeutralButton(BTN_SETTINGS, (x, w) -> { if (openSettings != null) openSettings.accept(service(p)); })
                .setNegativeButton(BTN_CANCEL, null)
                .create();
        d.show();
        return d;
    }

    /**
     * The save-time check (Settings / onboarding): both keys at the same time on a background thread (~5 s at most), the
     * answer on the main thread. Never throws; a network problem comes back as UNREACHABLE (saving is never blocked).
     */
    public static void checkBoth(String gemini, String groq, java.util.function.BiConsumer<KeyCheck.Verdict, KeyCheck.Verdict> onMain) {
        Thread t = new Thread(() -> {
            KeyCheck.Verdict[] v = new KeyCheck.Verdict[2];
            Thread g = new Thread(() -> v[1] = KeyCheck.check(KeyCheck.Service.GROQ, groq, true), "key-save-groq");
            g.setDaemon(true);
            g.start();
            v[0] = KeyCheck.check(KeyCheck.Service.GEMINI, gemini, true);
            try { g.join(7000); } catch (InterruptedException ignored) {}
            KeyCheck.Verdict a0 = v[0], b0 = v[1] == null ? KeyCheck.unreachable("no answer in time") : v[1];
            com.viral.shortsmaker.core.Net.main(() -> onMain.accept(a0, b0));
        }, "key-save-check");
        t.setDaemon(true);
        t.start();
    }
}
