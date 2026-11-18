package com.viral.shortsmaker.core;

import android.content.Context;
import android.content.SharedPreferences;

import java.util.Locale;

/**
 * code 31 (4.9): crash-proof rendering. His report (HONOR ELP-NX9, Qualcomm, Android 17, 4.8): the render of a 1080p avc1
 * clip with FFmpeg's MediaCodec decoder (h264_mediacodec) + the phone's hardware encoder (c2.qti.avc.encoder) died with
 * SIGSEGV about a second in, on two runs in a row. A native crash is not a Java exception: up to code 30 it ended the whole
 * app (the job with it) and the next job chose exactly the same hardware path again, so every job on that phone died.
 *
 * Now FFmpeg's video work runs in a separate process (RenderService, ":render"); when that process dies mid-command the
 * job is told (MediaRunner.EngineCrash), this class moves the PHONE one step down the ladder and the same short is
 * rendered again:
 *   level 0 FULL      hardware decoding + hardware encoder (the v4.5 encoder ladder, unchanged: phones that work keep it)
 *   level 1 SW_DECODE FFmpeg's own (software) decoder + hardware encoder (same quality settings)
 *   level 2 SW_ALL    software decoder + x264 (the bundled software encoder, crf 14: quality kept, slower)
 * The level is kept per phone (SharedPreferences, commit) and reset when the app version changes (a new build may have
 * a fixed path). If the app process itself died in native code while a video command ran in-process (no separate process
 * available), the in-flight marker written before the command tells the next start which path to step down from.
 */
public final class RenderSafety {
    private RenderSafety() {}

    public static final int FULL = 0, SW_DECODE = 1, SW_ALL = 2;
    /** what a video command used: its native risk */
    public static final int K_NONE = 0, K_SOFTWARE = 1, K_HW_ENCODE = 2, K_HW_DECODE = 3;
    /** code 33: the backup video engine (the phone's own MediaCodec decoder + GPU + MediaCodec encoder, no FFmpeg) */
    public static final int K_NATIVE = 4;
    /** first argument of a backup-engine "command" (runs in the separate video process like an FFmpeg command) */
    public static final String NATIVE_ARG = "@klipora-native";

    /**
     * code 33 (5.1): the second ladder, for crashes of FFmpeg's SOFTWARE video path. His vivo Y16 report (MediaTek Helio
     * P35, Android 12, 5.0): every render died with signal 11 in libswscale / libavfilter / libavutil / libavcodec while
     * running x264 + software decoding, 14 times. 5.0 only stepped down on a hardware decoder / encoder crash, so a
     * software crash re-ran the SAME path (4x per short, then a source switch + an 8 min download that can't help an engine
     * crash, then "safe mode" = the same libraries again). Rungs, each tried once, kept per phone (reset on a new build):
     *   R_NORMAL   the normal choice (the level above decides decoder / encoder)
     *   R_NO_X264  x264 crashed: the phone's hardware encoder for real (its "poor quality" mark and the x264 quality
     *              re-check are ignored - a sharp-enough short beats no short)
     *   R_NO_ASM   FFmpeg with every CPU-specific (NEON / asm) routine off, one thread per stage, x264 without asm
     *   R_NATIVE   the backup engine: no FFmpeg at all
     */
    public static final int R_NORMAL = 0, R_NO_X264 = 1, R_NO_ASM = 2, R_NATIVE = 3;

    static final String K_LEVEL = "rs_level", K_BUILD = "rs_build", K_CRASHES = "rs_crashes", K_LAST = "rs_last",
            K_INFLIGHT = "rs_inflight", K_INFLIGHT_AT = "rs_inflight_at", K_FACE_INFLIGHT = "rs_face_inflight", K_RUNG = "rs_rung";

    private static volatile int cached = -1, cachedRung = -1;

    // ------------------------------------------------------------------ pure (unit tested)

    /** the risk of one FFmpeg command line: a MediaCodec decoder (-c:v x_mediacodec before -i), a hardware encoder, else software video / none */
    public static int kindOf(String[] args) {
        if (args == null) return K_NONE;
        if (args.length > 0 && NATIVE_ARG.equals(args[0])) return K_NATIVE;
        boolean seenInput = false, video = false, hwEnc = false, hwDec = false;
        for (int i = 0; i < args.length; i++) {
            String a = args[i];
            if ("-i".equals(a)) { seenInput = true; continue; }
            if (("-c:v".equals(a) || "-vcodec".equals(a) || "-codec:v".equals(a)) && i + 1 < args.length) {
                String v = args[i + 1];
                if (v.endsWith("_mediacodec")) { if (seenInput) hwEnc = true; else hwDec = true; }
                if (seenInput && !"copy".equals(v)) video = true;
            }
            if ("-vf".equals(a) || "-filter_complex".equals(a) || "-lavfi".equals(a)) video = true;
        }
        if (hwDec) return K_HW_DECODE;
        if (hwEnc) return K_HW_ENCODE;
        return video ? K_SOFTWARE : K_NONE;
    }

    /**
     * Android's exit reason of the render process (JobGuard.reasonName, may carry ", signal N"): a crash steps the phone
     * down; Android ending the process (low memory, the user / system stopping the app, an update) does not. Unknown
     * (Android 10 and older, or not recorded) counts as a crash: a phone that really crashes must not loop.
     */
    public static boolean isCrashReason(String reason) {
        if (reason == null || reason.isEmpty() || reason.startsWith("UNKNOWN")) return true;
        return reason.startsWith("CRASH") || reason.startsWith("SIGNALED, signal 11") || reason.startsWith("INITIALIZATION_FAILURE");
    }

    /** true = run this command in the separate video process (video decoding / encoding / filtering) */
    public static boolean isolate(String[] args) { return kindOf(args) != K_NONE; }

    /** the level after a crash of a command of this kind at this level (never goes back up) */
    public static int levelAfterCrash(int level, int kind) {
        int next = level;
        if (kind == K_HW_DECODE) next = Math.max(level, SW_DECODE);
        else if (kind == K_HW_ENCODE) next = Math.max(level, SW_ALL);
        return Math.min(SW_ALL, next);
    }

    /** code 33: did this command encode with x264 (the bundled software encoder)? */
    public static boolean usesX264(String[] args) {
        if (args == null) return false;
        for (String a : args) if ("libx264".equals(a)) return true;
        return false;
    }

    /**
     * code 33: {level, rung} after a crash. kind = what the command used, x264 = it encoded with x264, hwEncOk = this phone
     * has a usable hardware encoder for the size. Every crash moves at least one step while a step is left (never the
     * same path twice); a crash of the backup engine (or with nothing left) changes nothing - the caller then stops.
     */
    public static int[] stepAfterCrash(int level, int rung, int kind, boolean x264, boolean hwEncOk) {
        int l = Math.max(FULL, Math.min(SW_ALL, level)), r = Math.max(R_NORMAL, Math.min(R_NATIVE, rung));
        if (kind == K_NATIVE) return new int[]{l, r};
        if (kind == K_HW_DECODE && l < SW_DECODE) return new int[]{SW_DECODE, r};
        if (kind == K_HW_ENCODE && l < SW_ALL) {
            // the hardware encoder crashed: x264 from now on; if x264 had crashed too, x264 without asm (or the backup engine)
            return new int[]{SW_ALL, r >= R_NO_X264 ? Math.min(R_NATIVE, Math.max(R_NO_ASM, r + 1)) : r};
        }
        // a software-path crash (or a hardware one with that step already taken)
        if (r < R_NO_X264 && x264 && hwEncOk && l < SW_ALL) return new int[]{l, R_NO_X264};
        if (r < R_NO_ASM) return new int[]{l, R_NO_ASM};
        if (r < R_NATIVE) return new int[]{l, R_NATIVE};
        return new int[]{l, r};
    }

    /**
     * code 34 (5.2): the lighter first step after a native crash in FFmpeg's own code: the SAME command with FFmpeg's NEON
     * routines off (-cpuflags -neon), nothing else changed. His vivo Y16 (Helio P35 MT6765, 8x Cortex-A53, Android 12):
     * signal 11 in libswscale at +0x239a4 = the NEON packed-RGB -> chroma routine (ld3 over 24-bit pixels), reached because
     * FFmpeg 8.1.2's filter graph first sets the scaler's output up as "gbr" and the first real frame (bt709) then makes
     * swscale convert YUV -> RGB -> YUV; that NEON routine reads past the end of the RGB buffer. Reproduced with the
     * shipped arm64 libraries on Android 12's bionic under qemu (2, 4 and 8 CPUs crash every time, on A53 / A72 / A76
     * models; 3, 5, 6 and 16 run clean); with -cpuflags -neon the identical command runs clean. x264 keeps its own NEON.
     * True = this crash is answered by that step first (level and rung stay, so the same path runs again without NEON).
     */
    public static boolean neonFirst(int kind, int rung, boolean noNeonAlready, String where) {
        if (noNeonAlready || rung >= R_NO_ASM) return false;
        if (kind == K_SOFTWARE) return true;
        if (kind == K_HW_DECODE || kind == K_HW_ENCODE) return ffmpegOwnCrash(where);
        return false;
    }

    /**
     * code 34: a crash report that names FFmpeg's scaler (libswscale) and no part of the phone's codec stack: the scaler runs
     * on the hardware paths too (the frames go through the same scale / pad / caption chain), so a hardware-path crash there
     * gets the no-NEON step first as well. A report naming the codec stack (his HONOR: the Qualcomm encoder) keeps the
     * 4.9 step (hardware decoder -> software decoder -> x264).
     */
    public static boolean ffmpegOwnCrash(String where) {
        if (where == null) return false;
        String w = where.toLowerCase(Locale.US);
        if (!w.contains("libswscale")) return false;
        for (String codec : new String[]{"c2", "codec2", "mediacodec", "stagefright", "sfplugin", "omx", "libmtk", "qti", "exynos", "vendor."})
            if (w.contains(codec)) return false;
        return true;
    }

    /**
     * code 34: {level, rung, noNeon (0/1)} after a crash: the no-NEON step first when {@link #neonFirst} says so, else the
     * 5.1 ladder ({@link #stepAfterCrash(int, int, int, boolean, boolean)}) with the NEON mark kept as it was.
     */
    public static int[] stepAfterCrash(int level, int rung, int kind, boolean x264, boolean hwEncOk, boolean noNeon, String where) {
        if (neonFirst(kind, rung, noNeon, where)) {
            int l = Math.max(FULL, Math.min(SW_ALL, level)), r = Math.max(R_NORMAL, Math.min(R_NATIVE, rung));
            return new int[]{l, r, 1};
        }
        int[] st = stepAfterCrash(level, rung, kind, x264, hwEncOk);
        return new int[]{st[0], st[1], noNeon ? 1 : 0};
    }

    public static String rungLabel(int rung) {
        switch (rung) {
            case R_NO_X264: return "hardware encoder (software encoder crashed)";
            case R_NO_ASM: return "CPU-safe FFmpeg (no NEON/asm, one thread)";
            case R_NATIVE: return "backup video engine (phone's own decoder + GPU + encoder)";
            default: return "";
        }
    }

    /** the stored level, or FULL when it was stored by another app version */
    public static int effective(int storedLevel, int storedBuild, int curBuild) {
        if (storedBuild != curBuild) return FULL;
        return Math.max(FULL, Math.min(SW_ALL, storedLevel));
    }

    public static String label(int level) {
        switch (level) {
            case SW_DECODE: return "software decoding + hardware encoder";
            case SW_ALL: return "software decoding + software encoder";
            default: return "hardware decoding + hardware encoder";
        }
    }

    public static String kindLabel(int kind) {
        switch (kind) {
            case K_HW_DECODE: return "hardware decoder";
            case K_HW_ENCODE: return "hardware encoder";
            case K_SOFTWARE: return "software video";
            case K_NATIVE: return "backup video engine";
            default: return "audio";
        }
    }

    // ------------------------------------------------------------------ the phone's state

    static int curBuild() { try { return com.viral.shortsmaker.BuildConfig.VERSION_CODE; } catch (Throwable t) { return 0; } }

    public static int level(Context c) {
        int l = cached;
        if (l >= 0) return l;
        if (c == null) return FULL;
        SharedPreferences sp = Prefs.sp(c);
        l = effective(sp.getInt(K_LEVEL, FULL), sp.getInt(K_BUILD, -1), curBuild());
        cached = l;
        return l;
    }

    /** level without a Context (static helpers like HwDecode): the last level read, FULL before any */
    public static int levelCached() { int l = cached; return l < 0 ? FULL : l; }

    /** code 33: the software-crash rung of this phone (R_NORMAL on a new build) */
    public static int rung(Context c) {
        int r = cachedRung;
        if (r >= 0) return r;
        if (c == null) return R_NORMAL;
        SharedPreferences sp = Prefs.sp(c);
        r = sp.getInt(K_BUILD, -1) == curBuild() ? Math.max(R_NORMAL, Math.min(R_NATIVE, sp.getInt(K_RUNG, R_NORMAL))) : R_NORMAL;
        cachedRung = r;
        return r;
    }

    public static int rungCached() { int r = cachedRung; return r < 0 ? R_NORMAL : r; }

    /** code 33: one number for "did anything change" checks: level * 10 + rung */
    public static int state(Context c) { return level(c) * 10 + rung(c) + (noNeon(c) ? 100 : 0); }

    /** code 33: x264 crashed on this phone: final shorts use the hardware encoder even if it was marked poor */
    public static boolean x264Bad(Context c) { return rung(c) >= R_NO_X264; }
    public static boolean noAsm(Context c) { return rung(c) >= R_NO_ASM; }
    public static boolean useNative(Context c) { return rung(c) >= R_NATIVE; }

    // ------------------------------------------------------------------ code 34 (5.2): FFmpeg without NEON

    static final String K_NO_NEON = "rs_no_neon", K_NO_NEON_LIBS = "rs_no_neon_libs";
    /**
     * the bundled FFmpeg libraries (ffmpeg-kit-full-gpl 2.2.3 = FFmpeg n8.1.2). The no-NEON mark is about these libraries on
     * this phone, so unlike the level / rung it is NOT reset by an app update - only by other FFmpeg libraries (change this
     * string whenever the ffmpeg-kit dependency changes).
     */
    public static final String FFMPEG_LIBS = "ffmpeg-kit-full-gpl-2.2.3/n8.1.2";
    /**
     * code 35 (5.3): what a no-NEON mark is about: these libraries running the 5.3 commands. 5.2's marks (stored with
     * FFMPEG_LIBS alone) came from the scaler's YUV->RGB->YUV detour, which the 5.3 colour tag removes (FfmpegCommands
     * .COLOUR_IN; arm64 kit: 0 crashes in 45 runs with NEON on), so they no longer count. A new crash marks the phone again.
     */
    public static final String NEON_MARK_ID = FFMPEG_LIBS + "+bt709";
    /**
     * code 35 (5.3): the Helio P35 / P22 chip rule of 5.2 (FFmpeg without NEON from the first render) is off: the crash it
     * avoided came from the RGB detour, which no 5.3 command takes any more (kit: safe / Fit / Blur / Fill / face crop
     * clean with NEON at 2, 4 and 8 CPUs). The safety net stays: the first native FFmpeg crash on any phone, these chips
     * included, re-runs the same command without NEON and the phone remembers it, then the 5.1 ladder.
     */
    public static final boolean NEON_OFF_FOR_RISKY_SOC = false;
    private static volatile int cachedNoNeon = -1;
    private static volatile Boolean riskySoc;

    /** pure: this command already runs FFmpeg without NEON (-cpuflags -neon, or the CPU-safe -cpuflags 0) */
    public static boolean commandNoNeon(String[] args) {
        if (args == null) return false;
        for (int i = 0; i + 1 < args.length; i++) if ("-cpuflags".equals(args[i])) return true;
        return false;
    }

    /** pure: the stored mark counts only for the same FFmpeg libraries */
    public static boolean noNeonStored(boolean mark, String libs) { return mark && NEON_MARK_ID.equals(libs); }

    /**
     * code 34: a MediaTek Helio P35 / P22 family chip (MT6765, MT6762: 8 Cortex-A53 cores; G35 / G37 / G25 are the same
     * silicon). His vivo Y16 crashed in FFmpeg's NEON scaler on every render; on these chips FFmpeg runs without NEON from
     * the FIRST render, so the crash never happens even once. Every other phone keeps FFmpeg exactly as before (same
     * command, same output). Inputs: Build.SOC_MODEL (Android 12+), Build.HARDWARE, Build.BOARD, the Hardware line of
     * /proc/cpuinfo - any of them naming the chip.
     */
    public static boolean riskySocName(String... names) {
        if (names == null) return false;
        for (String n : names) {
            if (n == null) continue;
            String s = n.toLowerCase(Locale.US).replace(" ", "");
            if (s.contains("mt6765") || s.contains("mt6762") || s.contains("heliop35") || s.contains("heliop22")
                    || s.contains("heliog35") || s.contains("heliog37") || s.contains("heliog25")) return true;
        }
        return false;
    }

    /** his phone by name, in case its build hides the chip: vivo Y16 = V2204 (Helio P35) */
    public static boolean riskyModel(String maker, String model) {
        return maker != null && model != null && maker.toLowerCase(Locale.US).contains("vivo") && "V2204".equalsIgnoreCase(model.trim());
    }

    /** the bug report's chip line: SoC model (Android 12+), hardware name, CPU cores, and whether FFmpeg runs without NEON */
    public static String chipLine() {
        String soc = "";
        try { if (android.os.Build.VERSION.SDK_INT >= 31) soc = android.os.Build.SOC_MANUFACTURER + " " + android.os.Build.SOC_MODEL; } catch (Throwable ignored) {}
        return (soc.trim().isEmpty() ? "" : soc.trim() + ", ") + "hardware " + android.os.Build.HARDWARE + ", " + Runtime.getRuntime().availableProcessors() + " cores"
                + (riskySoc() ? (NEON_OFF_FOR_RISKY_SOC ? ", FFmpeg without NEON (chip rule)" : ", Helio P35 family") : "");
    }

    /** the Hardware / model name line of /proc/cpuinfo (MediaTek kernels: "Hardware : MT6765V/WB"), "" when unreadable */
    static String cpuinfoHardware() {
        java.io.BufferedReader r = null;
        try {
            r = new java.io.BufferedReader(new java.io.InputStreamReader(new java.io.FileInputStream("/proc/cpuinfo"), "UTF-8"));
            String line;
            StringBuilder sb = new StringBuilder();
            int n = 0;
            while ((line = r.readLine()) != null && n++ < 400) {
                String l = line.toLowerCase(Locale.US);
                if (l.startsWith("hardware") || l.startsWith("model name") || l.startsWith("soc")) sb.append(line).append(' ');
            }
            return sb.toString();
        } catch (Throwable t) {
            return "";
        } finally {
            try { if (r != null) r.close(); } catch (Throwable ignored) {}
        }
    }

    /** this phone's chip is in the crash family (read once per process: a few hundred bytes of /proc, no other cost) */
    public static boolean riskySoc() {
        Boolean b = riskySoc;
        if (b != null) return b;
        String soc = "";
        try { if (android.os.Build.VERSION.SDK_INT >= 31) soc = android.os.Build.SOC_MODEL; } catch (Throwable ignored) {}
        boolean r = riskySocName(soc, android.os.Build.HARDWARE, android.os.Build.BOARD) || riskyModel(android.os.Build.MANUFACTURER, android.os.Build.MODEL);
        if (!r) r = riskySocName(cpuinfoHardware());
        riskySoc = r;
        return r;
    }

    /** tests: pretend a chip (null = read it again) */
    public static void setRiskySocForTest(Boolean b) { riskySoc = b; }

    /** the stored mark: a native crash of FFmpeg's own code happened on this phone with these libraries */
    public static boolean noNeonMarked(Context c) {
        int v = cachedNoNeon;
        if (v >= 0) return v == 1;
        if (c == null) return false;
        SharedPreferences sp = Prefs.sp(c);
        boolean m = noNeonStored(sp.getBoolean(K_NO_NEON, false), sp.getString(K_NO_NEON_LIBS, ""));
        cachedNoNeon = m ? 1 : 0;
        return m;
    }

    /** code 34: every FFmpeg video command on this phone runs with -cpuflags -neon (crash mark, or a chip of the crash family) */
    public static boolean noNeon(Context c) { return noNeonMarked(c) || (NEON_OFF_FOR_RISKY_SOC && riskySoc()); }

    /** why NEON is off here, for the log ("" = it is on) */
    public static String noNeonWhy(Context c) {
        if (noNeonMarked(c)) return "FFmpeg's NEON code crashed on this phone before";
        if (NEON_OFF_FOR_RISKY_SOC && riskySoc()) return "this phone's chip (MediaTek Helio P35 / P22 family) crashes in FFmpeg's NEON code";
        return "";
    }


    /** code 33: is a hardware encoder usable for this size on this phone (Pipeline sets it; tests can too) */
    public static volatile java.util.function.IntPredicate hwEncoderFor = w -> false;

    public static boolean hwDecodeAllowed(Context c) { return level(c) < SW_DECODE; }
    public static boolean hwEncodeAllowed(Context c) { return level(c) < SW_ALL; }

    /** a crash of a command of this kind: the phone steps down; returns the new level */
    public static synchronized int noteCrash(Context c, int kind, String what) { return noteCrash(c, kind, what, null); }

    /**
     * code 33: the same with the command line: an x264 / software crash now steps the second ladder (rung) too, so the next
     * render is never the path that just crashed. Returns the new level.
     */
    public static synchronized int noteCrash(Context c, int kind, String what, String[] args) {
        int before = level(c), rb = rung(c);
        int outW = 720;
        if (args != null) for (int i = 0; i + 1 < args.length; i++) if ("-s".equals(args[i])) { try { outW = Integer.parseInt(args[i + 1].split("x")[0]); } catch (Exception ignored) {} }
        boolean hwOk = false;
        try { hwOk = hwEncoderFor.test(outW); } catch (Throwable ignored) {}
        boolean nb = noNeon(c), x264 = usesX264(args) || (args == null && kind == K_SOFTWARE && before >= SW_ALL);
        // a stalled codec ("no progress") is not a NEON crash: the 5.1 ladder step, the NEON mark untouched
        boolean stall = what != null && what.startsWith("no progress");
        boolean neonStep = !stall && neonFirst(kind, rb, nb || commandNoNeon(args), what);
        int[] st = neonStep ? new int[]{before, rb, 1} : stepAfterCrash(before, rb, kind, x264, hwOk);
        int after = st[0], ra = st[1];
        boolean na = neonStep, wasMarked = noNeonMarked(c);
        cached = after;
        cachedRung = ra;
        if (na) cachedNoNeon = 1;
        if (c != null) {
            SharedPreferences sp = Prefs.sp(c);
            SharedPreferences.Editor ed = sp.edit();
            if (na && !wasMarked) ed.putBoolean(K_NO_NEON, true).putString(K_NO_NEON_LIBS, NEON_MARK_ID);
            ed.putInt(K_LEVEL, after).putInt(K_RUNG, ra).putInt(K_BUILD, curBuild()).putInt(K_CRASHES, crashes(c) + 1)
                    .putString(K_LAST, String.format(Locale.US, "%tF %<tT %s crashed (%s)", System.currentTimeMillis(),
                            kind == K_SOFTWARE && usesX264(args) ? "software video (x264)" : kindLabel(kind), what == null ? "" : what))
                    .commit();
        }
        return after;
    }

    public static int crashes(Context c) {
        SharedPreferences sp = Prefs.sp(c);
        return sp.getInt(K_BUILD, -1) == curBuild() ? sp.getInt(K_CRASHES, 0) : 0;
    }

    /** one line for the bug report */
    public static String reportLine(Context c) {
        if (c == null) return "";
        SharedPreferences sp = Prefs.sp(c);
        int l = level(c);
        String last = sp.getInt(K_BUILD, -1) == curBuild() ? sp.getString(K_LAST, "") : "";
        int r = rung(c);
        String nn = noNeonWhy(c);
        return "Video path: " + label(l) + (nn.isEmpty() ? "" : ", FFmpeg without NEON (" + nn + ")") + (r > R_NORMAL ? ", " + rungLabel(r) : "") + (l > FULL || r > R_NORMAL ? " (safe mode after " + crashes(c) + " crash" + (crashes(c) == 1 ? "" : "es") + ")" : "")
                + (last.isEmpty() ? "" : "\nLast video engine crash: " + last) + "\n";
    }

    /** before a video command runs in the app's own process: what it is (commit, survives a native crash) */
    public static void inflight(Context c, int kind) {
        if (c == null) return;
        Prefs.sp(c).edit().putInt(K_INFLIGHT, kind).putLong(K_INFLIGHT_AT, System.currentTimeMillis()).commit();
    }

    public static void inflightDone(Context c) {
        if (c == null) return;
        Prefs.sp(c).edit().remove(K_INFLIGHT).remove(K_INFLIGHT_AT).apply();
    }

    /** the face engine is running on the GPU in the app's own process (commit) */
    public static void faceInflight(Context c, boolean on) {
        if (c == null) return;
        // code 32 (5.0): counted - two shorts analyse faces at once; 4.9's single flag was cleared when the first one ended,
        // so a GPU crash in the second one was not remembered (and the next job crashed the same way)
        synchronized (FACE_LOCK) {
            faceRuns = Math.max(0, faceRuns + (on ? 1 : -1));
            if (on) Prefs.sp(c).edit().putBoolean(K_FACE_INFLIGHT, true).commit();
            else if (!on && faceRuns == 0) Prefs.sp(c).edit().remove(K_FACE_INFLIGHT).apply();
        }
    }
    private static final Object FACE_LOCK = new Object();
    private static int faceRuns;
    static int faceRunsForTest() { synchronized (FACE_LOCK) { return faceRuns; } }

    /**
     * At the next start, after Android said the app's process ended in a native crash (or Klipora's own crash) while a
     * job ran: the video command that was in flight in that process steps the phone down, and a GPU face run moves the
     * face engine to the CPU. Returns what was changed ("" = nothing was in flight).
     */
    public static String afterAppCrash(Context c, boolean nativeCrash) {
        if (c == null) return "";
        SharedPreferences sp = Prefs.sp(c);
        StringBuilder done = new StringBuilder();
        int kind = sp.getInt(K_INFLIGHT, K_NONE);
        if (kind != K_NONE && nativeCrash) {
            int l = noteCrash(c, kind, "the app closed during it");
            done.append("next renders use ").append(label(l));
        }
        if (sp.getBoolean(K_FACE_INFLIGHT, false) && nativeCrash) {
            c.getSharedPreferences("face_gpu", Context.MODE_PRIVATE).edit().putBoolean("bad", true).commit();
            if (done.length() > 0) done.append("; ");
            done.append("face tracking runs on the CPU");
        }
        sp.edit().remove(K_INFLIGHT).remove(K_INFLIGHT_AT).remove(K_FACE_INFLIGHT).commit();
        return done.toString();
    }

    /** tests */
    public static void resetForTest() { cached = -1; cachedRung = -1; cachedNoNeon = -1; riskySoc = null; }

    /**
     * Android 12+ keeps the native crash report (tombstone) of a crashed process: the libraries and functions in it say
     * where the crash was. A rough read of its text (the protobuf carries them as plain strings): up to 8 names.
     */
    public static String tombstoneHint(java.io.InputStream in) {
        if (in == null) return "";
        try {
            byte[] buf = new byte[512 * 1024];
            int n = 0, r;
            while (n < buf.length && (r = in.read(buf, n, buf.length - n)) > 0) n += r;
            return tombstoneHint(buf, n);
        } catch (Throwable t) {
            return "";
        } finally {
            try { in.close(); } catch (Throwable ignored) {}
        }
    }

    public static String tombstoneHint(byte[] b, int n) {
        java.util.LinkedHashSet<String> out = new java.util.LinkedHashSet<>();
        StringBuilder run = new StringBuilder();
        for (int i = 0; i <= n && out.size() < 8; i++) {
            int ch = i < n ? b[i] & 0xff : 0;
            if (ch >= 0x20 && ch < 0x7f) { run.append((char) ch); continue; }
            if (run.length() >= 4) {
                String s = run.toString();
                String lib = null;
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("([A-Za-z0-9_+.-]+\\.so)").matcher(s);
                if (m.find()) lib = m.group(1);
                if (lib != null && !lib.equals("libc.so") && !lib.startsWith("libart") && !lib.equals("libbase.so")) out.add(lib);
                else if (s.matches("(_Z|ff_|av_|avcodec_|mediacodec|AMediaCodec|android::|C2|Codec2)[A-Za-z0-9_:<>~.]{2,80}")) out.add(s);
            }
            run.setLength(0);
        }
        return String.join(", ", out);
    }
}
