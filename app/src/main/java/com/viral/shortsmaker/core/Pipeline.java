package com.viral.shortsmaker.core;

import android.app.ActivityManager;
import android.content.Context;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.provider.OpenableColumns;

import com.viral.shortsmaker.PromptManager;
import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionFonts;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.captions.Word;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * The whole processing job. Runs on ProcessingService's worker thread, never on the UI thread.
 *
 * v3 speed path:
 *  - one yt-dlp run downloads the audio AND returns the direct stream URLs (no second extraction),
 *  - speech is cut into overlapping pieces by stream copy (no re-encode) and transcribed in parallel,
 *  - shorts render two at a time on phones with enough cores/RAM, on the hardware H.264 encoder when it works
 *    (verified once per phone, automatic fallback to x264), captions + crop in a single pass.
 */
public class Pipeline {
    public static final class Request {
        public JobManager.Kind kind = JobManager.Kind.ANALYZE;
        // analyze
        public String ytUrl;
        public Uri localUri;
        public String title;
        public double duration;
        public String thumbUrl;
        public int count = 1;
        public PromptManager.Category category = PromptManager.Category.OTHER;
        public FfmpegCommands.Frame frame = FfmpegCommands.Frame.BLUR;
        /** v13: AI face tracking on/off */
        public boolean faceTrack = false;
        /** v14 output format: 9:16 (default) / 1:1 / 16:9 */
        public FfmpegCommands.Aspect aspect = FfmpegCommands.Aspect.PORTRAIT;
        // captions (null style = no captions)
        public CaptionStyle style;
        public CaptionLayout layout;
        /** v6 caption language: CaptionScript.HINGLISH / HINDI / ENGLISH */
        public String script = CaptionScript.HINGLISH;
        /** code 28: audio language of a multi-language video (yt-dlp code, "" = the original track) + its name */
        public String audioLang = "", audioLangName = "";
        // caption job: which clips
        public List<Integer> clipIndices = new ArrayList<>();
        /** code 30: continue the stopped job of this project (its picked moments and finished shorts are kept) */
        public String resumeId = "";

        /** v18: saved so "Retry" still works after the phone killed the app mid-job (2.8 lost the request with the process) */
        public String toJson() {
            try {
                org.json.JSONObject o = new org.json.JSONObject();
                o.put("kind", kind.name()).put("ytUrl", ytUrl == null ? "" : ytUrl).put("localUri", localUri == null ? "" : localUri.toString())
                        .put("title", title == null ? "" : title).put("duration", duration).put("thumbUrl", thumbUrl == null ? "" : thumbUrl)
                        .put("count", count).put("category", category.name()).put("frame", frame.name()).put("faceTrack", faceTrack)
                        .put("aspect", aspect.name()).put("script", script == null ? "" : script)
                        .put("audioLang", audioLang == null ? "" : audioLang).put("audioLangName", audioLangName == null ? "" : audioLangName)
                        .put("resumeId", resumeId == null ? "" : resumeId);
                if (style != null) o.put("style", style.toJson());
                if (layout != null) o.put("layout", layout.toJson());
                return o.toString();
            } catch (Exception e) { return ""; }
        }

        public static Request fromJson(String s) {
            try {
                org.json.JSONObject o = new org.json.JSONObject(s);
                Request r = new Request();
                r.kind = JobManager.Kind.valueOf(o.optString("kind", "ANALYZE"));
                if (r.kind != JobManager.Kind.ANALYZE) return null;
                r.ytUrl = o.optString("ytUrl", ""); if (r.ytUrl.isEmpty()) r.ytUrl = null;
                String lu = o.optString("localUri", ""); r.localUri = lu.isEmpty() ? null : Uri.parse(lu);
                if (r.ytUrl == null && r.localUri == null) return null;
                r.title = o.optString("title", ""); r.duration = o.optDouble("duration", 0); r.thumbUrl = o.optString("thumbUrl", "");
                r.count = o.optInt("count", 1);
                r.category = PromptManager.Category.valueOf(o.optString("category", "OTHER"));
                r.frame = FfmpegCommands.Frame.valueOf(o.optString("frame", "BLUR"));
                r.faceTrack = o.optBoolean("faceTrack", false);
                r.aspect = FfmpegCommands.Aspect.of(o.optString("aspect", "PORTRAIT"));
                r.script = o.optString("script", CaptionScript.HINGLISH);
                r.audioLang = o.optString("audioLang", ""); r.audioLangName = o.optString("audioLangName", "");
                r.resumeId = o.optString("resumeId", "");
                if (o.has("style")) r.style = CaptionStyle.fromJson(o.getJSONObject("style"));
                if (o.has("layout")) r.layout = CaptionLayout.fromJson(o.getJSONObject("layout"));
                return r;
            } catch (Exception e) { return null; }
        }
    }

    /** Groq documents 25 MB per file; stay well below it (a 15.7 MB single upload of a long podcast was refused in v2). */
    static final long MAX_GROQ_BYTES = 18L * 1024L * 1024L;
    /** target length of one transcription piece; pieces upload + transcribe in parallel */
    static final double PIECE_SEC = 300;
    static final double OVERLAP_SEC = 6.0;   // v8: 6 s so a piece's skipped opening is still covered by its neighbour
    static final int MAX_PIECES = 16;
    /** v17: every part has its own upload slot (2.7: 8 slots for 24 parts) */
    static final int MAX_PARALLEL_UPLOADS = 16;
    /** v17: audio sent per Groq request stays at most ~8 min (fewer, longer parts: Groq's free tier allows ~20 requests a minute) */
    static final double MAX_SENT_PIECE_SEC = 470;
    /**
     * Groq's free plan allows ~7200 audio-seconds per hour per key. Speech longer than this budget is sent slightly
     * sped up (atempo, pitch kept) so 2-3 hour podcasts fit; timestamps are scaled back exactly.
     */
    static final double AUDIO_BUDGET_SEC = 6600;

    /** speed-up applied to the speech sent for transcription (1 = none) */
    public static double speechTempo(double dur) {
        if (dur <= AUDIO_BUDGET_SEC) return 1.0;
        return Math.min(2.0, Math.ceil(dur / AUDIO_BUDGET_SEC * 100) / 100.0);
    }

    private final Context ctx;
    public final CancelToken ct = new CancelToken();
    private final JobManager jm = JobManager.get();
    private String[] streams;          // resolved direct URLs {video, audio}
    /** v14: YouTube channel / categories / tags for podcast detection */
    private String metaHint = "";
    private long streamsAt;

    public Pipeline(Context c) {
        ctx = c.getApplicationContext();
        // v26: renders ask the phone's own encoder list for the encoder, rate control, profile and input layout
        FfmpegCommands.planSource = HwEncode::plan;
        Prefs.migrateV26(ctx);
        // code 31: this phone's video path (stepped down after a crash of the phone's hardware decoder / encoder)
        int rl = RenderSafety.level(ctx);
        if (rl > RenderSafety.FULL) AppLog.i("Video path on this phone: " + RenderSafety.label(rl) + " (the faster path crashed here before)");
        String nn = RenderSafety.noNeonWhy(ctx);   // code 34
        if (!nn.isEmpty()) AppLog.i("Video engine: FFmpeg runs without NEON on this phone (" + nn + "); same settings and output size");
    }

    public void cancel() {
        ct.cancelled = true;
        YtEngine.cancel(ct);
        MediaRunner.cancel(ct);
        ct.cancelCalls();   // code 32: AI uploads end now
    }

    private void step(int s, double f, String status, String detail) { jm.step(s, f, status, detail); }

    /** 1080p is premium: without the unlock the export falls back to 720p (the UI asks before this ever happens) */
    /** v19: the sharper-source lookup started beside the transcription (null = not needed) */
    private volatile Future<?> hdF;
    private int srcMaxH = 1080;

    /** v19: true when the output crops the (landscape) source: 9:16 or 1:1 */
    public static boolean crops(Project pr) {
        return FfmpegCommands.Aspect.of(pr.aspect) != FfmpegCommands.Aspect.LANDSCAPE;
    }

    /**
     * v19 (3.0): the tallest source to fetch. A crop uses only ~1/3 of the frame's width, so a 9:16 / 1:1 short always
     * fetches up to 1080p (even when the export is 720p: a 608x1080 crop scaled to 720x1280 is far sharper than a 406x720
     * crop enlarged 1.78x). Only the 480p data-saver setting keeps its cap. 1080p is the top: above it YouTube only has
     * VP9/AV1, which this app decodes in software (too slow on mid-range phones for a 60-90 s short).
     */
    public static int sourceMaxHeight(Project pr, int quality) {
        int h = YtEngine.maxHeight(quality, pr.duration);
        return crops(pr) && quality != 3 ? Math.max(h, 1080) : h;
    }

    /**
     * v24 (Sakib: "face tracking mein video phat rahi hai"): the 9:16 window of a 16:9 video is only 9/16 x 9/16 = 32% of
     * its width, so from 1080p it is 608x1080 pixels, enlarged 1.78x for a 1080x1920 short. A cropped portrait short
     * (face tracking, split screen, Fill) now fetches a taller source when YouTube has one and this phone decodes it in
     * hardware: 1440p (crop 810x1440, 1.33x) and, for a 1080p export on a strong phone in high-speed mode, 4K (crop
     * 1215x2160 = no enlarging). 0 = no taller source for this job (the normal up-to-1080p source is used).
     */
    public static int hiSourceCap(Project pr, int quality, boolean strongPhone, boolean highSpeed) {
        if (quality == 3 || pr == null || pr.isLocal()) return 0;
        if (FfmpegCommands.Aspect.of(pr.aspect) != FfmpegCommands.Aspect.PORTRAIT) return 0;   // 1:1 / 16:9: 1080p already covers the output
        boolean fill = FfmpegCommands.Frame.FILL.name().equals(pr.frame);
        if (!fill && !pr.faceTrack) return 0;   // Fit / Blur show the whole 16:9 picture: 1080p is the output's own width
        return quality == 1 && strongPhone && highSpeed ? 2160 : 1440;
    }

    /** v24: does this short crop narrowly enough to use the taller source (portrait + tracking or Fill)? */
    boolean wantsHi(Project pr) {
        if (streamsHi == null || FfmpegCommands.Aspect.of(pr.aspect) != FfmpegCommands.Aspect.PORTRAIT) return false;
        return FfmpegCommands.Frame.FILL.name().equals(pr.frame) || trackingWanted(pr);
    }

    /** v24: the taller-than-1080p streams (null = none); the normal streams stay the fallback */
    private volatile String[] streamsHi;

    /** v24: the hardware-decodable streams above 1080p this job may use (cap from hiSourceCap) */
    YtEngine.HiOk hiOk(int cap) { return hiOk(cap, false); }

    /**
     * v26: sw = a 1080p export on a phone that can decode a 1440p VP9 clip in software (64-bit, 8+ cores, 3.5+ GB RAM,
     * not marked too slow). Up to code 25 the sharper source needed a HARDWARE VP9 / AV1 decoder for 1440p, which many
     * budget and mid-range chips (MediaTek Helio / Dimensity 6000-class, Snapdragon 4-series, Unisoc) do not list, so
     * their face-tracked / Fill / split-screen 1080p shorts were always cut from 1080p: a 608x1080 window stretched 1.78x.
     */
    YtEngine.HiOk hiOk(int cap, boolean sw) {
        final boolean noHi = Prefs.sp(ctx).getInt("hi_src_fail", 0) >= 2;   // this phone failed twice: stay at 1080p
        return (fam, h, fps) -> !noHi && h <= cap && ((HwDecode.maxHeight(fam) >= h
                && (fps <= 31 || (h <= 1440 && HwDecode.fast4k(fam))))
                || (sw && swHiOk(fam, h, fps)));
    }

    /** v26: may this (vp9, <=1440p, <=30 fps) stream be decoded in software for the sharper source? (pure rule) */
    static boolean swHiOk(String fam, int h, double fps) { return "vp9".equals(fam) && h > 1080 && h <= 1440 && fps <= 31; }

    /** v26: can this phone decode a 1440p VP9 clip in software fast enough for a short? */
    boolean swHiCapable() {
        try {
            if (Prefs.sp(ctx).getInt("hi_sw_slow", 0) >= 2) return false;
            if (android.os.Build.SUPPORTED_64_BIT_ABIS == null || android.os.Build.SUPPORTED_64_BIT_ABIS.length == 0) return false;
            if (Runtime.getRuntime().availableProcessors() < 8) return false;
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return !am.isLowRamDevice() && mi.totalMem >= 3500L * 1024 * 1024;
        } catch (Throwable e) { return false; }
    }

    /** v26: a software-decoded 1440p render slower than 3x the clip's length counts against using it again (2 = never) */
    void noteSwHiSpeed(double dur, double sec) {
        if (dur <= 3) return;
        double x = sec / dur;
        if (x > 3.0) {
            int n = Prefs.sp(ctx).getInt("hi_sw_slow", 0) + 1;
            Prefs.sp(ctx).edit().putInt("hi_sw_slow", n).apply();
            AppLog.i(String.format(Locale.US, "Sharper source in software decoding took %.1fx the clip's length%s", x, n >= 2 ? "; this phone uses the normal source from now on" : ""));
        }
    }

    boolean strongPhone() {
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            return !am.isLowRamDevice() && Runtime.getRuntime().availableProcessors() >= 8 && mi.totalMem >= 7000L * 1024 * 1024;
        } catch (Exception e) { return false; }
    }

    /**
     * code 33 (5.1): x264 threads for one-at-a-time rendering (2-3 GB phones). "auto" = 1.5 x cores = 12 threads on an
     * 8-core phone, each with its own lookahead / frame buffers. Box measurement (20 s 1080p FILL clip, 8 cores, 1080x1920
     * output): auto 520 MB peak memory, 6 threads 384 MB at the SAME wall time (4 threads: 343 MB but ~10 % slower).
     */
    static int sequentialX264Threads() { return sequentialX264Threads(Runtime.getRuntime().availableProcessors()); }
    static int sequentialX264Threads(int cores) { return cores >= 5 ? 6 : 0; }

    /** v24: picks the taller stream set from an info JSON (only when it is taller than the normal one) */
    void pickHi(JSONObject info, Project pr, int quality) {
        int cap = hiSourceCap(pr, quality, strongPhone(), Prefs.highSpeedOn(ctx));
        if (cap <= 1080 || info == null) return;
        if (cap == 2160) {
            // 4K only where it decodes at 60 fps in hardware (two shorts render at once)
            final YtEngine.HiOk base = hiOk(2160);
            String[] s4 = keepLangAudio(YtEngine.pickStreams(info, 2160, (f, h, fps) -> base.ok(f, h, fps) && (h <= 1440 || HwDecode.fast4k(f))), info);
            if (s4 != null && heightOf(s4) > 1080) { setHi(s4); return; }
        }
        // v26: 1440p VP9 may also be decoded in software for a 1080p export on a capable phone (see hiOk)
        String[] s = keepLangAudio(YtEngine.pickStreams(info, 1440, hiOk(1440, quality == 1 && swHiCapable())), info);
        if (s != null && heightOf(s) > Math.max(1080, heightOf(streamsHi))) setHi(s);
    }

    /**
     * code 28: the chosen audio language for this job. The stream list that won the audio race has it (the race prefers
     * such lists), or it doesn't: then the whole job uses the original audio - transcript, captions and every short
     * stay in one language - and the user is told once.
     */
    void applyAudioLanguage(Project pr, JSONObject info) {
        if (pr.audioLang == null || pr.audioLang.isEmpty()) return;
        String name = pr.audioLangName == null || pr.audioLangName.isEmpty() ? AudioTracks.displayName(pr.audioLang) : pr.audioLangName;
        if (AudioTracks.has(info, pr.audioLang)) {
            metaLang = pr.audioLang;   // the speech is in the dub's language (speech-language decision, transcript cache)
            AppLog.i("Audio language: " + name + " track found, audio + captions use it");
            return;
        }
        AppLog.i("Audio language: the " + name + " track was not available from YouTube right now, using the original audio");
        synchronized (warnings) { warnings.add(name + " audio wasn't available right now, so the original audio was used."); }
        pr.audioLang = "";
        pr.audioLangName = "";
        YtEngine.audioLang = "";
    }

    /**
     * code 28: a stream set picked from another route's list (sharper video) keeps the chosen-language audio link of
     * the main list when that list doesn't carry the dub (some player clients list only the original track).
     */
    String[] keepLangAudio(String[] s, JSONObject from) {
        String want = YtEngine.audioLang;
        if (s == null || want == null || want.isEmpty() || AudioTracks.has(from, want)) return s;
        String[] base = streams;
        if (base == null || base.length < 2 || base[1] == null) return s;
        String[] c = s.clone();
        c[1] = base[1];
        return c;
    }

    private void setHi(String[] s) {
        int have = heightOf(streams);
        if (heightOf(s) <= Math.max(have, 1080) || s[1] == null) return;
        streamsHi = s;
        boolean hwDec = HwDecode.maxHeight(HwDecode.family(s[3].contains(" ") ? s[3].split(" ")[1] : "")) >= heightOf(s);
        AppLog.i("Sharper source for cropped shorts: " + s[3] + " (" + (hwDec ? "hardware" : "software") + " decoded; the " + (have > 0 ? have + "p" : "normal") + " stream stays the fallback)");
    }

    /** height from the "WxH codec itag N" description of a picked stream (0 = unknown) */
    public static int heightOf(String[] s) {
        if (s == null || s.length < 4 || s[3] == null) return 0;
        try {
            String d = s[3].trim(); int x = d.indexOf('x'), sp = d.indexOf(' ');
            int h = Integer.parseInt(d.substring(x + 1, sp > x ? sp : d.length()));
            // code 32: the quality class is the short side (a vertical 1080x1920 is 1080p, not "1920p")
            int w = 0; try { w = Integer.parseInt(d.substring(0, x)); } catch (Exception ignored) {}
            return w > 0 && w < h ? w : h;
        }
        catch (Exception e) { return 0; }
    }

    /** v26: HW 0, HW_STD 1, HW_BASIC 2, X264 3 (higher = more conservative) */
    static int rank(FfmpegCommands.Encoder e) {
        return e == null ? 0 : e == FfmpegCommands.Encoder.HW ? 0 : e == FfmpegCommands.Encoder.HW_STD ? 1 : e == FfmpegCommands.Encoder.HW_BASIC ? 2 : 3;
    }

    static FfmpegCommands.Encoder ofRank(int r) {
        return r <= 0 ? FfmpegCommands.Encoder.HW : r == 1 ? FfmpegCommands.Encoder.HW_STD : r == 2 ? FfmpegCommands.Encoder.HW_BASIC : FfmpegCommands.Encoder.X264;
    }

    /**
     * v26: the encoder for a render of this size: the phone's level on the ladder (HW -> HW_STD -> HW_BASIC), never
     * above what already failed in this short (floor), x264 when the phone has no hardware encoder for this size, when
     * the hardware encoder failed for good, or when the quality guard found it starving the picture twice.
     */
    FfmpegCommands.Encoder hwEncoder(FfmpegCommands.Encoder floor, int outW) {
        if (!Prefs.hwEncodeOn(ctx) || Prefs.hwEncodeState(ctx) < 0) return FfmpegCommands.Encoder.X264;
        if (!RenderSafety.hwEncodeAllowed(ctx)) return FfmpegCommands.Encoder.X264;   // code 31: the hardware encoder crashed on this phone
        if (!FfmpegCommands.planFor(outW).hw) return FfmpegCommands.Encoder.X264;
        // code 33: x264 crashed on this phone: its hardware encoder even if the quality check once found it starving the picture
        if (Prefs.hwQualityPoor(ctx) && !RenderSafety.x264Bad(ctx)) return FfmpegCommands.Encoder.X264;
        int r = Math.max(Math.min(2, Prefs.hwTuneLevel(ctx)), rank(floor));
        return ofRank(r);
    }

    /** v26: a hardware encoder failure at enc: the next step for this short; counted for the phone (see Prefs) */
    private FfmpegCommands.Encoder encoderFailed(FfmpegCommands.Encoder enc, RenderFault.Kind kind, Exception e, String where) {
        boolean clear = kind == RenderFault.Kind.ENCODER;
        boolean moved = Prefs.hwLevelFault(ctx, rank(enc), clear);
        FfmpegCommands.Encoder next = enc.next();
        AppLog.i(where + ": hardware encoder (" + enc + ") failed (" + shortErr(e) + "), trying " + (next.hw() ? next + " settings" : "the software encoder")
                + (moved ? (next.hw() ? "; this phone keeps the " + next + " settings from now on" : "; this phone uses the software encoder from now on") : ""));
        return next;
    }

    /**
     * v26 quality guard. A final short from the hardware encoder whose video bitrate is far below the target (the
     * picture is starved: blocky motion, smeared faces on many MediaTek / Unisoc / Exynos encoders) is encoded once more
     * with x264 and the better file kept. Twice "really undershot" and this phone's final shorts use x264 directly.
     * build = the same render with another encoder into another file. Returns true when the x264 file replaced target.
     */
    interface Rebuild { String[] cmd(FfmpegCommands.Encoder enc, String outPath); }

    private boolean qualityGuard(File target, double dur, int outW, Rebuild build, String label, CancelToken ct) {
        try {
            if (target == null || !target.exists() || dur < 6) return false;
            if (RenderSafety.x264Bad(ctx)) return false;   // code 33: the x264 re-check would run the encoder that crashed here
            int[] gc = Prefs.guardCounts(ctx);
            if (gc[1] >= 3 && gc[1] > 3 * gc[0]) return false;   // this phone's pictures were just easy every time: no extra pass
            double hwMbps = target.length() * 8.0 / dur / 1e6 - FfmpegCommands.AUDIO_MBPS;
            if (!FfmpegCommands.starved(outW, hwMbps)) return false;
            AppLog.i(String.format(Locale.US, "%s: the hardware encoder gave only %.1f Mbps (target %.0f Mbps), checking the picture with the software encoder",
                    label, hwMbps, FfmpegCommands.hwBaseKbps(outW, false) / 1000.0));
            File g = new File(target.getParentFile(), "g_" + target.getName());
            g.delete();
            long t0 = System.currentTimeMillis();
            MediaRunner.run(build.cmd(FfmpegCommands.Encoder.X264, g.getAbsolutePath()), dur, null, ct);
            if (!g.exists() || g.length() < 20000) { g.delete(); return false; }
            double swMbps = g.length() * 8.0 / dur / 1e6 - FfmpegCommands.AUDIO_MBPS;
            boolean under = FfmpegCommands.undershot(hwMbps, swMbps);
            Prefs.guardResult(ctx, under);
            boolean ok = g.renameTo(target);
            if (!ok) { copyFile(g, target); g.delete(); ok = true; }
            AppLog.i(String.format(Locale.US, "%s: software encoder %.1f Mbps vs hardware %.1f Mbps in %.1f s: %s", label, swMbps, hwMbps,
                    (System.currentTimeMillis() - t0) / 1000.0, under ? "the hardware encoder was starving the picture, kept the sharper software encode"
                            + (Prefs.hwQualityPoor(ctx) ? "; this phone's final shorts use the software encoder from now on" : "") : "the picture is simply easy to compress, kept the software encode"));
            return ok;
        } catch (CancelToken.Cancelled c) {
            throw c;
        } catch (Exception e) {
            AppLog.e(label + ": quality check skipped (" + shortErr(e) + ")");
            return false;
        }
    }

    private int effectiveQuality() {
        int q = Prefs.videoQuality(ctx);
        if (q == 1 && !Prefs.hdUnlocked(ctx)) { AppLog.i("1080p is locked, exporting in 720p"); return 0; }
        return q;
    }

    // ================================================================== planning

    /** how many shorts render at the same time on this phone */
    int parallelism(int clips) {
        if (clips < 2) return 1;
        if (parForTest > 0) return Math.min(parForTest, clips);
        // code 30: the phone ran out of memory during the last job and closed Klipora: one short at a time until a job finishes
        if (JobGuard.lowMemoryNext(ctx)) return 1;
        int cores = Runtime.getRuntime().availableProcessors();
        long ram = 0;
        try {
            ActivityManager am = (ActivityManager) ctx.getSystemService(Context.ACTIVITY_SERVICE);
            ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo();
            am.getMemoryInfo(mi);
            ram = mi.totalMem;
            if (am.isLowRamDevice()) return 1;
        } catch (Exception ignored) {}
        if (cores >= 8 && ram >= 5500L * 1024 * 1024 && clips >= 3) return 3;
        return cores >= 6 && ram >= 3500L * 1024 * 1024 ? 2 : 1;
    }

    /** v22: whether the planned job will probably track faces (the user's switch; the podcast check comes later) */
    private volatile boolean faceTrackPrior;

    /** v22: this phone's learned render rates (seconds per short / per clip second) */
    RenderProgress.Rates rates() {
        return new RenderProgress.Rates(Prefs.learned(ctx, "r_prep", 8), Prefs.learned(ctx, "r_faces", 0.8), Prefs.learned(ctx, "r_enc", 1.2));
    }

    private void plan(boolean local, double dur, int count, int par, boolean captionJob) {
        double min = Math.max(0.5, dur / 60.0);
        double[] e = new double[JobManager.N];
        e[0] = local ? 3 + min * 0.3 : Prefs.learned(ctx, "dl_base", 7) + Prefs.learned(ctx, "dl_per_min", 0.8) * min;
        e[1] = Prefs.learned(ctx, "tr_base", 4) + Prefs.learned(ctx, "tr_per_min", 0.5) * min;
        e[2] = Prefs.learned(ctx, "ai", 9);
        // v22 (4.1): shorts are 45-90 s now (4.0 still assumed 45 s each) and the render step is the face analysis + the
        // encode of every short through the real pipeline; the estimate is replaced by the exact one once the clips are known
        double avg = Math.min(dur, Prefs.learned(ctx, "clip_avg", 55));
        double[] secs = new double[Math.max(1, count)];
        java.util.Arrays.fill(secs, avg);
        e[3] = RenderProgress.predictWall(secs, faceTrackPrior, rates(), par) + 2;
        e[4] = e[3];
        if (captionJob) { e[4] = RenderProgress.predictWall(secs, false, rates(), par) + 2; }
        jm.plan(e);
    }

    // ================================================================== ANALYZE

    public Project runAnalyze(Request rq) throws Exception {
        AppLog.clear();
        Project pr = new Project();
        File dir = pr.dir(ctx);
        pr.category = rq.category.name();
        pr.frame = rq.frame.name();
        pr.faceTrack = rq.faceTrack;
        pr.aspect = rq.aspect == null ? "PORTRAIT" : rq.aspect.name();
        faceTrackPrior = rq.faceTrack && FfmpegCommands.Aspect.of(pr.aspect) != FfmpegCommands.Aspect.LANDSCAPE;
        // code 28: the audio language chosen for a multi-language video ("" = original); every stream pick follows it
        pr.audioLang = rq.audioLang == null ? "" : rq.audioLang.trim();
        pr.audioLangName = rq.audioLangName == null ? "" : rq.audioLangName;
        YtEngine.audioLang = pr.audioLang;
        if (!pr.audioLang.isEmpty()) AppLog.i("Audio language chosen: " + (pr.audioLangName.isEmpty() ? pr.audioLang : pr.audioLangName) + " (" + pr.audioLang + ")");
        checkFreeSpace(dir);   // code 29: a full phone fails at once with a clear message, not after the download
        boolean high = Prefs.highSpeedOn(ctx);
        int quality = effectiveQuality();
        AppLog.i(high ? "Speed mode: HIGH SPEED (unlimited)" : "Speed mode: standard (max 3 MB/s)");
        hdF = null;
        int par = parallelism(Math.min(shortsFor(rq.count, Math.max(rq.duration, 60)), 5));
        long tStart = System.currentTimeMillis();
        jm.title = rq.title == null ? "" : rq.title;

        // ---------- 1. source + speech audio
        File speechSrc;
        String audioExt;
        if (rq.localUri != null) {
            pr.sourceType = "local";
            plan(true, Math.max(rq.duration, 60), shortsFor(rq.count, Math.max(rq.duration, 60)), par, false);
            step(0, 0, "Importing your video", null);
            File src = copyLocal(rq.localUri, dir);
            pr.localPath = src.getAbsolutePath();
            pr.title = rq.title != null ? rq.title : src.getName();
            pr.duration = probeDuration(src);
            if (pr.duration <= 0) throw new Exception("Could not read this video file (unsupported or corrupt)");
            AppLog.i(String.format(Locale.US, "Imported %s (%.0f s, %.1f MB)", pr.title, pr.duration, src.length() / 1048576.0));
            speechSrc = src;
            audioExt = ".m4a";
        } else {
            pr.sourceType = "yt";
            pr.url = YtEngine.cleanUrl(rq.ytUrl);
            pr.title = rq.title;
            pr.duration = rq.duration;
            pr.thumbUrl = rq.thumbUrl == null ? "" : rq.thumbUrl;
            plan(false, Math.max(pr.duration, 60), shortsFor(rq.count, Math.max(pr.duration, 60)), par, false);
            step(0, -1, "Reading the video link", "Starting the video engine…");
            final long[] lastLine = {0};
            YtEngine.AudioResult ar = YtEngine.downloadAudioWithInfo(pr.url, dir, high, (pct, line) -> {
                if (pct <= 0) { step(0, -1, "Reading the video link", "Finding the best audio stream…"); return; }
                long now = System.currentTimeMillis();
                if (now - lastLine[0] < 200 && pct < 100) return;
                lastLine[0] = now;
                String d = YtEngine.describeDownload(pct, line);
                step(0, pct / 100.0, "Downloading audio", d != null ? d : String.format(Locale.US, "%.0f%%", pct));
            }, ct);
            if (ar.info != null) {
                metaLang = ar.info.optString("language", "");
                String desc = ar.info.optString("description", "");
                metaDesc = desc.length() > 600 ? desc.substring(0, 600) : desc;
                applyAudioLanguage(pr, ar.info);
                metaHint = ar.info.optString("channel", "") + " " + ar.info.optString("categories", "") + " " + ar.info.optString("tags", "");
                if (pr.title == null || pr.title.isEmpty()) pr.title = ar.info.optString("title", "Video");
                double d = ar.info.optDouble("duration", 0);
                if (d > 0) pr.duration = d;
                if (pr.thumbUrl.isEmpty()) pr.thumbUrl = ar.info.optString("thumbnail", "");
                srcMaxH = sourceMaxHeight(pr, quality);
                String[] s = YtEngine.pickStreams(ar.info, srcMaxH);
                if (s != null) { streams = s; streamsAt = System.currentTimeMillis(); AppLog.i("Stream links ready from the same request (" + (s[1] != null ? "HD video + audio" : "single stream") + (s.length > 3 && s[3] != null ? ", " + s[3] : "") + ")"); }
                streamsHi = null;
                try { pickHi(ar.info, pr, quality); } catch (Exception e) { AppLog.i("Sharper source check skipped: " + e.getMessage()); }
                // v19 (3.0): a cropped 9:16 short needs the sharpest source; if this list tops out below 1080p, ask the
                // clients that list 1080p while the speech is transcribed (2.9 cropped a 720p stream: 406x720 -> 1.78x)
                int have = streams == null ? 0 : heightOf(streams);
                // v21 (4.0): every format, not only the 9:16 / 1:1 crop. 3.1 looked further only for a crop, so a 16:9 (or
                // fit / blur) short at 1080p was made from whatever the first list had (often 720p) = "low quality at 1080p".
                if (have > 0 && have < srcMaxH) {
                    final String url0 = pr.url; final int want0 = srcMaxH, have0 = have; final Project fpr0 = pr;
                    hdF = Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "hd-list"); t.setDaemon(true); return t; }).submit(() -> {
                        long t0 = System.currentTimeMillis();
                        JSONObject better = AudioRace.sharperList(url0, want0, have0, 30_000, ct);
                        String[] s2 = better == null ? null : keepLangAudio(YtEngine.pickStreams(better, want0), better);
                        try { pickHi(better, fpr0, quality); } catch (Exception ignored) {}
                        if (s2 != null && heightOf(s2) > have0) {
                            synchronized (Pipeline.this) { streams = s2; streamsAt = System.currentTimeMillis(); }
                            AppLog.i(String.format(Locale.US, "Sharper video: %dp -> %dp (%s, found in %.1f s)", have0, heightOf(s2), s2[3], (System.currentTimeMillis() - t0) / 1000.0));
                        } else AppLog.i(String.format(Locale.US, "Sharper video: YouTube has no stream above %dp for this video, using %dp (checked in %.1f s)", have0, have0, (System.currentTimeMillis() - t0) / 1000.0));
                        return null;
                    });
                }
            }
            if (pr.duration <= 0) pr.duration = probeDuration(ar.file);
            if (pr.title == null || pr.title.isEmpty()) pr.title = "Video";
            speechSrc = ar.file;
            String n = ar.file.getName().toLowerCase(Locale.US);
            audioExt = n.endsWith(".webm") ? ".webm" : n.endsWith(".mp3") ? ".mp3" : (n.endsWith(".ogg") || n.endsWith(".opus")) ? ".ogg" : ".m4a";
            // v8: one line (v7 logged the audio twice: the download alone, then again incl. the YouTube lookup)
            double audioTotal = (System.currentTimeMillis() - tStart) / 1000.0;
            AppLog.i(ar.downloadSec > 0 ? String.format(Locale.US, "Audio step total: %.1f s (YouTube link lookup %.1f s + download %.1f s)", audioTotal, ar.lookupSec, ar.downloadSec)
                    : String.format(Locale.US, "Audio step total: %.1f s", audioTotal));
        }
        jm.title = pr.title;
        double tGot = (System.currentTimeMillis() - tStart) / 1000.0;
        step(0, 1, null, null);

        // start resolving the clip streams while the AI works, only if the info didn't already give them
        ExecutorService side = Executors.newSingleThreadExecutor();
        final Project fpr = pr;
        Future<?> resolveF = null;
        if (!pr.isLocal() && streams == null) resolveF = side.submit(() -> { getStreams(fpr, false); return null; });
        // v6: list the Gemini models (cached 24 h) + open the connection while speech is transcribed
        Thread warm = new Thread(() -> new AiClient(ctx, ct).warmUp(), "ai-warm");
        warm.setDaemon(true);
        warm.start();

        try {
            // ---------- 2. transcript (parallel pieces)
            long t1 = System.currentTimeMillis();
            // v7: transcripts are cached per video (+ speech language), so the same video never costs Groq minutes twice
            // v8: decide the spoken language ONCE, then every part is transcribed in it (never auto per part, never translated)
            speechLang = chooseSpeechLanguage(rq, pr, speechSrc, audioExt, dir);
            File cacheF = transcriptCache(pr);
            Transcript tr = null;
            if (cacheF != null && cacheF.exists()) {
                try { tr = Transcript.fromJson(Project.readText(cacheF)); } catch (Exception ignored) {}
                if (tr != null && tr.segments.isEmpty()) tr = null;
                // v8: never reuse a transcript in another language (v7 cached English "transcripts" of Hindi speech)
                if (tr != null && !speechLang.equals(langFamily(tr.language))) tr = null;
                if (tr != null) AppLog.i("Transcript reused from cache (no new transcription needed)");
            }
            if (tr == null) {
                tr = transcribe(speechSrc, audioExt, pr.duration, dir);
                if (cacheF != null && !tr.partial) { try { Project.writeText(cacheF, tr.toJson().toString()); pruneCache(cacheF.getParentFile(), 20); } catch (Exception ignored) {} }
            }
            if (!pr.isLocal()) {
                // v16: keep the downloaded speech audio: each short cuts its own speech from it (2.6 deleted it, so
                // every short re-read its audio from YouTube over one throttled connection: ~45 s in his log)
                File keep = new File(dir, "speech_full" + audioExt);
                if (!speechSrc.equals(keep)) { keep.delete(); if (!speechSrc.renameTo(keep)) speechSrc.delete(); }
            }
            Project.writeText(pr.transcriptFile(ctx), tr.toJson().toString());
            pr.language = tr.language;
            double tTr = (System.currentTimeMillis() - t1) / 1000.0;
            step(1, 1, "Transcript ready", tr.words.size() + " words with timing");

            // ---------- 3. viral moments
            long t2 = System.currentTimeMillis();
            step(2, -1, "Finding viral moments", "AI is reading " + tr.words.size() + " words…");
            int want = shortsFor(rq.count, pr.duration);
            AppLog.i(rq.count > 0 ? "Making exactly " + want + " short" + (want > 1 ? "s" : "") + " (your choice)"
                    : "Auto (" + mmss(pr.duration) + " video): making " + want + " short" + (want > 1 ? "s" : ""));
            // v8: ask the AI for a few spare moments, so clips dropped by the hook/overlap rules don't lower the count
            // code 31: a song is picked as a song (chorus / hook lines), not like a talk. code 32: set BEFORE the AI request
            // (4.9 set it after findViralClips had already built its prompt, so the song rules reached the NEXT job's prompt)
            PromptManager.songJob = looksLikeMusic(pr.title, metaHint);
            if (PromptManager.songJob) AppLog.i("Detected: song / music video (moments are picked on the sung lines)");
            List<AiClient.Candidate> cands = new AiClient(ctx, ct).notices(warnings).findViralClips(rq.category, pr.title, tr, Math.min(MAX_SHORTS + 3, want + Math.max(3, want / 2)), pr.duration);   // v19: 1 more candidate to choose the biggest story from
            // v14: podcast or not (the user's pick, the AI's vote from the same request, title/metadata + transcript)
            PodcastDetector.Result pd = PodcastDetector.decide(rq.category == PromptManager.Category.PODCAST, pr.title, metaHint, tr, pr.duration, cands);
            pr.podcast = pd.podcast;
            pr.podcastWhy = pd.why;
            AppLog.i("Detected: " + (pd.podcast ? "podcast" : "not a podcast") + " (" + pd.why + ")");
            // v14: premium — one ad credit is used only when tracking will really run (a podcast in 9:16 / 1:1)
            if (trackingWanted(pr)) {
                String key = srcKey(pr);
                if (Prefs.paidRecently(ctx, Prefs.F_FACE, key)) AppLog.i("AI face tracking: unlocked for this video already");
                else if (!Prefs.useFaceTrack(ctx)) { pr.faceTrack = false; AppLog.i("AI face tracking is locked (watch 1 ad or use PRO), normal framing"); }
                else if (!Prefs.isPremium(ctx)) { Prefs.markPaid(ctx, Prefs.F_FACE, key); AppLog.i("AI face tracking: 1 ad credit used for this podcast (" + Prefs.faceTrackCredits(ctx) + " left)"); }
            }
            PromptManager.Category pickCat = pd.podcast ? PromptManager.Category.PODCAST : rq.category;
            List<Project.Clip> leftovers = new ArrayList<>();
            long tPick = System.currentTimeMillis();
            List<Project.Clip> picked = pickClips(cands, tr, pr.duration, want, pickCat, leftovers);
            // a 5.0 podcast log showed 17 s between the AI answer and the ending check, unexplained: say when placing is slow
            if (System.currentTimeMillis() - tPick > 1500)
                AppLog.i(String.format(java.util.Locale.US, "Placing the moments on the transcript took %.1f s", (System.currentTimeMillis() - tPick) / 1000.0));
            // code 29: fewer clean moments than asked (they overlapped) = ask the AI once more about the part of the video no
            // short covers, then fill what is still missing from the transcript (4.6 silently made 1 short of 3-5 asked)
            if (picked.size() < want) {
                AppLog.i("AI moments overlap: " + picked.size() + " of " + want + " so far, looking at the rest of the video");
                step(2, -1, "Finding viral moments", "Looking for " + (want - picked.size()) + " more moment" + (want - picked.size() == 1 ? "" : "s") + "…");
                try {
                    List<double[]> taken = new ArrayList<>();
                    for (Project.Clip c : picked) taken.add(new double[]{c.start, c.end});
                    List<AiClient.Candidate> more = new AiClient(ctx, ct).notices(warnings).findMoreClips(rq.category, pr.title, tr, taken, want - picked.size() + 2, pr.duration);
                    List<Project.Clip> extraLeft = new ArrayList<>();
                    for (Project.Clip c : pickClips(more, tr, pr.duration, MAX_SHORTS + 3, pickCat, extraLeft)) {
                        if (picked.size() >= want) break;
                        if (!overlapsAny(picked, c.start, c.end)) { picked.add(c); AppLog.i("Extra AI moment at " + mmss(c.start) + "–" + mmss(c.end)); }
                    }
                    leftovers.addAll(extraLeft);
                } catch (CancelToken.Cancelled c) { throw c; }
                catch (Exception e) { AppLog.i("Second look skipped (" + shortErr(e) + "), filling from the transcript"); }
                picked = topUpClips(picked, leftovers, tr, pr.duration, want, pd.podcast);
                java.util.Collections.sort(picked, (x, y) -> Integer.compare(y.score, x.score));
            }
            // v18 (2.9): a second, focused AI pass decides where each clip's topic really finishes (validated on the
            // transcript), then every end is moved onto the real pause in the audio (never mid-word)
            step(2, -1, "Finding viral moments", "Checking where each moment ends…");
            new AiClient(ctx, ct).notices(warnings).refineEnds(picked, tr, pr.duration);
            File speechAudio = new File(dir, "speech_full" + audioExt).exists() ? new File(dir, "speech_full" + audioExt) : (pr.isLocal() && pr.localPath != null && !pr.localPath.isEmpty() ? new File(pr.localPath) : null);
            snapEndsToAudio(picked, tr, speechAudio);
            snapStartsToVoice(picked, tr, speechAudio);
            trimOverlaps(picked, tr);
            if (picked.size() < want) {
                AppLog.i("AI found " + picked.size() + " clean moment" + (picked.size() == 1 ? "" : "s") + " of the " + want + " asked (the others started mid-sentence or overlapped)");
                // code 29: never silent - the results screen says why fewer shorts were made
                synchronized (warnings) { warnings.add("Made " + picked.size() + " of the " + want + " shorts you picked: this video has no room for more without repeating parts."); }
            }
            for (Project.Clip clip : picked) {
                clip.index = pr.clips.size();
                pr.clips.add(clip);
                AppLog.i(String.format(Locale.US, "Clip #%d • score %d • %s–%s (%.0f s) • hook: %s", clip.index + 1, clip.score, mmss(clip.start), mmss(clip.end), clip.end - clip.start, clip.hook));
                AppLog.i("   ends: \u201c" + ClipAligner.closingLine(tr.segments, tr.words, clip.start, clip.end) + "\u201d"
                        + (clip.endReason != null && !clip.endReason.isEmpty() ? " (" + clip.endReason + ")" : "")
                        + (Boolean.FALSE.equals(clip.topicComplete) && clip.end - clip.start >= ClipAligner.MAX_LEN - 15 ? " • topic continues, ended at the last point before 1:30" : ""));
            }
            if (pr.clips.isEmpty()) throw new Exception("No usable clips found. Try another genre or video.");
            double tAi = (System.currentTimeMillis() - t2) / 1000.0;
            step(2, 1, "Found " + pr.clips.size() + " moments", null);

            // ---------- 4. render (captions burned in the same pass)
            if (resolveF != null) { try { resolveF.get(60, TimeUnit.SECONDS); } catch (Exception ignored) {} }
            if (hdF != null) { try { hdF.get(20, TimeUnit.SECONDS); } catch (Exception ignored) {} }
            long t3 = System.currentTimeMillis();
            AppLog.i("Format: " + FfmpegCommands.Aspect.of(pr.aspect).label + (trackingWanted(pr) ? " • AI face tracking on (podcast)" : pr.faceTrack ? " • face tracking not used (" + (FfmpegCommands.Aspect.of(pr.aspect) == FfmpegCommands.Aspect.LANDSCAPE ? "16:9 keeps the whole frame" : "podcast genre only") + ")" : ""));
            if (trackingWanted(pr)) { try { FaceAnalyzer.ensureEngine(ctx); } catch (Throwable ignored) {} AppLog.i(FaceAnalyzer.statusLine()); }
            List<Integer> all = new ArrayList<>();
            for (int i = 0; i < pr.clips.size(); i++) all.add(i);
            CaptionStyle capStyle = captionsAllowed(pr, rq.style) ? rq.style : null;
            // code 30: checkpoint - the picked moments (and, as each short finishes, the short) are on disk, so a job the
            // phone stops here continues from them (Continue) instead of downloading, transcribing and asking the AI again
            pr.captionsWanted = capStyle != null;
            pr.quality = quality;
            pr.save(ctx);
            JobGuard.checkpoint(ctx, pr.id, pr.clips.size());
            double clipSec = renderAll(pr, all, tr, capStyle, rq.layout, rq.script, rq.frame, quality, high, 3);
            double tR = (System.currentTimeMillis() - t3) / 1000.0;

            // learn this phone's speed for the next ETA
            double min = Math.max(0.5, pr.duration / 60.0);
            if (!pr.isLocal()) Prefs.learn(ctx, "dl_per_min", Math.max(0.05, (tGot - 4) / min));
            Prefs.learn(ctx, "tr_per_min", Math.max(0.05, (tTr - 2) / min));
            Prefs.learn(ctx, "ai", tAi);
            if (clipSec > 0) Prefs.learn(ctx, "render_per_sec", tR * renderPar / clipSec);
            learnRates(pr);
            AppLog.i(String.format(Locale.US, "Timing: audio %.1fs • transcript %.1fs • AI %.1fs • render %.1fs • total %.1fs",
                    tGot, tTr, tAi, tR, (System.currentTimeMillis() - tStart) / 1000.0));
        } finally {
            side.shutdownNow();
        }
        pr.save(ctx);
        pr.makeCurrent(ctx);
        try { Project.pruneSources(ctx, 2); } catch (Exception ignored) {}
        dropOrphans(pr);
        return pr;
    }

    /** code 32: removes the folders of earlier jobs that never finished a short (keeps this one and the Continue checkpoint) */
    void dropOrphans(Project pr) {
        try {
            java.util.Set<String> keep = new java.util.HashSet<>();
            if (pr != null && pr.id != null) keep.add(pr.id);
            String cp = Prefs.sp(ctx).getString("job_project", "");
            if (cp != null && !cp.isEmpty()) keep.add(cp);
            int n = Project.dropOrphans(ctx, keep, System.currentTimeMillis());
            if (n > 0) AppLog.i("Storage: removed " + n + " unfinished job folder" + (n == 1 ? "" : "s") + " left by earlier attempts");
        } catch (Exception ignored) {}
    }

    // ================================================================== transcription

    /**
     * v7: AI candidates → final clips. Each range is snapped to sentence boundaries, then the hook rule moves a start
     * that opens on a filler ("aur", "toh", "so" …) to a clean sentence start (or skips the clip), lengths stay 20-60 s,
     * overlaps are dropped and the result is ranked by score. Pure logic: unit-tested on a real transcript.
     */
    public static List<Project.Clip> pickClips(List<AiClient.Candidate> cands, Transcript tr, double duration, int want) {
        return pickClips(cands, tr, duration, want, PromptManager.Category.PODCAST);
    }

    /**
     * v12: question-led starts only for PODCAST (other genres: the AI's best self-contained part, no question bias);
     * clips with gaali are left out unless clearly the most viral moment (never censored); ends on a complete thought.
     */
    public static List<Project.Clip> pickClips(List<AiClient.Candidate> cands, Transcript tr, double duration, int want, PromptManager.Category cat) {
        return pickClips(cands, tr, duration, want, cat, null);
    }

    /** code 29: same, and {@code leftovers} (when given) receives the moments not used (overlapping / mid-thought), best first */
    public static List<Project.Clip> pickClips(List<AiClient.Candidate> cands, Transcript tr, double duration, int want, PromptManager.Category cat,
                                               List<Project.Clip> leftovers) {
        boolean podcast = cat == PromptManager.Category.PODCAST;
        List<Project.Clip> pool = new ArrayList<>(), rejected = new ArrayList<>(), out = new ArrayList<>();
        java.util.Map<Project.Clip, AiClient.Candidate> profane = new java.util.IdentityHashMap<>();
        for (AiClient.Candidate c : cands) {
            // v11: start where the AI's copied first sentence really is (its seconds and line numbers can be off)
            int ns = tr.segments.size();
            double hint = c.startLine >= 0 && c.startLine < ns ? tr.segments.get(c.startLine).start : c.start;
            double hs = ClipAligner.locateHook(tr.words, c.hook, hint);
            if (hs >= 0 && Math.abs(hs - c.start) > 0.5) {
                double len = c.end - c.start;
                c.start = hs; c.startLine = -1;
                if (c.endLine < 0 && len > 0) c.end = hs + len;
            }
            double[] r = ClipAligner.align(tr.segments, tr.words, c.startLine, c.endLine, c.start, c.end, duration);
            if (hs >= 0) r = ClipAligner.startAt(tr.segments, tr.words, hs, r, duration);
            // v11: a question-led short opens on the question itself (not the answer, not a preamble) and keeps the answer
            double qs = podcast ? ClipAligner.questionStart(tr.segments, tr.words, r[0], r[1], c.questionLine, c.isQa()) : -1;
            boolean movedToQuestion = false;
            if (qs >= 0 && Math.abs(qs - r[0]) > 0.3) {
                double[] a = ClipAligner.align(tr.segments, tr.words, -1, -1, qs, Math.max(r[1], qs + ClipAligner.MIN_LEN), duration);
                if (Math.abs(a[0] - qs) < 0.8 && a[1] - a[0] >= 12) { r = a; movedToQuestion = true; }
            }
            double[] h = ClipAligner.hookSafe(tr.segments, tr.words, r, duration);
            Project.Clip clip = new Project.Clip();
            clip.title = c.title;
            clip.hookScore = c.hookScore; clip.standaloneScore = c.standaloneScore; clip.valueScore = c.valueScore; clip.payoffScore = c.payoffScore;
            if (h == null) {
                clip.start = r[0]; clip.end = r[1];
                clip.score = Math.max(1, Math.min(100, c.score));
                clip.hook = ClipAligner.openingLine(tr.segments, tr.words, r[0], r[1]);
                clip.reason = reasonFor(c, false);
                rejected.add(clip);
                AppLog.i("Skipped a moment at " + mmss(r[0]) + ": it opens mid-thought (\"" + clip.hook + "\")");
                continue;
            }
            if (h[1] - h[0] < 3) continue;
            clip.start = h[0];
            // v18: the end comes from where the AI says the topic finishes (its copied closing words when it gave them),
            // snapped to a complete sentence + pause; a topic longer than 1:30 ends at the last point before the limit
            double aiEnd = Math.max(h[1], c.end);
            double et = c.endText.isEmpty() ? -1 : ClipAligner.locateEnd(tr.words, c.endText, aiEnd, h[0]);
            if (et > h[0] + 8) aiEnd = et;
            // v19 (3.0): podcast shorts are whole stories of 45-90 s, never under 35 s unless the AI says the story
            // really ends there (2.9 made a 17 s short in his log)
            clip.minLen = podcast ? ClipAligner.POD_MIN_LEN : ClipAligner.MIN_LEN;
            boolean storyEnds = Boolean.TRUE.equals(c.topicComplete) && et > h[0] + 8;
            clip.end = ClipAligner.endFor(tr.segments, tr.words, h[0], aiEnd, duration, clip.minLen, storyEnds);
            if (clip.end - clip.start < 3) clip.end = h[1];
            clip.endReason = c.endReason;
            clip.topicComplete = c.topicComplete;
            // v19: the hook shown is a clean sentence: the AI's copy of the first sentence when it was found right at the
            // clip's start, else the transcript's first sentence (max ~15 words, cut at a sentence end when there is one)
            clip.hook = cleanHook(hs >= 0 && Math.abs(hs - h[0]) < 1.0 && c.hook != null && c.hook.trim().length() > 3
                    ? c.hook : ClipAligner.openingLine(tr.segments, tr.words, h[0], h[1]));
            boolean qa = podcast && (ClipAligner.isQuestion(clip.hook) || ((movedToQuestion || c.isQa()) && qs >= 0 && Math.abs(h[0] - qs) < 0.8));
            clip.score = rankScore(c, qa);
            // v24 (Sakib: shorts should open clean, like a question): a non-podcast short that opens on a clear question gets
            // a small lift (podcasts already get the bigger question bonus above); one that opens on a filler word drops
            if (!qa && ClipAligner.isQuestion(clip.hook)) clip.score = Math.min(99, clip.score + 4);
            if (ClipAligner.startsWithFiller(clip.hook)) clip.score = Math.max(1, clip.score - 8);
            // v19: a whole 45-90 s story beats a fragment when the AI scores them alike (his wish: most shorts 45 s - 1:30)
            if (podcast) {
                double len = clip.end - clip.start;
                boolean roomLimited = duration > 0 && duration - clip.start < ClipAligner.POD_MIN_LEN + 3;   // the video ends first
                clip.score = Math.max(1, Math.min(99, clip.score + (len >= ClipAligner.TARGET_LEN ? 3 : len < ClipAligner.POD_MIN_LEN && !roomLimited ? -6 : 0)));
            }
            clip.reason = reasonFor(c, qa);
            List<String> bad = Profanity.find(Profanity.textIn(tr, clip.start, clip.end));
            if (!bad.isEmpty() || c.aiProfanity) {
                profane.put(clip, c);
                AppLog.i("Moment at " + mmss(h[0]) + " has gaali (" + (bad.isEmpty() ? "AI" : bad.size() + "x") + ")");
            }
            // needs earlier context: ranked far down, used only to reach the number of shorts asked
            if (c.needsContext) AppLog.i("Moment at " + mmss(h[0]) + " needs earlier context, ranked last (\"" + clip.hook + "\")");
            pool.add(clip);
        }
        // v12 gaali rule: a clip with gaali stays only if it is clearly THE most viral moment (top score of all, >= 85 and
        // 5+ points above the best clean clip); otherwise it is used only when there aren't enough clean clips for the count
        applyProfanityRule(pool, profane);
        // best first, then no overlaps, then at most the number asked
        java.util.Collections.sort(pool, (x, y) -> Integer.compare(y.score, x.score));
        List<double[]> ranges = new ArrayList<>();
        List<Project.Clip> fragments = new ArrayList<>();
        for (Project.Clip clip : pool) {
            if (out.size() >= want) break;
            // v19: a podcast "short" under 25 s (e.g. the last seconds of the video) is a fragment, not a story: only used
            // when nothing else is left
            if (podcast && clip.end - clip.start < 25) { fragments.add(clip); continue; }
            boolean overlap = false;
            for (double[] e : ranges) if (Math.min(clip.end, e[1]) - Math.max(clip.start, e[0]) > 1.0) overlap = true;
            if (overlap) continue;
            ranges.add(new double[]{clip.start, clip.end});
            out.add(clip);
        }
        // his choice of how many shorts is kept: short fragments fill the count only when no full story is left
        for (Project.Clip clip : fragments) {
            if (out.size() >= want) break;
            boolean overlap = false;
            for (double[] e : ranges) if (Math.min(clip.end, e[1]) - Math.max(clip.start, e[0]) > 1.0) overlap = true;
            if (overlap) continue;
            ranges.add(new double[]{clip.start, clip.end});
            out.add(clip);
        }
        if (out.isEmpty() && !rejected.isEmpty()) {   // never fail a whole job on these rules alone
            java.util.Collections.sort(rejected, (x, y) -> Integer.compare(y.score, x.score));
            out.add(rejected.get(0));
        }
        if (leftovers != null) {
            for (Project.Clip c : pool) if (!out.contains(c)) leftovers.add(c);
            java.util.Collections.sort(rejected, (x, y) -> Integer.compare(y.score, x.score));
            for (Project.Clip c : rejected) if (!out.contains(c)) leftovers.add(c);
        }
        return out;
    }

    // ------------------------------------------------------------------ code 29: the number of shorts asked is the number made
    /**
     * code 29 (Sakib, 4.6: "3 ya zyada shorts choose kar rahe hain fir bhi 1 hi short ban kar aa rahi hai"): the clip picker
     * dropped every AI moment that overlapped a better one, and nothing replaced them. When the AI's moments sit close
     * together (they often do: a transcript with gaps after the speech service's hourly limit, a podcast story stretched to
     * 45-90 s, a short video), 3 or 5 asked became 1 made, silently. Now the shortfall is filled, in this order, from:
     * 1) the unused AI moments, trimmed to the part of the video no chosen short covers; 2) the best clean-starting
     * stretch of the transcript in each uncovered gap (question openers first, filler openers last); 3) when the
     * transcript itself has a gap, a plain stretch of the uncovered video. Shorts never overlap; a short is never under
     * {@link #TOPUP_MIN_LEN} s. Pure logic, unit-tested.
     */
    public static List<Project.Clip> topUpClips(List<Project.Clip> picked, List<Project.Clip> leftovers, Transcript tr, double duration,
                                                int want, boolean podcast) {
        List<Project.Clip> out = new ArrayList<>(picked);
        if (out.size() >= want || duration <= 0) return out;
        double target = podcast ? ClipAligner.TARGET_LEN : 40.0;
        double[] mins = podcast ? new double[]{ClipAligner.POD_MIN_LEN, ClipAligner.MIN_LEN, TOPUP_MIN_LEN} : new double[]{ClipAligner.MIN_LEN, TOPUP_MIN_LEN};
        for (double minLen : mins) {
            // 1) the AI's own unused moments, kept to the uncovered part of the video
            if (leftovers != null) for (Project.Clip l : leftovers) {
                if (out.size() >= want) break;
                double[] gap = bestGap(out, duration, l.start, l.end);
                if (gap == null) continue;
                double a = Math.max(gap[0], l.start - 2), b = Math.min(gap[1], Math.max(l.end, l.start + target) + 2);
                double[] w = windowIn(tr, a, b, duration, minLen, Math.max(minLen, Math.min(ClipAligner.MAX_LEN, l.end - l.start)), true);
                if (w == null) continue;
                Project.Clip c = extraClip(tr, w, Math.max(1, l.score - 5), l.reason, l.title, podcast);
                c.hookScore = l.hookScore; c.standaloneScore = l.standaloneScore; c.valueScore = l.valueScore; c.payoffScore = l.payoffScore;
                out.add(c);
                AppLog.i("Extra short from an unused AI moment: " + mmss(c.start) + "–" + mmss(c.end));
            }
            // 2) the best clean-starting stretch of speech in each uncovered gap (largest gaps first)
            while (out.size() < want) {
                double[] best = null; double bestScore = -1e9;
                for (double[] g : freeGaps(out, duration)) {
                    double[] w = windowIn(tr, g[0], g[1], duration, minLen, target, true);
                    if (w == null) continue;
                    double sc = windowScore(tr, w);
                    if (sc > bestScore) { bestScore = sc; best = w; }
                }
                if (best == null) break;
                out.add(extraClip(tr, best, 40, "Extra moment, picked to make the number of shorts you chose", "", podcast));
                AppLog.i("Extra short from the transcript: " + mmss(best[0]) + "–" + mmss(best[1]));
            }
            if (out.size() >= want) break;
        }
        // 3) the transcript has no usable speech in the uncovered part (e.g. a gap left by the speech service): plain stretches
        while (out.size() < want) {
            double[] best = null;
            for (double[] g : freeGaps(out, duration)) {
                if (g[1] - g[0] < TOPUP_MIN_LEN) continue;
                if (best == null || g[1] - g[0] > best[1] - best[0]) best = g;
            }
            if (best == null) break;
            double len = Math.min(target, best[1] - best[0]);
            double s0 = best[0] + Math.max(0, (best[1] - best[0] - len) / 2);
            out.add(extraClip(tr, new double[]{s0, s0 + len}, 30, "Extra moment, picked to make the number of shorts you chose", "", podcast));
            AppLog.i("Extra short (no transcript there): " + mmss(s0) + "–" + mmss(s0 + len));
        }
        return out;
    }

    /** code 29: what a full phone is told (FFmpeg's "No space left on device" was shown as an empty-output error) */
    public static final String NO_SPACE_MSG = "Your phone is almost out of storage. Free up some space (about 500 MB) and retry.";
    static final long MIN_FREE_BYTES = 300L * 1024 * 1024;

    public static boolean isNoSpace(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            String m = String.valueOf(t.getMessage()).toLowerCase(Locale.US);
            // code 32: FFmpeg's own log too (its one-line summary may name another line)
            if (t instanceof MediaRunner.Failed) m += " " + ((MediaRunner.Failed) t).log.toLowerCase(Locale.US);
            if (m.contains("no space left") || m.contains("enospc") || m.contains("disk full")) return true;
        }
        return false;
    }

    static void checkFreeSpace(File dir) throws Exception {
        long free;
        try { free = dir.getUsableSpace(); } catch (Exception e) { return; }
        if (free > 0 && free < MIN_FREE_BYTES) throw new Exception(NO_SPACE_MSG);
    }

    /** code 29: the shortest extra short made to reach the count (a regular short is 20 s+, a podcast story 35 s+) */
    static final double TOPUP_MIN_LEN = 15.0;
    /** code 29: room kept between two shorts */
    static final double TOPUP_MARGIN = 0.5;

    /** uncovered stretches of [0, duration] (sorted), each shrunk by the margin next to a chosen short */
    static List<double[]> freeGaps(List<Project.Clip> taken, double duration) {
        List<double[]> r = new ArrayList<>();
        for (Project.Clip c : taken) r.add(new double[]{c.start, c.end});
        java.util.Collections.sort(r, (x, y) -> Double.compare(x[0], y[0]));
        List<double[]> gaps = new ArrayList<>();
        double at = 0;
        for (double[] x : r) {
            double a = at == 0 ? 0 : at + TOPUP_MARGIN, b = x[0] - TOPUP_MARGIN;
            if (b - a > 1) gaps.add(new double[]{a, b});
            at = Math.max(at, x[1]);
        }
        double a = at == 0 ? 0 : at + TOPUP_MARGIN;
        if (duration - a > 1) gaps.add(new double[]{a, duration});
        return gaps;
    }

    /** the uncovered stretch that shares the most time with [s, e] (null = none) */
    static double[] bestGap(List<Project.Clip> taken, double duration, double s, double e) {
        double[] best = null; double bestOv = 0;
        for (double[] g : freeGaps(taken, duration)) {
            double ov = Math.min(e, g[1]) - Math.max(s, g[0]);
            if (ov > bestOv) { bestOv = ov; best = g; }
        }
        return best;
    }

    /**
     * the best short inside [a, b]: it opens on a transcript line that starts a sentence (question openers first, filler
     * openers last), ends on a complete thought where possible and is minLen..MAX_LEN long; null when none fits
     */
    static double[] windowIn(Transcript tr, double a, double b, double duration, double minLen, double target, boolean speechOnly) {
        if (tr == null || b - a < minLen) return null;
        // code 32: one token list for the whole search (4.9 rebuilt it 3x per candidate: 3 h podcast = 18 s per gap on a
        // desktop, minutes on a phone, with no progress and no Cancel)
        try (AutoCloseable pin = ClipAligner.pin(tr.segments, tr.words)) { return windowInPinned(tr, a, b, duration, minLen, target); }
        catch (RuntimeException re) { throw re; }
        catch (Exception e) { return windowInPinned(tr, a, b, duration, minLen, target); }
    }

    private static double[] windowInPinned(Transcript tr, double a, double b, double duration, double minLen, double target) {
        List<Transcript.Segment> segs = tr.segments;
        double[] best = null; double bestScore = -1e9;
        for (int i = 0; i < segs.size(); i++) {
            Transcript.Segment sg = segs.get(i);
            if (sg.start < a - 0.01) continue;
            if (sg.start + minLen > b) break;
            boolean sentenceStart = i == 0 || ClipAligner.endsSentence(segs.get(i - 1).text) || sg.start - segs.get(i - 1).end >= 0.7;
            double s = sg.start;
            double e;
            try { e = ClipAligner.endFor(segs, tr.words, s, Math.min(b, s + target), duration, minLen, false); }
            catch (Exception ex) { e = Math.min(b, s + target); }
            if (e > b || e - s < minLen) {
                // the end that fits: the last transcript line ending inside the gap
                e = -1;
                for (int j = i; j < segs.size() && segs.get(j).end <= b; j++) if (segs.get(j).end - s <= ClipAligner.MAX_LEN) e = segs.get(j).end;
                if (e < 0 || e - s < minLen) continue;
            }
            e = Math.min(e, Math.min(b, s + ClipAligner.MAX_LEN));
            if (e - s < minLen) continue;
            double[] w = {s, e};
            double sc = windowScore(tr, w) + (sentenceStart ? 6 : 0) - Math.abs((e - s) - target) / 10.0;
            if (sc > bestScore) { bestScore = sc; best = w; }
        }
        return best;
    }

    /** how good a stretch is as a short: a question opener, no filler opener, a complete ending, enough speech */
    static double windowScore(Transcript tr, double[] w) {
        String open = ClipAligner.openingLine(tr.segments, tr.words, w[0], w[1]);
        double sc = 0;
        if (ClipAligner.isQuestion(open)) sc += 10;
        if (ClipAligner.startsWithFiller(open)) sc -= 8;
        try { if (ClipAligner.endsOnThought(tr.segments, tr.words, w[1])) sc += 4; } catch (Exception ignored) {}
        int words = 0;
        for (Word x : tr.words) if (x.start >= w[0] && x.end <= w[1]) words++;
        if (tr.words.isEmpty()) for (Transcript.Segment g : tr.segments) if (g.start >= w[0] && g.end <= w[1]) words += g.text.split("\\s+").length;
        sc += Math.min(10, words / Math.max(1.0, w[1] - w[0]) * 3);   // speech density (talk beats silence / music)
        return sc;
    }

    static Project.Clip extraClip(Transcript tr, double[] w, int score, String reason, String title, boolean podcast) {
        Project.Clip c = new Project.Clip();
        c.start = w[0]; c.end = w[1];
        c.score = Math.max(1, Math.min(99, score));
        c.reason = reason == null ? "" : reason;
        c.title = title == null ? "" : title;
        c.hook = tr == null ? "" : cleanHook(ClipAligner.openingLine(tr.segments, tr.words, w[0], w[1]));
        c.minLen = Math.min(w[1] - w[0], podcast ? ClipAligner.POD_MIN_LEN : ClipAligner.MIN_LEN);
        return c;
    }

    /** code 29: no two shorts share more than a second (sorted by start; a later end-refinement overlap is cut back) */
    static boolean overlapsAny(List<Project.Clip> taken, double s, double e) {
        for (Project.Clip c : taken) if (Math.min(e, c.end) - Math.max(s, c.start) > 1.0) return true;
        return false;
    }

    /**
     * code 29: the ending pass can move an end past the start of the next short (same words in two shorts); such an end is
     * moved back to the last transcript line that ends before the next short, when that keeps the short long enough
     */
    static void trimOverlaps(List<Project.Clip> clips, Transcript tr) {
        List<Project.Clip> byStart = new ArrayList<>(clips);
        java.util.Collections.sort(byStart, (x, y) -> Double.compare(x.start, y.start));
        for (int i = 0; i + 1 < byStart.size(); i++) {
            Project.Clip a = byStart.get(i), b = byStart.get(i + 1);
            if (a.end - b.start <= 1.0) continue;
            double lim = b.start - TOPUP_MARGIN, sentence = -1, word = -1;
            if (tr != null) for (Word w : tr.words) {
                if (w.end > lim) break;
                if (w.end <= a.start) continue;
                word = w.end;
                if (w.sentenceEnd || ClipAligner.endsSentence(w.text)) sentence = w.end;
            }
            double cut = sentence - a.start >= TOPUP_MIN_LEN ? sentence : word - a.start >= TOPUP_MIN_LEN ? word : lim;
            if (cut - a.start >= TOPUP_MIN_LEN) { a.end = Math.min(lim, cut + 0.15); AppLog.i("Short at " + mmss(a.start) + " now ends at " + mmss(a.end) + " (the next short starts there)"); }
        }
    }

    /**
     * v11 ranking (OpusClip-style virality): the AI's sub-scores (hook 35 / standalone 20 / value 25 / payoff 20), plus a
     * clear bonus when the short opens on a question and pays it off, minus a penalty when it can't stand alone.
     */
    /** v19: first sentence, at most 15 words (no trailing fragment) */
    public static String cleanHook(String s) {
        if (s == null) return "";
        s = s.trim().replaceAll("\\s+", " ");
        for (int i = 0; i < s.length(); i++) {
            char ch = s.charAt(i);
            if ((ch == '.' || ch == '?' || ch == '!' || ch == '\u0964') && i >= 12) { s = s.substring(0, i + 1); break; }
        }
        String[] w = s.split(" ");
        if (w.length > 15) s = String.join(" ", java.util.Arrays.copyOf(w, 15)) + "\u2026";
        return s;
    }

    static int rankScore(AiClient.Candidate c, boolean qa) {
        double s = c.score;
        if (qa) s += c.payoffScore < 0 || c.payoffScore >= 65 ? 10 : 4;
        if (c.standaloneScore >= 0 && c.standaloneScore < 55) s -= 12;
        if (c.hookScore >= 0 && c.hookScore < 50) s -= 6;
        if (c.needsContext) s -= 25;
        return (int) Math.max(1, Math.min(99, Math.round(s)));
    }


    static final int PROFANITY_KEEP_MIN = 85, PROFANITY_MARGIN = 5, PROFANITY_PENALTY = 60;

    static void applyProfanityRule(List<Project.Clip> pool, java.util.Map<Project.Clip, AiClient.Candidate> profane) {
        if (profane.isEmpty()) return;
        int bestClean = 0, bestDirty = 0;
        Project.Clip top = null;
        for (Project.Clip c : pool) {
            if (profane.containsKey(c)) { if (c.score > bestDirty) { bestDirty = c.score; top = c; } }
            else bestClean = Math.max(bestClean, c.score);
        }
        for (Project.Clip c : pool) {
            if (!profane.containsKey(c)) continue;
            boolean keep = c == top && c.score >= PROFANITY_KEEP_MIN && c.score >= bestClean + PROFANITY_MARGIN;
            if (keep) {
                c.profane = true;
                AppLog.i("Kept a moment with gaali at " + mmss(c.start) + ": it is the most viral one (score " + c.score + ")");
            } else {
                c.profane = true;
                c.score = Math.max(1, c.score - PROFANITY_PENALTY);
            }
        }
    }

    static String reasonFor(AiClient.Candidate c, boolean qa) {
        String base = !c.title.isEmpty() ? c.title + (c.reason.isEmpty() ? "" : " — " + c.reason) : c.reason;
        return qa ? "Q&A • " + base : base;
    }

    /** v7: long videos give more shorts — about one per 6 minutes (never fewer than asked, max 15) */
    public static int shortsFor(int asked, double durationSec) {
        // v8: the number the user picked is the number made. 0 = Auto: about one per 6 minutes (1..15).
        if (asked > 0) return Math.min(MAX_SHORTS, asked);
        return Math.max(1, Math.min(MAX_SHORTS, (int) Math.floor(durationSec / 360.0)));
    }

    public static final int MAX_SHORTS = 5;   // v9: at most 5 shorts per video (Sakib)

    /** v7: Hindi and Urdu are one spoken language (Whisper only differs in script) */
    static String langFamily(String lang) {
        String c = Transcript.languageCode(lang);
        return c.equals("ur") ? "hi" : c;
    }

    /** {cutStart, cutLen, keepFrom, keepTo} per piece. Pieces overlap by OVERLAP_SEC; each owns its own range. */
    public static List<double[]> piecePlan(double dur) {
        List<double[]> out = new ArrayList<>();
        if (dur <= 240) { out.add(new double[]{0, dur + 1, 0, Double.MAX_VALUE}); return out; }
        // v17: 5-min parts while that is <= 12 requests; longer videos get longer parts (up to ~8 min of audio each)
        // so the whole video stays well under Groq's ~20 requests a minute (2.7: 24 parts + re-checks hit 429s)
        int n = (int) Math.max(2, Math.ceil(dur / PIECE_SEC));
        if (n > 12) n = (int) Math.min(MAX_PIECES, Math.max(12, Math.ceil(dur / (MAX_SENT_PIECE_SEC * speechTempo(dur) - 2 * OVERLAP_SEC))));
        double len = dur / n;
        for (int k = 0; k < n; k++) {
            double from = k * len, to = (k + 1) * len;
            double cs = Math.max(0, from - (k > 0 ? OVERLAP_SEC : 0));
            double ce = k == n - 1 ? dur + 1 : to + OVERLAP_SEC;
            out.add(new double[]{cs, ce - cs, k == 0 ? 0 : from, k == n - 1 ? Double.MAX_VALUE : to});
        }
        return out;
    }

    private Transcript transcribe(File src, String ext, double dur, File dir) throws Exception {
        List<double[]> plan = piecePlan(dur);
        int n = plan.size();
        final String lang = speechLang;
        step(1, 0, "Transcribing speech", n > 1 ? "Splitting speech into " + n + " parts…" : "Preparing speech…");
        final double tempo = speechTempo(dur);
        if (tempo > 1.0) AppLog.i(String.format(Locale.US, "Long video (%.0f min): speech sent at %.2fx speed to stay inside Groq's hourly audio limit", dur / 60, tempo));
        File[] pieces = new File[n];
        boolean direct = n == 1 && tempo == 1.0 && !src.getName().startsWith("source_") && src.length() <= MAX_GROQ_BYTES && src.length() > 2000;
        // v17 (2.8): 2.7 counted a part as "cut" only when an upload slot picked it up, and only 8 of 24 parts could be
        // in flight (each slot also waited for Groq's answer and for loop re-checks), so the bar showed "8 of 24 parts
        // cut" and ~200 KB/s while finished parts sat waiting. Now: fewer, longer parts (fewer requests against Groq's
        // per-minute limit), every part has its own upload slot, a part counts as cut the moment ffmpeg finishes it,
        // and cutting uses all cores (uploads are network, not CPU).
        final int cores = Runtime.getRuntime().availableProcessors();
        ExecutorService cutEx = direct ? null : Executors.newFixedThreadPool(Math.min(n, Math.max(3, cores)));
        final long tCut0 = System.currentTimeMillis();
        final AtomicLong sent = new AtomicLong();
        final AtomicLong cutBytes = new AtomicLong();
        final long[] perPiece = new long[n];
        final int[] done = {0}, cutDone = {0};
        final long[] tUp0 = {0};
        final long tb = Math.max(1, (long) (dur / tempo * FfmpegCommands.SPEECH_KBPS * 125));   // bitrate estimate until all parts exist
        List<Future<File>> cf = new ArrayList<>();
        if (direct) pieces[0] = src;    // short audio: send the downloaded file as is
        else {
            for (int k = 0; k < n; k++) {
                final int fk = k;
                final double[] pp = plan.get(k);
                cf.add(cutEx.submit(() -> {
                    File f = cutPiece(src, ext, pp[0], pp[1], dir, fk, tempo);
                    synchronized (perPiece) {
                        cutBytes.addAndGet(f.length());
                        cutDone[0]++;
                        if (cutDone[0] == n) AppLog.i(String.format(Locale.US, "Speech: %d part%s, %.1f MB (exact cuts in %.1f s, each sent as soon as it was cut)",
                                n, n > 1 ? "s" : "", cutBytes.get() / 1048576.0, (System.currentTimeMillis() - tCut0) / 1000.0));
                    }
                    return f;
                }));
            }
            cutEx.shutdown();
        }
        // 2) upload + transcribe in parallel, each part as soon as it is cut
        ExecutorService ex = Executors.newFixedThreadPool(Math.min(n, MAX_PARALLEL_UPLOADS));
        List<Future<Transcript>> fs = new ArrayList<>();
        for (int k = 0; k < n; k++) {
            final int fk = k;
            final double[] pp = plan.get(k);
            final double lenSec = Math.max(1, Math.min(pp[1], dur - pp[0]) / tempo);   // this part's audio length as sent
            fs.add(ex.submit(() -> {
                File piece = direct ? pieces[0] : get(cf.get(fk));
                pieces[fk] = piece;
                Transcript t = transcribeOne(piece, lenSec, lang, dir, fk, b -> {
                    synchronized (perPiece) {
                        if (tUp0[0] == 0) tUp0[0] = System.currentTimeMillis();
                        long delta = b - perPiece[fk];
                        perPiece[fk] = b;
                        long s = sent.addAndGet(delta);
                        long tot = cutDone[0] == n ? Math.max(1, cutBytes.get()) : Math.max(tb, cutBytes.get());
                        double up = Math.min(1.0, (double) s / tot);
                        double secs = Math.max(0.5, (System.currentTimeMillis() - tUp0[0]) / 1000.0);
                        if (up < 1.0) step(1, up * 0.7, "Uploading speech to AI", String.format(Locale.US, "%.1f of %.1f MB • %s • %d of %d parts ready",
                                s / 1048576.0, tot / 1048576.0, speed(s / secs), cutDone[0], n));
                        else step(1, 0.7, "AI is writing the transcript", done[0] + " of " + n + " parts done");
                    }
                });
                synchronized (perPiece) {
                    done[0]++;
                    step(1, 0.7 + 0.3 * done[0] / n, "AI is writing the transcript", done[0] + " of " + n + " parts done");
                }
                return t;
            }));
        }
        List<Transcript> parts = new ArrayList<>();
        List<Integer> missing = new ArrayList<>();
        Exception firstErr = null;
        try {
            for (int k = 0; k < n; k++) {
                try { parts.add(get(fs.get(k))); }
                catch (CancelToken.Cancelled c) { throw c; }
                catch (Exception e) {
                    // v17: one part that neither Groq nor Gemini could do no longer fails the whole job
                    if (fatalAiError(e)) throw e;
                    if (isNoSpace(e)) throw new Exception(NO_SPACE_MSG, e);   // code 32: every part would fail the same way
                    if (firstErr == null) firstErr = e;
                    parts.add(null);
                    missing.add(k);
                    double[] pp = plan.get(k);
                    AppLog.e("Part " + (k + 1) + " (" + mmss(pp[2]) + "–" + mmss(Math.min(pp[3], dur)) + ") could not be transcribed: " + AiClient.describe(e) + " — continuing without it");
                }
            }
        } finally {
            ex.shutdownNow();
            if (cutEx != null) cutEx.shutdownNow();
        }
        if (!direct) for (File f : pieces) if (f != null) f.delete();
        if (missing.size() == n) throw firstErr != null ? firstErr : new Exception("No speech found in this video (empty transcript)");
        if (tempo > 1.0) for (int k = 0; k < n; k++) if (parts.get(k) != null) parts.set(k, parts.get(k).scaled(tempo));
        double[] cs = new double[n], kf = new double[n], kt = new double[n];
        for (int k = 0; k < n; k++) { cs[k] = plan.get(k)[0]; kf[k] = plan.get(k)[2]; kt[k] = plan.get(k)[3]; }
        Transcript tr = Transcript.merge(parts, cs, kf, kt);
        if (lang != null && !lang.isEmpty()) tr.language = lang;   // v8: the language that was asked for, never a part's guess
        tr.punctuateWords();
        if (tr.segments.isEmpty()) throw firstErr != null ? firstErr : new Exception("No speech found in this video (empty transcript)");
        boolean gemGap = false;
        for (Transcript p : parts) if (p != null && p.partial) gemGap = true;
        if (!missing.isEmpty() || gemGap) {
            tr.partial = true;
            AppLog.e("Transcript has gaps (" + (missing.isEmpty() ? "a few Gemini pieces" : missing.size() + " of " + n + " parts")
                    + " missing) — shorts are picked from the rest; this transcript is not cached, so a retry later fills the gaps");
        }
        AppLog.i("Transcript: " + tr.segments.size() + " lines, " + tr.words.size() + " timed words (" + tr.language + ")");
        return tr;
    }

    static String speed(double bytesPerSec) {
        return bytesPerSec >= 1048576 ? String.format(Locale.US, "%.1f MB/s", bytesPerSec / 1048576.0) : String.format(Locale.US, "%.0f KB/s", bytesPerSec / 1024.0);
    }

    /** errors that no other part / provider can fix (bad key, blocked account, no internet): the job stops with them */
    static boolean fatalAiError(Throwable e) {
        String m = String.valueOf(e.getMessage());
        if (m.startsWith("Groq's free hourly audio limit")) return false;   // a gap for this part, never the whole job
        return m.contains("API key") || m.contains("blocked this key") || m.contains("isn't available") || m.contains("No internet");
    }

    /** v17: Groq's hourly audio limit: until this time new parts go to Gemini; after it, Groq is tried again (mixed) */
    private volatile long groqBackAt;

    /** 5.3: a key that can still be used (right format, not rejected recently by its provider) */
    private boolean keyUsable(KeyCheck.Service s) {
        String key = s == KeyCheck.Service.GEMINI ? Prefs.geminiKey(ctx) : Prefs.groqKey(ctx);
        boolean format = s == KeyCheck.Service.GEMINI ? Prefs.isValidGeminiKey(key) : Prefs.isValidGroqKey(key);
        return format && !KeyCheck.knownInvalid(s, key);
    }

    /**
     * 5.3: Groq rejected the key (401 / 403): this part (and every later one) is transcribed by Gemini instead of failing
     * the job, and the result says so in one line. Fails only when Gemini can't do it either, naming the bad key.
     */
    private Transcript groqKeyBadUseGemini(File piece, double lenSec, String lang, File dir, int k) throws Exception {
        try {
            Transcript t = geminiPart(piece, lenSec, lang, dir, k);
            String n = AiClient.GROQ_FALLBACK_NOTE;
            boolean first;
            synchronized (warnings) { first = !warnings.contains(n); if (first) warnings.add(n); }
            if (first) AppLog.i(n);
            return t;
        } catch (CancelToken.Cancelled c) {
            throw c;
        } catch (AiClient.KeyRejected kr) {
            if (kr.service == KeyCheck.Service.GEMINI) throw AiClient.bothRejected(kr);
            throw kr;
        } catch (Exception ge) {
            throw new AiClient.ApiException("Your Groq API key isn't valid and Gemini couldn't transcribe this part either ("
                    + KeyCheck.sentence(AiClient.describe(ge)).replaceAll("\\.$", "") + "). Update your Groq key in Settings.");
        }
    }

    private Transcript transcribeOne(File piece, double lenSec, String lang, File dir, int k, java.util.function.LongConsumer up) throws Exception {
        if (!keyUsable(KeyCheck.Service.GROQ) && Prefs.isValidGroqKey(Prefs.groqKey(ctx)) && keyUsable(KeyCheck.Service.GEMINI))
            return groqKeyBadUseGemini(piece, lenSec, lang, dir, k);
        if (groqOut && System.currentTimeMillis() < groqBackAt && Prefs.isValidGeminiKey(Prefs.geminiKey(ctx)))
            return geminiOrGroq(piece, lenSec, lang, dir, k, up);
        try {
            Transcript t = new AiClient(ctx, ct).transcribe(piece, lang, up);
            if ("hi".equals(lang) && mostlyLatin(t)) {
                // v8: an English text for Hindi speech is a translation, not a transcript: ask once more
                AppLog.i("Part " + (k + 1) + " came back in English letters for Hindi speech, asking again");
                try {
                    Transcript t2 = new AiClient(ctx, ct).transcribe(piece, lang, null, 0.2);
                    if (!mostlyLatin(t2)) t = t2;
                } catch (CancelToken.Cancelled c) { throw c; } catch (AiClient.GroqExhausted g) { noteGroqOut(g); } catch (Exception ignored) {}
            }
            if (t.looping()) t = recheckLoops(t, piece, lenSec, lang, dir, k);
            return t;
        } catch (AiClient.GroqExhausted gx) {
            noteGroqOut(gx);
            if (!Prefs.isValidGeminiKey(Prefs.geminiKey(ctx))) {
                if (gx.waitMs > GROQ_WAIT_MAX_MS) throw gx;
                AppLog.i("Part " + (k + 1) + ": waiting " + (gx.waitMs / 1000) + " s for Groq's limit (no Gemini key for a backup)");
                pause(gx.waitMs);
                return transcribeOrNull(piece, lang, up);
            }
            return geminiOrGroq(piece, lenSec, lang, dir, k, up);
        } catch (AiClient.KeyRejected kr) {
            if (kr.service != KeyCheck.Service.GROQ || !keyUsable(KeyCheck.Service.GEMINI)) throw kr;
            AppLog.i("Part " + (k + 1) + ": Groq rejected your key, transcribing with Gemini instead");
            return groqKeyBadUseGemini(piece, lenSec, lang, dir, k);
        } catch (AiClient.TooLargeException big) {
            // Groq refused the size: re-encode this piece as small mono speech and try once more
            AppLog.i("Part " + (k + 1) + " refused as too large (" + (piece.length() / 1048576) + " MB), compressing and retrying");
            File small = new File(dir, "speech_small_" + k + ".ogg");
            MediaRunner.run(FfmpegCommands.speechAudioOpus(piece.getAbsolutePath(), small.getAbsolutePath()), 0, null, ct);
            try { return transcribeOrNull(small, lang, up); } finally { small.delete(); }
        } catch (Exception e) {
            String m = e.getMessage() == null ? "" : e.getMessage();
            if (m.startsWith("No speech found")) return null;   // silent piece (music/intro): not fatal
            throw e;
        }
    }

    /** a limit that frees up within this long is waited for when Gemini can't do a part */
    static final long GROQ_WAIT_MAX_MS = 150_000;

    private void noteGroqOut(AiClient.GroqExhausted gx) {
        long back = System.currentTimeMillis() + Math.max(30_000, gx.waitMs);
        synchronized (this) {
            boolean first = !groqOut || System.currentTimeMillis() >= groqBackAt;
            groqOut = true;
            groqBackAt = Math.max(groqBackAt, back);
            if (first) AppLog.i(gx.getMessage() + " — parts go to Gemini until then, and back to Groq after (" + (Prefs.isValidGeminiKey(Prefs.geminiKey(ctx)) ? "mixed" : "no Gemini key") + ")");
        }
    }

    private void pause(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) { ct.check(); try { Thread.sleep(250); } catch (InterruptedException ie) { throw new CancelToken.Cancelled(); } }
    }

    /** v17: Gemini first while Groq's hour is used up; if Gemini fails and Groq frees up soon, Groq does this part */
    private Transcript geminiOrGroq(File piece, double lenSec, String lang, File dir, int k, java.util.function.LongConsumer up) throws Exception {
        try {
            return geminiPart(piece, lenSec, lang, dir, k);
        } catch (CancelToken.Cancelled c) {
            throw c;
        } catch (Exception ge) {
            if (fatalAiError(ge) && !String.valueOf(ge.getMessage()).contains("Gemini")) throw ge;
            long wait = Math.max(0, groqBackAt - System.currentTimeMillis());
            if (wait > GROQ_WAIT_MAX_MS) throw new Exception("Groq's free hourly audio limit is used up and Gemini failed too (" + AiClient.describe(ge) + ")");
            AppLog.i("Part " + (k + 1) + ": Gemini failed (" + AiClient.describe(ge) + "), Groq's limit frees up in " + (wait / 1000) + " s — using Groq for it");
            pause(wait);
            try { return transcribeOrNull(piece, lang, up); }
            catch (AiClient.GroqExhausted g2) {
                noteGroqOut(g2);
                if (g2.waitMs > GROQ_WAIT_MAX_MS) throw new Exception("Groq's free hourly audio limit is used up and Gemini failed too (" + AiClient.describe(ge) + ")");
                pause(g2.waitMs);
                return transcribeOrNull(piece, lang, up);
            }
        }
    }

    /** v17: Gemini gets short pieces (2.7 sent a whole 4.6-min part; its long answer came back cut off and failed the job) */
    static final double GEMINI_CHUNK_SEC = 150;
    static final double GEMINI_OVERLAP_SEC = 3;
    /** Gemini's free tier allows ~15 requests a minute: at most this many audio requests at once (whole app) */
    private static final java.util.concurrent.Semaphore GEMINI_SLOTS = new java.util.concurrent.Semaphore(4);

    /** {cutStart, cutLen, keepFrom, keepTo} chunks of a part of {@code len} seconds for Gemini */
    public static List<double[]> geminiChunks(double len) {
        List<double[]> out = new ArrayList<>();
        int m = Math.max(1, (int) Math.ceil(len / GEMINI_CHUNK_SEC));
        double cl = len / m;
        for (int j = 0; j < m; j++) {
            double from = j * cl, to = (j + 1) * cl;
            double cs = Math.max(0, from - (j > 0 ? GEMINI_OVERLAP_SEC : 0));
            double ce = j == m - 1 ? len + 1 : to + GEMINI_OVERLAP_SEC;
            out.add(new double[]{cs, ce - cs, j == 0 ? 0 : from, j == m - 1 ? Double.MAX_VALUE : to});
        }
        return out;
    }

    private Transcript geminiPart(File piece, double lenSec, String lang, File dir, int k) throws Exception {
        if (!Prefs.isValidGeminiKey(Prefs.geminiKey(ctx))) throw new Exception("no Gemini key in Settings");
        List<double[]> plan = geminiChunks(lenSec);
        int m = plan.size();
        long t0 = System.currentTimeMillis();
        ExecutorService ex = Executors.newFixedThreadPool(m);
        List<Future<Transcript>> fs = new ArrayList<>();
        for (int j = 0; j < m; j++) {
            final int fj = j;
            final double[] cp = plan.get(j);
            fs.add(ex.submit(() -> geminiChunk(piece, cp[0], Math.min(cp[1], lenSec - cp[0] + 1), lang, dir, k, fj)));
        }
        List<Transcript> parts = new ArrayList<>();
        int failed = 0;
        Exception last = null;
        try {
            for (Future<Transcript> f : fs) {
                try { parts.add(get(f)); }
                catch (CancelToken.Cancelled c) { throw c; }
                catch (Exception e) { parts.add(null); failed++; last = e; }
            }
        } finally { ex.shutdownNow(); }
        if (failed == m) throw last;
        double[] cs = new double[m], kf = new double[m], kt = new double[m];
        for (int j = 0; j < m; j++) { cs[j] = plan.get(j)[0]; kf[j] = plan.get(j)[2]; kt[j] = plan.get(j)[3]; }
        Transcript t = Transcript.merge(parts, cs, kf, kt);
        t.language = lang == null ? "" : lang;
        t.partial = failed > 0;
        AppLog.i(String.format(Locale.US, "Part %d via Gemini: %d lines from %d piece%s in %.1f s%s", k + 1, t.segments.size(), m, m > 1 ? "s" : "",
                (System.currentTimeMillis() - t0) / 1000.0, failed > 0 ? " (" + failed + " piece" + (failed > 1 ? "s" : "") + " failed: " + AiClient.describe(last) + ")" : ""));
        if (t.segments.isEmpty()) throw new Exception("Gemini returned an empty transcript");
        return t;
    }

    private Transcript geminiChunk(File piece, double from, double len, String lang, File dir, int k, int j) throws Exception {
        File c = new File(dir, "speech_gem_" + k + "_" + j + ".ogg");
        try {
            MediaRunner.run(FfmpegCommands.cutAudioOpus(piece.getAbsolutePath(), from, len, c.getAbsolutePath(), 1.0), len, null, ct);
            Transcript t = askGemini(c, lang, len);
            double covered = t.lastEnd();
            if (t.salvaged || covered < len - 25) {
                // the answer stopped early (cut off / malformed): ask again for only the missing tail
                double tailFrom = Math.max(0, covered - 1.5);
                if (len - tailFrom > 8) {
                    AppLog.i(String.format(Locale.US, "Part %d: Gemini's answer stopped at %s of %s, asking again for the rest", k + 1, mmss(covered), mmss(len)));
                    File tail = new File(dir, "speech_gem_" + k + "_" + j + "_tail.ogg");
                    try {
                        MediaRunner.run(FfmpegCommands.cutAudioOpus(c.getAbsolutePath(), tailFrom, len - tailFrom + 1, tail.getAbsolutePath(), 1.0), 0, null, ct);
                        Transcript t2 = askGemini(tail, lang, len - tailFrom);
                        t.spliceSpan(t2, tailFrom, covered, len + 2);
                    } catch (CancelToken.Cancelled cc) { throw cc; }
                    catch (Exception e) { AppLog.i("Part " + (k + 1) + ": the rest could not be re-asked (" + AiClient.describe(e) + "), keeping what came back"); }
                    finally { tail.delete(); }
                }
            }
            return t;
        } finally { c.delete(); }
    }

    private Transcript askGemini(File f, String lang, double len) throws Exception {
        while (!GEMINI_SLOTS.tryAcquire(250, java.util.concurrent.TimeUnit.MILLISECONDS)) ct.check();
        try { return new AiClient(ctx, ct).transcribeGemini(f, lang, len); }
        finally { GEMINI_SLOTS.release(); }
    }

    /**
     * v17: Whisper looped on a phrase: re-check ONLY the looped span (+4 s each side), once, at temperature 0.3, and put
     * that answer into the gap. 2.7 re-sent the whole part twice in parallel and cancelled the loser: that used ~2x the
     * part's audio from Groq's hourly limit for every looping part (his 130-min log hit the limit) and the cancelled
     * upload was logged as "Groq network error: InterruptedIOException".
     */
    private Transcript recheckLoops(Transcript t, File piece, double lenSec, String lang, File dir, int k) throws Exception {
        List<double[]> spans = t.loopSpans(20);
        int r = 0;
        for (double[] sp : spans) {
            if (r++ >= 3) break;
            double a = Math.max(0, sp[0] - 4), b = Math.min(lenSec + 1, sp[1] + 4);
            if (b - a < 15) { b = Math.min(lenSec + 1, a + 15); a = Math.max(0, b - 15); }
            boolean whole = b - a > lenSec * 0.6;
            AppLog.i(String.format(Locale.US, "Part %d: speech recognizer repeated itself at %s–%s, re-checking %s", k + 1, mmss(sp[0]), mmss(sp[1]),
                    whole ? "that part" : "only those " + Math.round(b - a) + " s"));
            File sub = whole ? piece : new File(dir, "speech_loop_" + k + "_" + r + ".ogg");
            try {
                if (!whole) MediaRunner.run(FfmpegCommands.cutAudioOpus(piece.getAbsolutePath(), a, b - a, sub.getAbsolutePath(), 1.0), 0, null, ct);
                Transcript t2 = new AiClient(ctx, ct).transcribe(sub, lang, null, 0.3);
                if (whole) { if (!t2.looping() || t2.words.size() > t.words.size()) t = t2; break; }
                // keep the original's edges (the re-check may clip its own first/last second)
                t.spliceSpan(t2, a, a > 0 ? a + 1.5 : 0, b < lenSec ? b - 1.5 : lenSec + 2);
            } catch (CancelToken.Cancelled c) {
                throw c;
            } catch (AiClient.GroqExhausted g) {
                noteGroqOut(g);
                AppLog.i("Part " + (k + 1) + ": kept without the repeated words (Groq's hourly limit)");
                break;
            } catch (Exception e) {
                String m = String.valueOf(e.getMessage());
                if (!m.startsWith("No speech found")) AppLog.i("Part " + (k + 1) + ": re-check failed (" + AiClient.describe(e) + "), kept without the repeated words");
            } finally { if (!whole) sub.delete(); }
        }
        return t;
    }

    public File transcriptCache(Project pr) {
        try {
            String key;
            if (!pr.isLocal()) {
                String id = YtEngine.videoId(pr.url);
                if (id == null || id.isEmpty()) return null;
                key = "yt_" + id;
                // code 28: a dub has its own transcript (the original's words are not the Hindi dub's words)
                if (pr.audioLang != null && !pr.audioLang.isEmpty()) key += "_a" + pr.audioLang.replaceAll("[^A-Za-z0-9-]", "");
            } else {
                File f = new File(pr.localPath);
                if (!f.exists()) return null;
                key = "local_" + Integer.toHexString((pr.title + "|" + f.length()).hashCode());
            }
            File d = new File(ctx.getFilesDir(), "tcache");
            d.mkdirs();
            dropLegacyCache(d);
            return new File(d, cacheName(key, speechLang));
        } catch (Exception e) { return null; }
    }

    /** v8: transcript-mode version, part of every transcript cache name */
    static final String TRANSCRIPT_MODE = "t8";

    public static String cacheName(String videoKey, String lang) {
        return videoKey + "_" + TRANSCRIPT_MODE + "_" + (lang == null || lang.isEmpty() ? "xx" : lang) + ".json";
    }

    /** v8: v7 cache files (auto language: English translations of Hindi speech) are deleted, never read */
    public static int dropLegacyCache(File dir) {
        File[] fs = dir == null ? null : dir.listFiles();
        int n = 0;
        if (fs != null) for (File x : fs) if (!x.getName().contains("_" + TRANSCRIPT_MODE + "_") && x.delete()) n++;
        return n;
    }

    static void pruneCache(File dir, int keep) {
        File[] fs = dir == null ? null : dir.listFiles();
        if (fs == null || fs.length <= keep) return;
        java.util.Arrays.sort(fs, (a, b) -> Long.compare(b.lastModified(), a.lastModified()));
        for (int i = keep; i < fs.length; i++) fs[i].delete();
    }

    /** v8: the one spoken language this job's speech is transcribed in ("hi", "en", ...) */
    private volatile String speechLang = "";
    private String metaLang = "";
    /** code 31: the video's description (first 600 characters: its script is a language hint for a Latin title) */
    private String metaDesc = "";

    /**
     * v8: the spoken language, decided once per job. v7 let Whisper auto-detect every part, and on Sakib's phone all 8
     * parts of a Hindi interview came back labelled "English" — Whisper had translated them, so captions were English
     * text with made-up timing. Rules (pure, unit-tested):
     *  1. the video's own language says Hindi/Urdu, or the title is in Devanagari / says Hindi -> "hi"
     *  2. any detection sample heard Hindi/Urdu -> "hi"
     *  3. Hinglish or हिंदी captions were chosen -> "hi" (those captions are the spoken Hindi words)
     *  4. otherwise the samples' majority, else the video's language, else "en".
     */
    public static String decideSpeechLanguage(String script, String title, String metaLanguage, List<String> samples) {
        return decideSpeechLanguage(script, title, "", "", metaLanguage, samples);
    }

    /**
     * code 31 (4.9): his HONOR report: a Bengali song (title in Bengali script, "Bangla New Sad Song") was transcribed as
     * English ("heard [en, en, en]"): the rules only knew Hindi, and the three 12 s samples landed on music, which Whisper
     * labels English. Now, before the samples:
     *  - YouTube's own audio language (yt-dlp "language", or the dub picked) when it names a language other than English;
     *  - the script of the title (or, for a Latin title, the description): Bengali -> bn, Gurmukhi -> pa, Gujarati -> gu,
     *    Odia -> or, Tamil -> ta, Telugu -> te, Kannada -> kn, Malayalam -> ml, Sinhala, Thai, Korean, Japanese, Chinese,
     *    Arabic, Hebrew, Greek... (Devanagari stays Hindi, as before);
     *  - a title that names the language ("Bangla song", "Tamil movie") when the samples heard only English and the video
     *    looks like music (Whisper hears English in music) or no sample was heard.
     * hintText = channel / categories / tags (music detection).
     */
    public static String decideSpeechLanguage(String script, String title, String description, String hintText, String metaLanguage, List<String> samples) {
        String meta = whisperOnly(langFamily(metaLanguage));
        if (meta.equals("hi")) return "hi";
        if (titleSaysHindi(title)) return "hi";
        String scriptLang = scriptLanguage(title);
        if (scriptLang.isEmpty() && latinOnly(title)) scriptLang = scriptLanguage(description);
        // code 32: a language the speech model can't transcribe (Odia "or": Groq answers 400) never decides - the samples do
        if (!scriptLang.equals("ur")) scriptLang = whisperOnly(scriptLang);
        // code 32: Urdu letters (ٹ ڈ ڑ ں ھ ہ ے) = the Hindi family, like an Urdu-speaking video always was
        if (scriptLang.equals("ur")) return "hi";
        if (!scriptLang.isEmpty() && !scriptLang.equals("hi")) {
            if (!meta.isEmpty() && !meta.equals("en")) return meta;
            if (!ambiguousScript(scriptLang)) return scriptLang;
            // code 32: Arabic letters (Arabic / Persian / Pashto...), Cyrillic (Russian / Ukrainian / Kazakh...) and Han
            // characters (Chinese / Cantonese) name no single language: the samples decide when they heard one other than
            // English (4.9 forced ar / ru / zh and skipped the samples), else the script's main language
            String heard = majority(samples);
            if (heard != null && !heard.equals("en")) return heard;
            return scriptLang;
        }
        if (!meta.isEmpty() && !meta.equals("en")) return meta;
        if (samples != null) for (String l : samples) if (langFamily(l).equals("hi")) return "hi";
        if (CaptionScript.HINGLISH.equals(script) || CaptionScript.HINDI.equals(script)) return "hi";
        String named = whisperOnly(namedLanguage(title));
        Map<String, Integer> votes = new HashMap<>();
        String maj = null; int best = 0;
        if (samples != null) for (String l : samples) {
            String c = langFamily(l);
            if (c.isEmpty()) continue;
            int v = votes.merge(c, 1, Integer::sum);
            if (v > best) { best = v; maj = c; }
        }
        if (!named.isEmpty() && !named.equals("en") && (maj == null || (maj.equals("en") && looksLikeMusic(title, hintText)))) return named;
        if (maj != null) return maj;
        if (!named.isEmpty()) return named;
        return meta.isEmpty() ? "en" : meta;
    }

    /** the language of the dominant non-Latin script in the text (at least 3 letters of it); "" = Latin / none */
    public static String scriptLanguage(String text) {
        if (text == null || text.isEmpty()) return "";
        Map<String, Integer> n = new HashMap<>();
        int latin = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            i += Character.charCount(cp);
            if (!Character.isLetter(cp) && Character.getType(cp) != Character.NON_SPACING_MARK && Character.getType(cp) != Character.COMBINING_SPACING_MARK) continue;
            String l = scriptOf(cp);
            if (l == null) continue;
            if (l.equals("latin")) { latin++; continue; }
            n.merge(l, 1, Integer::sum);
        }
        // code 32: any Urdu-only letter makes the Arabic-script letters Urdu; any kana makes the kanji Japanese
        if (n.containsKey("ur")) { n.merge("ur", n.getOrDefault("ar", 0), Integer::sum); n.remove("ar"); }
        if (n.containsKey("ja")) { n.merge("ja", n.getOrDefault("zh", 0), Integer::sum); n.remove("zh"); }
        String best = ""; int bn = 0;
        for (Map.Entry<String, Integer> e : n.entrySet()) if (e.getValue() > bn) { bn = e.getValue(); best = e.getKey(); }
        return bn >= 3 ? best : "";
    }

    /** code 32: the code when Whisper can transcribe that language, else "" */
    static String whisperOnly(String code) { return code == null || code.isEmpty() || Transcript.whisperKnows(code) ? (code == null ? "" : code) : ""; }

    /** code 32: a script shared by several spoken languages (decides only together with the samples) */
    static boolean ambiguousScript(String l) { return "ar".equals(l) || "ru".equals(l) || "zh".equals(l); }

    /** code 32: the most heard language family of the samples (null = none) */
    static String majority(List<String> samples) {
        if (samples == null) return null;
        Map<String, Integer> votes = new HashMap<>();
        String maj = null; int best = 0;
        for (String l : samples) {
            String c = langFamily(l);
            if (c.isEmpty()) continue;
            int v = votes.merge(c, 1, Integer::sum);
            if (v > best) { best = v; maj = c; }
        }
        return maj;
    }

    static boolean latinOnly(String text) { return scriptLanguage(text).isEmpty(); }

    /** the language a letter's script stands for (Whisper codes); "latin"; null = not a letter we know */
    static String scriptOf(int c) {
        if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z') || (c >= 0xC0 && c <= 0x24F)) return "latin";
        if (c >= 0x0900 && c <= 0x097F) return "hi";
        if (c >= 0x0980 && c <= 0x09FF) return "bn";
        if (c >= 0x0A00 && c <= 0x0A7F) return "pa";
        if (c >= 0x0A80 && c <= 0x0AFF) return "gu";
        if (c >= 0x0B00 && c <= 0x0B7F) return "or";
        if (c >= 0x0B80 && c <= 0x0BFF) return "ta";
        if (c >= 0x0C00 && c <= 0x0C7F) return "te";
        if (c >= 0x0C80 && c <= 0x0CFF) return "kn";
        if (c >= 0x0D00 && c <= 0x0D7F) return "ml";
        if (c >= 0x0D80 && c <= 0x0DFF) return "si";
        if (c >= 0x0E00 && c <= 0x0E7F) return "th";
        if (c == 0x0679 || c == 0x0688 || c == 0x0691 || c == 0x06BA || c == 0x06BE || c == 0x06C1 || c == 0x06C3 || c == 0x06D2 || c == 0x06D3) return "ur";
        if (c >= 0x0600 && c <= 0x06FF || c >= 0x0750 && c <= 0x077F) return "ar";
        if (c >= 0x0590 && c <= 0x05FF) return "he";
        if (c >= 0x0370 && c <= 0x03FF) return "el";
        if (c >= 0x0400 && c <= 0x04FF) return "ru";
        if (c >= 0xAC00 && c <= 0xD7AF || c >= 0x1100 && c <= 0x11FF) return "ko";
        if (c >= 0x3040 && c <= 0x30FF) return "ja";
        if (c >= 0x4E00 && c <= 0x9FFF) return "zh";
        return null;
    }

    /** a language named in the title ("Bangla New Sad Song", "Tamil movie scene"); "" = none */
    static String namedLanguage(String title) {
        if (title == null) return "";
        String t = " " + title.toLowerCase(Locale.US).replaceAll("[^a-z]+", " ") + " ";
        String[][] m = {{"bangla", "bn"}, {"bengali", "bn"}, {"tamil", "ta"}, {"telugu", "te"}, {"punjabi", "pa"}, {"marathi", "mr"},
                {"gujarati", "gu"}, {"kannada", "kn"}, {"malayalam", "ml"}, {"odia", "or"}, {"oriya", "or"}, {"nepali", "ne"},
                {"bhojpuri", "hi"}, {"haryanvi", "hi"}, {"urdu", "hi"}, {"assamese", "as"}, {"sinhala", "si"},
                {"spanish", "es"}, {"espanol", "es"}, {"arabic", "ar"}, {"korean", "ko"}, {"japanese", "ja"}};
        for (String[] p : m) if (t.contains(" " + p[0] + " ")) return p[1];
        return "";
    }

    /** a song / music video (title words in several languages, or YouTube's Music category / tags) */
    public static boolean looksLikeMusic(String title, String hintText) {
        String t = ((title == null ? "" : title) + " ").toLowerCase(Locale.ROOT);
        String h = hintText == null ? "" : hintText.toLowerCase(Locale.ROOT);
        if (h.contains("'music'") || h.contains("\"music\"") || h.matches(".*\\bmusic\\b.*")) return true;
        return t.matches(".*\\b(song|songs|lyrics?|lyrical|music video|official video|official audio|audio song|full song|lofi|lo-fi|remix|cover|gaan|gana|gaana|geet|naat|qawwali|bhajan)\\b.*")
                || t.contains("গান") || t.contains("गाना") || t.contains("गीत") || t.contains("பாடல்") || t.contains("పాట");
    }

    /** why the samples are not needed: "title in bn script" / "video language bn"; "" = take samples */
    static String strongHint(String title, String description, String metaLanguage) {
        String meta = whisperOnly(langFamily(metaLanguage));
        String sl = scriptLanguage(title);
        if (!sl.equals("ur")) sl = whisperOnly(sl);
        boolean fromDesc = false;
        if (sl.isEmpty() && latinOnly(title)) { sl = scriptLanguage(description); fromDesc = true; if (!sl.equals("ur")) sl = whisperOnly(sl); }
        if (!meta.isEmpty() && !meta.equals("en") && !meta.equals("hi") && !sl.isEmpty()) return "video language " + meta;
        // code 32: Urdu (decided as Hindi before this) and the shared scripts take the samples
        if (!sl.isEmpty() && !sl.equals("hi") && !sl.equals("ur") && !ambiguousScript(sl)) return (fromDesc ? "description in " : "title in ") + sl + " script";
        if (!meta.isEmpty() && !meta.equals("en") && !meta.equals("hi")) return "video language " + meta;
        return "";
    }

    static boolean titleSaysHindi(String title) {
        if (title == null) return false;
        for (int i = 0; i < title.length(); i++) { char c = title.charAt(i); if (c >= 0x0900 && c <= 0x097F) return true; }
        String t = title.toLowerCase(Locale.US);
        return t.matches(".*\\b(hindi|hinglish)\\b.*");
    }

    /** true when the words are almost all Latin script (an English text) */
    static boolean mostlyLatin(Transcript t) {
        if (t == null || t.words.size() < 20) return false;
        int latin = 0, indic = 0;
        for (com.viral.shortsmaker.captions.Word w : t.words) for (int i = 0; i < w.text.length(); i++) {
            char c = w.text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
            else if ((c >= 0x0900 && c <= 0x097F) || (c >= 0x0600 && c <= 0x06FF)) indic++;
        }
        return latin > 0 && indic < (latin + indic) * 0.1;
    }

    private String chooseSpeechLanguage(Request rq, Project pr, File src, String ext, File dir) {
        String script = rq.style == null ? null : rq.script;    // no captions: nothing was chosen
        String quick = decideSpeechLanguage(script, pr.title, metaDesc, metaHint, metaLang, null);
        List<String> samples = new ArrayList<>();
        // code 31: a title in another script (Bengali...) or YouTube's own non-English language settles it like Hindi does
        String strong = strongHint(pr.title, metaDesc, metaLang);
        // samples only when nothing above settled it (English captions or no captions): 3 short pieces, large-v3
        if (!quick.equals("hi") && strong.isEmpty()) {
            try { samples = detectSamples(src, ext, pr.duration, dir); } catch (CancelToken.Cancelled c) { throw c; } catch (Exception e) {
                AppLog.i("Language check skipped (" + AiClient.describe(e) + ")");
            }
        }
        String lang = decideSpeechLanguage(script, pr.title, metaDesc, metaHint, metaLang, samples);
        String why = langFamily(metaLang).equals("hi") ? "video language" : titleSaysHindi(pr.title) ? "title"
                : !strong.isEmpty() ? strong
                : !samples.isEmpty() ? "heard " + samples + (!namedLanguage(pr.title).isEmpty() && lang.equals(namedLanguage(pr.title)) && !samples.contains(lang) ? ", title says " + lang : "") : script != null && lang.equals("hi") ? "you chose " + CaptionScript.label(script) + " captions" : "default";
        AppLog.i("Speech language: " + lang + " (" + why + ") — every part is transcribed as " + lang + ", never translated");
        return lang;
    }

    /** three ~12 s samples (25/50/75 %) cut without re-encoding, language-checked at the same time */
    private List<String> detectSamples(File src, String ext, double dur, File dir) throws Exception {
        List<String> out = java.util.Collections.synchronizedList(new ArrayList<>());
        double[] at = dur > 60 ? new double[]{dur * 0.25, dur * 0.5, dur * 0.75} : new double[]{0};
        ExecutorService ex = Executors.newFixedThreadPool(at.length);
        List<Future<?>> fs = new ArrayList<>();
        for (int k = 0; k < at.length; k++) {
            final int fk = k;
            final double from = at[k];
            fs.add(ex.submit(() -> {
                File piece = cutPiece(src, ext, from, Math.min(12, Math.max(1, dur - from)), dir, 90 + fk, 1.0);
                try {
                    String l = new AiClient(ctx, ct).detectLanguage(piece);
                    if (l != null && !l.isEmpty()) out.add(Transcript.languageCode(l));
                } finally { piece.delete(); }
                return null;
            }));
        }
        try { for (Future<?> f : fs) { try { f.get(); } catch (java.util.concurrent.ExecutionException ee) { if (ee.getCause() instanceof CancelToken.Cancelled) throw (CancelToken.Cancelled) ee.getCause(); } } }
        finally { ex.shutdownNow(); }
        return new ArrayList<>(out);
    }

    /** v7: set once Groq's hourly audio quota is used up; the remaining parts go to Gemini */
    private volatile boolean groqOut;

    private Transcript transcribeOrNull(File f, String lang, java.util.function.LongConsumer up) throws Exception {
        try { return new AiClient(ctx, ct).transcribe(f, lang, up); }
        catch (Exception e) { if (String.valueOf(e.getMessage()).startsWith("No speech found")) return null; throw e; }
    }

    private static <T> T get(Future<T> f) throws Exception {
        try { return f.get(); }
        catch (java.util.concurrent.ExecutionException e) {
            Throwable c = e.getCause();
            if (c instanceof Exception) throw (Exception) c;
            throw new Exception(c);
        }
    }

    /**
     * v9: off. A stream-copied piece starts at the packet/fragment before the cut (ffmpeg keeps that pre-roll when
     * copying), so the recognizer's 0 s is not the planned cut time and every word of that part shifts. Pieces are now
     * decoded with an accurate seek and encoded as small Opus speech, so 0 s of a piece is exactly its cut time.
     */
    static final boolean COPY_PIECES = false;

    private File cutPiece(File src, String ext, double start, double len, File dir, int k, double tempo) throws Exception {
        ct.check();
        if (tempo == 1.0 && COPY_PIECES) {
            File out = new File(dir, "speech_" + k + ext);
            try {
                MediaRunner.run(FfmpegCommands.cutAudioCopy(src.getAbsolutePath(), start, len, out.getAbsolutePath()), 0, null, ct);
                if (out.length() > 2000 && out.length() <= MAX_GROQ_BYTES) return out;
                AppLog.i("Speech part " + (k + 1) + (out.length() > MAX_GROQ_BYTES ? " too big" : " empty") + " as a copy, compressing it");
            } catch (CancelToken.Cancelled c) {
                throw c;
            } catch (Exception e) {
                AppLog.i("Speech copy not possible (" + e.getMessage() + "), compressing instead");
            }
            out.delete();
        }
        File opus = new File(dir, "speech_" + k + ".ogg");
        try {
            MediaRunner.run(FfmpegCommands.cutAudioOpus(src.getAbsolutePath(), start, len, opus.getAbsolutePath(), tempo), len, null, ct);
            if (opus.length() < 500) throw new Exception("empty opus piece");
        } catch (CancelToken.Cancelled c) {
            throw c;
        } catch (Exception e) {
            opus.delete();
            File m4a = new File(dir, "speech_" + k + ".m4a");
            try {
                MediaRunner.run(FfmpegCommands.cutAudioAac(src.getAbsolutePath(), start, len, m4a.getAbsolutePath(), tempo), len, null, ct);
                return m4a;
            } catch (CancelToken.Cancelled c) {
                throw c;
            } catch (Exception e2) {
                m4a.delete();
                // v20 (3.1): damaged audio bytes - decode again skipping the damaged packets (3.0 gave up here on every part)
                try {
                    MediaRunner.run(FfmpegCommands.tolerant(FfmpegCommands.cutAudioOpus(src.getAbsolutePath(), start, len, opus.getAbsolutePath(), tempo)), len, null, ct);
                    if (opus.length() < 500) throw new Exception("empty piece");
                    AppLog.i("Speech part " + (k + 1) + ": some damaged audio was skipped");
                    return opus;
                } catch (CancelToken.Cancelled c) {
                    throw c;
                } catch (Exception e3) {
                    opus.delete();
                    // code 32: a full phone is not "damaged audio" (4.9 dropped the cause, so NO_SPACE_MSG never showed)
                    if (isNoSpace(e3) || isNoSpace(e2)) throw new Exception(NO_SPACE_MSG, e3);
                    throw new Exception("the downloaded audio is damaged in this part", e3);
                }
            }
        }
        return opus;
    }

    /**
     * v18 (2.9): captions = 1 ad for ONE video. The credit is used the first time this video's shorts are really
     * rendered with captions; after that the same video's shorts can be re-styled in Caption Studio for free.
     */
    /** v18: which video this is (a retry of the same video keeps its ad unlock) */
    static String srcKey(Project pr) {
        if (pr == null) return "";
        if (pr.url != null && !pr.url.isEmpty()) { String id = YtEngine.videoId(pr.url); return id.isEmpty() ? pr.url : "yt:" + id; }
        return pr.title == null ? "" : "local:" + pr.title + ":" + Math.round(pr.duration);
    }

    public boolean captionsAllowedForTest(Project pr, CaptionStyle style) { return captionsAllowed(pr, style); }

    boolean captionsAllowed(Project pr, CaptionStyle style) {
        if (style == null) return false;
        String key = srcKey(pr);
        if (pr.captionsPaid || Prefs.isPremium(ctx) || Prefs.paidRecently(ctx, Prefs.F_CAPTIONS, key)) { pr.captionsPaid = true; return true; }
        if (Prefs.useCaptionCredit(ctx)) {
            pr.captionsPaid = true;
            Prefs.markPaid(ctx, Prefs.F_CAPTIONS, key);
            AppLog.i("Captions: 1 ad credit used for this video (" + Prefs.captionCredits(ctx) + " left)");
            return true;
        }
        AppLog.i("Captions are locked (watch 1 ad for this video, or use PRO): shorts made without captions");
        return false;
    }

    // ================================================================== code 30: CONTINUE a stopped job

    /**
     * Continues the job the phone stopped: the picked moments come from the checkpoint project, finished shorts are kept,
     * only the missing ones are rendered (same format, frame, caption style and export quality as the first run - an ad
     * unlock used for that job is honoured). Without a usable checkpoint it is a normal new job (the transcript of the
     * video is reused from the cache, so nothing is transcribed twice).
     */
    public Project runResume(Request rq) throws Exception {
        Project pr = JobGuard.checkpointProject(ctx, rq.resumeId);
        List<Integer> missing = new ArrayList<>();
        if (pr != null) for (int i = 0; i < pr.clips.size(); i++) if (!JobGuard.clipDone(pr.clips.get(i))) missing.add(i);
        if (pr == null || pr.clips.isEmpty() || pr.isLocal()) {
            AppLog.i("Nothing saved from the stopped job, starting it again");
            rq.resumeId = "";
            return runAnalyze(rq);
        }
        AppLog.clear();
        AppLog.i("Continuing the stopped job: " + (pr.clips.size() - missing.size()) + " of " + pr.clips.size() + " shorts already done");
        jm.title = pr.title;
        YtEngine.audioLang = pr.audioLang == null ? "" : pr.audioLang;
        faceTrackPrior = trackingWanted(pr);
        if (!missing.isEmpty()) {
            checkFreeSpace(pr.dir(ctx));
            String json = Project.readText(pr.transcriptFile(ctx));
            Transcript tr = json == null ? new Transcript() : Transcript.fromJson(json);
            FfmpegCommands.Frame frame;
            try { frame = FfmpegCommands.Frame.valueOf(pr.frame); } catch (Exception e) { frame = rq.frame; }
            int quality = pr.quality >= 0 ? pr.quality : effectiveQuality();
            boolean high = Prefs.highSpeedOn(ctx);
            double sec = 0;
            for (int i : missing) sec += pr.clips.get(i).duration();
            plan(false, sec, missing.size(), parallelism(missing.size()), false);
            step(0, 1, "Video ready", "kept from the stopped job");
            step(1, 1, "Transcript ready", "kept from the stopped job");
            step(2, 1, "Found " + pr.clips.size() + " moments", "kept from the stopped job");
            if (trackingWanted(pr)) { try { FaceAnalyzer.ensureEngine(ctx); } catch (Throwable ignored) {} }
            CaptionStyle capStyle = pr.captionsWanted && rq.style != null ? rq.style : null;
            JobGuard.checkpoint(ctx, pr.id, pr.clips.size());
            long t = System.currentTimeMillis();
            double clipSec = renderAll(pr, missing, tr, capStyle, rq.layout, rq.script, frame, quality, high, 3);
            if (clipSec > 0) Prefs.learn(ctx, "render_per_sec", (System.currentTimeMillis() - t) / 1000.0 * renderPar / clipSec);
        }
        pr.save(ctx);
        pr.makeCurrent(ctx);
        // the shorts that exist (a short that failed again is reported by renderAll's own error)
        Project live = Project.loadFrom(pr.dir(ctx));
        return live != null ? live : pr;
    }

    // ================================================================== CAPTIONS (re-style existing clips)

    public void runCaptions(Request rq, Project pr) throws Exception {
        YtEngine.audioLang = pr.audioLang == null ? "" : pr.audioLang;   // code 28: re-renders fetch the same audio language
        String json = Project.readText(pr.transcriptFile(ctx));
        Transcript tr = json == null ? new Transcript() : Transcript.fromJson(json);
        FfmpegCommands.Frame frame;
        try { frame = FfmpegCommands.Frame.valueOf(pr.frame); } catch (Exception e) { frame = FfmpegCommands.Frame.BLUR; }
        int quality = effectiveQuality();
        boolean high = Prefs.highSpeedOn(ctx);
        jm.title = pr.title;
        double sec = 0;
        for (int i : rq.clipIndices) if (i >= 0 && i < pr.clips.size()) sec += pr.clips.get(i).duration();
        plan(pr.isLocal(), sec, 1, parallelism(rq.clipIndices.size()), true);
        long t = System.currentTimeMillis();
        if (rq.style != null && !captionsAllowed(pr, rq.style)) throw new Exception("Captions are locked. Watch 1 ad (captions for 1 video) or use PRO.");
        double clipSec = renderAll(pr, rq.clipIndices, tr, rq.style, rq.layout, rq.script, frame, quality, high, 4);
        if (clipSec > 0) Prefs.learn(ctx, "render_per_sec", (System.currentTimeMillis() - t) / 1000.0 * renderPar / clipSec);
        learnRates(pr);
        pr.save(ctx);
    }

    // ================================================================== rendering

    private volatile int renderPar = 1;
    /** v22: progress of the render batch running now (phases of each short) */
    private volatile RenderProgress curRp;

    /** v22: phase ph of the short with project clip index ci is at fraction x (no-op outside a batch) */
    private void phase(int ci, int ph, double x) {
        RenderProgress rp = curRp;
        if (rp != null) rp.set(rp.slot(ci), ph, x);
    }

    /** v22: learn this phone's measured phase rates (the next run's estimate starts from them) */
    private void learnRates(Project pr) {
        RenderProgress rp = curRp;
        if (rp == null) return;
        double a = rp.measuredRate(RenderProgress.PREP, false), b = rp.measuredRate(RenderProgress.FACES, true), c = rp.measuredRate(RenderProgress.ENCODE, true);
        if (!Double.isNaN(a)) Prefs.learn(ctx, "r_prep", Math.max(1, Math.min(120, a)));
        if (!Double.isNaN(b) && b > 0.02) Prefs.learn(ctx, "r_faces", Math.max(0.05, Math.min(10, b)));
        if (!Double.isNaN(c)) Prefs.learn(ctx, "r_enc", Math.max(0.1, Math.min(10, c)));
        double s = 0; int k = 0;
        for (Project.Clip cl : pr.clips) { s += cl.duration(); k++; }
        if (k > 0) Prefs.learn(ctx, "clip_avg", Math.max(10, Math.min(120, s / k)));
    }
    /** v7: share of the render ring used by caption-language conversion (0 when none is needed) */
    static final double CAP_SHARE = 0.15;
    /** v9: off — captions are written per short from its own audio after it is rendered (captionPass) */
    static final boolean UPFRONT_CAPTIONS = false;
    private volatile double capBase;

    /** renders the given clips (in parallel where the phone allows); returns total clip seconds rendered */
    private double renderAll(Project pr, List<Integer> idx, Transcript tr, CaptionStyle style, CaptionLayout layout, String script,
                             FfmpegCommands.Frame frame, int quality, boolean high, int stage) throws Exception {
        try { return renderAllInner(pr, idx, tr, style, layout, script, frame, quality, high, stage); }
        finally { FaceAnalyzer.release(); }   // v14: free the face models after the batch
    }

    private double renderAllInner(Project pr, List<Integer> idx, Transcript tr, CaptionStyle style, CaptionLayout layout, String script,
                             FfmpegCommands.Frame frame, int quality, boolean high, int stage) throws Exception {
        final List<Integer> list = new ArrayList<>();
        for (int i : idx) if (i >= 0 && i < pr.clips.size()) list.add(i);
        final int n = list.size();
        if (n == 0) return 0;
        capBase = 0;
        // v6: captions in the chosen language for ALL clips in one AI request, before the parallel renders start
        capWords.clear();
        if (style != null && UPFRONT_CAPTIONS) {
            List<List<Word>> cw = new ArrayList<>();
            for (int i : list) { Project.Clip c = pr.clips.get(i); cw.add(tr.wordsForClip(c.start, c.end)); }
            boolean needs = false;
            for (List<Word> w : cw) if (!CaptionScript.action(w, tr.language, script).equals("keep")) needs = true;
            List<List<Word>> conv;
            if (needs) {
                // v7: writing captions in another language is the first part (up to 15%) of this step's ring, so it
                // never sits at 0% while the AI converts the words
                capBase = CAP_SHARE;
                final String cHead = stage == 4 ? "Adding captions" : "Rendering shorts", cMsg = "Writing captions in " + CaptionScript.label(script) + "…";
                final int st = stage;
                step(st, 0.01, cHead, cMsg);
                final long c0 = System.currentTimeMillis();
                final java.util.concurrent.atomic.AtomicBoolean capDone = new java.util.concurrent.atomic.AtomicBoolean();
                Thread tick = new Thread(() -> {
                    while (!capDone.get()) {
                        double sec = (System.currentTimeMillis() - c0) / 1000.0;
                        step(st, CAP_SHARE * (1 - Math.exp(-sec / 6.0)) * 0.95, cHead, cMsg);
                        try { Thread.sleep(300); } catch (InterruptedException e) { return; }
                    }
                }, "cap-tick");
                tick.setDaemon(true);
                tick.start();
                try { conv = CaptionScript.convertAll(ctx, ct, tr, cw, script, warnings); }
                finally { capDone.set(true); tick.interrupt(); }
                step(st, CAP_SHARE, cHead, cMsg);
            } else conv = CaptionScript.convertAll(ctx, ct, tr, cw, script, warnings);
            for (int k = 0; k < n; k++) capWords.put(list.get(k), conv.get(k));
        }
        final double[] frac = new double[n];
        final boolean[] fin = new boolean[n];
        final long t0 = System.currentTimeMillis();
        double total = 0;
        for (int i : list) total += pr.clips.get(i).duration();
        final double totalSec = total;
        final String verb = stage == 4 ? (style == null ? "Removing captions" : "Adding captions") : "Rendering shorts";
        final String head = stage == 3 && style != null ? verb + " with captions" : verb;
        final Object lock = new Object();
        // v22 (4.1): one progress object for the whole step (see RenderProgress): weighted by the real cost of each
        // short's phases, one writer for the status line, time left from a simulation of what is still to run
        int parGuess = Math.min(parallelism(n), n);
        if (trackingWanted(pr)) parGuess = Math.min(parGuess, 2);
        int[] cidx = new int[n]; double[] csec = new double[n];
        for (int k = 0; k < n; k++) { cidx[k] = list.get(k); csec[k] = pr.clips.get(list.get(k)).duration(); }
        final boolean trackAll = trackingWanted(pr) && FaceAnalyzer.available(ctx);
        final RenderProgress rp = new RenderProgress(cidx, csec, trackAll, rates(), parGuess, t0);
        curRp = rp;
        jm.replan(stage, RenderProgress.predictWall(csec, trackAll, rates(), parGuess) + 1);
        final long[] lastSim = {0};
        final double[] lastFr = {0};
        Runnable report = () -> {
            long now = System.currentTimeMillis();
            double fr;
            synchronized (lastSim) {
                // the simulation is cheap but not free: at most ~1.5 times a second
                if (now - lastSim[0] >= 700) { lastSim[0] = now; lastFr[0] = rp.timeFraction(now); jm.stageRemaining(stage, rp.remainingSeconds(now)); }
                fr = lastFr[0];
            }
            step(stage, capBase + (1 - capBase) * fr, head, rp.detail());
        };
        rp.setOnChange(report);
        step(stage, capBase, head, rp.detail());

        // hardware encoder: verify once on this phone with the first clip alone, then go parallel
        boolean hwWanted = Prefs.hwEncodeOn(ctx) && Prefs.hwEncodeState(ctx) >= 0;
        int start = 0;
        if (hwWanted && Prefs.hwEncodeState(ctx) == 0) {
            renderOne(pr, list.get(0), tr, style, layout, script, frame, quality, high, 0,
                    f -> rp.set(0, RenderProgress.ENCODE, f));
            rp.done(0);
            start = 1;
        }
        int par = Math.min(parallelism(n - start), Math.max(1, n - start));
        // v14: with face tracking the model runs beside the renders: at most 2 at once so they don't fight for the CPU
        if (trackingWanted(pr)) par = Math.min(par, 2);
        renderPar = par;
        FfmpegCommands.hwBoost = Prefs.learned(ctx, "hw_rate_boost", 1.0);   // v23: this phone's encoder correction
        // v22 (4.1): shorts that wait for a free slot get their clips fetched now (network, not CPU), so a slot that
        // frees up goes straight to face analysis / encoding. His 4.0 run: short #3 fetched 15.7 MB only after short #1
        // finished (14.8 s on the critical path).
        ExecutorService pre = null;
        // code 28: shorts beyond the ones preparing at once (par + PREP_AHEAD, see acquireRenderSlot) still get their clip
        // fetched ahead here; the ones in the pool fetch their own
        final int ahead = par <= 1 ? par : par + PREP_AHEAD;
        if (!pr.isLocal() && n - start > ahead) {
            pre = Executors.newSingleThreadExecutor();
            final boolean preTrack = trackingWanted(pr);
            for (int k = start + ahead; k < n; k++) {
                final Project.Clip c = pr.clips.get(list.get(k));
                pre.submit(() -> {
                    try {
                        if (ct.cancelled || renderHookForTest != null) return;
                        if (preTrack && FaceAnalyzer.available(ctx)) analysisSegment(pr, c, pr.dir(ctx));
                        if (ct.cancelled) return;
                        clipSegment(pr, c, pr.dir(ctx));
                    } catch (Throwable ignored) {}   // the short fetches it itself later
                });
            }
            pre.shutdown();
        }
        try {
        if (par <= 1) {
            for (int k = start; k < n; k++) {
                ct.check();
                final int fk = k;
                renderOne(pr, list.get(k), tr, style, layout, script, frame, quality, high, sequentialX264Threads(),
                        f -> rp.set(fk, RenderProgress.ENCODE, f));
                rp.done(k);
            }
        } else {
            AppLog.i("Rendering " + par + " shorts at the same time (" + Runtime.getRuntime().availableProcessors() + " CPU cores)");
            int threads = Math.max(2, Runtime.getRuntime().availableProcessors() / par);
            // code 28: every short starts its network preparation at once; the heavy part takes one of par slots and the
            // second encoder starts only once the first one produces frames (see acquireRenderSlot)
            renderSlots = new java.util.concurrent.Semaphore(par, true);
            encodeGate = new java.util.concurrent.CountDownLatch(1);
            encodeGateOwner.set(-1);
            slotWaitMs.set(0);
            ExecutorService ex = Executors.newFixedThreadPool(Math.min(n - start, par + PREP_AHEAD));
            List<Future<?>> fs = new ArrayList<>();
            for (int k = start; k < n; k++) {
                final int fk = k;
                fs.add(ex.submit(() -> {
                    renderOne(pr, list.get(fk), tr, style, layout, script, frame, quality, high, threads,
                            f -> rp.set(fk, RenderProgress.ENCODE, f));
                    rp.done(fk);
                    return null;
                }));
            }
            try {
                Exception first = null;
                for (Future<?> f : fs) {
                    try { get(f); }
                    catch (CancelToken.Cancelled c) { throw c; }
                    catch (Exception e) { if (first == null) first = e; }
                }
                if (ct.cancelled) throw new CancelToken.Cancelled();
                if (first != null) throw first;
            } finally {
                ex.shutdownNow();
                renderSlots = null; encodeGate = null; encodeGateOwner.set(-1);
                AppLog.i(String.format(Locale.US, "Render step: %d shorts, %d at a time, %.1f s in all", n, par, (System.currentTimeMillis() - t0) / 1000.0));
            }
        }
        } finally {
            if (pre != null) pre.shutdownNow();
        }
        return totalSec;
    }

    // ------------------------------------------------------------------ code 28: faster last step
    /**
     * code 28 (4.6): the render step used a pool of {@code par} threads, so a short waiting for a free slot had not even
     * started its network part (clip fetch, the short's own speech transcription + caption writing): every waiting short
     * added that time to the critical path, and the second short of a parallel run waited (up to 20 s) for the first
     * one's whole caption preparation before starting. Now every short starts its network preparation at once and only
     * the heavy part (face analysis, decoding + encoding, the captions burn) takes one of the {@code par} slots;
     * the second encoder still starts only after the first one produces frames (the v22 fontconfig / encoder-init guard).
     */
    private volatile java.util.concurrent.Semaphore renderSlots;
    private volatile java.util.concurrent.CountDownLatch encodeGate;
    private final java.util.concurrent.atomic.AtomicInteger encodeGateOwner = new java.util.concurrent.atomic.AtomicInteger(-1);
    private final ThreadLocal<Boolean> slotHeld = ThreadLocal.withInitial(() -> false);
    /** code 28: wall time shorts spent waiting for a render slot (logged once per step) */
    final java.util.concurrent.atomic.AtomicLong slotWaitMs = new java.util.concurrent.atomic.AtomicLong();

    /** takes this short's render slot (once per short; no-op when shorts render one after another) */
    void acquireRenderSlot(int clipIndex) throws InterruptedException {
        java.util.concurrent.Semaphore s = renderSlots;
        if (s == null || slotHeld.get()) return;
        long t0 = System.currentTimeMillis();
        while (!s.tryAcquire(300, TimeUnit.MILLISECONDS)) ct.check();
        slotHeld.set(true);
        slotWaitMs.addAndGet(System.currentTimeMillis() - t0);
    }

    /**
     * right before a short's encoder starts: the first short to get here starts at once, every other one waits until
     * that one produces frames (at most ENCODE_GATE_MS), so the caption renderer's font setup (fontconfig) and two
     * hardware encoders never initialise at the same instant (the v22 guard, now without holding up the preparation)
     */
    void awaitEncodeGate(int clipIndex) throws InterruptedException {
        java.util.concurrent.CountDownLatch g = encodeGate;
        if (g == null || g.getCount() == 0) return;
        if (encodeGateOwner.compareAndSet(-1, clipIndex) || encodeGateOwner.get() == clipIndex) return;
        long until = System.currentTimeMillis() + ENCODE_GATE_MS;
        while (g.getCount() > 0 && System.currentTimeMillis() < until) { ct.check(); g.await(200, TimeUnit.MILLISECONDS); }
    }

    void releaseRenderSlot() {
        java.util.concurrent.Semaphore s = renderSlots;
        if (slotHeld.get()) { slotHeld.set(false); if (s != null) s.release(); }
    }

    /** opens the encoder-start gate (the first parallel short produces frames, or ended) */
    void openEncodeGate(int clipIndex) {
        java.util.concurrent.CountDownLatch g = encodeGate;
        if (g != null && clipIndex == encodeGateOwner.get()) g.countDown();
    }

    /** code 28: how long a second encoder waits for the first one to produce frames (was a 20 s wait before its prep) */
    static final long ENCODE_GATE_MS = 20_000;
    /** code 28: shorts that prepare (clip fetch + captions, network) while the render slots are busy */
    static final int PREP_AHEAD = 2;

    /** code 29 test seam: replaces one short's render (download + encode) so the real scheduling runs on the JVM */
    interface RenderHook { void render(Pipeline p, Project pr, int ci, int threads, MediaRunner.Progress prog) throws Exception; }
    static volatile RenderHook renderHookForTest;
    /** code 29 test seam: shorts rendered at the same time (0 = this phone's own answer) */
    static volatile int parForTest = 0;

    /** code 29 test entry: the real render step (scheduling, slots, gate, progress) for the given clips */
    double renderAllForTest(Project pr, List<Integer> idx, Transcript tr, int stage) throws Exception {
        return renderAll(pr, idx, tr, null, null, CaptionScript.HINGLISH, FfmpegCommands.Frame.FILL, 0, false, stage);
    }

    /** code 29 test entry: a short's end bookkeeping exactly as a real render does it */
    void finishClipForTest(Project pr, Project.Clip clip, File out) { finishClip(pr, clip, out, "", ""); }

    private void renderOne(Project pr, int ci, Transcript tr, CaptionStyle style, CaptionLayout layout, String script,
                           FfmpegCommands.Frame frame, int quality, boolean high, int threads, MediaRunner.Progress prog) throws Exception {
        Project.Clip clip0 = pr.clips.get(ci);
        try {
            RenderHook hook = renderHookForTest;
            if (hook != null) { hook.render(this, pr, ci, threads, f -> { if (prog != null) prog.on(f); if (f > 0) openEncodeGate(clip0.index); }); return; }
            renderOneInner(pr, ci, tr, style, layout, script, frame, quality, high, threads, f -> {
                if (prog != null) prog.on(f);
                if (f > 0) openEncodeGate(clip0.index);
            });
        } finally {
            releaseRenderSlot();
            openEncodeGate(clip0.index);
        }
    }

    private void renderOneInner(Project pr, int ci, Transcript tr, CaptionStyle style, CaptionLayout layout, String script,
                           FfmpegCommands.Frame frame, int quality, boolean high, int threads, MediaRunner.Progress prog) throws Exception {
        Project.Clip clip = pr.clips.get(ci);
        File dir = pr.dir(ctx);
        // v9: two passes. 1) the short is rendered WITHOUT captions; 2) its finished audio is transcribed (word times,
        // language locked) and the captions are burned on top. Caption time 0 = the first frame of the file he gets,
        // whatever the seek, the stream or the full transcript's stitching did (v8: every caption ~8.1 s late).
        final boolean wantCaps = style != null;
        File fonts = MediaRunner.fontsDir(ctx);
        // v14: output format (9:16 / 1:1 / 16:9) is part of the render spec, so every size and cache key follows it
        int outW = FfmpegCommands.Aspect.of(pr.aspect).spec(quality == 1 ? 1080 : 720);
        layout = layoutFor(pr, layout);
        File out = new File(dir, String.format(Locale.US, "Klipora_short%d_%d.mp4", clip.index + 1, System.currentTimeMillis() % 100000000L));
        double dur = clip.duration();
        final String rawKey = rawKey(clip, frame, outW) + (trackingWanted(pr) ? "|ft14" : "");
        File rawOut = new File(dir, "raw_" + clip.index + ".mp4");
        if (clip.rawPath != null && !clip.rawPath.isEmpty() && rawKey.equals(clip.rawKey) && new File(clip.rawPath).exists()
                && new File(clip.rawPath).length() > 20000) {
            // only the captions changed (Caption Studio): reuse the uncaptioned short, no new download or render
            File raw = new File(clip.rawPath);
            acquireRenderSlot(clip.index);   // code 28: the captions burn is the heavy part
            awaitEncodeGate(clip.index);
            phase(clip.index, RenderProgress.ENCODE, 0);
            AppLog.i("Short #" + (clip.index + 1) + ": reusing the rendered short, " + (wantCaps ? "drawing new captions" : "removing captions"));
            String note = "";
            boolean capsOk = false;
            if (wantCaps) {
                try { captionPass(pr, clip, raw, out, tr, style, layout, script, outW, threads); capsOk = true; }
                catch (CancelToken.Cancelled c) { throw c; }
                catch (Exception e) { AppLog.e("Captions could not be drawn on short #" + (clip.index + 1) + ": " + shortErr(e)); note = "Captions could not be drawn on this clip on your phone"; }
            }
            if (!capsOk) copyFile(raw, out);
            finishClip(pr, clip, out, capsOk ? style.name : "", note);
            return;
        }
        // v10 single pass: transcribe the clip's audio cut straight from the source (seconds, audio only), build the
        // captions, then render the short ONCE with them burned in. v9 encoded every short twice (render, then burn).
        // If that is not possible (no network for the transcript, no audio), the v9 two-pass path below still runs.
        File preFlac = null;
        String singleAss = null;
        // v16 (2.7): face tracking (clip fetch + face analysis) runs at the same time as the caption preparation (the
        // short's speech transcription + Hinglish), instead of after it. Shorts without tracking fetch their clip in
        // parallel parts the same way, so no render reads YouTube over one throttled connection any more.
        final boolean wantTrack = trackingWanted(pr);
        // code 28: face analysis is CPU-heavy, so a tracked short takes its slot before it starts; an untracked short
        // fetches its clip and prepares its captions (network) first and takes the slot only for the encode
        if (wantTrack) acquireRenderSlot(clip.index);
        phase(clip.index, RenderProgress.PREP, 0);
        final MediaRunner.Progress prepProg = x -> phase(clip.index, RenderProgress.PREP, Math.min(0.9, x * 0.9));
        ExecutorService side = Executors.newSingleThreadExecutor();
        Future<FfmpegCommands.Track> trackF = null;
        Future<File> segF = null;
        if (wantTrack) {
            trackF = side.submit(() -> {
                try { return faceTrackFor(pr, clip, dir, quality, prepProg); }
                catch (CancelToken.Cancelled c) { throw c; }
                catch (Throwable e) { AppLog.e("Face tracking skipped on short #" + (clip.index + 1) + ": " + FaceAnalyzer.causeChain(e)); return null; }
            });
        } else if (!pr.isLocal()) {
            segF = side.submit(() -> clipSegment(pr, clip, dir));
        }
        side.shutdown();
        if (wantCaps && SINGLE_PASS) {
            try {
                long tp = System.currentTimeMillis();
                preFlac = new File(dir, "src_speech_" + clip.index + ".flac");
                String want = clipLanguage(script, tr);
                boolean cached = clip.spokenJson != null && !clip.spokenJson.isEmpty() && spokenKey(clip).equals(clip.spokenKey)
                        && (want.isEmpty() || want.equals(clip.spokenLang));
                if (!cached) extractSourceSpeech(pr, clip, preFlac, quality, high, prepProg);
                List<Word> spoken = spokenWords(pr, clip, null, tr, script, cached ? null : preFlac);
                if (!spoken.isEmpty() && ownAudio.get()) {
                    singleAss = writeAss(pr, clip, spoken, style, layout, script);
                    AppLog.i(String.format(Locale.US, "Short #%d captions ready before render (%.1f s, one encode)", clip.index + 1, (System.currentTimeMillis() - tp) / 1000.0));
                } else AppLog.i("Short #" + (clip.index + 1) + ": captions will be timed on the rendered short (two passes)");
            } catch (CancelToken.Cancelled c) {
                throw c;
            } catch (Exception e) {
                AppLog.i("Short #" + (clip.index + 1) + ": single-pass captions not possible (" + shortErr(e) + "), using two passes");
                singleAss = null;
            }
        }
        File target = wantCaps && singleAss == null ? rawOut : out;
        // v13: AI face tracking (off = exactly the 2.3 framing). Any problem here = the normal framing, never a failed short.
        FfmpegCommands.Track track = null;
        boolean segReady = false;
        try {
            if (trackF != null) track = get(trackF);
            if (segF != null) segReady = get(segF) != null;
        } catch (CancelToken.Cancelled c) {
            side.shutdownNow(); throw c;
        } catch (Exception e) {
            AppLog.e("Short #" + (clip.index + 1) + ": " + (trackF != null ? "face tracking skipped: " + FaceAnalyzer.causeChain(e) : "clip fetch: " + shortErr(e)));
            track = null;
        }
        if (wantTrack && !pr.isLocal()) {
            File sf = new File(dir, "seg_" + clip.index + ".mp4");
            segReady = sf.exists() && sf.length() > 50000 && String.format(Locale.US, "%.2f|%.2f|v16", clip.start, clip.end).equals(Project.readText(new File(dir, "seg_" + clip.index + ".key")));
        }
        acquireRenderSlot(clip.index);   // code 28: decoding + encoding take a slot
        phase(clip.index, RenderProgress.ENCODE, 0);
        Exception err = null;
        FfmpegCommands.Encoder encFloor = null;   // v26: the most conservative encoder step this short has reached
        boolean hwDecFailedHere = false;
        int engineCrashes = 0;   // code 31: the separate video process crashed on this short (each time one path safer)
        boolean nativeTestFailed = false;   // code 33: the backup engine (test switch) failed on this short: FFmpeg instead
        final int outWf = outW;
        RenderSafety.hwEncoderFor = w -> Prefs.hwEncodeOn(ctx) && Prefs.hwEncodeState(ctx) >= 0 && FfmpegCommands.planFor(outWf).hw;
        // Attempt plan. YouTube: direct stream -> fresh stream links -> downloaded file -> downloaded file with the
        // simplest filter chain. Local: file -> simple chain. The last resort drops captions (the user is told).
        // A hardware-encoder failure never uses up an attempt: the same attempt reruns on the x264 software encoder.
        final int[] plan = pr.isLocal() ? new int[]{SRC_FILE, SRC_FILE | SAFE, SRC_FILE | SAFE | NOCAP}
                : new int[]{SRC_STREAM, SRC_STREAM_FRESH, SRC_FILE, SRC_FILE | SAFE, SRC_FILE | SAFE | NOCAP};
        boolean captionsDropped = false;
        final File segFile = new File(dir, "seg_" + clip.index + ".mp4");
        final boolean segOk = segReady && !pr.isLocal() && segFile.exists() && segFile.length() > 50000;
        for (int attempt = 0; attempt < plan.length; attempt++) {
            ct.check();
            int mode = plan[attempt];
            if ((mode & NOCAP) != 0 && singleAss == null) continue;
            String video, audio = null;
            double ss = clip.start;
            if ((mode & SRC_FILE) != 0) {
                if (!pr.isLocal()) ensureDownloaded(pr, quality, high, x -> {});
                video = pr.localPath;
                String la = new File(dir, "source_audio.m4a").getAbsolutePath();
                if (!pr.isLocal() && new File(la).exists()) audio = la;
                if (video == null || video.isEmpty() || !new File(video).exists()) throw new Exception("Source video is no longer available. Start a new project.");
            } else if ((mode & SRC_STREAM_FRESH) == 0 && segOk) {
                video = segFile.getAbsolutePath(); audio = null; ss = 0;   // v14: the clip already fetched for tracking
            } else {
                String[] s = getStreams(pr, (mode & SRC_STREAM_FRESH) != 0);
                if (s == null) { if ((mode & SRC_STREAM_FRESH) == 0) attempt++; continue; }
                video = s[0]; audio = s[1];
            }
            boolean safe = (mode & SAFE) != 0;
            // v24: the clip / file is decoded by the phone's hardware decoder (frees the CPU for face analysis, captions and
            // the other short; a 1440p / 4K VP9 / AV1 clip always, it is too big for software decoding)
            String decoder = video.startsWith("http") ? null : decoderFor(new File(video), hwDecFailedHere);
            int srcH = 0;
            if (frame == FfmpegCommands.Frame.FILL && track == null && !video.startsWith("http")) { Object[] pv = probeVideo(new File(video)); if (pv != null) srcH = (Integer) pv[2]; }
            String useAss = (mode & NOCAP) != 0 ? null : singleAss;
            FfmpegCommands.Frame useFrame = safe ? FfmpegCommands.Frame.FIT : frame;
            // code 33: the backup engine (no FFmpeg) after every FFmpeg rung crashed here, or forced by the Premium test switch
            final boolean nativeRun = !nativeTestFailed && (RenderSafety.useNative(ctx) || Prefs.backupEngineTest(ctx));
            if (nativeRun) { safe = false; useFrame = frame; decoder = null; }
            FfmpegCommands.Encoder enc = nativeRun ? FfmpegCommands.Encoder.HW : safe ? (RenderSafety.x264Bad(ctx) && hwEncoder(encFloor, outW).hw() ? hwEncoder(encFloor, outW) : FfmpegCommands.Encoder.X264) : hwEncoder(encFloor, outW);
            final boolean hiSeg = video.equals(segFile.getAbsolutePath()) && hiClip(segFile) && streams != null;
            final int stBefore = RenderSafety.state(ctx);
            try {
                AppLog.i("Rendering short #" + (clip.index + 1) + (useAss != null ? " + captions" : "") + (video.startsWith("http") ? " (direct stream)" : video.equals(segFile.getAbsolutePath()) ? " (clip copy)" : "")
                        + (nativeRun ? " [backup video engine: phone's own decoder + GPU + hardware encoder" + (RenderSafety.useNative(ctx) ? "" : ", test switch") + "]"
                        : (enc.hw() ? " [hardware encoder, " + (enc == FfmpegCommands.Encoder.HW ? "quality" : enc == FfmpegCommands.Encoder.HW_STD ? "standard" : "basic") + " settings]" : " [software encoder]") + (safe ? " [safe mode]" : "")
                        + (RenderSafety.noAsm(ctx) ? " [CPU-safe mode]" : RenderSafety.noNeon(ctx) ? " [FFmpeg without NEON]" : "")
                        + (hiSeg ? " [sharper source" + (decoder == null ? ", software decoded" : "") + "]" : "")
                        + (decoder != null ? " [hardware decoder " + decoder + "]" : "")));
                awaitEncodeGate(clip.index);   // code 28 (no wait for the first short, nor after the first encoder runs)
                long t0 = System.currentTimeMillis();
                String[] cmd = nativeRun
                        ? nativeArgs(pr, clip, video, audio, ss, dur, useFrame, useAss != null ? lastAssWords.get(clip.index) : null, style, layout, track, srcH, target, outW)
                        : safe && !enc.hw()
                        ? FfmpegCommands.renderClipSafe(video, audio, ss, dur, useAss, fonts.getAbsolutePath(), target.getAbsolutePath(), outW)
                        : safe   // code 33: x264 crashed here: "safe mode" = the plain fit frame on the hardware encoder
                        ? FfmpegCommands.renderClip(video, audio, ss, dur, FfmpegCommands.Frame.FIT, useAss, fonts.getAbsolutePath(), target.getAbsolutePath(), outW, enc, threads, null,
                                target.getName().startsWith("raw_"), null, srcH)
                        : FfmpegCommands.renderClip(video, audio, ss, dur, useFrame, useAss, fonts.getAbsolutePath(), target.getAbsolutePath(), outW, enc, threads, track,
                                target.getName().startsWith("raw_"), decoder, srcH);   // v23: the raw short of a two-pass caption render = intermediate quality
                MediaRunner.run(cmd, dur, prog, ct);
                if (!target.exists() || target.length() < 20000) throw new Exception("FFmpeg finished but the output file is empty (" + (target.exists() ? target.length() : 0) + " bytes)");
                if (nativeRun) {
                    if (!validVideo(target, dur)) throw new Exception("backup engine output did not play back");
                } else if (enc.hw()) {
                    if (!validVideo(target, dur)) throw new HwException("hardware encoder output did not play back");
                    if (Prefs.hwEncodeState(ctx) == 0) { Prefs.setHwEncodeState(ctx, 1); AppLog.i("Hardware encoder works on this phone ✓"); }
                    if (enc == FfmpegCommands.Encoder.HW && Prefs.hwTuneState(ctx) == 0) { Prefs.setHwTuneState(ctx, 1); AppLog.i("Hardware encoder quality mode works ✓ (" + FfmpegCommands.planFor(outW).describe() + ")"); }
                    Prefs.hwLevelOk(ctx, rank(enc));
                }
                // v26: a software-decoded sharper (1440p) clip that renders too slowly on this phone is not used again
                if (hiSeg && decoder == null) noteSwHiSpeed(dur, (System.currentTimeMillis() - t0) / 1000.0);
                String rateNote = enc.hw() && !safe && !nativeRun ? hwRateNote(target, dur, outW, target.getName().startsWith("raw_")) : "";
                if (enc.hw() && !safe && !nativeRun && !target.getName().startsWith("raw_")) {
                    // v26 quality guard (final short only; the raw short of a two-pass caption render is checked after its captions pass)
                    final String gv = video, ga = audio, gAss = useAss, gDec = decoder; final double gss = ss; final int gSrcH = srcH;
                    final FfmpegCommands.Frame gFrame = useFrame; final FfmpegCommands.Track gTrack = track;
                    if (qualityGuard(target, dur, outW, (e2, path) -> FfmpegCommands.renderClip(gv, ga, gss, dur, gFrame, gAss, fonts.getAbsolutePath(), path, outW, e2, threads, gTrack,
                            false, gDec, gSrcH), "Short #" + (clip.index + 1), ct)) rateNote += ", re-encoded in software for a sharper picture";
                }
                AppLog.i(String.format(Locale.US, "Short #%d %s in %.1f s (%.1f MB%s)", clip.index + 1, wantCaps && useAss == null ? "rendered" : "done",
                        (System.currentTimeMillis() - t0) / 1000.0, target.length() / 1048576.0, rateNote));
                if (useAss != null) {
                    // proof: the rendered audio must start where the transcribed audio started (else re-time on the short)
                    double[] lg = verifySinglePass(preFlac, target);
                    if (lg != null && syncNeedsRetime(lg[0], lg[1])) {
                        AppLog.i(String.format(Locale.US, "Short #%d: rendered audio is %+.2f s off the transcribed audio (match %.2f), re-timing captions on the short", clip.index + 1, lg[0], lg[1]));
                        clip.spokenJson = ""; clip.spokenKey = "";
                        File rawNow = new File(dir, "raw_" + clip.index + ".mp4");
                        // the captioned file can't be un-captioned: render the raw short and burn again (v9 path)
                        singleAss = null; target.delete(); target = rawNow; if (preFlac != null) preFlac.delete(); attempt--; continue;
                    }
                    if (preFlac != null) preFlac.delete();
                    finishClip(pr, clip, out, style.name, safe ? "Rendered in safe mode (simple framing)" : "");
                    return;
                }
                if (wantCaps && (mode & NOCAP) != 0 && singleAss != null) {
                    finishClip(pr, clip, out, "", "Captions could not be drawn on this clip on your phone");
                    return;
                }
                if (wantCaps) {
                    clip.rawPath = rawOut.getAbsolutePath();
                    clip.rawKey = rawKey;
                    try {
                        long tc = System.currentTimeMillis();
                        captionPass(pr, clip, rawOut, out, tr, style, layout, script, outW, threads);
                        AppLog.i(String.format(Locale.US, "Short #%d captions done in %.1f s (%.1f MB)", clip.index + 1, (System.currentTimeMillis() - tc) / 1000.0, out.length() / 1048576.0));
                    } catch (CancelToken.Cancelled c) {
                        throw c;
                    } catch (Exception ce) {
                        AppLog.e("Captions could not be drawn on short #" + (clip.index + 1) + ": " + shortErr(ce));
                        captionsDropped = true;
                        copyFile(rawOut, out);
                    }
                }
                finishClip(pr, clip, out, wantCaps && !captionsDropped ? style.name : "",
                        captionsDropped ? "Captions could not be drawn on this clip on your phone" : (safe ? "Rendered in safe mode (simple framing)" : ""));
                return;
            } catch (CancelToken.Cancelled c) {
                throw c;
            } catch (Exception e) {
                err = e;
                out.delete();
                target.delete();
                if (e instanceof MediaRunner.EngineCrash) {
                    // code 31 (4.9): a native crash in the phone's video codec / FFmpeg ended only the separate video process
                    // (4.8: the whole app, every time, his HONOR Android 17). RenderSafety already moved this phone one
                    // step down (hardware decoding -> software decoding -> software encoder); the same short again.
                    MediaRunner.EngineCrash ec = (MediaRunner.EngineCrash) e;
                    engineCrashes++;
                    if (!RenderSafety.hwEncodeAllowed(ctx)) encFloor = FfmpegCommands.Encoder.X264;
                    if (decoder != null && !RenderSafety.hwDecodeAllowed(ctx)) hwDecFailedHere = true;
                    // code 33 (5.1): a crash renders again only on a path that is really different (the ladder moved); a
                    // phone ending the process (low memory) may retry the same path up to 3 times. 5.0 re-ran the very same
                    // crashing path 4x, then switched the video SOURCE (stream, 8 min download), which can't fix an engine crash.
                    boolean stepped = RenderSafety.state(ctx) != stBefore;
                    boolean crashRetry = ec.crash ? stepped : engineCrashes <= 3;
                    if (nativeRun && !RenderSafety.useNative(ctx)) {
                        // the Premium test switch: the backup engine failed here, the short is made the normal way
                        nativeTestFailed = true; crashRetry = true;
                    }
                    AppLog.e("Short #" + (clip.index + 1) + ": " + ec.getMessage() + (ec.where.isEmpty() ? "" : " in " + ec.where)
                            + "; Klipora kept running" + (crashRetry ? ", rendering it again with " + pathLabel(encFloor, outW) + (ec.crash ? " (kept for this phone)" : "")
                            : ec.crash ? "; no safer video path is left on this phone" : ""));
                    if (hiSeg && decoder != null) {
                        // the taller clip needs the hardware decoder: the normal (up to 1080p) clip instead
                        segFile.delete();
                        new File(dir, "seg_" + clip.index + ".key").delete();
                        streamsHi = null;
                        File again = clipSegment(pr, clip, dir);
                        if (again != null && track != null) { Object[] pv = probeVideo(again); if (pv != null) track.srcH = (Integer) pv[2]; }
                        if (again == null) continue;
                    }
                    if (crashRetry) { attempt--; continue; }
                    if (ec.crash) break;   // code 33: never a new download / stream for an engine crash
                    continue;
                }
                if (nativeRun) {
                    // code 33: the backup engine failed (a Java-side error, not a crash): with the test switch the short is made
                    // the normal way; as the last rung there is nothing safer left
                    AppLog.e("Short #" + (clip.index + 1) + ": backup video engine failed (" + shortErr(e) + ")"
                            + (RenderSafety.useNative(ctx) ? "" : ", making it the normal way"));
                    if (!RenderSafety.useNative(ctx)) { nativeTestFailed = true; attempt--; continue; }
                    break;
                }
                // v26: what was this failure about? A dropped network stream says nothing about the encoder or decoder (up
                // to code 25 it switched the phone to the bare encoder settings for good); an encoder error is not the
                // decoder's fault (it used to push the phone off the sharper source for good)
                RenderFault.Kind kind = RenderFault.classify(e, video.startsWith("http"));
                if (kind == RenderFault.Kind.NETWORK) {
                    AppLog.e("Render attempt " + (attempt + 1) + "/" + plan.length + ": the video stream dropped (" + shortErr(e) + "), trying again");
                    continue;
                }
                if (kind == RenderFault.Kind.ENCODER && enc.hw()) {
                    encFloor = encoderFailed(enc, kind, e, "Short #" + (clip.index + 1));
                    attempt--;
                    continue;
                }
                if (hiSeg && decoder == null) {
                    // v26: the software-decoded sharper clip failed: the normal clip, same attempt (counted like a decoder failure)
                    AppLog.e("The sharper clip failed in software decoding (" + shortErr(e) + "), using the normal stream");
                    int nf = Prefs.sp(ctx).getInt("hi_src_fail", 0) + 1;
                    Prefs.sp(ctx).edit().putInt("hi_src_fail", nf).apply();
                    segFile.delete();
                    new File(dir, "seg_" + clip.index + ".key").delete();
                    String[] keepHi = streamsHi;
                    streamsHi = null;
                    File again = clipSegment(pr, clip, dir);
                    streamsHi = nf >= 2 ? null : keepHi;
                    if (again == null) continue;
                    if (track != null) { Object[] pv = probeVideo(again); if (pv != null) track.srcH = (Integer) pv[2]; }
                    attempt--;
                    continue;
                }
                if (decoder != null && !hiSeg) {
                    // v24: the same attempt again with software decoding; two such failures and this phone decodes in software
                    AppLog.e("Hardware decoder " + decoder + " failed (" + shortErr(e) + "), decoding in software");
                    hwDecFailedHere = true;
                    int nf = Prefs.sp(ctx).getInt("hw_dec_fail", 0) + 1;
                    Prefs.sp(ctx).edit().putInt("hw_dec_fail", nf).apply();
                    attempt--;
                    continue;
                }
                if (decoder != null) {
                    // v24: the taller clip failed on this phone's hardware decoder: fetch the normal (up to 1080p) clip and
                    // render again; two such failures and this phone stays on the normal source
                    AppLog.e("Hardware decoding of the sharper clip failed (" + shortErr(e) + "), using the normal stream");
                    int nf = Prefs.sp(ctx).getInt("hi_src_fail", 0) + 1;
                    Prefs.sp(ctx).edit().putInt("hi_src_fail", nf).apply();
                    segFile.delete();
                    new File(dir, "seg_" + clip.index + ".key").delete();
                    String[] keepHi = streamsHi;
                    streamsHi = null;
                    File again = clipSegment(pr, clip, dir);
                    streamsHi = nf >= 2 ? null : keepHi;
                    if (again == null) continue;   // no clip copy: go on with the next (stream) attempt
                    if (track != null) { Object[] pv = probeVideo(again); if (pv != null) track.srcH = (Integer) pv[2]; }
                    attempt--;
                    continue;
                }
                if (enc.hw()) {
                    // v21: the tuned settings are tried first; v26: then step by step (standard, basic, x264) - an unclear
                    // failure counts half for the phone, so one bad moment no longer decides its settings for good
                    encFloor = encoderFailed(enc, kind, e, "Short #" + (clip.index + 1));
                    attempt--;
                    continue;
                }
                if (track != null && !safe) {
                    // v13: never lose a short to face tracking: the same attempt again with the normal framing
                    AppLog.e("Face-tracking render failed (" + shortErr(e) + "), rendering with the normal framing");
                    track = null;
                    attempt--;
                    continue;
                }
                AppLog.e("Render attempt " + (attempt + 1) + "/" + plan.length + " failed: " + shortErr(e));
            }
        }
        throw new Exception("Could not render short #" + (clip.index + 1) + ". " + (err == null ? "unknown error" : shortErr(err)));
    }



    // ================================================================== code 33 (5.1): backup video engine

    /** the caption words of each short as they were written into its ASS file (the backup engine draws them itself) */
    final java.util.concurrent.ConcurrentHashMap<Integer, List<Word>> lastAssWords = new java.util.concurrent.ConcurrentHashMap<>();

    /** what the next render of a short will use, in plain words (the crash log line) */
    String pathLabel(FfmpegCommands.Encoder floor, int outW) {
        if (RenderSafety.useNative(ctx)) return RenderSafety.rungLabel(RenderSafety.R_NATIVE);
        boolean hwEnc = hwEncoder(floor, outW).hw();
        return (RenderSafety.hwDecodeAllowed(ctx) ? "hardware decoding" : "software decoding") + " + " + (hwEnc ? "hardware encoder" : "software encoder")
                + (RenderSafety.noAsm(ctx) ? ", CPU-safe mode (no NEON/asm)" : RenderSafety.noNeon(ctx) ? ", FFmpeg without NEON (same settings)" : "");
    }

    /** the backup engine's "command": its spec as JSON (runs in the separate video process like an FFmpeg command) */
    String[] nativeArgs(Project pr, Project.Clip clip, String video, String audio, double ss, double dur, FfmpegCommands.Frame frame,
                        List<Word> words, CaptionStyle style, CaptionLayout layout, FfmpegCommands.Track track, int srcH, File target, int outW) throws Exception {
        org.json.JSONObject o = NativeEngine.spec(video, audio, ss, dur, target.getAbsolutePath(), outW, frame,
                FfmpegCommands.Aspect.of(pr.aspect).ratio(), FfmpegCommands.hwBaseKbps(outW, target.getName().startsWith("raw_")) * 1000L,
                FfmpegCommands.planFor(outW).codecName);
        if (track != null && track.plan != null) {
            File cf = new File(pr.dir(ctx), "native_track_" + clip.index + ".txt");
            Project.writeText(cf, NativeEngine.trackLines(track.plan, (int) Math.round(dur * FfmpegCommands.OUTPUT_FPS)));
            o.put("track", cf.getAbsolutePath());
            o.put("trackSrcH", track.srcH);
        } else o.put("srcH", srcH);
        if (words != null && !words.isEmpty() && style != null) {
            o.put("words", NativeEngine.wordsJson(words));
            o.put("style", style.toJson());
            o.put("layout", (layout == null ? new CaptionLayout() : layout).toJson());
        }
        return new String[]{RenderSafety.NATIVE_ARG, o.toString()};
    }

    // ================================================================== v13: AI face tracking

    /**
     * Analyses the short's frames on the phone (cached per clip), plans the camera path and writes the sendcmd script.
     * null = no tracking for this short (vertical video, no faces, models unavailable): the chosen framing is used.
     */
    /**
     * v14: face tracking runs only when it was switched on (premium: 1 ad = this project, or the 24 h pass), the genre
     * is Podcast (Sakib: "sirf podcasts pe", not MrBeast-style videos) and the format is 9:16 or 1:1. A 16:9 short of a
     * 16:9 video keeps the whole frame; a 16:9 short of a vertical video uses the blurred-background fit.
     */
    public static boolean trackingWanted(Project pr) {
        return pr.faceTrack && (pr.podcast || PromptManager.Category.PODCAST.name().equals(pr.category))
                && FfmpegCommands.Aspect.of(pr.aspect) != FfmpegCommands.Aspect.LANDSCAPE;
    }

    /** the caption layout drawn for this project's format (the frame size travels inside the layout) */
    public static CaptionLayout layoutFor(Project pr, CaptionLayout l) {
        FfmpegCommands.Aspect a = FfmpegCommands.Aspect.of(pr.aspect);
        CaptionLayout out = l == null ? CaptionLayout.defaultFor((float) a.ratio()) : l.copy();
        out.aspect = (float) a.ratio();
        return out;
    }

    /**
     * v14: for a YouTube stream, the clip's own video + audio fetched ONCE by stream copy (no decoding: network speed,
     * a few seconds), then face analysis and the render both read this local file. 2.4 decoded the network stream
     * twice (analysis, then render) while other shorts did the same. Exact: on a test clip the frames are identical
     * (frame hashes) and the audio offset is 0 ms compared with reading the stream directly.
     */
    File clipSegment(Project pr, Project.Clip clip, File dir) {
        synchronized (segLock(clip.index, "seg")) { return clipSegmentInner(pr, clip, dir); }
    }

    /** v22: one download per clip file at a time (the prefetch and the short's own render can ask for the same one) */
    private final java.util.Map<String, Object> segLocks = new java.util.HashMap<>();
    private Object segLock(int idx, String kind) {
        synchronized (segLocks) {
            String k = kind + idx;
            Object o = segLocks.get(k);
            if (o == null) { o = new Object(); segLocks.put(k, o); }
            return o;
        }
    }

    File clipSegmentInner(Project pr, Project.Clip clip, File dir) {
        File seg = new File(dir, "seg_" + clip.index + ".mp4"), keyF = new File(dir, "seg_" + clip.index + ".key");
        String key = String.format(Locale.US, "%.2f|%.2f|v16", clip.start, clip.end);
        if (seg.exists() && seg.length() > 50000 && key.equals(Project.readText(keyF))) return seg;
        long t0 = System.currentTimeMillis();
        // v24: a cropped portrait short first tries the taller (1440p / 4K, hardware-decoded) stream; any problem = the
        // normal stream below, exactly as before
        String[] hi = wantsHi(pr) ? streamsHi : null;
        if (hi != null) {
            try {
                if (rangeCopy(clip, hi[0], hi[1], seg, "seg_" + clip.index, dir) && probeVideo(seg) != null) {
                    Project.writeText(keyF, key);
                    AppLog.i(String.format(Locale.US, "Short #%d: sharper clip fetched (%.1f MB in %.1f s, video %s)", clip.index + 1,
                            seg.length() / 1048576.0, (System.currentTimeMillis() - t0) / 1000.0, hi[3]));
                    return seg;
                }
                AppLog.i("Short #" + (clip.index + 1) + ": sharper clip not available, using the normal stream");
            } catch (CancelToken.Cancelled c) { throw c; } catch (Exception e) {
                AppLog.i("Short #" + (clip.index + 1) + ": sharper clip fetch failed (" + shortErr(e) + "), using the normal stream");
            }
            seg.delete();
            t0 = System.currentTimeMillis();
        }
        String[] st = getStreams(pr, false);
        if (st == null || st[0] == null) return null;
        // v16 (2.7): only the clip's own fragments, found in the stream's index and fetched with parallel range
        // requests (2.6: FFmpeg read the stream over one connection, which YouTube throttles: 3.0 MB in 27.8 s)
        try {
            if (rangeCopy(clip, st[0], st.length > 1 ? st[1] : null, seg, "seg_" + clip.index, dir)) {
                Project.writeText(keyF, key);
                AppLog.i(String.format(Locale.US, "Short #%d: clip fetched in parallel parts (%.1f MB in %.1f s, video %s)", clip.index + 1,
                        seg.length() / 1048576.0, (System.currentTimeMillis() - t0) / 1000.0, st.length > 3 && st[3] != null ? st[3] : "?"));
                return seg;
            }
        } catch (CancelToken.Cancelled c) { throw c; } catch (Exception e) {
            AppLog.i("Short #" + (clip.index + 1) + ": fast clip fetch not possible (" + shortErr(e) + "), reading the stream");
        }
        try {
            t0 = System.currentTimeMillis();
            double d = clip.duration() + 1.0;
            List<String> a = new ArrayList<>(java.util.Arrays.asList("-y", "-hide_banner"));
            FfmpegCommands.addInput(a, st[0], clip.start, d);
            if (st[1] != null) FfmpegCommands.addInput(a, st[1], clip.start, d);
            a.addAll(java.util.Arrays.asList("-map", "0:v:0", "-map", st[1] != null ? "1:a:0" : "0:a:0?", "-sn", "-dn", "-c", "copy", seg.getAbsolutePath()));
            MediaRunner.run(a.toArray(new String[0]), d, null, ct);
            if (!seg.exists() || seg.length() < 50000) { seg.delete(); return null; }
            Project.writeText(keyF, key);
            AppLog.i(String.format(Locale.US, "Short #%d: clip fetched once for tracking + render (%.1f MB in %.1f s)", clip.index + 1,
                    seg.length() / 1048576.0, (System.currentTimeMillis() - t0) / 1000.0));
            return seg;
        } catch (CancelToken.Cancelled c) { throw c; } catch (Exception e) {
            seg.delete();
            AppLog.i("Short #" + (clip.index + 1) + ": clip copy not possible (" + shortErr(e) + "), reading the stream");
            return null;
        }
    }

    /**
     * v16: video (+ audio) fragments of [clip.start, clip.end + 1 s] fetched by byte range (DashRange), then copied into
     * one file that starts at clip.start (same stream-copy command as 2.5/2.6, now on local files). False = the stream
     * has no index (not DASH MP4): the caller reads the stream as before.
     */
    boolean rangeCopy(Project.Clip clip, String videoUrl, String audioUrl, File out, String tag, File dir) throws Exception {
        boolean high = Prefs.highSpeedOn(ctx);
        double from = clip.start, to = clip.end + 1.0, d = clip.duration() + 1.0;
        File vf = new File(dir, tag + ".v.part.mp4"), af = new File(dir, tag + ".a.part.m4a");
        try {
            DashRange.Got gv = DashRange.fetch(Net.downloadFor(videoUrl), videoUrl, from, to, vf, high, ct);
            if (gv == null) return false;
            DashRange.Got ga = null;
            if (audioUrl != null) { ga = DashRange.fetch(Net.downloadFor(audioUrl), audioUrl, from, to, af, high, ct); if (ga == null) return false; }
            List<String> a = new ArrayList<>(java.util.Arrays.asList("-y", "-hide_banner"));
            FfmpegCommands.addInput(a, vf.getAbsolutePath(), Math.max(0, from - gv.startSec), d);
            if (ga != null) FfmpegCommands.addInput(a, af.getAbsolutePath(), Math.max(0, from - ga.startSec), d);
            a.addAll(java.util.Arrays.asList("-map", "0:v:0", "-map", ga != null ? "1:a:0" : "0:a:0?", "-sn", "-dn", "-c", "copy", out.getAbsolutePath()));
            MediaRunner.run(a.toArray(new String[0]), d, null, ct);
            if (!out.exists() || out.length() < 20000) { out.delete(); return false; }
            return true;
        } finally { vf.delete(); af.delete(); }
    }

    /** v16: the small (about 360p) copy of the clip that face analysis reads; null = analyse the full-size copy */
    File analysisSegment(Project pr, Project.Clip clip, File dir) {
        synchronized (segLock(clip.index, "low")) {
            // v22: fetched ahead by the prefetch while an earlier short was busy: reuse it
            File low = new File(dir, "ft_low_" + clip.index + ".mp4"), keyF = new File(dir, "ft_low_" + clip.index + ".key");
            String key = String.format(Locale.US, "%.2f|%.2f|v22", clip.start, clip.end);
            if (low.exists() && low.length() > 20000 && key.equals(Project.readText(keyF))) return low;
            keyF.delete();
            File f = analysisSegmentInner(pr, clip, dir);
            if (f != null) { try { Project.writeText(keyF, key); } catch (Exception ignored) {} }
            return f;
        }
    }

    File analysisSegmentInner(Project pr, Project.Clip clip, File dir) {
        String[] st = getStreams(pr, false);
        if (st == null || st.length < 3 || st[2] == null) return null;
        File low = new File(dir, "ft_low_" + clip.index + ".mp4");
        try {
            long t0 = System.currentTimeMillis();
            if (rangeCopy(clip, st[2], st[1], low, "ft_low_" + clip.index, dir)) {
                AppLog.i(String.format(Locale.US, "Short #%d: small copy for face analysis (%.1f MB in %.1f s)", clip.index + 1,
                        low.length() / 1048576.0, (System.currentTimeMillis() - t0) / 1000.0));
                return low;
            }
        } catch (CancelToken.Cancelled c) { throw c; } catch (Exception e) {
            AppLog.i("Short #" + (clip.index + 1) + ": small analysis copy not possible (" + shortErr(e) + "), analysing the full-size clip");
        }
        low.delete();
        return null;
    }

    private FfmpegCommands.Track faceTrackFor(Project pr, Project.Clip clip, File dir, int quality, MediaRunner.Progress prog) throws Exception {
        if (!FaceAnalyzer.available(ctx)) { AppLog.i("Face tracking: models not available, normal framing"); return null; }
        double dur = clip.duration();
        String key = String.format(Locale.US, "ft21|%d|%.2f|%.2f", FaceAnalyzer.FPS, clip.start, clip.end);   // v21: fps in the key (8 fps now)
        File cache = new File(dir, "ft_" + clip.index + ".txt");
        List<FaceTrack.Sample> samples = null;
        int sw = 0, sh = 0;
        String txt = Project.readText(cache);
        if (txt != null && txt.startsWith(key + "\n")) {
            String body = txt.substring(txt.indexOf('\n') + 1);
            int nl = body.indexOf('\n');
            String[] dims = body.substring(0, nl).split("x");
            sw = Integer.parseInt(dims[0].trim()); sh = Integer.parseInt(dims[1].trim());
            samples = FaceTrack.samplesFromText(body.substring(nl + 1));
            AppLog.i("Face tracking: reusing the analysis of short #" + (clip.index + 1));
        }
        if (samples == null) {
            String video = null, audio = null;
            double t0s = clip.start;
            File low = null;
            Future<File> segF = null;
            ExecutorService bg = null;
            try {
                if (pr.localPath != null && !pr.localPath.isEmpty() && new File(pr.localPath).exists()) {
                    video = pr.localPath;
                    String la = new File(dir, "source_audio.m4a").getAbsolutePath();
                    if (!pr.isLocal() && new File(la).exists()) audio = la;
                } else if (!pr.isLocal()) {
                    // v16: the full-size clip (for the render) downloads in the background while a small ~360p copy is
                    // fetched and analysed: faces are found on small frames, the crop is taken from the full-size frames
                    bg = Executors.newSingleThreadExecutor();
                    segF = bg.submit(() -> clipSegment(pr, clip, dir));
                    low = analysisSegment(pr, clip, dir);
                    if (low != null) { video = low.getAbsolutePath(); audio = null; t0s = 0; }
                    else {
                        File seg = get(segF);
                        if (seg != null) { video = seg.getAbsolutePath(); audio = null; t0s = 0; }
                        else {
                            String[] st = getStreams(pr, false);
                            if (st != null) { video = st[0]; audio = st[1]; }
                        }
                    }
                }
                if (video == null) return null;
                final int n = clip.index + 1;
                // v22 (4.1): clip ready; the analysis reports real frames done into this short's FACES phase (4.0 wrote
                // "Tracking faces… Short #n" with no fraction into the shared status line, which flickered with the others)
                phase(clip.index, RenderProgress.PREP, 1);
                // code 31: a native crash of the face engine on the GPU ends the app; the next start moves it to the CPU
                FaceAnalyzer.prepare(ctx);   // code 32: 4.9 read onGpu() before the engine existed (first short: false) -> a GPU crash was not remembered
                boolean gpuFace = FaceAnalyzer.onGpu();
                if (gpuFace) RenderSafety.faceInflight(ctx, true);
                FaceAnalyzer.Result r;
                try {
                    try {
                        r = FaceAnalyzer.analyze(ctx, video, audio, t0s, dur, dir, String.valueOf(clip.index), ct,
                                f -> phase(clip.index, RenderProgress.FACES, f));
                    } catch (Exception fe) {
                        // code 32: the engine restarted under this clip's frame extraction: once more, whole clip
                        if (!(fe.getCause() instanceof MediaRunner.EngineCrash)) throw fe;
                        AppLog.i("Short #" + n + ": face frames were cut off by an engine restart, analysing again");
                        r = FaceAnalyzer.analyze(ctx, video, audio, t0s, dur, dir, String.valueOf(clip.index), ct,
                                f -> phase(clip.index, RenderProgress.FACES, f));
                    }
                } finally { if (gpuFace) RenderSafety.faceInflight(ctx, false); }
                samples = r.samples; sw = r.srcW; sh = r.srcH;
                AppLog.i(String.format(Locale.US, "Short #%d: faces analysed in %.1f s (%d frames, %d face checks + %d close-ups of wide shots, %d body checks%s; %d face engine%s at once)", n, r.seconds, samples.size(), r.faceCalls, r.tileCalls, r.poseCalls,
                        r.reusedHeads > 0 ? ", " + r.reusedHeads + " reused" : "", r.engines, r.engines == 1 ? "" : "s"));
                try { Project.writeText(cache, key + "\n" + sw + "x" + sh + "\n" + FaceTrack.samplesToText(samples)); } catch (Exception ignored) {}
            } finally {
                if (low != null) low.delete();
                if (segF != null) { try { get(segF); } catch (CancelToken.Cancelled c) { throw c; } catch (Exception ignored) {} }
                if (bg != null) bg.shutdownNow();
            }
        }
        FaceTrack.Plan plan = FaceTrack.plan(samples, FaceAnalyzer.FPS, sw, sh, dur, 30, FfmpegCommands.Aspect.of(pr.aspect).ratio());
        if (!plan.usable) { AppLog.i("Short #" + (clip.index + 1) + ": face tracking not used (" + plan.why + ")"); return null; }
        File cmd = new File(dir, "ft_" + clip.index + ".cmd");
        Project.writeText(cmd, plan.sendcmd());
        double[] j = plan.jitter(quality == 1 ? 1080 : 720);
        AppLog.i(String.format(Locale.US, "Short #%d face tracking: %s, %s", clip.index + 1, plan.summary(), plan.steadiness()));
        // v16: the quality line his next log needs: what the crop is cut from, how big the crop is, what it becomes
        int[] rs = renderSourceSize(pr, clip, dir);
        int[] od = FfmpegCommands.dims(FfmpegCommands.Aspect.of(pr.aspect).spec(quality == 1 ? 1080 : 720));
        if (rs != null) {
            int ch = rs[1], cw = (int) Math.min(rs[0], Math.round(rs[1] * plan.aspect / 2.0) * 2);
            double up = od[1] / (double) ch;
            AppLog.i(String.format(Locale.US, "Short #%d quality: source %dx%d, crop %dx%d -> output %dx%d (%s)", clip.index + 1, rs[0], rs[1], cw, ch, od[0], od[1],
                    up > 1.02 ? String.format(Locale.US, "enlarged %.2fx, lanczos + light sharpening", up) : "no enlarging, lanczos"));
            if (rs[1] < 720) AppLog.e("Short #" + (clip.index + 1) + ": YouTube gave only " + rs[1] + "p for this video, the tracked short will be softer");
        }
        return new FfmpegCommands.Track(cmd.getAbsolutePath(), plan, rs == null ? 0 : rs[1]);
    }

    /** v24: {mime, width, height} of a local video file's first video track (null = unreadable) */
    static Object[] probeVideo(File f) {
        if (f == null || !f.exists()) return null;
        android.media.MediaExtractor ex = new android.media.MediaExtractor();
        try {
            ex.setDataSource(f.getAbsolutePath());
            for (int i = 0; i < ex.getTrackCount(); i++) {
                android.media.MediaFormat mf = ex.getTrackFormat(i);
                String mime = mf.getString(android.media.MediaFormat.KEY_MIME);
                if (mime != null && mime.startsWith("video/"))
                    return new Object[]{mime, mf.containsKey("width") ? mf.getInteger("width") : 0, mf.containsKey("height") ? mf.getInteger("height") : 0};
            }
            return null;
        } catch (Exception e) {
            return null;
        } finally {
            try { ex.release(); } catch (Exception ignored) {}
        }
    }

    /** v24: is this clip taller than 1080p (the sharper source of a cropped short)? */
    static boolean hiClip(File f) {
        Object[] v = probeVideo(f);
        return v != null && Math.min((Integer) v[1], (Integer) v[2]) > 1088;
    }

    /**
     * v24: FFmpeg's MediaCodec decoder for a local clip / file (null = software decoding). Taller than 1080p: always
     * (VP9 / AV1 / HEVC at that size is too slow in software). Up to 1080p: unless this phone's hardware decoding failed
     * twice (or in this short already).
     */
    String decoderFor(File f, boolean failedHere) {
        Object[] v = probeVideo(f);
        if (v == null) return null;
        String mime = ((String) v[0]).toLowerCase(Locale.US);
        boolean big = Math.min((Integer) v[1], (Integer) v[2]) > 1088;
        if (failedHere) return null;
        if (!RenderSafety.hwDecodeAllowed(ctx)) return null;   // code 31: hardware decoding crashed on this phone (software decoding)
        if (!big && Prefs.sp(ctx).getInt("hw_dec_fail", 0) >= 2) return null;
        if (mime.contains("avc")) return "h264_mediacodec";
        if (mime.contains("hevc")) return "hevc_mediacodec";
        // v26: a VP9 / AV1 clip taller than this phone's hardware decoder handles is decoded in software (the sharper
        // 1440p source on phones without such a decoder); up to code 25 the MediaCodec decoder was asked anyway and failed
        String fam = mime.contains("vp9") ? "vp9" : mime.contains("av01") || mime.endsWith("/av1") ? "av1" : "";
        if (big && !fam.isEmpty() && HwDecode.maxHeight(fam) < Math.min((Integer) v[1], (Integer) v[2])) return null;
        return HwDecode.ffDecoder(mime);
    }

    /** {width, height} of the video the render will crop from (the fetched clip, or the local file); null = unknown */
    int[] renderSourceSize(Project pr, Project.Clip clip, File dir) {
        File f = new File(dir, "seg_" + clip.index + ".mp4");
        String path = f.exists() && f.length() > 50000 ? f.getAbsolutePath() : pr.localPath;
        if (path == null || path.isEmpty() || !new File(path).exists()) return null;
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(path);
            int w = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            String rot = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION);
            if ("90".equals(rot) || "270".equals(rot)) { int t = w; w = h; h = t; }
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (Exception e) {
            return null;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    static final boolean SINGLE_PASS = true;
    /** AAC encoder delay of the rendered short's audio: 1024 samples at its sample rate (code 27: 48 kHz) */
    public static final double AAC_PRIMING_SEC = 1024.0 / FfmpegCommands.AUDIO_RATE;

    static File speechFull(File dir) {
        for (String e : new String[]{".m4a", ".webm", ".ogg", ".mp3", ".opus"}) {
            File f = new File(dir, "speech_full" + e);
            if (f.exists() && f.length() > 1000) return f;
        }
        return null;
    }

    /** v10: the clip's speech cut from the source (local file, downloaded audio, or the audio stream) */
    private void extractSourceSpeech(Project pr, Project.Clip clip, File flac, int quality, boolean high, MediaRunner.Progress prog) throws Exception {
        double dur = clip.duration();
        List<String> inputs = new ArrayList<>();
        File dir = pr.dir(ctx);
        if (pr.isLocal()) { if (pr.localPath != null) inputs.add(pr.localPath); }
        else {
            File full = speechFull(dir);
            if (full != null) inputs.add(full.getAbsolutePath());   // v16: the audio downloaded for the transcript (local, instant)
            File la = new File(dir, "source_audio.m4a");
            if (la.exists() && la.length() > 1000) inputs.add(la.getAbsolutePath());
            if (pr.localPath != null && new File(pr.localPath).exists()) inputs.add(pr.localPath);
            String[] st = full != null ? null : getStreams(pr, false);
            if (st != null) inputs.add(st[1] != null ? st[1] : st[0]);
        }
        Exception last = null;
        for (String in : inputs) {
            try {
                MediaRunner.run(FfmpegCommands.sourceSpeechAudio(in, clip.start, dur, flac.getAbsolutePath()), 0, null, ct);
                if (flac.exists() && flac.length() > 2000) return;
            } catch (CancelToken.Cancelled c) { throw c; } catch (Exception e) { last = e; }
        }
        throw last != null ? last : new Exception("no audio source");
    }

    private String writeAss(Project pr, Project.Clip clip, List<Word> spoken, CaptionStyle style, CaptionLayout layout, String script) throws Exception {
        Transcript lt = new Transcript();
        lt.language = clip.spokenLang;
        List<Word> words = CaptionScript.convertAll(ctx, ct, lt, java.util.Collections.singletonList(spoken), script, warnings).get(0);
        File assF = new File(pr.dir(ctx), "captions_" + clip.index + ".ass");
        Project.writeText(assF, CaptionBuilder.buildAss(words, CaptionScript.styleFor(script, style), layout == null ? new CaptionLayout() : layout, clip.duration()));
        lastAssWords.put(clip.index, words);   // code 33: the backup engine draws the same words itself
        return assF.getAbsolutePath();
    }


    /**
     * v19 (3.0): when the captions must be re-timed on the rendered short. 2.9 re-rendered the whole short for a 40 ms
     * offset at match 0.99 (an [ERROR] line + ~35 s lost in his log) - 40 ms is one caption frame and below what anyone
     * can see. Now: a clear match (>= 0.9) within 0.12 s is in sync; a weak match (0.6-0.9) is tolerated to 0.08 s;
     * below 0.6 the measurement itself is not trusted (no re-render on a guess).
     */
    public static final double SYNC_TOL_SEC = 0.12, SYNC_TOL_WEAK_SEC = 0.08;
    public static boolean syncNeedsRetime(double lagSec, double match) {
        if (Double.isNaN(lagSec) || Double.isNaN(match) || match <= 0.6) return false;
        double off = Math.abs(lagSec - AAC_PRIMING_SEC);
        return match >= 0.9 ? off > SYNC_TOL_SEC : off > SYNC_TOL_WEAK_SEC;
    }

    /** {lagSec, correlation} of the rendered file's audio vs the audio the captions were timed on; null if unknown */
    private double[] verifySinglePass(File preFlac, File rendered) {
        try {
            if (preFlac == null || !preFlac.exists()) return null;
            float[] a = ClipSync.envelope(readPcm(preFlac, 0, 30), 16000);
            float[] b = ClipSync.envelope(readPcm(rendered, 0, 30), 16000);
            double[] r = ClipSync.lag(a, b, 0.5);
            AppLog.i(String.format(Locale.US, "Single-pass sync check: rendered audio %+.2f s vs transcribed (match %.2f) - %s", r[0], r[1],
                    syncNeedsRetime(r[0], r[1]) ? "off, captions will be re-timed" : r[1] <= 0.6 ? "weak match, kept as is" : "in sync \u2713"));
            return r;
        } catch (CancelToken.Cancelled c) { throw c; } catch (Exception e) { return null; }
    }

    // ================================================================== v9: captions timed from the short itself

    static String rawKey(Project.Clip c, FfmpegCommands.Frame frame, int outW) {
        return String.format(Locale.US, "%s|%d|%.2f|%.2f", frame == null ? "" : frame.name(), outW, c.start, c.end);
    }

    public static String spokenKey(Project.Clip c) { return String.format(Locale.US, "v10|%.2f|%.2f", c.start, c.end); }   // v10: re-time old caches with the new word timing

    static void copyFile(File a, File b) throws Exception {
        try (java.io.InputStream in = new java.io.FileInputStream(a); java.io.OutputStream o = new java.io.FileOutputStream(b)) {
            byte[] buf = new byte[1 << 16];
            int n;
            while ((n = in.read(buf)) > 0) o.write(buf, 0, n);
        }
    }

    private void finishClip(Project pr, Project.Clip clip, File out, String styleName, String note) {
        File old = clip.file();
        clip.outPath = out.getAbsolutePath();
        clip.captionStyle = styleName == null ? "" : styleName;
        clip.note = note == null ? "" : note;
        if (!clip.note.isEmpty()) { synchronized (warnings) { warnings.add("Short #" + (clip.index + 1) + ": " + clip.note); } }
        clip.saved = false;
        if (old != null && !old.equals(out) && !old.getAbsolutePath().equals(clip.rawPath)) old.delete();
        if (clip.index == 0 || !pr.thumbFile(ctx).exists()) saveThumb(pr, out);
        // v14: the clip copy used for tracking + render is not needed any more
        new File(pr.dir(ctx), "seg_" + clip.index + ".mp4").delete();
        new File(pr.dir(ctx), "seg_" + clip.index + ".key").delete();
        pr.save(ctx);
    }

    /** writes the captions for one finished (uncaptioned) short and burns them into {@code out} */
    private void captionPass(Project pr, Project.Clip clip, File raw, File out, Transcript tr, CaptionStyle style, CaptionLayout layout,
                             String script, int outW, int threads) throws Exception {
        ct.check();
        File dir = pr.dir(ctx);
        double dur = clip.duration();
        List<Word> spoken = spokenWords(pr, clip, raw, tr, script);
        if (spoken.isEmpty()) throw new Exception("no speech found in this short");
        Transcript lt = new Transcript();
        lt.language = clip.spokenLang;
        List<Word> words = CaptionScript.convertAll(ctx, ct, lt, java.util.Collections.singletonList(spoken), script, warnings).get(0);
        File assF = new File(dir, "captions_" + clip.index + ".ass");
        Project.writeText(assF, CaptionBuilder.buildAss(words, CaptionScript.styleFor(script, style), layout == null ? new CaptionLayout() : layout, dur));
        String fonts = MediaRunner.fontsDir(ctx).getAbsolutePath();
        FfmpegCommands.hwBoost = Prefs.learned(ctx, "hw_rate_boost", 1.0);
        FfmpegCommands.Encoder enc = hwEncoder(null, outW);
        Exception last = null;
        String dec = decoderFor(raw, false);   // v24: the raw short is decoded in hardware (software if that fails)
        int capCrashes = 0;
        boolean capNativeFailed = false;
        for (int a = 0; a < 6; a++) {   // v26: quality -> standard -> basic hardware settings -> software (+ software decoding)
            final int stBefore = RenderSafety.state(ctx);
            // code 33: on the backup engine (rung or Premium test switch) the captions are drawn by it too, no FFmpeg
            final boolean nat = !capNativeFailed && (RenderSafety.useNative(ctx) || Prefs.backupEngineTest(ctx));
            try {
                if (nat) {
                    AppLog.i("Short #" + (clip.index + 1) + " captions pass [backup video engine]");
                    MediaRunner.run(nativeArgs(pr, clip, raw.getAbsolutePath(), null, 0, dur, FfmpegCommands.Frame.FIT, words, style, layout, null, 0, out, outW), dur, null, ct);
                    if (!out.exists() || out.length() < 20000 || !validVideo(out, dur)) throw new Exception("backup engine captions output did not play back");
                    return;
                }
                MediaRunner.run(FfmpegCommands.burnCaptions(raw.getAbsolutePath(), assF.getAbsolutePath(), fonts, out.getAbsolutePath(), enc, outW, threads, dec), dur, null, ct);
                if (!out.exists() || out.length() < 20000) throw new Exception("captioned file is empty");
                if (enc.hw() && !validVideo(out, dur)) throw new HwException("hardware encoder output did not play back");
                if (enc.hw()) {
                    Prefs.hwLevelOk(ctx, rank(enc));
                    String n = hwRateNote(out, dur, outW, false); if (!n.isEmpty()) AppLog.i("Short #" + (clip.index + 1) + " captions pass" + n);
                    // v26 quality guard on the final (captioned) short
                    final String fDec = dec; final String rawP = raw.getAbsolutePath(), assP = assF.getAbsolutePath();
                    qualityGuard(out, dur, outW, (e2, path) -> FfmpegCommands.burnCaptions(rawP, assP, fonts, path, e2, outW, threads, fDec),
                            "Short #" + (clip.index + 1) + " captions pass", ct);
                }
                return;
            } catch (CancelToken.Cancelled c) {
                throw c;
            } catch (Exception e) {
                last = e;
                out.delete();
                if (nat && !RenderSafety.useNative(ctx)) {
                    // the Premium test switch: the backup engine failed, the captions are burnt the normal way
                    AppLog.e("Captions pass: backup video engine failed (" + shortErr(e) + "), drawing them the normal way");
                    capNativeFailed = true; a--; continue;
                }
                if (e instanceof MediaRunner.EngineCrash) {
                    // code 31: the separate video process crashed: the safer path RenderSafety moved this phone to.
                    // code 33: again only on a path that really changed (or after a low-memory stop, up to 3x)
                    MediaRunner.EngineCrash ec = (MediaRunner.EngineCrash) e;
                    boolean retry = ec.crash ? RenderSafety.state(ctx) != stBefore : ++capCrashes <= 3;
                    if (!RenderSafety.hwDecodeAllowed(ctx)) dec = null;
                    if (!RenderSafety.hwEncodeAllowed(ctx)) enc = FfmpegCommands.Encoder.X264;
                    else if (RenderSafety.x264Bad(ctx)) enc = hwEncoder(null, outW);
                    AppLog.e("Captions pass: " + e.getMessage() + (retry ? ", drawing them again with " + pathLabel(null, outW) : "; no safer video path is left on this phone"));
                    if (retry) { a--; continue; }
                    break;
                }
                if (nat) break;
                RenderFault.Kind kind = RenderFault.classify(e, false);
                if (enc.hw() && kind == RenderFault.Kind.ENCODER) { enc = encoderFailed(enc, kind, e, "Captions pass"); continue; }
                if (dec != null) { AppLog.e("Captions pass: hardware decoder failed (" + shortErr(e) + "), decoding in software"); dec = null; continue; }
                if (enc.hw()) { enc = encoderFailed(enc, kind, e, "Captions pass"); continue; }
                break;
            }
        }
        throw last == null ? new Exception("caption pass failed") : last;
    }

    static final String HI_PROMPT = "नमस्ते, आज हम हिंदी में बात कर रहे हैं।";

    /** the language a short's captions are transcribed in: "hi" for Hinglish/हिंदी; for English the speech language ("" = detect) */
    String clipLanguage(String script, Transcript tr) {
        if (CaptionScript.HINGLISH.equals(script) || CaptionScript.HINDI.equals(script)) return "hi";
        String job = langFamily(speechLang == null || speechLang.isEmpty() ? (tr == null ? "" : tr.language) : speechLang);
        // code 31: a job language other than English (Bengali from the title script...) is kept for each short too: the
        // short's own audio of a song was heard as English again, captions were English gibberish
        return "hi".equals(job) ? "hi" : !job.isEmpty() && !"en".equals(job) && !"xx".equals(job) ? job : "";   // anything else is checked on the short's own audio (v8 called Hindi "English")
    }

    /**
     * The words spoken in a finished short, timed on ITS timeline (0 = first frame), in the spoken language:
     * transcribed from the short's own audio. If that is impossible (no network / quota), the full transcript's words
     * are used, moved by the audio offset measured between the short and the source.
     */
    List<Word> spokenWords(Project pr, Project.Clip clip, File raw, Transcript tr, String script) throws Exception {
        return spokenWords(pr, clip, raw, tr, script, null);
    }

    /** true when the last spokenWords() call on this thread timed the words from the clip's own audio */
    private final ThreadLocal<Boolean> ownAudio = ThreadLocal.withInitial(() -> false);

    List<Word> spokenWords(Project pr, Project.Clip clip, File raw, Transcript tr, String script, File preFlac) throws Exception {
        double dur = clip.duration();
        ownAudio.set(false);
        String want = clipLanguage(script, tr);
        if (clip.spokenJson != null && !clip.spokenJson.isEmpty() && spokenKey(clip).equals(clip.spokenKey)
                && (want.isEmpty() || want.equals(clip.spokenLang))) {
            List<Word> cached = wordsFromJson(clip.spokenJson);
            if (!cached.isEmpty()) { ownAudio.set(true); return cached; }
        }
        File dir = pr.dir(ctx);
        File fa = preFlac != null ? preFlac : new File(dir, "clip_speech_" + clip.index + ".flac");
        if (preFlac == null && raw == null) return new ArrayList<>();
        if (preFlac == null) MediaRunner.run(FfmpegCommands.clipSpeechAudio(raw.getAbsolutePath(), fa.getAbsolutePath()), 0, null, ct);
        List<Word> words = null;
        String lang = want, how = "";
        try {
            if (lang.isEmpty()) {
                String d = null;
                try { d = new AiClient(ctx, ct).detectLanguage(fa); } catch (CancelToken.Cancelled c) { throw c; } catch (Exception ignored) {}
                lang = langFamily(d == null ? "" : d);
                if (lang.isEmpty()) lang = langFamily(tr == null ? "" : tr.language);
                if (lang.isEmpty()) lang = "en";
            }
            Transcript t = transcribeClipChecked(fa, lang, dur);
            if (t != null) {
                recoverDropouts(t, fa, lang, dur, clip);   // 5.4: stretches Whisper looped on / skipped are transcribed again in short pieces
                words = ClipSync.clipWords(t, dur);
                if (words.size() < Math.max(1, t.segments.size()) && !t.segments.isEmpty()) words = alignWithoutWordTimes(t, fa, dur);
                else {
                    // word starts that sit in silence move to where the speech begins (measured on this short's audio)
                    try { words = ClipSync.refineWords(words, ClipSync.envelope(readPcm(fa, 0, 0), 16000)); }
                    catch (CancelToken.Cancelled c) { throw c; } catch (Exception ignored) {}
                }
                how = "the short's own audio (" + lang + ")";
                ownAudio.set(!words.isEmpty());
            }
        } catch (CancelToken.Cancelled c) {
            throw c;
        } catch (Exception e) {
            AppLog.i("Short #" + (clip.index + 1) + ": its own audio could not be transcribed (" + AiClient.describe(e) + "), using the full transcript");
        }
        if ((words == null || words.isEmpty()) && raw == null) {
            // v16 (2.7): the short's own audio could not be transcribed (Groq's hourly limit, network). The speech cut
            // for this short comes from the same downloaded audio the full transcript was timed on, so the full
            // transcript's words are already on the short's timeline (his 2.6 log: +0.04 s). Use them now, in the same
            // single render (2.6 rendered, waited for the limit, transcribed the short and burned captions again:
            // +34 s). The render's own audio is still compared with this cut afterwards (sync check).
            if (preFlac != null && tr != null && speechFull(dir) != null && preFlac.exists()) {
                List<Word> fw = tr.wordsForClip(clip.start, clip.end);
                if (!fw.isEmpty()) {
                    List<Word> sh = new ArrayList<>();
                    for (Word w : fw) sh.add(w.shift(AAC_PRIMING_SEC));
                    clip.spokenLang = langFamily(tr.language);
                    clip.spokenJson = ""; clip.spokenKey = "";   // not the short's own audio: never cached as such
                    ownAudio.set(true);
                    AppLog.i("Short #" + (clip.index + 1) + " captions: " + sh.size() + " words from the full transcript (same audio, one render, no waiting)");
                    return sh;
                }
            }
            return new ArrayList<>();   // v10 single pass: the caller falls back to the v9 two-pass path
        }
        if (words == null || words.isEmpty()) {
            double off = 0;
            try { off = measureOffset(pr, clip, raw); } catch (CancelToken.Cancelled c) { throw c; } catch (Exception ignored) {}
            words = tr == null ? new ArrayList<>() : tr.wordsForClip(clip.start + off, clip.end + off);
            lang = langFamily(tr == null ? "" : tr.language);
            how = String.format(Locale.US, "the full transcript, moved %+.2f s (measured on the audio)", off);
        } else if (tr != null && "hi".equals(langFamily(tr.language)) == "hi".equals(lang)) {
            double[] o = ClipSync.wordOffset(words, tr.wordsForClip(clip.start, clip.end));
            if (o[2] >= 5) AppLog.i(String.format(Locale.US, "Short #%d timing check: the full transcript was %+.2f s off here (%d words compared); captions use the short's own audio",
                    clip.index + 1, o[0], (int) o[2]));
        }
        if (preFlac == null) fa.delete();
        else if (ownAudio.get()) {
            // v10: words timed on the source cut are moved onto the rendered file's timeline: the AAC encoder's 1024
            // priming samples at 44.1 kHz put the short's audio 23.2 ms later (measured: +23.25 ms on 3 clips)
            List<Word> sh = new ArrayList<>();
            for (Word w : words) sh.add(w.shift(AAC_PRIMING_SEC));
            words = sh;
        }
        clip.spokenLang = lang;
        clip.spokenJson = wordsToJson(words);
        clip.spokenKey = spokenKey(clip);
        AppLog.i("Short #" + (clip.index + 1) + " captions: " + words.size() + " words timed from " + how);
        return words;
    }

    /**
     * 5.4: a short's speech that Whisper looped on or skipped (his clip: 12-33 s garbled, then no captions while the voice
     * went on) is transcribed again in 6 s pieces and put into the gap. Never fails the short: any problem keeps what was heard.
     */
    private void recoverDropouts(Transcript t, File fa, String lang, double dur, Project.Clip clip) throws Exception {
        try {
            final AiClient ai = new AiClient(ctx, ct);
            final File dir = fa.getParentFile();
            final int[] n = {0};
            CaptionRecovery.recover(t, dur, (a, b) -> {
                File sub = new File(dir, "recover_" + clip.index + "_" + (n[0]++) + ".ogg");
                try {
                    MediaRunner.run(FfmpegCommands.cutAudioOpus(fa.getAbsolutePath(), a, b - a, sub.getAbsolutePath(), 1.0), 0, null, ct);
                    Transcript s = ai.transcribeClip(sub, lang, null);
                    if ("hi".equals(lang) && latinShare(s) > 0.6) {
                        Transcript s2 = ai.transcribeClip(sub, lang, HI_PROMPT);
                        if (latinShare(s2) > 0.6) return null;
                        s = s2;
                    }
                    return s;
                } catch (CancelToken.Cancelled c) {
                    throw c;
                } catch (Exception e) {
                    if (String.valueOf(e.getMessage()).startsWith("No speech found")) return null;
                    throw e;
                } finally { sub.delete(); }
            }, m -> AppLog.i("Short #" + (clip.index + 1) + ": " + m));
        } catch (CancelToken.Cancelled c) {
            throw c;
        } catch (Exception e) {
            AppLog.i("Short #" + (clip.index + 1) + ": speech re-check skipped (" + AiClient.describe(e) + ")");
        }
    }

    /** whisper-large-v3 on the short's audio; for Hindi an English (Roman) answer is refused: asked again, then Gemini */
    private Transcript transcribeClipChecked(File fa, String lang, double dur) throws Exception {
        AiClient ai = new AiClient(ctx, ct);
        Transcript t = ai.transcribeClip(fa, lang, null);
        if ("hi".equals(lang) && latinShare(t) > 0.6) {
            AppLog.i("A short came back in English letters for Hindi speech, asking again");
            Transcript t2 = ai.transcribeClip(fa, lang, HI_PROMPT);
            if (latinShare(t2) < latinShare(t)) t = t2;
        }
        if (t.looping()) {
            try { Transcript t2 = ai.transcribe(fa, lang, null, 0.4, new String[]{"whisper-large-v3"}, null); if (!t2.looping()) t = t2; }
            catch (CancelToken.Cancelled c) { throw c; } catch (Exception ignored) {}
        }
        if ("hi".equals(lang) && latinShare(t) > 0.6) {
            // still a translation: Gemini writes the Hindi text, the timing comes from Whisper's segments + the audio
            try {
                File small = new File(fa.getParentFile(), fa.getName() + ".ogg");
                MediaRunner.run(FfmpegCommands.speechAudioOpus(fa.getAbsolutePath(), small.getAbsolutePath()), 0, null, ct);
                Transcript g = ai.transcribeGemini(small, "hi");
                small.delete();
                if (g != null && latinShare(g) <= 0.6 && !g.segments.isEmpty()) { g.words.clear(); return g; }
            } catch (CancelToken.Cancelled c) { throw c; } catch (Exception ignored) {}
            return null;
        }
        return t;
    }

    /** share of Latin letters among Latin + Indic letters (1.0 = an English text) */
    static double latinShare(Transcript t) {
        if (t == null) return 1;
        int latin = 0, indic = 0;
        for (Word w : t.words) for (int i = 0; i < w.text.length(); i++) {
            char c = w.text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
            else if ((c >= 0x0900 && c <= 0x097F) || (c >= 0x0600 && c <= 0x06FF)) indic++;
        }
        if (latin + indic == 0) for (Transcript.Segment s : t.segments) for (int i = 0; i < s.text.length(); i++) {
            char c = s.text.charAt(i);
            if ((c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')) latin++;
            else if ((c >= 0x0900 && c <= 0x097F) || (c >= 0x0600 && c <= 0x06FF)) indic++;
        }
        return latin + indic == 0 ? 0 : (double) latin / (latin + indic);
    }

    /** segments without word times: each word placed by length, its edges moved to real pauses in the audio */
    private List<Word> alignWithoutWordTimes(Transcript t, File fa, double dur) throws Exception {
        float[] env = null;
        try { env = ClipSync.envelope(readPcm(fa, 0, 0), 16000); } catch (CancelToken.Cancelled c) { throw c; } catch (Exception ignored) {}
        List<Word> out = new ArrayList<>();
        for (Transcript.Segment s : t.segments) {
            String txt = s.text == null ? "" : s.text.replace("\uFFFD", "").trim();
            if (txt.isEmpty()) continue;
            List<Word> ws = ClipSync.alignSegment(txt.split("\\s+"), Math.max(0, s.start), Math.min(dur, s.end), env);
            if (!ws.isEmpty()) ws.get(ws.size() - 1).sentenceEnd = true;
            out.addAll(ws);
        }
        return out;
    }

    /** v22 (4.1): a short opens this long before its first spoken word */
    public static final double START_LEAD = 0.2;

    /**
     * v22 (4.1, Sakib: "shorts start 2-3 s before the topic"): every clip starts exactly 0.2 s before its first word is
     * really spoken. Whisper often stretches a sentence's first word back over the silence (or breath / music) before
     * it, and 4.0 started 0.12 s before that stretched time; the speech audio around the first word is measured here and
     * the start is put 0.2 s before the voice actually begins - never inside the previous word, never inside the first one.
     */
    void snapStartsToVoice(List<Project.Clip> clips, Transcript tr, File audio) {
        if (clips.isEmpty() || tr == null || tr.words == null || tr.words.isEmpty()) return;
        long t0 = System.currentTimeMillis();
        int moved = 0; double maxShift = 0;
        for (Project.Clip c : clips) {
            try {
                double s = audioStart(c.start, c.end, tr.words, audio);
                if (s >= 0 && Math.abs(s - c.start) > 0.02 && c.end - s >= 3) {
                    maxShift = Math.max(maxShift, Math.abs(s - c.start));
                    c.start = s; moved++;
                }
            } catch (CancelToken.Cancelled x) { throw x; }
            catch (Throwable ignored) {}
        }
        AppLog.i(String.format(Locale.US, "Clip starts: %d of %d set to %.1f s before the first spoken word (largest move %.1f s, %.1f s)", moved, clips.size(), START_LEAD, maxShift, (System.currentTimeMillis() - t0) / 1000.0));
    }

    /** new start for one clip (-1 = keep): the voice onset of its first word from the audio, else the word time */
    double audioStart(double start, double end, List<Word> words, File audio) throws Exception {
        int i = firstWordIndex(words, start, end);
        if (i < 0) return -1;
        Word w = words.get(i);
        double prevEnd = i > 0 ? words.get(i - 1).end : 0;
        double onset = -1;
        if (audio != null && audio.exists()) {
            double from = Math.max(0, Math.min(prevEnd, w.start) - 1.0), len = Math.min(10.0, w.end + 1.5 - from);
            if (len > 1) {
                short[] pcm = readPcm(audio, from, len);
                if (pcm.length >= 1600) onset = voiceOnset(ClipSync.envelope(pcm, 16000), from, w.start, w.end, prevEnd);
            }
        }
        return startBefore(onset >= 0 ? onset : w.start, prevEnd);
    }

    /** index of the clip's first word: the first one that has not finished by the start (a start inside a word keeps the whole word) */
    public static int firstWordIndex(List<Word> words, double start, double end) {
        for (int i = 0; i < words.size(); i++) {
            Word w = words.get(i);
            if (w.end > start + 0.05) return w.start < end ? i : -1;
        }
        return -1;
    }

    /** START_LEAD before the first word's voice onset, but after the previous word (its tail never opens the short) */
    public static double startBefore(double onset, double prevEnd) {
        double s = onset - START_LEAD;
        if (prevEnd > 0) s = Math.max(s, Math.min(prevEnd + 0.01, onset - 0.03));
        return Math.max(0, s);
    }

    /**
     * pure logic (unit-tested): the moment the first word's voice begins, from a 10 ms loudness envelope starting at
     * {@code from}. Searched between the previous word's end (or 0.5 s before Whisper's start) and Whisper's end of the
     * word; a voiced run must last >= 60 ms. -1 = no clear speech/silence contrast (use the word time).
     */
    public static double voiceOnset(float[] env, double from, double wStart, double wEnd, double prevEnd) {
        if (env == null || env.length < 50) return -1;
        double hop = 0.01;
        float[] sorted = env.clone();
        java.util.Arrays.sort(sorted);
        // the quiet level from the quietest 3% (a fast talker leaves only short gaps in a 6-10 s window)
        double floor = sorted[(int) (sorted.length * 0.03)], speech = sorted[(int) (sorted.length * 0.90)];
        if (speech - floor < 0.3) return -1;
        double thr = floor + 0.3 * (speech - floor);
        double lo = Math.max(wStart - 0.5, prevEnd + 0.02), hi = wEnd - 0.03;
        int a = (int) Math.max(0, Math.round((lo - from) / hop)), b = (int) Math.min(env.length - 1, Math.round((hi - from) / hop));
        if (b - a < 3) return -1;
        // the previous word may still ring into the window: skip any voiced run touching the window's left edge
        int f = a;
        if (prevEnd > 0 && env[f] >= thr) { while (f < b && env[f] >= thr) f++; while (f < b && env[f] < thr) f++; if (f >= b) return -1; }
        for (; f <= b - 5; f++) {
            if (env[f] < thr) continue;
            int k = f;
            while (k < env.length && env[k] >= thr && k - f < 6) k++;
            if (k - f >= 6) return from + f * hop;
        }
        return -1;
    }

    /**
     * v18 (2.9): moves each clip's end onto the real pause in the audio. Whisper's word end times are often a little
     * early (the last syllable got cut) or late; here the speech audio around the end is measured and the clip ends
     * ~0.12 s after the voice really stops, never inside the next word.
     */
    void snapEndsToAudio(List<Project.Clip> clips, Transcript tr, File audio) {
        if (audio == null || !audio.exists() || clips.isEmpty()) return;
        long t0 = System.currentTimeMillis();
        int moved = 0;
        for (Project.Clip c : clips) {
            try {
                double[] r = audioEnd(c.start, c.end, tr, audio);
                if (r != null && Math.abs(r[0] - c.end) > 0.02) { c.end = r[0]; moved++; }
            } catch (CancelToken.Cancelled x) { throw x; }
            catch (Throwable ignored) {}
        }
        AppLog.i(String.format(Locale.US, "Clip endings: %d of %d moved onto the pause in the audio (%.1f s)", moved, clips.size(), (System.currentTimeMillis() - t0) / 1000.0));
    }

    /** {newEnd} for one clip, or null (keep). Pure logic in endOnPause (unit-tested). */
    double[] audioEnd(double start, double end, Transcript tr, File audio) throws Exception {
        double lastEnd = -1, nextStart = Double.MAX_VALUE;
        for (Word w : tr.words) {
            if (w.end <= end + 0.02 && w.start >= start) lastEnd = Math.max(lastEnd, w.end);
            if (w.start >= end - 0.02 && w.start < nextStart) nextStart = w.start;
        }
        if (lastEnd < 0) return null;
        double from = Math.max(0, lastEnd - 6.0), len = Math.min(8.0, (nextStart == Double.MAX_VALUE ? lastEnd + 2.0 : Math.min(lastEnd + 2.0, nextStart + 0.3)) - from);
        if (len <= 1) return null;
        short[] pcm = readPcm(audio, from, len);
        if (pcm.length < 1600) return null;
        float[] env = ClipSync.envelope(pcm, 16000);
        double e = endOnPause(env, from, lastEnd, nextStart);
        return e < 0 ? null : new double[]{e};
    }

    /**
     * v18: given a loudness envelope (10 ms frames starting at {@code from}), the last word's transcript end and the next
     * word's start: the time ~0.12 s after the voice really stops. -1 = nothing reliable.
     */
    public static double endOnPause(float[] env, double from, double lastEnd, double nextStart) {
        if (env == null || env.length < 50) return -1;
        double hop = 0.01;
        float[] sorted = env.clone();
        java.util.Arrays.sort(sorted);
        double floor = sorted[(int) (sorted.length * 0.10)], speech = sorted[(int) (sorted.length * 0.90)];
        if (speech - floor < 0.3) return -1;              // no clear speech/silence contrast here
        double thr = floor + 0.3 * (speech - floor);
        int le = (int) Math.round((lastEnd - from) / hop);
        double hardMax = Math.min(lastEnd + 0.9, nextStart == Double.MAX_VALUE ? Double.MAX_VALUE : nextStart - 0.04);
        int maxF = (int) Math.min(env.length - 1, Math.floor((hardMax - from) / hop));
        int minF = (int) Math.max(1, Math.round((lastEnd - 0.35 - from) / hop));
        if (le < minF || le > env.length - 1) le = Math.max(minF, Math.min(env.length - 1, le));
        // walk forward from the transcript's end while the voice is still on (its last syllable)
        int f = le;
        while (f < maxF && env[f] >= thr) f++;
        // then require a real quiet stretch (>= 80 ms)
        int q = f;
        while (q < maxF) {
            int k = q;
            while (k < maxF && env[k] < thr) k++;
            if (k - q >= 8 || k >= maxF) break;
            q = k; while (q < maxF && env[q] >= thr) q++;
        }
        double voiceEnd = from + q * hop;
        if (voiceEnd < lastEnd - 0.35) voiceEnd = lastEnd;
        double e = Math.min(voiceEnd + 0.12, hardMax);
        return Math.max(e, Math.min(lastEnd + 0.05, hardMax));
    }

    /** decoded 16 kHz mono PCM of [start, start+len) of a file (len 0 = to the end) */
    private short[] readPcm(File f, double start, double len) throws Exception {
        File pcm = new File(f.getParentFile(), f.getName() + "_" + System.nanoTime() + ".pcm");
        try {
            MediaRunner.run(FfmpegCommands.speechPcm(f.getAbsolutePath(), start, len, pcm.getAbsolutePath()), 0, null, ct);
            byte[] b = IoUtil.readAll(pcm);
            short[] s = new short[b.length / 2];
            java.nio.ByteBuffer.wrap(b).order(java.nio.ByteOrder.LITTLE_ENDIAN).asShortBuffer().get(s);
            return s;
        } finally { pcm.delete(); }
    }

    /** seconds the short's audio really starts after clip.start in the source the transcript was made from (0 = unknown) */
    private double measureOffset(Project pr, Project.Clip clip, File raw) throws Exception {
        File dir = pr.dir(ctx);
        File src = new File(dir, "source_audio.m4a");
        if (!src.exists() && pr.localPath != null && !pr.localPath.isEmpty()) src = new File(pr.localPath);
        if (!src.exists()) return 0;
        double len = Math.min(30, clip.duration());
        double lead = Math.min(12, clip.start);
        float[] a = ClipSync.envelope(readPcm(raw, 0, len), 16000);
        float[] b = ClipSync.envelope(readPcm(src, clip.start - lead, len + lead + 12), 16000);
        double[] r = ClipSync.audioOffset(a, b, lead);
        AppLog.i(String.format(Locale.US, "Short #%d audio offset vs source: %+.2f s (match %.2f)", clip.index + 1, r[0], r[1]));
        return r[1] >= 0.5 ? r[0] : 0;
    }

    static String wordsToJson(List<Word> ws) {
        org.json.JSONArray a = new org.json.JSONArray();
        try { for (Word w : ws) a.put(new org.json.JSONObject().put("t", w.text).put("s", w.start).put("e", w.end).put("x", w.sentenceEnd)); }
        catch (Exception ignored) {}
        return a.toString();
    }

    public static String wordsToJsonPublic(List<Word> ws) { return wordsToJson(ws); }

    public static List<Word> wordsFromJson(String s) {
        List<Word> out = new ArrayList<>();
        try {
            org.json.JSONArray a = new org.json.JSONArray(s);
            for (int i = 0; i < a.length(); i++) {
                org.json.JSONObject o = a.getJSONObject(i);
                Word w = new Word(o.getString("t"), o.getDouble("s"), o.getDouble("e"));
                w.sentenceEnd = o.optBoolean("x", false);
                out.add(w);
            }
        } catch (Exception ignored) {}
        return out;
    }

    static final int SRC_STREAM = 1, SRC_STREAM_FRESH = 2, SRC_FILE = 4, SAFE = 8, NOCAP = 16;
    /** non-fatal problems the user should see on the results screen (never silent) */
    public final List<String> warnings = new ArrayList<>();
    /** clip index -> caption words already in the chosen language (filled by renderAll) */
    private final java.util.Map<Integer, List<Word>> capWords = new java.util.concurrent.ConcurrentHashMap<>();

    static final class HwException extends Exception { HwException(String m) { super(m); } }

    private static String shortErr(Exception e) {
        String m = e.getMessage() == null ? e.toString() : e.getMessage();
        return m.length() > 300 ? m.substring(m.length() - 300) : m;
    }

    /** the file really decodes: has video, a sane duration and a first frame */
    /**
     * v23 (code 23): what the phone's hardware encoder really delivered for this file (video bitrate = size minus the
     * 192k audio). Many encoders give less than they are asked; the ratio is learned per phone (hw_rate_boost, up to
     * 2.5x) so the next shorts ask enough to really land at 18 Mbps (1080p) / 10 Mbps (720p) (code 27). Returns a note for the log.
     */
    private String hwRateNote(File f, double dur, int outW, boolean intermediate) {
        try {
            if (f == null || !f.exists() || dur < 6) return "";
            double mbps = f.length() * 8.0 / dur / 1e6 - FfmpegCommands.AUDIO_MBPS;
            long base = FfmpegCommands.hwBaseKbps(outW, intermediate);
            double was = FfmpegCommands.hwBoost;
            Prefs.learn(ctx, "hw_rate_boost", FfmpegCommands.nextBoost(was, base, mbps));
            double now = Prefs.learned(ctx, "hw_rate_boost", 1.0);
            FfmpegCommands.hwBoost = now;
            return String.format(Locale.US, ", video %.1f Mbps for a %.0f Mbps target%s", mbps, base / 1000.0,
                    Math.abs(now - was) > 0.05 ? String.format(Locale.US, ", encoder correction %.2fx -> %.2fx", Math.max(1, was), Math.max(1, now)) : "");
        } catch (Exception e) {
            return "";
        }
    }

    static boolean validVideo(File f, double expected) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String hv = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO);
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            double sec = d == null ? 0 : Long.parseLong(d) / 1000.0;
            if (!"yes".equals(hv) || sec < Math.min(1.0, expected * 0.5)) return false;
            Bitmap b = r.getFrameAtTime((long) (Math.min(1.0, sec / 2) * 1_000_000), MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            return b != null;
        } catch (Exception e) {
            return false;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private void saveThumb(Project pr, File video) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(video.getAbsolutePath());
            Bitmap b = r.getFrameAtTime(1_000_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
            if (b == null) return;
            int w = 270, h = Math.max(1, b.getHeight() * w / Math.max(1, b.getWidth()));
            Bitmap s = Bitmap.createScaledBitmap(b, w, h, true);
            try (FileOutputStream fo = new FileOutputStream(pr.thumbFile(ctx))) { s.compress(Bitmap.CompressFormat.JPEG, 82, fo); }
        } catch (Exception ignored) {
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    private synchronized String[] getStreams(Project pr, boolean refresh) {
        if (!refresh && streams != null && System.currentTimeMillis() - streamsAt < 4 * 3600 * 1000L) return streams;
        String[] s = YtEngine.resolveStreamsH(pr.url, srcMaxH, ct);
        if (s != null) { streams = s; streamsAt = System.currentTimeMillis(); AppLog.i("Direct stream ready (" + (s[1] != null ? "HD video + audio" : "single stream") + ")"); }
        return s;
    }

    private synchronized void ensureDownloaded(Project pr, int quality, boolean high, MediaRunner.Progress prog) throws Exception {
        if (pr.localPath != null && !pr.localPath.isEmpty() && new File(pr.localPath).exists()) return;
        AppLog.i("Direct streaming failed, downloading the video instead…");
        File[] f = YtEngine.downloadStreams(pr.url, pr.dir(ctx), quality, pr.duration, high,
                x -> jm.step(jm.stage, -1, "Downloading the video", String.format(Locale.US, "%.0f%%", x)), ct);
        pr.localPath = f[0].getAbsolutePath();
    }

    private File copyLocal(Uri uri, File dir) throws Exception {
        String name = "source_local.mp4";
        long size = -1;
        try (Cursor c = ctx.getContentResolver().query(uri, null, null, null, null)) {
            if (c != null && c.moveToFirst()) {
                int si = c.getColumnIndex(OpenableColumns.SIZE);
                if (si >= 0) size = c.getLong(si);
            }
        } catch (Exception ignored) {}
        File out = new File(dir, name);
        try (InputStream in = ctx.getContentResolver().openInputStream(uri); FileOutputStream fo = new FileOutputStream(out)) {
            if (in == null) throw new Exception("Cannot open the selected video");
            byte[] b = new byte[262144];
            long done = 0; int r;
            long last = 0;
            while ((r = in.read(b)) > 0) {
                ct.check();
                fo.write(b, 0, r);
                done += r;
                long now = System.currentTimeMillis();
                if (size > 0 && now - last > 150) {
                    last = now;
                    step(0, (double) done / size, "Importing your video", String.format(Locale.US, "%.0f of %.0f MB", done / 1048576.0, size / 1048576.0));
                }
            }
        }
        return out;
    }

    public static double probeDuration(File f) {
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(f.getAbsolutePath());
            String d = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
            return d == null ? 0 : Long.parseLong(d) / 1000.0;
        } catch (Exception e) {
            return 0;
        } finally {
            try { r.release(); } catch (Exception ignored) {}
        }
    }

    static String mmss(double s) {
        long t = (long) s;
        return t >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60) : String.format(Locale.US, "%02d:%02d", t / 60, t % 60);
    }
}
