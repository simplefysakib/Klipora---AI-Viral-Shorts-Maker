package com.viral.shortsmaker.core;

import android.content.Context;
import android.content.SharedPreferences;

import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.captions.CaptionStyles;

import org.json.JSONObject;

/** Same preference file and keys as v1 ("AppConfig"), so existing users keep keys, PRO timers and settings. */
public final class Prefs {
    public static final String NAME = "AppConfig";
    private Prefs() {}

    public static SharedPreferences sp(Context c) { return c.getApplicationContext().getSharedPreferences(NAME, Context.MODE_PRIVATE); }

    // v10: keys are encrypted with an Android Keystore key (SecureKeys); v1-v9 plain-text keys migrate on first read
    public static String geminiKey(Context c) { return SecureKeys.get(c, "gemini_key").trim(); }
    public static String groqKey(Context c) { return SecureKeys.get(c, "groq_key").trim(); }
    public static void setKeys(Context c, String gemini, String groq) { SecureKeys.put(c, "gemini_key", gemini); SecureKeys.put(c, "groq_key", groq); }

    public static boolean isValidGeminiKey(String key) {
        if (key == null) return false;
        String k = key.trim();
        if (k.length() < 30 || k.contains(" ")) return false;
        if (!(k.startsWith("AIza") || k.startsWith("AQ."))) return false;
        return k.matches("^[A-Za-z0-9_\\-\\.]+$");
    }

    public static boolean isValidGroqKey(String key) {
        if (key == null) return false;
        String k = key.trim();
        if (k.length() < 35 || k.contains(" ")) return false;
        return k.matches("^gsk_[A-Za-z0-9_\\-]+$");
    }

    public static boolean keysValid(Context c) { return isValidGeminiKey(geminiKey(c)) && isValidGroqKey(groqKey(c)); }

    // ---- monetisation (unchanged semantics from v1)
    public static final long ONE_HOUR_MS = 3600000L;
    public static final long DAY_MS = 86400000L;

    /**
     * v18 (2.9): every timed unlock is clamped to its own length, so moving the phone's clock back can never stretch
     * it (2.8: left = stored + length - now, which a clock set back a day turned into 25 hours).
     */
    static long timedLeft(long now, long grantedAt, long length) {
        if (grantedAt <= 0 || length <= 0) return 0L;
        return Math.max(0L, Math.min(length, grantedAt + length - now));
    }
    public static long timedLeftForTest(long now, long grantedAt, long length) { return timedLeft(now, grantedAt, length); }

    /**
     * v21 audit (PRO pass expiry): the clock the unlock timers read. 3.1 used the phone's clock as is, so setting the
     * clock back kept an unlock's time from running down (a 24 h PRO pass could be stretched for free). The newest time
     * ever seen is remembered; a clock set back reads as that time. A clock that was wrong in the future by more than
     * 2 days and then fixed resets the mark (so a real correction never locks what was earned for days).
     */
    static long now(Context c) { return nowFor(sp(c), wall.getAsLong()); }

    /** code 32: clocks (tests replace them) */
    public static java.util.function.LongSupplier wall = System::currentTimeMillis;
    public static java.util.function.LongSupplier elapsed = android.os.SystemClock::elapsedRealtime;

    static int bootCount(Context c) {
        try { return android.provider.Settings.Global.getInt(c.getContentResolver(), android.provider.Settings.Global.BOOT_COUNT, -1); }
        catch (Throwable t) { return -1; }
    }

    /**
     * code 32 (5.0): a timed unlock is granted: stamped with the wall clock (kept for older builds) AND the phone's uptime
     * clock + boot number. Uptime can't be changed by the user, so within one boot the time used is exact whatever the
     * clock says.
     */
    public static void stampGrant(Context c, SharedPreferences.Editor e, String key) {
        long at = now(c);
        e.putLong(key, at).putLong(key + "_stamp", at).putLong(key + "_el", elapsed.getAsLong()).putInt(key + "_boot", bootCount(c)).putLong(key + "_used", 0L);
    }

    /**
     * code 32: how long a timed unlock has run. 4.9 read only the wall clock (with a "newest time seen" mark that a jump
     * of more than 2 days reset): a clock set back 3 days right after an ad kept a 1 h 1080p unlock full for 3 days
     * (24 h PRO: 4 days), and a clock set back a few hours froze every timer. Now: the uptime clock within one boot; the
     * wall clock only across a reboot (the phone was off); and the time used never goes down.
     */
    static long usedMs(Context c, String key) {
        SharedPreferences p = sp(c);
        long at = p.getLong(key, 0);
        if (at <= 0) return Long.MAX_VALUE / 4;
        // our own stamp of THIS grant? (a grant written by an older build has no stamp, or the stamp of an earlier grant)
        boolean ours = p.getLong(key + "_stamp", -1) == at;
        long el = p.getLong(key + "_el", Long.MIN_VALUE), nowEl = elapsed.getAsLong(), stored = ours ? p.getLong(key + "_used", 0) : 0L;
        int boot = p.getInt(key + "_boot", -2), nowBoot = bootCount(c);
        boolean sameBoot = ours && el != Long.MIN_VALUE && boot == nowBoot && nowEl >= el;
        long used;
        if (sameBoot) {
            used = nowEl - el;
            if (nowBoot < 0) used = Math.max(used, now(c) - at);   // boot number unknown: never less than the wall clock says
        } else used = now(c) - at;                                   // after a reboot (or a grant from an older build)
        used = Math.max(used, stored);
        if (!sameBoot || used - stored >= 60_000L) {   // (the timers are read every second: write once a minute)
            SharedPreferences.Editor e = p.edit().putLong(key + "_used", used);
            if (!sameBoot) e.putLong(key + "_el", nowEl - used).putInt(key + "_boot", nowBoot).putLong(key + "_stamp", at);   // count on uptime from here
            e.apply();
        }
        return used;
    }

    static long monoLeft(Context c, String key, long length) {
        if (length <= 0 || sp(c).getLong(key, 0) <= 0) return 0L;
        long used = usedMs(c, key);
        return Math.max(0L, Math.min(length, length - Math.max(0L, used)));
    }
    /** the time every unlock is stamped with (same clock the timers read) */
    public static long clockNow(Context c) { return now(c); }
    static synchronized long nowFor(SharedPreferences p, long wall) {
        long high = p.getLong("clock_high", 0);
        if (high - wall > 2 * DAY_MS) high = 0;
        if (wall > high + 30_000L || high == 0) { p.edit().putLong("clock_high", wall).apply(); return wall; }
        return Math.max(wall, high);
    }
    public static long nowForTest(SharedPreferences p, long wall) { return nowFor(p, wall); }
    /**
     * Premium edition (the "premium" build flavor, Oct 31): every PRO feature is permanently unlocked - 1080p, captions,
     * AI face tracking (still podcasts only, as a feature rule), 2-5 shorts, high-speed downloads - with no timer and
     * no ads. The normal ("free") build keeps the rewarded-ad unlocks exactly as before. A compile-time constant, so R8
     * drops the other edition's code paths.
     */
    public static final boolean PREMIUM_EDITION = com.viral.shortsmaker.BuildConfig.PREMIUM;

    public static boolean isPremium(Context c) { return PREMIUM_EDITION || premiumLeftMs(c) > 0; }
    public static long premiumLeftMs(Context c) {
        migrateV18(c);
        return monoLeft(c, "premium_unlock_time", DAY_MS);
    }
    public static long highSpeedLeftMs(Context c) {
        return monoLeft(c, "high_speed_unlock_time", ONE_HOUR_MS);
    }
    public static boolean highSpeedUnlocked(Context c) { return isPremium(c) || highSpeedLeftMs(c) > 0; }
    public static boolean highSpeedOn(Context c) { return sp(c).getBoolean("high_speed", PREMIUM_EDITION) && highSpeedUnlocked(c); }

    /**
     * Premium edition, first start (also when installed over the normal app): high-speed download on and the export
     * quality set to 1080p HD, once. After that the user's own choices in Settings stand (e.g. 720p to save data).
     * The normal build never changes anything here.
     */
    public static void premiumEditionDefaults(Context c) {
        if (!PREMIUM_EDITION) return;
        SharedPreferences p = sp(c);
        if (p.getBoolean("premium_edition_defaults", false)) return;
        SharedPreferences.Editor e = p.edit().putBoolean("premium_edition_defaults", true).putBoolean("high_speed", true);
        int q = p.getInt("video_quality", 0);
        if (q == 0 || q == 2) e.putInt("video_quality", 1);   // Auto / 720p -> 1080p HD (480p data saver is kept)
        e.commit();
    }

    /**
     * v18 (2.9) unlock rules (Sakib): 1 rewarded ad =
     *   1080p HD export for 1 HOUR (survives restarts, shows the time left),
     *   captions for ONE video (the next job; that video's shorts can be re-styled in Caption Studio for free),
     *   AI face tracking for ONE podcast (used only when tracking really runs).
     * The 10-ad PRO pass = everything unlimited for 24 hours.
     */
    public static final long HD_UNLOCK_MS = ONE_HOUR_MS;
    public static final int MAX_CREDITS = 5;
    public static final String F_CAPTIONS = "captions", F_HD = "hd1080";

    public static long hdLeftMs(Context c) {
        migrateV18(c);
        SharedPreferences p = sp(c);
        return monoLeft(c, "hd_unlock_time", p.getLong("hd_unlock_len", HD_UNLOCK_MS));
    }
    public static boolean hdUnlocked(Context c) { return isPremium(c) || hdLeftMs(c) > 0; }
    public static void unlockHd(Context c) {
        SharedPreferences.Editor e = sp(c).edit().putLong("hd_unlock_len", HD_UNLOCK_MS);
        stampGrant(c, e, "hd_unlock_time");
        e.apply();
    }
    /** kept for older callers: F_HD = 1 hour, F_CAPTIONS = one more video */
    public static void unlockFeature(Context c, String feature) {
        if (F_HD.equals(feature)) unlockHd(c); else if (F_CAPTIONS.equals(feature)) addCaptionCredit(c);
    }
    public static long featureLeftMs(Context c, String feature) { return F_HD.equals(feature) ? hdLeftMs(c) : 0L; }

    public static int captionCredits(Context c) { migrateV18(c); return Math.max(0, sp(c).getInt("caption_credits", 0)); }
    public static synchronized void addCaptionCredit(Context c) {
        sp(c).edit().putInt("caption_credits", Math.min(MAX_CREDITS, captionCredits(c) + 1)).apply();
    }
    /** true = captions can be used on the next video (PRO or at least one ad credit) */
    public static boolean captionsUnlocked(Context c) { return isPremium(c) || captionCredits(c) > 0; }
    /** called when a video's shorts are really rendered with captions; false = locked (render without captions) */
    public static synchronized boolean useCaptionCredit(Context c) {
        if (isPremium(c)) return true;
        int n = captionCredits(c);
        if (n <= 0) return false;
        sp(c).edit().putInt("caption_credits", n - 1).commit();
        return true;
    }

    /**
     * v18: an ad credit used for a video stays good for that same video for 6 hours: if the phone kills the app mid-job
     * (or the job fails) the retry of the same video does not ask for another ad.
     */
    public static void markPaid(Context c, String feature, String srcKey) {
        if (srcKey == null || srcKey.isEmpty()) return;
        SharedPreferences.Editor e = sp(c).edit().putString("paid_" + feature + "_src", srcKey);
        stampGrant(c, e, "paid_" + feature + "_at");
        e.apply();
    }
    public static boolean paidRecently(Context c, String feature, String srcKey) {
        if (srcKey == null || srcKey.isEmpty()) return false;
        SharedPreferences p = sp(c);
        return srcKey.equals(p.getString("paid_" + feature + "_src", "")) && monoLeft(c, "paid_" + feature + "_at", 6 * ONE_HOUR_MS) > 0;
    }
    public static final String F_FACE = "face";

    /**
     * v18: one-time migration from the 2.8 rules. An HD unlock still running keeps exactly the time it had left
     * (old rule: 3 hours); a captions unlock still running becomes one caption credit. Nothing earned is lost.
     */
    public static synchronized void migrateV18(Context c) {
        SharedPreferences p = sp(c);
        if (p.getBoolean("migrated_v18", false)) return;
        SharedPreferences.Editor e = p.edit();
        long now = System.currentTimeMillis(), old3h = 3 * ONE_HOUR_MS;
        long hd = p.getLong("unlock_" + F_HD, 0);
        long hdLeft = timedLeft(now, hd, old3h);
        if (hdLeft > 0 && p.getLong("hd_unlock_time", 0) == 0) { e.putLong("hd_unlock_len", hdLeft); stampGrant(c, e, "hd_unlock_time"); }
        long cap = p.getLong("unlock_" + F_CAPTIONS, 0);
        if (timedLeft(now, cap, old3h) > 0) e.putInt("caption_credits", Math.min(MAX_CREDITS, p.getInt("caption_credits", 0) + 1));
        e.remove("unlock_" + F_HD).remove("unlock_" + F_CAPTIONS).putBoolean("migrated_v18", true).commit();
    }

    // ---- processing
    // ---- processing
    public static int videoQuality(Context c) { return sp(c).getInt("video_quality", 0); }
    public static FfmpegCommands.Frame frame(Context c) {
        try { return FfmpegCommands.Frame.valueOf(sp(c).getString("frame_mode", "BLUR")); } catch (Exception e) { return FfmpegCommands.Frame.BLUR; }
    }
    public static void setFrame(Context c, FfmpegCommands.Frame f) { sp(c).edit().putString("frame_mode", f.name()).apply(); }
    /** v14 output format: 9:16 (default) / 1:1 / 16:9, remembered */
    public static FfmpegCommands.Aspect aspect(Context c) { return FfmpegCommands.Aspect.of(sp(c).getString("out_aspect", "PORTRAIT")); }
    public static void setAspect(Context c, FfmpegCommands.Aspect a) { sp(c).edit().putString("out_aspect", a.name()).apply(); }

    /**
     * v14: AI face tracking is premium. One rewarded ad = one project with tracking ("ek baar"); the 10-ad 24-hour
     * pass (isPremium) = unlimited. A credit is used when a job with tracking actually starts.
     */
    public static int faceTrackCredits(Context c) { return sp(c).getInt("face_track_credits", 0); }
    public static void addFaceTrackCredit(Context c) { sp(c).edit().putInt("face_track_credits", Math.min(5, faceTrackCredits(c) + 1)).apply(); }
    public static boolean faceTrackUnlocked(Context c) { return isPremium(c) || faceTrackCredits(c) > 0; }
    /** called when a job starts with tracking on; false = not unlocked (the job runs without tracking) */
    public static synchronized boolean useFaceTrack(Context c) {
        if (isPremium(c)) return true;
        int n = faceTrackCredits(c);
        if (n <= 0) return false;
        sp(c).edit().putInt("face_track_credits", n - 1).apply();
        return true;
    }

    /** v13: AI face tracking switch (v14: premium, podcasts only) */
    public static boolean faceTrack(Context c) { return sp(c).getBoolean("face_track", true); }
    public static void setFaceTrack(Context c, boolean on) { sp(c).edit().putBoolean("face_track", on).apply(); }
    /** speech language for Whisper. v6: always auto-detect; the caption language is chosen separately (captionScript) */
    public static String captionLanguage(Context c) { return ""; }

    // ---- captions
    public static boolean captionsOn(Context c) { return sp(c).getBoolean("caption_enabled", true); }
    public static void setCaptionsOn(Context c, boolean on) { sp(c).edit().putBoolean("caption_enabled", on).apply(); }

    public static CaptionStyle captionStyle(Context c) {
        String j = sp(c).getString("caption_style_json", null);
        if (j == null) return CaptionStyles.byId("hormozi");
        try { return CaptionStyle.fromJson(new JSONObject(j)); } catch (Exception e) { return CaptionStyles.byId("hormozi"); }
    }
    public static CaptionLayout captionLayout(Context c) { return captionLayout(c, FfmpegCommands.Aspect.PORTRAIT); }

    /** v14: one caption box per format (9:16 keeps the 2.4 key, so his saved layout stays) */
    public static CaptionLayout captionLayout(Context c, FfmpegCommands.Aspect a) {
        float r = (float) a.ratio();
        String j = sp(c).getString(layoutKey(a), null);
        CaptionLayout l;
        try { l = j == null ? CaptionLayout.defaultFor(r) : CaptionLayout.fromJson(new JSONObject(j)); } catch (Exception e) { l = CaptionLayout.defaultFor(r); }
        l.aspect = r;
        return l;
    }
    static String layoutKey(FfmpegCommands.Aspect a) { return a == FfmpegCommands.Aspect.PORTRAIT ? "caption_layout_json" : "caption_layout_json_" + a.name().toLowerCase(java.util.Locale.US); }
    /** v6 caption language: CaptionScript.HINGLISH (default) / HINDI / ENGLISH */
    public static String captionScript(Context c) {
        String s = sp(c).getString("caption_script", null);
        String v = CaptionScript.valid(s);   // v44: the 26 other caption languages too ("lang:xx")
        return v == null ? CaptionScript.HINGLISH : v;
    }
    public static void setCaptionScript(Context c, String s) { sp(c).edit().putString("caption_script", s).apply(); }
    static FfmpegCommands.Aspect aspectOfLayout(CaptionLayout l) {
        return l.aspect > 1.2f ? FfmpegCommands.Aspect.LANDSCAPE : l.aspect > 0.8f ? FfmpegCommands.Aspect.SQUARE : FfmpegCommands.Aspect.PORTRAIT;
    }

    public static void saveCaption(Context c, CaptionStyle s, CaptionLayout l) {
        sp(c).edit().putString("caption_style_json", s.toJson().toString())
                .putString(layoutKey(aspectOfLayout(l)), l.toJson().toString()).apply();
    }

    // ---- v3: speed + behaviour
    /** hardware (MediaCodec) H.264 encoder. v17 (2.8): always on (the Settings switch was removed); it still falls
     *  back to software by itself on a phone where it fails (hwEncodeState -1) */
    public static boolean hwEncodeOn(Context c) { migrateV17(c); return true; }

    /**
     * code 33 (5.1): Premium-only test switch "Use backup video engine (test)": every short is made by the backup engine
     * (phone's own decoder + GPU + encoder, no FFmpeg). The Free build has no such setting (BuildConfig flag false there, so
     * even a stored value is ignored and the code is removed by R8).
     */
    public static final String K_BACKUP_ENGINE_TEST = "backup_engine_test";
    public static boolean backupEngineTest(Context c) {
        return com.viral.shortsmaker.BuildConfig.BACKUP_ENGINE_SWITCH && c != null && sp(c).getBoolean(K_BACKUP_ENGINE_TEST, false);
    }
    /** 0 = untested, 1 = works on this phone, -1 = failed on this phone */
    public static int hwEncodeState(Context c) { migrateV26(c); return sp(c).getInt("hw_encode_state", 0); }
    /** v21: hardware encoder quality mode (High profile + CBR): 0 not tried yet, 1 works, -1 this phone refuses it */
    public static int hwTuneState(Context c) { return sp(c).getInt("hw_tune_state", 0); }
    public static void setHwTuneState(Context c, int v) { sp(c).edit().putInt("hw_tune_state", v).apply(); }
    public static void setHwEncodeState(Context c, int s) { sp(c).edit().putInt("hw_encode_state", s).apply(); }

    // ---- v26 (code 26): encoder ladder + quality guard, learned per phone
    /** v26: the hardware settings this phone keeps: 0 = full (HW), 1 = HW_STD, 2 = HW_BASIC */
    public static int hwTuneLevel(Context c) { migrateV26(c); return sp(c).getInt("hw_tune_level", 0); }
    /**
     * v26: one failure of the hardware encoder at a level. A clear encoder error counts 2 points, an unclear one 1; at 4
     * points the phone keeps the next, more conservative level (a single dropped stream no longer decides anything).
     * Returns true when the phone's level moved down.
     */
    public static boolean hwLevelFault(Context c, int level, boolean clearEncoderError) {
        android.content.SharedPreferences p = sp(c);
        int pts = p.getInt("hw_lvl_fault_" + level, 0) + (clearEncoderError ? 2 : 1);
        android.content.SharedPreferences.Editor e = p.edit().putInt("hw_lvl_fault_" + level, pts);
        boolean moved = false;
        if (pts >= 4) {
            if (level >= 2) e.putInt("hw_encode_state", -1);   // even the bare settings fail: x264 on this phone
            else if (p.getInt("hw_tune_level", 0) <= level) e.putInt("hw_tune_level", level + 1);
            moved = true;
        }
        e.apply();
        return moved;
    }
    /** v26: a level worked: its fault points are forgotten */
    public static void hwLevelOk(Context c, int level) { if (sp(c).getInt("hw_lvl_fault_" + level, 0) != 0) sp(c).edit().putInt("hw_lvl_fault_" + level, 0).apply(); }
    /** v26: guard results: [0] = hardware encoder really undershot, [1] = easy picture (false alarm) */
    public static int[] guardCounts(Context c) { return new int[]{sp(c).getInt("hw_guard_under", 0), sp(c).getInt("hw_guard_easy", 0)}; }
    public static void guardResult(Context c, boolean under) { String k = under ? "hw_guard_under" : "hw_guard_easy"; sp(c).edit().putInt(k, sp(c).getInt(k, 0) + 1).apply(); }
    /** v26: this phone's hardware encoder undershot twice even with the learned correction: final shorts use x264 */
    public static boolean hwQualityPoor(Context c) { return sp(c).getInt("hw_guard_under", 0) >= 2; }

    private static volatile boolean migrated26;
    /**
     * v26: up to code 25 a single failed render (often only a dropped network stream) could switch a phone to the bare
     * hardware settings (hw_tune_state -1), to x264 (hw_encode_state -1) or to the 1080p source (hi_src_fail 2) for
     * good. Every phone gets those re-tested once with the new, capability-chosen encoder settings.
     */
    public static void migrateV26(Context c) {
        if (migrated26 || c == null) return;
        android.content.SharedPreferences p = sp(c);
        if (!p.getBoolean("v26_hw_retest", false)) {
            boolean any = p.getInt("hw_tune_state", 0) < 0 || p.getInt("hw_encode_state", 0) < 0 || p.getInt("hi_src_fail", 0) > 0;
            android.content.SharedPreferences.Editor e = p.edit();
            if (p.getInt("hw_tune_state", 0) < 0) e.putInt("hw_tune_state", 0);
            if (p.getInt("hw_encode_state", 0) < 0) e.putInt("hw_encode_state", 0);
            e.putInt("hi_src_fail", 0).putBoolean("v26_hw_retest", true).apply();
            if (any) AppLog.i("Video settings that an earlier version had turned down on this phone will be re-tested once");
        }
        migrated26 = true;
    }
    /** v17 (2.8): always on while a job runs in front (the Settings switch was removed) */
    public static boolean keepScreenOn(Context c) { migrateV17(c); return true; }

    private static volatile boolean migrated17;
    /**
     * v17: the removed switches' old values are dropped, so nobody stays stuck with them off. Someone who had turned the
     * hardware encoder off gets it re-tested once on the next render (state 0); a phone where it really failed (-1)
     * keeps the software encoder.
     */
    public static void migrateV17(Context c) {
        if (migrated17 || c == null) return;
        android.content.SharedPreferences p = sp(c);
        if (!p.getBoolean("v17_switches_migrated", false)) {
            android.content.SharedPreferences.Editor e = p.edit();
            if (p.contains("hw_encode") && !p.getBoolean("hw_encode", true) && p.getInt("hw_encode_state", 0) >= 0) e.putInt("hw_encode_state", 0);
            e.remove("hw_encode").remove("keep_screen_on").putBoolean("v17_switches_migrated", true).apply();
        }
        migrated17 = true;
    }
    public static boolean haptics(Context c) { return true; }   // v8: always on (the Settings toggle was removed)

    /** learned timings (seconds) used for the ETA: per minute of source for download/transcribe, flat for AI, per clip-second for render */
    public static double learned(Context c, String key, double dflt) {
        return Double.longBitsToDouble(sp(c).getLong("learn_" + key, Double.doubleToLongBits(dflt)));
    }
    public static void learn(Context c, String key, double value) {
        if (Double.isNaN(value) || Double.isInfinite(value) || value <= 0) return;
        double old = learned(c, key, value);
        sp(c).edit().putLong("learn_" + key, Double.doubleToLongBits(old * 0.5 + value * 0.5)).apply();
    }
}
