package com.viral.shortsmaker.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * Builds FFmpeg argument arrays (no shell quoting problems). Pure Java, unit tested.
 * Renders target the chosen format (720p / 1080p class) @30fps H.264: the phone's hardware encoder, x264 as fallback.
 */
public final class FfmpegCommands {
    public enum Frame { FIT, BLUR, FILL }

    /**
     * v14 output format. The render size is "spec" = short side (720/1080) + 10000 x aspect code, so every existing
     * caller that passes 720/1080 still means 9:16 (unchanged), and caches keyed on it never mix formats.
     *   PORTRAIT 9:16 -> 720x1280 / 1080x1920, SQUARE 1:1 -> 720x720 / 1080x1080, LANDSCAPE 16:9 -> 1280x720 / 1920x1080
     */
    public enum Aspect {
        PORTRAIT("9:16", 9, 16), SQUARE("1:1", 1, 1), LANDSCAPE("16:9", 16, 9);
        public final String label; public final int num, den;
        Aspect(String l, int n, int d) { label = l; num = n; den = d; }
        /** width / height */
        public double ratio() { return (double) num / den; }
        public String expr() { return num == den ? "1" : num + "/" + den; }
        public int spec(int shortSide) { return (shortSide >= 1080 ? 1080 : shortSide <= 480 ? 480 : 720) + 10000 * ordinal(); }
        public static Aspect of(String s) {
            if (s != null) for (Aspect a : values()) if (a.name().equalsIgnoreCase(s) || a.label.equals(s)) return a;
            return PORTRAIT;
        }
    }

    public static Aspect aspectOf(int spec) { int k = spec / 10000; return k <= 0 || k >= Aspect.values().length ? Aspect.PORTRAIT : Aspect.values()[k]; }

    /** {width, height} of a render spec (both even) */
    public static int[] dims(int spec) {
        int s = spec % 10000;
        int sh = s >= 1080 ? 1080 : s <= 480 && s > 0 ? 480 : 720;
        Aspect a = aspectOf(spec);
        int w, h;
        if (a == Aspect.PORTRAIT) { w = sh; h = sh * 16 / 9; }
        else if (a == Aspect.SQUARE) { w = sh; h = sh; }
        else { h = sh; w = sh * 16 / 9; }
        if (sh == 480 && a != Aspect.SQUARE) { if (a == Aspect.PORTRAIT) h = 854; else w = 854; }
        return new int[]{w & ~1, h & ~1};
    }

    private FfmpegCommands() {}

    static String f3(double v) { return String.format(Locale.US, "%.3f", v); }

    /** escape a path for use inside a filtergraph option value */
    public static String filterPath(String p) {
        return p.replace("\\", "\\\\").replace(":", "\\:").replace("'", "\\'").replace(",", "\\,").replace("[", "\\[").replace("]", "\\]");
    }

    /**
     * code 35 (5.3): the colour tag every video graph starts with. The shipped FFmpeg (ffmpeg-kit, FFmpeg 8.1.2) hands its
     * filter graph an input whose colour space reads "gbr" (RGB) instead of the decoded video's own (BT.709), so every
     * scaler in the graph (scale, the encoder's format step) saw a YUV->YUV conversion with "different" colour matrices
     * and went through an intermediate RGB picture: an extra YUV->RGB->YUV round trip on every frame, and the NEON RGB
     * input routine of libswscale (+0x239a4) that reads past its buffer and crashed the vivo Y16 (Helio P35). The
     * output was also labelled "gbr" (identity matrix), which some players show with wrong colours. setparams labels
     * the frames and the graph's first link as BT.709, limited (TV) range, before anything else runs: every scaler then
     * sees the same matrix on both sides and converts YUV->YUV directly (no RGB picture), and the short is labelled
     * BT.709 / TV range. The pixel values of a BT.709 source pass through unchanged (no colour change: kit PSNR vs a
     * correct reference rose, see PROGRESS 5.3). YouTube serves HD video as BT.709 / TV range.
     */
    public static final String COLOUR_IN = "setparams=field_mode=auto:range=tv:color_primaries=bt709:color_trc=bt709:colorspace=bt709";

    /** code 35: a graph that starts at the main video input gets {@link #COLOUR_IN} first. Idempotent. */
    public static String colourIn(String graph) {
        if (graph == null || graph.contains(COLOUR_IN)) return graph;
        if (graph.startsWith("[0:v]")) return "[0:v]" + COLOUR_IN + "," + graph.substring(5);
        return COLOUR_IN + "," + graph;   // a plain -vf chain
    }

    public static String frameGraph(Frame frame, String assPath, String fontsDir) { return frameGraph(frame, assPath, fontsDir, 720); }

    public static String frameGraph(Frame frame, String assPath, String fontsDir, int outW) { return frameGraph(frame, assPath, fontsDir, outW, "yuv420p"); }

    /** v24: the audio's first 80 ms fade in (no click when the short starts right before the first word) */
    public static final String AUDIO_IN = "afade=t=in:st=0:d=0.08";

    public static String frameGraph(Frame frame, String assPath, String fontsDir, int outW, String pixFmt) {
        return frameGraph(frame, assPath, fontsDir, outW, pixFmt, 0);
    }

    public static String frameGraph(Frame frame, String assPath, String fontsDir, int outW, String pixFmt, int srcH) {
        int[] d = dims(outW);
        int ow = d[0], oh = d[1];
        Aspect asp = aspectOf(outW);
        String r = asp.expr(), ir = asp.num == asp.den ? "1" : asp.den + "/" + asp.num;
        // small blurred background: 144x256 for 9:16, 192x192 for 1:1, 256x144 for 16:9
        int bw = asp == Aspect.PORTRAIT ? 144 : asp == Aspect.SQUARE ? 192 : 256, bh = asp == Aspect.PORTRAIT ? 256 : asp == Aspect.SQUARE ? 192 : 144;
        String g;
        switch (frame) {
            case FILL:
                // v6: crop the output-shaped window first, then scale only that (v5 scaled the whole wide frame up, then cropped)
                g = "[0:v]fps=30,crop=w='min(iw,trunc(ih*" + r + "/2)*2)':h='min(ih,trunc(iw*" + ir + "/2)*2)'," + scaleHq(ow, oh, asp == Aspect.LANDSCAPE ? 0 : srcH, oh) + ",setsar=1";   // v16: sharp crop; v24: + light sharpening when enlarged
                break;
            case BLUR:
                // v6: 60 fps sources are cut to 30 fps BEFORE any filtering (v5 filtered every frame and dropped half at
                // the encoder); the background is blurred and darkened small and only then upscaled
                g = "[0:v]fps=30,split=2[a][b];"
                        + "[a]scale=" + bw + ":" + bh + ":force_original_aspect_ratio=increase:flags=fast_bilinear,crop=" + bw + ":" + bh + ",boxblur=4:2,eq=brightness=-0.06,"
                        + "scale=" + ow + ":" + oh + ":flags=bilinear[bg];"
                        + "[b]scale=" + ow + ":" + oh + ":force_original_aspect_ratio=decrease:flags=lanczos[fg];"   // v23: lanczos (box: PSNR of this step 55.8 -> 63.4 dB, same filter time)
                        + "[bg][fg]overlay=(W-w)/2:(H-h)/2:shortest=1,setsar=1";
                break;
            case FIT:
            default:
                // v21 (4.0): bicubic, not fast_bilinear (box test, 1080p source -> 1080x1920: PSNR vs ideal 50.0 -> 64.6 dB,
                // only 11% more filter time)
                // v23 (code 23): lanczos (box, 1080p source -> 1080x1920 frame: PSNR of the scale step 55.8 -> 63.4 dB, same filter time)
                g = "[0:v]fps=30,scale=" + ow + ":" + oh + ":force_original_aspect_ratio=decrease:flags=lanczos,"
                        + "pad=" + ow + ":" + oh + ":(ow-iw)/2:(oh-ih)/2:color=black,setsar=1";
        }
        if (assPath != null) g += "," + subtitles(assPath, fontsDir);
        return g + ",format=" + pixFmt + "[v]";
    }

    /** v13: what the face-tracking render needs: the sendcmd script on disk and the plan it was written from */
    public static final class Track {
        public final String cmdPath;
        public final FaceTrack.Plan plan;
        /** v16: height of the video the render crops from (0 = unknown); below the output height = light sharpening */
        public int srcH;
        public Track(String cmdPath, FaceTrack.Plan plan) { this.cmdPath = cmdPath; this.plan = plan; }
        public Track(String cmdPath, FaceTrack.Plan plan, int srcH) { this(cmdPath, plan); this.srcH = srcH; }
    }

    /**
     * v13 face tracking: a 9:16 window (full height) whose x follows the camera path, driven per frame by sendcmd
     * (crop@m). Split-screen parts: two 9:8 panels (crop@t / crop@b) stacked and overlaid only inside those parts.
     * Nothing here changes timestamps: fps=30 first, as in every other framing, so captions stay where they were.
     */
    public static String trackGraph(Track tr, String assPath, String fontsDir, int outW, String pixFmt) {
        int[] d = dims(outW);
        int ow = d[0], oh = d[1];
        FaceTrack.Plan p = tr.plan;
        String cropWH = "w='min(iw,trunc(ih*" + aspectOf(outW).expr() + "/2)*2)':h='trunc(ih/2)*2'";
        String scaleMain = scaleHq(ow, oh, tr.srcH, oh) + ",setsar=1";
        String g;
        if (!p.hasSplitSegments()) {
            g = "[0:v]fps=30,sendcmd=f=" + filterPath(tr.cmdPath) + ",crop@m=" + cropWH + ":x='(iw-ow)*" + String.format(Locale.US, "%.5f", p.lx(0)) + "':y=0," + scaleMain;
        } else {
            // v22 (4.1): one path per frame. The timeline is cut into camera segments and split parts (trim by frame
            // number at 30 fps, so nothing moves in time), each segment does only its own crop + scale, and concat joins
            // them back in order. 4.0 scaled + sharpened both hidden split panels on every frame (overlay enable only
            // hid them): box test, 30 s 1080p clip with 2 split parts, filter time 5.1 s -> 2.3 s, identical frames.
            // A split part's panels are static (per part), so their positions are baked in here, not sent by sendcmd.
            List<int[]> segs = p.segments();
            boolean anyCam = false;
            for (int[] sg : segs) if (sg[0] == 0) anyCam = true;
            StringBuilder sb = new StringBuilder("[0:v]fps=30," + (anyCam ? "sendcmd=f=" + filterPath(tr.cmdPath) + "," : "") + "split=" + segs.size());
            for (int k = 0; k < segs.size(); k++) sb.append("[s").append(k).append(']');
            sb.append(';');
            StringBuilder cat = new StringBuilder();
            for (int k = 0; k < segs.size(); k++) {
                int[] sg = segs.get(k);
                boolean last = k == segs.size() - 1;
                sb.append("[s").append(k).append("]trim=start_frame=").append(sg[1]);
                if (!last) sb.append(":end_frame=").append(sg[2]);
                sb.append(",setpts=PTS-STARTPTS,");
                if (sg[0] == 0) {
                    sb.append(p.camName(sg[3])).append('=').append(cropWH).append(":x='(iw-ow)*").append(String.format(Locale.US, "%.5f", p.lx(sg[1]))).append("':y=0,")
                      .append(scaleMain).append("[o").append(k).append("];");
                } else {
                    FaceTrack.Split sp = p.splits.get(sg[3]);
                    String pw = String.format(Locale.US, "%.5f", sp.pw), ph = String.format(Locale.US, "%.5f", sp.ph);
                    String wh = "w='trunc(iw*" + pw + "/2)*2':h='trunc(ih*" + ph + "/2)*2'";
                    String sc = scaleHq(ow, oh / 2, tr.srcH <= 0 ? 0 : (int) Math.round(tr.srcH * sp.ph), oh / 2) + ",setsar=1";
                    sb.append("split=2[t").append(k).append("][b").append(k).append("];")
                      .append("[t").append(k).append("]crop=").append(wh).append(String.format(Locale.US, ":x='(iw-ow)*%.5f':y='(ih-oh)*%.5f',", FaceTrack.lxOf(sp.topCx, sp.pw), FaceTrack.lyOf(sp.topCy, sp.ph)))
                      .append(sc).append("[tv").append(k).append("];")
                      .append("[b").append(k).append("]crop=").append(wh).append(String.format(Locale.US, ":x='(iw-ow)*%.5f':y='(ih-oh)*%.5f',", FaceTrack.lxOf(sp.botCx, sp.pw), FaceTrack.lyOf(sp.botCy, sp.ph)))
                      .append(sc).append("[bv").append(k).append("];")
                      .append("[tv").append(k).append("][bv").append(k).append("]vstack=inputs=2,setsar=1[o").append(k).append("];");
                }
                cat.append("[o").append(k).append(']');
            }
            sb.append(cat).append("concat=n=").append(segs.size()).append(":v=1:a=0,setsar=1");
            g = sb.toString();
        }
        if (assPath != null) g += "," + subtitles(assPath, fontsDir);
        return g + ",format=" + pixFmt + "[v]";
    }

    /**
     * v16 (2.7): the crop is a narrow part of the source, so it is scaled with lanczos (sharpest swscale filter, 2.6 used
     * bilinear) and, only when it has to be enlarged (crop height below the output height), lightly sharpened on luma.
     * cropH = pixel height of the cropped area (0 = unknown: no sharpening).
     */
    /** code 28: light denoise for an enlarged crop (spatial luma 1.5, chroma 1.2, temporal 3 / 2.5) */
    static final String DENOISE = "hqdn3d=1.5:1.2:3:2.5";

    static String scaleHq(int ow, int oh, int cropH, int outH) {
        String s = "scale=" + ow + ":" + oh + ":flags=lanczos";
        // v22 (4.1): the light sharpening runs on the crop BEFORE it is enlarged (3x3 on 608x1080 instead of 5x5 on
        // 1080x1920 = ~3x fewer pixels). Box test vs the 4.0 output: SSIM 0.997, PSNR 52.7 dB, detail (laplacian
        // variance) 8.40 vs 8.78 - the same look; main-path filter time 4.6 s -> 2.0 s for a 30 s 1080p clip.
        // code 28 (4.6, Sakib: "grainy"): the 4.1 sharpening (3x3, amount 1.0 / 0.6) pushed fine detail to 1.15-1.24x of a
        // real 4K crop: it sharpened the source's compression noise, which the enlarge then magnified (grain, flicker on
        // flat areas). Now the small crop is lightly denoised first (hqdn3d, light, its own instance per clip/segment)
        // and then sharpened more gently over a wider 5x5 radius, by how much it is enlarged. Box study (two real 4K
        // clips made into YouTube-like 1080p/1440p sources, scored against the same crop cut from the 4K master):
        // 1080p -> 1080x1920: SSIM 0.9765 -> 0.9801, PSNR +1.2 dB, detail 1.24x -> 1.01x of the 4K ideal, flat-area
        // flicker -38% (dark clip); same filter order (denoise -> sharpen -> lanczos) on the crop, literal amounts
        // (no locale decimal comma). No enlarge (4K sources, 1:1 from 1080p): unchanged, nothing extra.
        if (cropH > 0 && cropH < outH * 0.98) s = DENOISE + ",unsharp=5:5:" + (cropH < outH * 0.7 ? "0.35" : "0.20") + ":5:5:0.0," + s;
        return s;
    }

    /**
     * libass via the "ass" filter with complex (HarfBuzz) shaping forced on. The "subtitles" filter used in v2
     * leaves libass on simple shaping, which breaks Hindi conjuncts (क्या was drawn as क + ् + या).
     */
    static String subtitles(String assPath, String fontsDir) {
        String s = "ass=filename=" + filterPath(assPath);
        if (fontsDir != null) s += ":fontsdir=" + filterPath(fontsDir);
        return s + ":shaping=complex";
    }

    static List<String> encodeTail(boolean copyAudio) { return encodeTail(copyAudio, Encoder.X264, 720, 0); }

    /**
     * v21 (4.0): HW = the phone's encoder tuned for quality (High profile + constant bitrate: many phone encoders in their
     * default VBR mode deliver far less than asked - his 2.4 run got ~5 Mbps of 12M - and default to Baseline profile).
     * HW_BASIC = the 3.1 hardware settings, used on a phone whose encoder refuses the tuned ones. X264 = software.
     */
    public enum Encoder {
        X264, HW, HW_BASIC,
        /** v26: the tuned settings without the full-speed operating rate and the QP ceiling (step between HW and HW_BASIC) */
        HW_STD;
        public boolean hw() { return this != X264; }
        /** v26: the next, more conservative step after a failure: HW -> HW_STD -> HW_BASIC -> X264 */
        public Encoder next() { return this == HW ? HW_STD : this == HW_STD ? HW_BASIC : X264; }
    }

    /** v26: where the encoder plan for an output size comes from (the phone's encoder list; null = the code 25 settings) */
    public interface PlanSource { HwEncode.Plan plan(int w, int h); }
    public static volatile PlanSource planSource;

    /** v26: the encoder plan for a render spec (legacy settings when no encoder list is known, e.g. in unit tests) */
    public static HwEncode.Plan planFor(int spec) {
        int[] d = dims(spec);
        PlanSource ps = planSource;
        HwEncode.Plan p = null;
        try { if (ps != null) p = ps.plan(d[0], d[1]); } catch (Throwable ignored) {}
        return p == null ? HwEncode.Plan.legacy() : p;
    }

    /** v26: the pixel format the filter graph hands to the encoder (the hardware encoder's own input layout) */
    public static String pixFor(Encoder enc, int spec) { return enc.hw() ? planFor(spec).pixFmt : "yuv420p"; }

    /** v26: QP ceilings (Android 12+ encoders with QP bounds): never blockier than this, whatever the rate control does */
    public static final int QP_I_MAX = 30, QP_P_MAX = 32, QP_B_MAX = 34;
    /** v26: an encoder without constant bitrate (VBR / default) is asked this much more (VBR encoders deliver less) */
    public static final double VBR_ASK = 1.4;

    /**
     * HW = the phone's MediaCodec H.264 encoder (several times faster than x264 and frees the CPU for decoding,
     * scaling and captions), at a bitrate that keeps quality above x264 ultrafast/crf23. X264 = software fallback.
     */
    /**
     * v14: hardware bitrate by pixel count. 2.4 asked 12M for 1080p; his phone's encoder delivered ~5.3 Mbps
     * (20 MB per 31 s). 8M for 1080x1920 / 1920x1080, 6M for 1080x1080, 4M for 720p-class, 2.5M for 480p.
     */
    static String hwBitrate(int spec) { return hwBitrate(spec, false); }

    /**
     * v23 (code 23): 12M for 1080x1920 / 1920x1080 (was 10M), 8M for 1080x1080 (was 7M), 6M for 720x1280, 4M for
     * 720x720 (was 3M), 2.5M for 480p. An intermediate file (the raw short that captions are burnt onto in a second
     * pass) gets 1.5x so the second encode starts from a cleaner picture. hwBoost (learned per phone from what its
     * encoder really delivered) lifts the request on phones whose encoder gives less than asked.
     */
    static String hwBitrate(int spec, boolean intermediate) { return hwBitrate(spec, intermediate, Encoder.HW); }

    /**
     * v26: x the VBR factor when the encoder has no constant-bitrate mode (or runs in the bare HW_BASIC settings, i.e.
     * its own default rate control), and clamped to the encoder's own bitrate ceiling (a request above it made
     * configure() fail on some phones); a final short at most 24 Mbps, an intermediate at most 30 Mbps.
     */
    static String hwBitrate(int spec, boolean intermediate, Encoder enc) {
        return fmtKbps(hwAskKbps(spec, intermediate, enc));
    }

    public static long hwAskKbps(int spec, boolean intermediate, Encoder enc) {
        HwEncode.Plan p = planFor(spec);
        boolean cbr = enc != Encoder.HW_BASIC && "cbr".equals(p.mode);
        double f = cbr ? 1.0 : VBR_ASK;
        long k = Math.round(hwBaseKbps(spec, intermediate) * f * Math.max(1.0, Math.min(HW_BOOST_MAX, hwBoost)));
        // code 27: final shorts at most 40 Mbps, the intermediate at most 50 Mbps (H.264 High level 4.1/4.2 limit);
        // always inside the encoder's own reported bitrate range (getBitrateRange): above its ceiling configure() fails
        // on some phones, below its floor it is raised to the floor
        long cap = intermediate ? INTERMEDIATE_CAP_KBPS : FINAL_CAP_KBPS;
        if (p.maxKbps > 0) cap = Math.min(cap, p.maxKbps);
        long floor = Math.max(1000, p.minKbps);
        return Math.max(Math.min(floor, cap), Math.min(k, cap));
    }

    /** code 27: request ceilings (kbps) before the encoder's own range */
    public static final long FINAL_CAP_KBPS = 40000, INTERMEDIATE_CAP_KBPS = 50000;
    /** code 27: every short is rendered at this frame rate ("-r") */
    public static final int OUTPUT_FPS = 30;
    /** code 27: AAC audio of every rendered short: 192 kbps stereo 48 kHz (was 128 kbps 44.1 kHz) */
    public static final int AUDIO_KBPS = 192, AUDIO_RATE = 48000;
    /** code 27: what the audio track (+ MP4 overhead) adds to a short's size, subtracted to get its video Mbps */
    public static final double AUDIO_MBPS = 0.2;
    /**
     * code 27: x264 (software) quality: crf 14 for a final short (was 19), 12 for an intermediate (was 16), 16 for the
     * safe-mode render (was 20). Box, 1080x1920 superfast: crf 17 gave 11.9 / 14.4 Mbps on two real clips (below the
     * 18 Mbps target), crf 14 gave 18.1 / 23.2 Mbps, i.e. at or above the hardware target.
     */
    public static final int X264_CRF = 14, X264_CRF_INTERMEDIATE = 12, X264_CRF_SAFE = 16;

    static String fmtKbps(long k) { return k % 1000 == 0 ? (k / 1000) + "M" : k + "k"; }

    /** the bitrate the output should end up with on a phone whose encoder delivers what it is asked (kbps) */
    public static long hwBaseKbps(int spec, boolean intermediate) { return hwBaseKbps(spec, intermediate, OUTPUT_FPS); }

    /**
     * code 27 (high bitrate, Sakib Nov 2): 18 Mbps for 1080x1920 / 1920x1080 (was 12), 12 Mbps for 1080x1080 (was 8),
     * 10 Mbps for 720x1280 / 1280x720 (was 6), 6 Mbps for 720x720 (was 4), 4.5 Mbps for 480x854 and 3 Mbps for 480x480
     * (was 2.5) - by pixel count. 50 fps and above x4/3 (1080p60 = 24 Mbps). The intermediate (re-encoded once more for
     * the captions) gets 2x: two lossy hardware passes compound, and weak encoders lose the most in the first one.
     */
    public static long hwBaseKbps(int spec, boolean intermediate, int fps) {
        int[] d = dims(spec);
        long px = (long) d[0] * d[1];
        long k = px >= 1_900_000 ? 18000 : px >= 1_100_000 ? 12000 : px >= 800_000 ? 10000 : px >= 500_000 ? 6000 : px >= 300_000 ? 4500 : 3000;
        if (fps >= 50) k = k * 4 / 3;
        return intermediate ? k * 2 : k;
    }

    /** v23: learned multiplier for phones whose hardware encoder delivers less than asked (1 = asks the plain bitrate) */
    public static volatile double hwBoost = 1.0;
    public static final double HW_BOOST_MAX = 2.5;   // v26: 1.8 -> 2.5 (MediaTek VBR encoders deliver ~40% of the ask)

    /**
     * v23: the boost to learn from one hardware render: base / delivered (clamped 1 .. HW_BOOST_MAX). videoMbps is the
     * file's video bitrate (size minus the 128k audio); 0 or a short clip = no information (returns the current boost).
     */
    public static double nextBoost(double currentBoost, long baseKbps, double videoMbps) {
        if (videoMbps <= 0.2 || baseKbps <= 0) return currentBoost;
        double asked = baseKbps / 1000.0 * Math.max(1.0, Math.min(HW_BOOST_MAX, currentBoost));
        // the encoder gives k x what it is asked: to end up at base it has to be asked base / k
        double k = videoMbps / asked;
        double want = (baseKbps / 1000.0) / k;
        return Math.max(1.0, Math.min(HW_BOOST_MAX, want / (baseKbps / 1000.0)));
    }

    /**
     * v26 quality guard: a final short from the hardware encoder whose video bitrate is below this share of the target
     * (code 27: 18 Mbps 1080x1920 -> below 8.1, 10 Mbps 720x1280 -> below 4.5) is encoded once more with x264 (which spends what the picture needs).
     */
    public static final double GUARD_FRACTION = 0.45;

    /** v26: does this hardware-encoded final short look starved (video Mbps far below the target)? 0 = unknown */
    public static boolean starved(int spec, double videoMbps) {
        return videoMbps > 0.2 && videoMbps * 1000 < hwBaseKbps(spec, false) * GUARD_FRACTION;
    }

    /**
     * v26: after the guard's x264 re-encode: true = the hardware encoder really undershot (x264 needed at least 1.5x its
     * bits for the same picture), false = the picture is simply easy to compress (both small).
     */
    public static boolean undershot(double hwMbps, double x264Mbps) { return hwMbps > 0 && x264Mbps >= hwMbps * 1.5; }

    static List<String> encodeTail(boolean copyAudio, Encoder enc, int outW, int threads) { return encodeTail(copyAudio, enc, outW, threads, false); }

    /**
     * v23: intermediate = the raw short that captions are burnt onto in a second pass (and that Caption Studio re-burns
     * later): 1.5x the hardware bitrate / x264 crf 16, and no "+faststart" (that option rewrites the whole file once
     * more after encoding; a file only this app reads doesn't need it).
     */
    static List<String> encodeTail(boolean copyAudio, Encoder enc, int outW, int threads, boolean intermediate) {
        List<String> a = new ArrayList<>();
        a.addAll(Arrays.asList("-r", String.valueOf(OUTPUT_FPS)));
        if (enc.hw()) {
            HwEncode.Plan p = planFor(outW);
            a.addAll(Arrays.asList("-c:v", "h264_mediacodec"));
            // v26: exactly the encoder the plan picked (a hardware one that supports this size), not the phone's default
            if (enc != Encoder.HW_BASIC && p.codecName != null) a.addAll(Arrays.asList("-codec_name", p.codecName));
            a.addAll(Arrays.asList("-b:v", hwBitrate(outW, intermediate, enc), "-g", "60"));
            // v21: operating_rate 1000 asks the chip to run the encoder at full clock (offline transcode, not a live call);
            // the same value androidx Media3 Transformer uses on devices that overflow on larger ones.
            // v26: profile / rate control from the encoder's own capability list (High or Main; CBR, else VBR asked 1.4x);
            // a phone that refuses the full set steps down HW -> HW_STD (no operating rate / QP ceiling) -> HW_BASIC -> x264.
            if (enc == Encoder.HW || enc == Encoder.HW_STD) {
                if (p.profile != null) a.addAll(Arrays.asList("-profile:v", p.profile));
                if (p.mode != null) a.addAll(Arrays.asList("-bitrate_mode", p.mode));
            }
            if (enc == Encoder.HW) {
                a.addAll(Arrays.asList("-operating_rate", "1000"));
                if (p.qpCap) a.addAll(Arrays.asList("-qp_i_max", String.valueOf(QP_I_MAX), "-qp_p_max", String.valueOf(QP_P_MAX), "-qp_b_max", String.valueOf(QP_B_MAX)));
            }
            a.addAll(Arrays.asList("-pix_fmt", p.pixFmt, "-bsf:v", "extract_extradata"));
        } else {
            // v21 (4.0): software fallback without "fastdecode" (it switches off CABAC and the deblocking filter = blocky
            // faces). v23: crf 21 -> 19 (box, 1080x1920 frame of a 24 Mbps 1080p source: SSIM 0.9850 -> 0.9874, same
            // encode time, ~7 -> ~10 Mbps); 16 for an intermediate file.
            // code 27: crf 19 -> 14 (16 -> 12 intermediate), High profile: the software path spends what the picture needs
            a.addAll(Arrays.asList("-c:v", "libx264", "-preset", "superfast", "-profile:v", "high", "-crf", String.valueOf(intermediate ? X264_CRF_INTERMEDIATE : X264_CRF),
                    "-g", "60", "-pix_fmt", "yuv420p", "-threads", threads > 0 ? String.valueOf(threads) : "0"));
        }
        if (copyAudio) a.addAll(Arrays.asList("-c:a", "copy"));
        else a.addAll(Arrays.asList("-c:a", "aac", "-b:a", AUDIO_KBPS + "k", "-ac", "2", "-ar", String.valueOf(AUDIO_RATE)));
        if (!intermediate) a.addAll(Arrays.asList("-movflags", "+faststart"));
        return a;
    }

    /**
     * Cut [start, start+duration] out of a local file or an http(s) stream and format it to 9:16,
     * optionally burning captions in the same pass. Input seeking (-ss before -i) is frame-accurate
     * when transcoding and avoids decoding everything before the clip. Audio and video are cut together
     * (no audio-only trimming, which caused lip-sync drift in v1).
     */
    public static void addInput(List<String> a, String input, double start, double duration) {
        if (input.startsWith("http")) {
            a.addAll(Arrays.asList("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_on_network_error", "1",
                    "-reconnect_delay_max", "5", "-rw_timeout", "20000000"));
        }
        a.addAll(Arrays.asList("-ss", f3(Math.max(0, start)), "-t", f3(duration), "-i", input));
    }

    public static String[] renderClip(String input, double start, double duration, Frame frame,
                                      String assPath, String fontsDir, String out) {
        return renderClip(input, null, start, duration, frame, assPath, fontsDir, out, 720);
    }

    /**
     * Cut [start, start+duration] from a local file or http(s) stream (optionally a separate audio stream) and
     * format it to 9:16, burning captions in the same pass. Input seeking is frame-accurate when transcoding and
     * skips decoding everything before the clip. Audio and video are cut identically (v1 trimmed silence from the
     * audio only, which drifted lip-sync).
     */
    public static String[] renderClip(String video, String audio, double start, double duration, Frame frame,
                                      String assPath, String fontsDir, String out, int outW) {
        return renderClip(video, audio, start, duration, frame, assPath, fontsDir, out, outW, Encoder.X264, 0);
    }

    public static String[] renderClip(String video, String audio, double start, double duration, Frame frame,
                                      String assPath, String fontsDir, String out, int outW, Encoder enc, int threads) {
        return renderClip(video, audio, start, duration, frame, assPath, fontsDir, out, outW, enc, threads, null);
    }
    /** v13: same render; with a face-tracking plan the 9:16 window follows the speaker (frame mode unused then) */
    public static String[] renderClip(String video, String audio, double start, double duration, Frame frame,
                                      String assPath, String fontsDir, String out, int outW, Encoder enc, int threads, Track track) {
        return renderClip(video, audio, start, duration, frame, assPath, fontsDir, out, outW, enc, threads, track, false);
    }

    /** v23: intermediate = the raw short of a two-pass caption render (see encodeTail) */
    public static String[] renderClip(String video, String audio, double start, double duration, Frame frame,
                                      String assPath, String fontsDir, String out, int outW, Encoder enc, int threads, Track track,
                                      boolean intermediate) {
        return renderClip(video, audio, start, duration, frame, assPath, fontsDir, out, outW, enc, threads, track, intermediate, null, 0);
    }

    /**
     * v24: decoder = FFmpeg's MediaCodec decoder for a 1440p / 4K VP9 / AV1 clip (null = normal decoding);
     * srcH = height of the source a Fill crop is cut from (0 = unknown), so an enlarged crop gets the same light
     * sharpening as the face-tracking crop. The audio fades in over 80 ms, so a short never opens on a click.
     */
    public static String[] renderClip(String video, String audio, double start, double duration, Frame frame,
                                      String assPath, String fontsDir, String out, int outW, Encoder enc, int threads, Track track,
                                      boolean intermediate, String decoder, int srcH) {
        List<String> a = new ArrayList<>();
        a.add("-y");
        a.add("-hide_banner");
        if (decoder != null) a.addAll(Arrays.asList("-c:v", decoder));
        addInput(a, video, start, duration);
        if (audio != null) addInput(a, audio, start, duration);
        String pf = pixFor(enc, outW);   // v26: the encoder's own input layout (NV12 or I420)
        a.addAll(Arrays.asList("-filter_complex", colourIn(track != null ? trackGraph(track, assPath, fontsDir, outW, pf) : frameGraph(frame, assPath, fontsDir, outW, pf, srcH)),   // code 35: colour tag first
                "-map", "[v]", "-map", audio != null ? "1:a:0?" : "0:a:0?", "-af", AUDIO_IN, "-sn", "-dn", "-max_muxing_queue_size", "1024"));
        a.addAll(encodeTail(false, enc, outW, threads, intermediate));
        a.addAll(Arrays.asList("-t", f3(duration), "-avoid_negative_ts", "make_zero"));
        a.add(out);
        return a.toArray(new String[0]);
    }

    /**
     * Safe-mode render used after a normal render failed: one scale+pad chain (no split/blur/overlay), x264 baseline,
     * classic subtitles filter fallback is not needed (ass filter is the same libass), explicit stream mapping that
     * tolerates a missing audio track, and generous muxing queue for odd sources.
     */
    public static String[] renderClipSafe(String video, String audio, double start, double duration,
                                          String assPath, String fontsDir, String out, int outW) {
        int[] d = dims(outW);
        int ow = d[0], oh = d[1];
        // v26: lanczos + crf 20 (was the default bicubic + crf 23); code 27: crf 16, 192k 48 kHz audio: the last-resort render is no longer visibly softer
        String g = "[0:v]scale=" + ow + ":" + oh + ":force_original_aspect_ratio=decrease:flags=lanczos,pad=" + ow + ":" + oh
                + ":(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=30";
        if (assPath != null) g += "," + subtitles(assPath, fontsDir);
        g = colourIn(g + ",format=yuv420p[v]");
        List<String> a = new ArrayList<>();
        a.addAll(Arrays.asList("-y", "-hide_banner", "-fflags", "+genpts+discardcorrupt"));
        addInput(a, video, start, duration);
        if (audio != null) addInput(a, audio, start, duration);
        a.addAll(Arrays.asList("-filter_complex", g, "-map", "[v]", "-map", audio != null ? "1:a:0?" : "0:a:0?", "-sn", "-dn",
                "-c:v", "libx264", "-preset", "veryfast", "-profile:v", "main", "-crf", String.valueOf(X264_CRF_SAFE), "-pix_fmt", "yuv420p",
                "-c:a", "aac", "-b:a", AUDIO_KBPS + "k", "-ac", "2", "-ar", String.valueOf(AUDIO_RATE), "-max_muxing_queue_size", "2048",
                "-movflags", "+faststart", "-t", f3(duration), "-avoid_negative_ts", "make_zero", out));
        return a.toArray(new String[0]);
    }

    /**
     * code 33 (5.1): the same command with every CPU-specific routine of FFmpeg switched off (-cpuflags 0: plain C code in
     * libswscale / libavfilter / libavcodec / libavutil instead of NEON / asm), one thread per stage (decoder, filter
     * graph) and x264 without its asm (2 threads). Same filters, same sizes, same captions - only slower. Used after the
     * software path crashed twice on a phone (his vivo Y16, signal 11 in libswscale / libavfilter). Idempotent.
     */
    public static String[] cpuSafe(String[] args) {
        if (args == null || args.length == 0 || "-cpuflags".equals(args[0])) return args;
        List<String> a = new ArrayList<>(Arrays.asList("-cpuflags", "0", "-filter_threads", "1", "-filter_complex_threads", "1"));
        int lastInput = -1;
        for (int i = 0; i < args.length; i++) if ("-i".equals(args[i])) lastInput = i;
        boolean x264 = false;
        for (String s : args) if ("libx264".equals(s)) x264 = true;
        for (int i = 0; i < args.length; i++) {
            String s = args[i];
            if ("-i".equals(s)) a.addAll(Arrays.asList("-threads", "1"));
            if ("-threads".equals(s) && i > lastInput && i + 1 < args.length) { a.add(s); a.add(x264 ? "2" : "1"); i++; continue; }
            a.add(s);
            if ("libx264".equals(s) && (i < 1 || !"-x264-params".equals(args[i - 1]))) a.addAll(Arrays.asList("-x264-params", "asm=0"));
        }
        return a.toArray(new String[0]);
    }

    /**
     * code 34 (5.2): the same command with FFmpeg's NEON routines off: "-cpuflags -neon" goes first (a global option, so it
     * is in force before any input is opened); every other argument stays exactly as it was. Plain C in libswscale /
     * libavfilter / libavcodec / libavutil instead of NEON; x264 keeps its own NEON code. Used on a phone where FFmpeg's
     * NEON scaler crashed (his vivo Y16) and on chips of that family from the first render. A command that already has
     * -cpuflags (the CPU-safe rung: -cpuflags 0) is returned unchanged. Idempotent.
     */
    public static String[] noNeon(String[] args) {
        if (args == null || args.length == 0) return args;
        for (int i = 0; i + 1 < args.length; i++) if ("-cpuflags".equals(args[i])) return args;
        if (args.length > 0 && args[0].startsWith("@")) return args;   // the backup engine's spec, not an FFmpeg command
        String[] a = new String[args.length + 2];
        a[0] = "-cpuflags";
        a[1] = "-neon";
        System.arraycopy(args, 0, a, 2, args.length);
        return a;
    }

    /** Stream-copy a piece of an audio file (no re-encode: instant) for parallel transcription. */
    public static String[] cutAudioCopy(String input, double start, double duration, String out) {
        return new String[]{"-y", "-hide_banner", "-ss", f3(Math.max(0, start)), "-t", f3(duration), "-i", input,
                "-vn", "-sn", "-dn", "-map", "0:a:0", "-c:a", "copy", out};
    }

    /** Small mono 16 kHz Opus speech piece (used when a stream-copied piece would be too big). */
    public static String[] cutAudioOpus(String input, double start, double duration, String out) {
        return cutAudioOpus(input, start, duration, out, 1.0);
    }

    /** Opus speech piece, optionally sped up (atempo keeps pitch) so very long videos fit Groq's audio limits. */
    public static String[] cutAudioOpus(String input, double start, double duration, String out, double tempo) {
        // v17 (2.8): resample to 16 kHz mono FIRST, then atempo (2.7 ran atempo on the 48 kHz stereo decode and only then
        // downmixed); fastest libopus mode + 60 ms frames + 16 kbps. Box: 38% less CPU per part, 23% fewer bytes, and the
        // sample-accurate input seek is unchanged, so 0 s of a part is still exactly its cut time.
        List<String> a = new ArrayList<>(Arrays.asList("-y", "-hide_banner", "-ss", f3(Math.max(0, start)), "-t", f3(duration), "-i", input,
                "-vn", "-sn", "-dn", "-map", "0:a:0", "-af", speechFilter(tempo), "-ac", "1", "-ar", "16000"));
        // v18 (2.9): 14 kbps (box, 130-min file: 7.0 -> 6.1 MB per hour of speech, -12.5%, same CPU). Still wideband
        // (8 kHz audio band, what Whisper hears); libopus drops to narrowband (4 kHz) at 12 kbps and below, so not lower.
        a.addAll(Arrays.asList("-c:a", "libopus", "-b:a", SPEECH_KBPS + "k", "-vbr", "on", "-application", "voip", "-compression_level", "0",
                "-frame_duration", "60", out));
        return a.toArray(new String[0]);
    }

    /** v18: speech bitrate for Whisper (kbps); 13+ keeps libopus in wideband */
    public static final int SPEECH_KBPS = 14;

    /** v17: 16 kHz mono first (cheap), then the optional speed-up */
    static String speechFilter(double tempo) {
        return "aresample=16000:filter_size=16,aformat=sample_rates=16000:channel_layouts=mono" + (tempo > 1.0001 ? ",atempo=" + f3(tempo) : "");
    }

    /**
     * v20 (3.1): the same command, told to skip damaged audio packets instead of stopping at the first one
     * (FFmpeg's -err_detect ignore_err + -fflags +discardcorrupt, as input options).
     */
    public static String[] tolerant(String[] args) {
        List<String> a = new ArrayList<>();
        boolean done = false;
        for (String s : args) {
            // -max_error_rate 1: FFmpeg otherwise exits with code 69 once more than 2/3 of the packets fail to decode -
            // the "code 69" in his 3.0 log (box repro: the same file, the same exit code)
            if (!done && "-i".equals(s)) { a.addAll(Arrays.asList("-max_error_rate", "1", "-err_detect", "ignore_err", "-fflags", "+discardcorrupt+genpts")); done = true; }
            a.add(s);
        }
        return a.toArray(new String[0]);
    }

    /** AAC fallback piece for phones where libopus fails. */
    public static String[] cutAudioAac(String input, double start, double duration, String out, double tempo) {
        List<String> a = new ArrayList<>(Arrays.asList("-y", "-hide_banner", "-ss", f3(Math.max(0, start)), "-t", f3(duration), "-i", input,
                "-vn", "-sn", "-dn", "-map", "0:a:0", "-af", speechFilter(tempo), "-ac", "1", "-ar", "16000"));
        a.addAll(Arrays.asList("-c:a", "aac", "-b:a", "32k", out));
        return a.toArray(new String[0]);
    }

    /**
     * v9: the short's own speech for caption timing: decoded from the FINISHED clip file, padded/trimmed so sample 0
     * is the file's time 0 (aresample first_pts=0 — an audio stream that starts after the video, or AAC priming, can't
     * shift the captions), mono 16 kHz FLAC (lossless, small: ~1 MB per minute).
     */
    public static String[] clipSpeechAudio(String clip, String out) {
        return new String[]{"-y", "-hide_banner", "-i", clip, "-vn", "-sn", "-dn", "-map", "0:a:0",
                "-af", "aresample=async=1:first_pts=0", "-ac", "1", "-ar", "16000", "-c:a", "flac", out};
    }

    /**
     * v10 single pass: the clip's speech straight from the source (file or stream), cut with the SAME input seek the
     * render uses (-ss/-t before -i, decoded, so frame/sample accurate), sample 0 = clip start (first_pts=0). Only the
     * audio is read, so this takes a second or two instead of a whole video render.
     */
    public static String[] sourceSpeechAudio(String input, double start, double duration, String out) {
        List<String> a = new ArrayList<>(Arrays.asList("-y", "-hide_banner"));
        addInput(a, input, start, duration);
        a.addAll(Arrays.asList("-vn", "-sn", "-dn", "-map", "0:a:0", "-af", "aresample=async=1:first_pts=0",
                "-ac", "1", "-ar", "16000", "-t", f3(duration), "-c:a", "flac", out));
        return a.toArray(new String[0]);
    }

    /** v9: raw 16 kHz mono PCM of a stretch of a file (accurate seek: decoded, not stream-copied) for sync checks */
    public static String[] speechPcm(String input, double start, double duration, String out) {
        List<String> a = new ArrayList<>(Arrays.asList("-y", "-hide_banner"));
        if (start > 0) a.addAll(Arrays.asList("-ss", f3(start)));
        if (duration > 0) a.addAll(Arrays.asList("-t", f3(duration)));
        a.addAll(Arrays.asList("-i", input, "-vn", "-sn", "-dn", "-map", "0:a:0", "-af", "aresample=async=1:first_pts=0",
                "-ac", "1", "-ar", "16000", "-f", "s16le", out));
        return a.toArray(new String[0]);
    }

    /** v9: burn captions onto the finished clip with the chosen encoder; audio is stream-copied (bit-identical) */
    public static String[] burnCaptions(String rawClip, String assPath, String fontsDir, String out, Encoder enc, int outW, int threads) {
        return burnCaptions(rawClip, assPath, fontsDir, out, enc, outW, threads, null);
    }

    /** v24: decoder = FFmpeg's MediaCodec decoder for the raw short (null = software) */
    public static String[] burnCaptions(String rawClip, String assPath, String fontsDir, String out, Encoder enc, int outW, int threads, String decoder) {
        List<String> a = new ArrayList<>(Arrays.asList("-y", "-hide_banner"));
        if (decoder != null) a.addAll(Arrays.asList("-c:v", decoder));
        a.addAll(Arrays.asList("-i", rawClip,
                "-filter_complex", colourIn("[0:v]" + subtitles(assPath, fontsDir) + ",format=" + pixFor(enc, outW) + "[v]"),
                "-map", "[v]", "-map", "0:a:0?"));
        a.addAll(encodeTail(true, enc, outW, threads));
        a.add(out);
        return a.toArray(new String[0]);
    }

    /** Burn captions onto an already formatted 720x1280 clip; audio is stream-copied. */
    public static String[] burnCaptions(String rawClip, String assPath, String fontsDir, String out) {
        List<String> a = new ArrayList<>(Arrays.asList("-y", "-hide_banner", "-i", rawClip,
                "-filter_complex", colourIn("[0:v]" + subtitles(assPath, fontsDir) + ",format=yuv420p[v]"),
                "-map", "[v]", "-map", "0:a:0?"));
        a.addAll(encodeTail(true));
        a.add(out);
        return a.toArray(new String[0]);
    }

    /** Small mono 16 kHz speech track for Whisper (Opus ~16 kbps: ~7 MB per hour). */
    public static String[] speechAudioOpus(String input, String out) {
        return new String[]{"-y", "-hide_banner", "-i", input, "-vn", "-sn", "-dn", "-ac", "1", "-ar", "16000",
                "-c:a", "libopus", "-b:a", SPEECH_KBPS + "k", "-vbr", "on", "-compression_level", "3", out};
    }

    /** Fallback speech track (AAC) if libopus fails on a device. */
    public static String[] speechAudioAac(String input, String out, int kbps) {
        return new String[]{"-y", "-hide_banner", "-i", input, "-vn", "-sn", "-dn", "-ac", "1", "-ar", "16000",
                "-c:a", "aac", "-b:a", kbps + "k", out};
    }

    /** Join args for logs only. */
    public static String pretty(String[] args) {
        StringBuilder sb = new StringBuilder("ffmpeg");
        for (String s : args) sb.append(' ').append(s.contains(" ") || s.contains(";") ? "\"" + s + "\"" : s);
        return sb.toString();
    }
}
