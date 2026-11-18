package com.viral.shortsmaker.core;

import java.io.File;
import java.util.Locale;

/**
 * v20 (3.1): a downloaded audio file is decoded at a few spots before the speech is cut from it. His 3.0 run cut a
 * file whose middle was zeros/duplicated chunks (a 403 halfway, then the standard downloader picked up the partial
 * file): FFmpeg failed on every part and the job died. Now a damaged file is caught here and fetched again.
 */
public final class AudioCheck {
    private AudioCheck() {}

    public interface Runner { void run(String[] args) throws Exception; }

    /** FFmpegKit on the phone */
    public static Runner ffmpeg(CancelToken ct) { return args -> MediaRunner.run(args, 0, null, ct); }

    static final double WINDOW_SEC = 3;

    /** where to decode: start, 1/4, middle, 3/4, end (fewer for short files; just the start when the length is unknown) */
    static double[] spots(double dur) {
        if (dur <= 0) return new double[]{0};
        if (dur < 30) return new double[]{0};
        double end = Math.max(0, dur - 8);
        return new double[]{0, dur * 0.25, dur * 0.5, dur * 0.75, end};
    }

    /** FFmpeg args: decode 3 s of the first audio stream from t, stop at the first decode error */
    static String[] decodeArgs(String path, double t) {
        return new String[]{"-hide_banner", "-v", "error", "-xerror", "-ss", String.format(Locale.US, "%.3f", Math.max(0, t)),
                "-t", String.format(Locale.US, "%.1f", WINDOW_SEC), "-i", path, "-map", "0:a:0", "-vn", "-sn", "-dn", "-f", "null", "-"};
    }

    /** null when the file decodes cleanly at every spot; otherwise a short plain reason ("damaged near 11:57") */
    public static String problem(File f, double dur, Runner r) {
        if (f == null || !f.exists() || f.length() < 1024) return "file missing or empty";
        for (double t : spots(dur)) {
            try { r.run(decodeArgs(f.getAbsolutePath(), t)); }
            catch (CancelToken.Cancelled c) { throw c; }
            catch (Exception e) {
                AppLog.debug("Audio check failed at " + t + " s of " + f.getName() + ": " + e.getMessage());
                return "damaged near " + mmss(t);
            }
        }
        return null;
    }

    static String mmss(double s) { int x = (int) Math.round(s); return String.format(Locale.US, "%d:%02d", x / 60, x % 60); }
}
