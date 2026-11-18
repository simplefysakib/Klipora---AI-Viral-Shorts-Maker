package com.viral.shortsmaker.core;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v26 (code 26): the phone's H.264 ENCODERS, read from MediaCodecList, and the encoder plan for one output size.
 *
 * Up to code 25 the render let FFmpeg take the phone's DEFAULT "video/avc" encoder with one fixed set of settings (High
 * profile + constant bitrate + full-speed operating rate). That is sharp on phones whose default encoder accepts all of
 * it, and soft / blocky on the rest:
 *  - an encoder that rejects any one key (bitrate mode, profile, operating rate) dropped to the bare settings = the
 *    encoder's own default rate control (usually VBR) and often Baseline profile. MediaTek / Unisoc / some Exynos encoders
 *    deliver far less than asked in VBR, so motion turns blocky ("quality phat-ti hai");
 *  - on a few phones the first "video/avc" encoder in the list is Google's software encoder (c2.android / OMX.google:
 *    Baseline-class, the weakest picture of all) or a hardware encoder that does not support the portrait size;
 *  - a bitrate above the encoder's own maximum, or an input colour format it does not take, made configure() fail.
 * Now the plan picks, per output size: the best HARDWARE encoder that really supports that size at 30 fps (by name, so
 * FFmpeg opens exactly that one), the best rate control it supports (CBR, else VBR asked higher), High profile when
 * listed (else Main), the input layout it takes (NV12 or I420), its bitrate ceiling, and on Android 12+ a QP ceiling when
 * the encoder supports QP bounds (it can never go blockier than that, whatever its rate control does). No hardware
 * encoder for the size = x264 software (sharp, slower) instead of a weak encoder. Pure decision logic in choose() is
 * unit tested with the encoder lists of typical phones (DeviceMatrix in the tests).
 */
public final class HwEncode {
    private HwEncode() {}

    /** one encoder as MediaCodecList describes it (plain data, so phones can be simulated in unit tests) */
    public static final class Enc {
        public String name;
        public boolean hardware;
        /** does it encode w x h at 30 fps (VideoCapabilities.areSizeAndRateSupported / isSizeSupported) */
        public SizeOk size;
        public boolean cbr, vbr, cq;
        public boolean high, main;
        public boolean nv12, i420, flexible;
        public boolean qpBounds;
        public int maxKbps;   // 0 = unknown
        public int minKbps;   // 0 = unknown (code 27: lower end of getBitrateRange)

        public interface SizeOk { boolean ok(int w, int h); }

        public Enc(String name, boolean hardware) { this.name = name; this.hardware = hardware; }
        @Override public String toString() { return name + (hardware ? " (hardware)" : " (software)"); }
    }

    /** what the render asks for one output size */
    public static final class Plan {
        /** false = x264 software encoder (no suitable hardware encoder for this size) */
        public boolean hw;
        /** encoder name for FFmpeg's -codec_name (null = FFmpeg's default "video/avc" encoder) */
        public String codecName;
        /** "cbr" / "vbr" / null (encoder default) */
        public String mode = "cbr";
        /** "high" / "main" / null (encoder default) */
        public String profile = "high";
        public String pixFmt = "nv12";
        /** encoder's bitrate ceiling in kbps (0 = unknown) */
        public int maxKbps;
        /** code 27: encoder's bitrate floor in kbps (0 = unknown) */
        public int minKbps;
        /** Android 12+ QP bounds supported */
        public boolean qpCap;
        public String why = "";

        /** the plan used before the encoder list is known (= the code 25 settings, used by unit tests and old phones) */
        public static Plan legacy() { Plan p = new Plan(); p.hw = true; p.why = "encoder list not read"; return p; }

        public String describe() {
            if (!hw) return "x264 software (" + why + ")";
            return (codecName == null ? "default encoder" : codecName) + ": " + (mode == null ? "default rate control" : mode.toUpperCase(Locale.US))
                    + ", " + (profile == null ? "default profile" : profile + " profile") + ", " + pixFmt
                    + (maxKbps > 0 ? ", up to " + Math.round(maxKbps / 1000.0) + " Mbps" : "") + (qpCap ? ", QP ceiling" : "");
        }
    }

    static boolean isSoftwareName(String n) {
        n = n.toLowerCase(Locale.US);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.startsWith("c2.google.") || n.contains(".sw.")
                || n.contains("ffmpeg") || n.contains("software") || n.contains("avcenc.sw");
    }

    /** encoders a render must never pick even when they claim the size: secure / low-latency / ULL instances */
    static boolean unusable(String n) {
        n = n.toLowerCase(Locale.US);
        return n.contains(".secure") || n.contains("low_latency") || n.contains("lowlatency") || n.endsWith(".ull") || n.contains(".tunnel");
    }

    /**
     * The decision for one output size from the phone's encoder list (in MediaCodecList order). Pure logic.
     * Hardware encoders that support w x h at 30 fps, in list order (the vendor's preferred one first); a phone with
     * none gets x264.
     */
    public static Plan choose(List<Enc> encs, int w, int h) {
        Plan p = new Plan();
        Enc best = null;
        if (encs != null) for (Enc e : encs) {
            if (e == null || e.name == null || !e.hardware || isSoftwareName(e.name) || unusable(e.name)) continue;
            // FFmpeg encodes H.264 at the 16-aligned width / height and crops in the SPS (1080 -> 1088): both must fit
            boolean ok = e.size == null || (e.size.ok(w, h) && e.size.ok(align16(w), align16(h)));
            if (!ok) continue;
            if (!e.nv12 && !e.i420 && !e.flexible) continue;   // no input layout FFmpeg can fill
            best = e;
            break;
        }
        if (best == null) {
            p.hw = false;
            p.mode = null; p.profile = null; p.pixFmt = "yuv420p";
            boolean any = false;
            if (encs != null) for (Enc e : encs) if (e != null && e.hardware && !isSoftwareName(e.name)) any = true;
            p.why = any ? "no hardware encoder here supports " + w + "x" + h : "no hardware H.264 encoder on this phone";
            return p;
        }
        p.hw = true;
        p.codecName = best.name;
        p.mode = best.cbr ? "cbr" : best.vbr ? "vbr" : null;
        p.profile = best.high ? "high" : best.main ? "main" : null;
        p.pixFmt = best.nv12 || best.flexible || !best.i420 ? "nv12" : "yuv420p";
        p.maxKbps = best.maxKbps;
        p.minKbps = best.minKbps;
        p.qpCap = best.qpBounds;
        p.why = best.toString();
        return p;
    }

    static int align16(int v) { return (v + 15) & ~15; }

    // ------------------------------------------------------------------ the phone (Android only)

    private static volatile List<Enc> cache;

    /** the phone's H.264 encoders (cached; empty on any error) */
    public static List<Enc> list() {
        List<Enc> c = cache;
        if (c != null) return c;
        c = new ArrayList<>();
        try {
            for (MediaCodecInfo ci : new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos()) {
                if (!ci.isEncoder()) continue;
                String type = null;
                for (String t : ci.getSupportedTypes()) if ("video/avc".equalsIgnoreCase(t)) type = t;
                if (type == null) continue;
                try {
                    MediaCodecInfo.CodecCapabilities cc = ci.getCapabilitiesForType(type);
                    Enc e = new Enc(ci.getName(), !HwDecode.software(ci));
                    final MediaCodecInfo.VideoCapabilities vc = cc.getVideoCapabilities();
                    if (vc != null) {
                        e.size = (w, hh) -> {
                            try { return vc.isSizeSupported(w, hh) && vc.areSizeAndRateSupported(w, hh, 30); }
                            catch (Throwable t) { return false; }
                        };
                        try { e.maxKbps = (int) Math.min(Integer.MAX_VALUE, vc.getBitrateRange().getUpper() / 1000L); } catch (Throwable ignored) {}
                        try { e.minKbps = (int) Math.max(0, Math.min(Integer.MAX_VALUE, vc.getBitrateRange().getLower() / 1000L)); } catch (Throwable ignored) {}
                    }
                    MediaCodecInfo.EncoderCapabilities ec = cc.getEncoderCapabilities();
                    if (ec != null) {
                        e.cbr = ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR);
                        e.vbr = ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_VBR);
                        e.cq = ec.isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CQ);
                    }
                    if (cc.profileLevels != null) for (MediaCodecInfo.CodecProfileLevel pl : cc.profileLevels) {
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileHigh) e.high = true;
                        if (pl.profile == MediaCodecInfo.CodecProfileLevel.AVCProfileMain) e.main = true;
                    }
                    if (cc.colorFormats != null) for (int f : cc.colorFormats) {
                        if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) e.nv12 = true;
                        else if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) e.i420 = true;
                        else if (f == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) e.flexible = true;
                    }
                    if (Build.VERSION.SDK_INT >= 31) {
                        try { e.qpBounds = cc.isFeatureSupported(MediaCodecInfo.CodecCapabilities.FEATURE_QpBounds); } catch (Throwable ignored) {}
                    }
                    c.add(e);
                } catch (Throwable ignored) {}
            }
        } catch (Throwable e) {
            AppLog.e("Encoder list could not be read: " + e.getMessage());
        }
        cache = c;
        StringBuilder sb = new StringBuilder("H.264 encoders on this phone: ");
        if (c.isEmpty()) sb.append("none listed");
        for (int i = 0; i < c.size(); i++) {
            Enc e = c.get(i);
            sb.append(i == 0 ? "" : "; ").append(e).append(e.cbr ? " CBR" : "").append(e.vbr ? " VBR" : "").append(e.high ? " High" : e.main ? " Main" : " Baseline")
                    .append(e.maxKbps > 0 ? " max " + (e.maxKbps / 1000) + "M" : "").append(e.qpBounds ? " QP-bounds" : "");
        }
        AppLog.i(sb.toString());
        return c;
    }

    private static final java.util.Map<Long, Plan> plans = new java.util.concurrent.ConcurrentHashMap<>();

    /** the plan for one output size on this phone (cached, logged once). Unit tests (no encoder list) get legacy(). */
    public static Plan plan(int w, int h) {
        long k = ((long) w << 20) | h;
        Plan p = plans.get(k);
        if (p != null) return p;
        if (Test.override != null) p = choose(Test.override, w, h);
        else {
            List<Enc> l = list();
            p = l.isEmpty() ? Plan.legacy() : choose(l, w, h);
        }
        plans.put(k, p);
        AppLog.i("Video encoder for " + w + "x" + h + ": " + p.describe());
        return p;
    }

    /** test hook: a simulated phone's encoder list (null = the real phone) */
    public static final class Test {
        public static volatile List<Enc> override;
        public static void set(List<Enc> l) { override = l; plans.clear(); }
    }
}
