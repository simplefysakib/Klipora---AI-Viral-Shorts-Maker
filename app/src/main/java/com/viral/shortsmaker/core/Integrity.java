package com.viral.shortsmaker.core;

import android.app.Activity;
import android.content.Context;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.os.Build;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.viral.shortsmaker.BuildConfig;

import java.security.MessageDigest;

/**
 * v10 tamper check. A release build refuses to run when:
 *  - it is signed with any certificate other than Klipora's own (someone unpacked, modified and re-signed the APK), or
 *  - it was re-packaged as debuggable.
 * Debug builds (development) skip the check. This raises the bar; no Android app can be made impossible to crack.
 */
public final class Integrity {
    private Integrity() {}

    // SHA-256 of Klipora's release signing certificate (CN=Klipora, O=Simplefy), stored in two halves
    private static final String A = "005E19F4C9E5EC1E8BE36FF79287061D";
    private static final String B = "0611B21F9DD2DD5F548E2FEE1DF99BF1";

    private static volatile Boolean cached;

    public static boolean ok(Context c) {
        if (BuildConfig.DEBUG) return true;
        Boolean v = cached;
        if (v != null) return v;
        boolean r = check(c);
        cached = r;
        return r;
    }

    static boolean check(Context c) {
        try {
            if ((c.getApplicationInfo().flags & ApplicationInfo.FLAG_DEBUGGABLE) != 0) return false;
            // 5.4: no isDebuggerConnected() call (malware scanners list it as "anti-debug"). A release build is not debuggable, so a
            // debugger can only be attached to a copy that was re-packaged debuggable (caught just above) or re-signed (caught below).
            String want = A + B;
            for (String h : certHashes(c)) if (h.equalsIgnoreCase(want)) return true;
            return false;
        } catch (Throwable t) {
            return true;   // a phone that can't answer the question must not lock the owner out
        }
    }

    @SuppressWarnings("deprecation")
    static String[] certHashes(Context c) throws Exception {
        PackageManager pm = c.getPackageManager();
        Signature[] sigs;
        if (Build.VERSION.SDK_INT >= 28) {
            PackageInfo pi = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNING_CERTIFICATES);
            sigs = pi.signingInfo == null ? new Signature[0]
                    : pi.signingInfo.hasMultipleSigners() ? pi.signingInfo.getApkContentsSigners() : pi.signingInfo.getSigningCertificateHistory();
        } else {
            sigs = pm.getPackageInfo(c.getPackageName(), PackageManager.GET_SIGNATURES).signatures;
        }
        String[] out = new String[sigs == null ? 0 : sigs.length];
        MessageDigest md = MessageDigest.getInstance("SHA-256");
        for (int i = 0; i < out.length; i++) out[i] = hex(md.digest(sigs[i].toByteArray()));
        return out;
    }

    static String hex(byte[] b) {
        StringBuilder sb = new StringBuilder();
        for (byte x : b) sb.append(String.format("%02X", x));
        return sb.toString();
    }

    /** call first in onCreate of every entry activity; returns false (and closes the app) when tampered */
    public static boolean enforce(Activity a) {
        if (ok(a)) return true;
        try {
            new MaterialAlertDialogBuilder(a)
                    .setTitle("Modified app")
                    .setMessage("This copy of Klipora was changed by someone else and can't run. Install the original Klipora app.")
                    .setCancelable(false)
                    .setPositiveButton("Close", (d, w) -> a.finishAffinity())
                    .show();
        } catch (Throwable t) {
            a.finishAffinity();
        }
        return false;
    }
}
