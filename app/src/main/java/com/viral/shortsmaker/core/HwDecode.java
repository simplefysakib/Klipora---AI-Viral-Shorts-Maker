package com.viral.shortsmaker.core;

import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.os.Build;

/**
 * v24: the phone's hardware video decoders. A 9:16 crop of a 1080p video is only 608x1080 pixels, stretched 1.78x to
 * 1080x1920 = the soft "phat-ti" tracked shorts. Above 1080p YouTube serves only VP9 / AV1, which FFmpeg decodes in
 * software (too slow on phones for 1440p / 4K), so a taller source is fetched only when this phone has a hardware decoder
 * for that codec at that size, and the render then decodes it with FFmpeg's MediaCodec decoder (vp9_mediacodec /
 * av1_mediacodec). Anything that fails falls back to the normal (up to 1080p) source.
 */
public final class HwDecode {
    private HwDecode() {}

    private static volatile int[] cache;   // {vp9 max height, av1 max height, 4K at 60 fps (vp9), 4K at 60 fps (av1)}

    /** "vp9" / "av01" / "avc1" etc. from yt-dlp's vcodec ("vp09.00.50.08" -> vp9) */
    public static String family(String vcodec) {
        if (vcodec == null) return "";
        String v = vcodec.toLowerCase(java.util.Locale.US);
        if (v.startsWith("vp09") || v.startsWith("vp9")) return "vp9";
        if (v.startsWith("av01") || v.startsWith("av1")) return "av1";
        if (v.startsWith("avc") || v.startsWith("h264")) return "avc";
        if (v.startsWith("hev") || v.startsWith("hvc") || v.startsWith("h265")) return "hevc";
        return v;
    }

    /** FFmpeg's MediaCodec decoder for a MediaFormat mime / codec family (null = none) */
    public static String ffDecoder(String mimeOrFamily) {
        if (mimeOrFamily == null) return null;
        String m = mimeOrFamily.toLowerCase(java.util.Locale.US);
        if (m.contains("vp9")) return "vp9_mediacodec";
        if (m.contains("av01") || m.equals("av1") || m.endsWith("/av1")) return "av1_mediacodec";
        return null;
    }

    /** the tallest 16:9 height (2160 / 1440 / 0) this phone decodes in hardware at 30 fps for the codec family */
    public static int maxHeight(String family) {
        if (RenderSafety.levelCached() >= RenderSafety.SW_DECODE) return 0;   // code 31: hardware decoding crashed on this phone
        int[] c = caps();
        return "vp9".equals(family) ? c[0] : "av1".equals(family) ? c[1] : 0;
    }

    /** true when a 4K stream of this codec decodes in hardware at 60 fps (room for two shorts at once) */
    public static boolean fast4k(String family) {
        if (RenderSafety.levelCached() >= RenderSafety.SW_DECODE) return false;
        int[] c = caps();
        return "vp9".equals(family) ? c[2] == 1 : "av1".equals(family) && c[3] == 1;
    }

    private static int[] caps() {
        int[] c = cache;
        if (c != null) return c;
        c = new int[4];
        try {
            MediaCodecInfo[] all = new MediaCodecList(MediaCodecList.REGULAR_CODECS).getCodecInfos();
            for (MediaCodecInfo ci : all) {
                if (ci.isEncoder() || software(ci)) continue;
                for (String type : ci.getSupportedTypes()) {
                    String t = type.toLowerCase(java.util.Locale.US);
                    int k = t.equals("video/x-vnd.on2.vp9") ? 0 : t.equals("video/av01") ? 1 : -1;
                    if (k < 0) continue;
                    try {
                        MediaCodecInfo.VideoCapabilities vc = ci.getCapabilitiesForType(type).getVideoCapabilities();
                        if (vc == null) continue;
                        int h = 0;
                        if (vc.areSizeAndRateSupported(3840, 2160, 30)) h = 2160;
                        else if (vc.areSizeAndRateSupported(2560, 1440, 30)) h = 1440;
                        if (h > c[k]) c[k] = h;
                        if (h == 2160 && vc.areSizeAndRateSupported(3840, 2160, 60)) c[k + 2] = 1;
                    } catch (Throwable ignored) {}
                }
            }
        } catch (Throwable e) {
            AppLog.e("Hardware decoder check failed: " + e.getMessage());
        }
        cache = c;
        AppLog.i("Hardware decoders: VP9 up to " + (c[0] == 0 ? "none above 1080p" : c[0] + "p") + ", AV1 up to " + (c[1] == 0 ? "none above 1080p" : c[1] + "p"));
        return c;
    }

    static boolean software(MediaCodecInfo ci) {
        if (Build.VERSION.SDK_INT >= 29) {
            try { if (ci.isSoftwareOnly() || !ci.isHardwareAccelerated()) return true; } catch (Throwable ignored) {}
        }
        String n = ci.getName().toLowerCase(java.util.Locale.US);
        return n.startsWith("omx.google.") || n.startsWith("c2.android.") || n.contains(".sw.") || n.contains("ffmpeg") || n.contains("software");
    }
}
