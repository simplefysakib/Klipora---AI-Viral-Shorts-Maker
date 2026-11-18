package com.viral.shortsmaker.core;

import android.content.Context;
import android.content.SharedPreferences;
import android.os.Build;

import org.json.JSONObject;

import java.util.Locale;
import java.util.concurrent.TimeUnit;

/**
 * code 31 (4.9): "a new version is available". Sakib keeps getting bug reports from old builds that a newer one already
 * fixed. At app start (at most once every 12 h, in the background, 6 s timeout, silent on any failure) Klipora reads
 * version.json from the website ({"versionCode":31,"versionName":"4.9","url":...,"notes":...,"apk":{"universal":...,
 * "arm64":...}}); if that fails, GitHub's latest-release API (tag "v4.9", compared by name). A newer build is offered once
 * per app start: Download (the GitHub APK for this phone: arm64 or universal), Website, Later (hides that version 3 days).
 * Premium edition: no check at all (its builds are not public; the public site's file would replace it with the free app).
 */
public final class UpdateCheck {
    private UpdateCheck() {}

    public static final String VERSION_URL = "https://klipora.sites.tab.bot/version.json";
    public static final String GITHUB_API = "https://api.github.com/repos/simplefysakib/Klipora---AI-Viral-Shorts-Maker/releases/latest";
    public static final String SITE = "https://klipora.sites.tab.bot/";
    public static final String APK_UNIVERSAL = "https://github.com/simplefysakib/Klipora---AI-Viral-Shorts-Maker/releases/latest/download/Klipora_universal.apk";
    public static final String APK_ARM64 = "https://github.com/simplefysakib/Klipora---AI-Viral-Shorts-Maker/releases/latest/download/Klipora_arm64.apk";
    public static final long CHECK_EVERY_MS = 12 * 3600_000L;
    public static final long SNOOZE_MS = 3 * 24 * 3600_000L;

    static final String K_AT = "upd_checked_at", K_CODE = "upd_code", K_NAME = "upd_name", K_NOTES = "upd_notes", K_SITE = "upd_site",
            K_UNI = "upd_apk_uni", K_ARM = "upd_apk_arm", K_SNOOZE_CODE = "upd_snooze_code", K_SNOOZE_NAME = "upd_snooze_name", K_SNOOZE_UNTIL = "upd_snooze_until";

    /** what the latest public build is */
    public static final class Info {
        public int code;          // 0 = unknown (GitHub tag only)
        public String name = "", notes = "", site = SITE, apkUniversal = APK_UNIVERSAL, apkArm64 = APK_ARM64;
        public String apkFor(boolean arm64) { return arm64 ? apkArm64 : apkUniversal; }
    }

    // ------------------------------------------------------------------ pure (unit tested)

    /** numeric compare of "4.9" / "v4.10" / "4.9.1" (missing parts = 0); <0 a older, >0 a newer */
    public static int compareNames(String a, String b) {
        int[] x = parts(a), y = parts(b);
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? x[i] : 0, q = i < y.length ? y[i] : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    static int[] parts(String v) {
        if (v == null) return new int[0];
        String s = v.trim().replaceFirst("^[vV]", "").replaceAll("[^0-9.].*$", "");
        if (s.isEmpty()) return new int[0];
        String[] p = s.split("\\.");
        int[] o = new int[p.length];
        for (int i = 0; i < p.length; i++) { try { o[i] = Integer.parseInt(p[i]); } catch (Exception e) { o[i] = 0; } }
        return o;
    }

    /** is this build newer than the running one? versionCode when known, else the version name */
    public static boolean newer(Info i, int curCode, String curName) {
        if (i == null) return false;
        if (i.code > 0) return i.code > curCode;
        return !i.name.isEmpty() && parts(i.name).length > 0 && compareNames(i.name, curName) > 0;
    }

    /** time for a new check (12 h since the last one; a clock set back counts as due) */
    public static boolean due(long now, long lastCheck) { return lastCheck <= 0 || now - lastCheck >= CHECK_EVERY_MS || lastCheck > now + 60_000; }

    /** "Later" on this version hides it for 3 days; a newer version shows at once */
    public static boolean snoozed(long now, Info i, int snoozeCode, String snoozeName, long until) {
        if (i == null || until <= now || until - now > SNOOZE_MS + 60_000) return false;
        if (i.code > 0) return i.code == snoozeCode;
        return snoozeName != null && !snoozeName.isEmpty() && compareNames(i.name, snoozeName) == 0;
    }

    public static Info parseVersionJson(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        Info i = new Info();
        i.code = o.optInt("versionCode", 0);
        i.name = o.optString("versionName", "");
        i.notes = o.optString("notes", "");
        String site = o.optString("url", "");
        if (site.startsWith("https://")) i.site = site;
        JSONObject apk = o.optJSONObject("apk");
        if (apk != null) {
            String u = apk.optString("universal", ""), a = apk.optString("arm64", "");
            if (u.startsWith("https://")) i.apkUniversal = u;
            if (a.startsWith("https://")) i.apkArm64 = a;
        }
        if (i.code <= 0 && i.name.isEmpty()) throw new Exception("no version in version.json");
        return i;
    }

    public static Info parseGithubLatest(String body) throws Exception {
        JSONObject o = new JSONObject(body);
        Info i = new Info();
        i.name = o.optString("tag_name", "").replaceFirst("^[vV]", "");
        if (parts(i.name).length == 0) throw new Exception("no tag");
        return i;
    }

    // ------------------------------------------------------------------ the phone

    public static boolean enabled() {
        // Permanently disabled in the 5.9.1 maintenance build. The old public endpoint can
        // advertise stale or unrelated builds, so Klipora must never show a false update notice.
        return false;
    }

    static int curCode() { try { return com.viral.shortsmaker.BuildConfig.VERSION_CODE; } catch (Throwable t) { return 0; } }
    static String curName() { try { return com.viral.shortsmaker.BuildConfig.VERSION_NAME; } catch (Throwable t) { return ""; } }

    public static boolean arm64() {
        try { return Build.SUPPORTED_ABIS != null && Build.SUPPORTED_ABIS.length > 0 && "arm64-v8a".equals(Build.SUPPORTED_ABIS[0]); }
        catch (Throwable t) { return false; }
    }

    /** the stored latest build (null = none known) */
    public static Info stored(Context c) {
        SharedPreferences sp = Prefs.sp(c);
        Info i = new Info();
        i.code = sp.getInt(K_CODE, 0);
        i.name = sp.getString(K_NAME, "");
        if (i.code <= 0 && i.name.isEmpty()) return null;
        i.notes = sp.getString(K_NOTES, "");
        i.site = sp.getString(K_SITE, SITE);
        i.apkUniversal = sp.getString(K_UNI, APK_UNIVERSAL);
        i.apkArm64 = sp.getString(K_ARM, APK_ARM64);
        return i;
    }

    static void store(Context c, Info i, long now) {
        Prefs.sp(c).edit().putLong(K_AT, now).putInt(K_CODE, i.code).putString(K_NAME, i.name).putString(K_NOTES, i.notes)
                .putString(K_SITE, i.site).putString(K_UNI, i.apkUniversal).putString(K_ARM, i.apkArm64).apply();
    }

    /** a newer build is known (for the error card / report); null = this is the newest known */
    public static Info newerKnown(Context c) {
        if (!enabled()) return null;
        Info i = stored(c);
        return newer(i, curCode(), curName()) ? i : null;
    }

    /** a newer build to offer now (known, newer, not snoozed) */
    public static Info toOffer(Context c, long now) {
        Info i = newerKnown(c);
        if (i == null) return null;
        SharedPreferences sp = Prefs.sp(c);
        return snoozed(now, i, sp.getInt(K_SNOOZE_CODE, 0), sp.getString(K_SNOOZE_NAME, ""), sp.getLong(K_SNOOZE_UNTIL, 0)) ? null : i;
    }

    public static void later(Context c, Info i, long now) {
        if (i == null) return;
        Prefs.sp(c).edit().putInt(K_SNOOZE_CODE, i.code).putString(K_SNOOZE_NAME, i.name).putLong(K_SNOOZE_UNTIL, now + SNOOZE_MS).apply();
    }

    public interface Fetcher { String get(String url) throws Exception; }

    /**
     * the check itself (call off the main thread): due, version.json, else GitHub; stores what it found. Never throws;
     * returns the newer build to offer (null = none / failed / not due).
     */
    public static Info check(Context c, long now, Fetcher f) {
        try {
            if (!enabled()) return null;
            SharedPreferences sp = Prefs.sp(c);
            if (!due(now, sp.getLong(K_AT, 0))) return toOffer(c, now);
            Info i = null;
            try { i = parseVersionJson(f.get(VERSION_URL)); } catch (Throwable ignored) {}
            if (i == null) { try { i = parseGithubLatest(f.get(GITHUB_API)); } catch (Throwable ignored) {} }
            if (i == null) { sp.edit().putLong(K_AT, now - CHECK_EVERY_MS + 3600_000L).apply(); return null; }   // failed: try again in an hour
            store(c, i, now);
            return toOffer(c, now);
        } catch (Throwable t) {
            return null;
        }
    }

    /** OkHttp, 6 s */
    public static Fetcher http() {
        okhttp3.OkHttpClient client = new okhttp3.OkHttpClient.Builder().connectTimeout(6, TimeUnit.SECONDS).readTimeout(6, TimeUnit.SECONDS)
                .callTimeout(8, TimeUnit.SECONDS).build();
        return url -> {
            okhttp3.Request rq = new okhttp3.Request.Builder().url(url).header("Accept", "application/json")
                    .header("User-Agent", "Klipora-Android").build();
            try (okhttp3.Response r = client.newCall(rq).execute()) {
                if (!r.isSuccessful() || r.body() == null) throw new Exception("HTTP " + r.code());
                String b = r.body().string();
                if (b.length() > 64 * 1024) throw new Exception("too big");
                return b;
            }
        };
    }

    /** true when the phone has a network (no request is made without one) */
    public static boolean online(Context c) {
        try {
            android.net.ConnectivityManager cm = (android.net.ConnectivityManager) c.getSystemService(Context.CONNECTIVITY_SERVICE);
            android.net.NetworkInfo ni = cm == null ? null : cm.getActiveNetworkInfo();
            return ni != null && ni.isConnected();
        } catch (Throwable t) { return false; }
    }

    /** "4.9" for the notice (the one place a version number is shown) */
    public static String label(Info i) { return i == null ? "" : !i.name.isEmpty() ? i.name : String.format(Locale.US, "build %d", i.code); }
}
