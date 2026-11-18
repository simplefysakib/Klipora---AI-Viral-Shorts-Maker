package com.viral.shortsmaker.core;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * v16 (2.7): fetches only the part of a YouTube DASH stream (fragmented MP4: ftyp + moov + sidx + moof/mdat...) that a
 * short needs. The stream's own index (sidx) says where every ~5 s fragment starts in bytes and in time, so the clip's
 * fragments are one byte range, downloaded with parallel range requests (2.6 let FFmpeg read the stream over one
 * connection, which YouTube throttles to about playback speed: 3 MB in 27.8 s on his phone).
 * The file written = init (ftyp + moov) + the clip's fragments; FFmpeg plays it directly. Pure Java parsing (unit tested).
 */
public final class DashRange {
    private DashRange() {}

    public static final class Index {
        public long initEnd;          // end of moov (exclusive): the init bytes
        public long timescale;
        public long ept;              // earliest presentation time of the first fragment (timescale units)
        public long firstByte;        // stream offset of the first fragment
        public long[] size, dur;      // per fragment
    }

    public static final class Pick {
        public long from, to;         // stream bytes, inclusive
        public double startSec, endSec;   // presentation time of the first byte's fragment / the end of the last one
        public int fragments;
    }

    public static final class Got {
        public File file;
        public double startSec;       // presentation time of the file's first frame (seconds in the source video)
        public long bytes;
        public double seconds;
    }

    static long u32(byte[] b, int o) { return ((b[o] & 0xFFL) << 24) | ((b[o + 1] & 0xFFL) << 16) | ((b[o + 2] & 0xFFL) << 8) | (b[o + 3] & 0xFFL); }
    static long u64(byte[] b, int o) { return (u32(b, o) << 32) | u32(b, o + 4); }

    /**
     * Index from the stream's first n bytes. Returns null when it is not a fragmented MP4 with a sidx, and throws
     * NeedMore when the bytes stop inside the boxes we need.
     */
    public static Index parse(byte[] b, int n) throws NeedMore {
        int o = 0;
        long moovEnd = -1;
        while (o + 8 <= n) {
            long size = u32(b, o);
            String type = new String(b, o + 4, 4, java.nio.charset.StandardCharsets.ISO_8859_1);
            int hdr = 8;
            if (size == 1) { if (o + 16 > n) throw new NeedMore(o + 16); size = u64(b, o + 8); hdr = 16; }
            else if (size == 0) return null;
            if (size < hdr) return null;
            if (o == 0 && !type.equals("ftyp") && !type.equals("styp")) return null;
            if (type.equals("moov")) moovEnd = o + size;
            if (type.equals("moof") || type.equals("mdat")) return null;   // fragments before any index
            if (type.equals("sidx")) {
                if (o + size > n) throw new NeedMore(o + size);
                if (moovEnd < 0) return null;
                int p = o + hdr;
                int version = b[p] & 0xFF;
                p += 4;          // version + flags
                p += 4;          // reference_ID
                Index ix = new Index();
                ix.timescale = u32(b, p); p += 4;
                long firstOffset;
                if (version == 0) { ix.ept = u32(b, p); firstOffset = u32(b, p + 4); p += 8; }
                else { ix.ept = u64(b, p); firstOffset = u64(b, p + 8); p += 16; }
                p += 2;          // reserved
                int count = ((b[p] & 0xFF) << 8) | (b[p + 1] & 0xFF); p += 2;
                if (ix.timescale <= 0 || count <= 0) return null;
                ix.size = new long[count]; ix.dur = new long[count];
                for (int i = 0; i < count; i++) {
                    long r = u32(b, p);
                    if ((r & 0x80000000L) != 0) return null;   // hierarchical index: not used by YouTube
                    ix.size[i] = r & 0x7FFFFFFFL;
                    ix.dur[i] = u32(b, p + 4);
                    p += 12;
                }
                ix.initEnd = moovEnd;
                ix.firstByte = o + size + firstOffset;
                return ix;
            }
            if (o + size > Integer.MAX_VALUE) return null;
            o += (int) size;
        }
        throw new NeedMore(o + 8);
    }

    // ------------------------------------------------------------------ v24: WebM (YouTube's VP9 streams)

    /** v24: WebM layout facts the writer needs to patch the header (offsets in the stream) */
    public static final class Webm {
        public long segSizePos; public int segSizeLen;    // the Segment's size field
        public long cuesPos = -1; public int cuesHdr; public long cuesData;      // the Cues element (ID + size + data)
        public long seekPos = -1; public int seekHdr; public long seekData;      // the SeekHead element
    }

    static boolean isEbml(byte[] b, int n) { return n >= 4 && (b[0] & 0xFF) == 0x1A && (b[1] & 0xFF) == 0x45 && (b[2] & 0xFF) == 0xDF && (b[3] & 0xFF) == 0xA3; }

    /** EBML element ID at o: {id, length} (the ID keeps its marker bits) */
    static long[] ebmlId(byte[] b, int o, int n) throws NeedMore {
        if (o >= n) throw new NeedMore(o + 8);
        int first = b[o] & 0xFF, len = first >= 0x80 ? 1 : first >= 0x40 ? 2 : first >= 0x20 ? 3 : first >= 0x10 ? 4 : 0;
        if (len == 0) return null;
        if (o + len > n) throw new NeedMore(o + len);
        long v = 0;
        for (int i = 0; i < len; i++) v = (v << 8) | (b[o + i] & 0xFF);
        return new long[]{v, len};
    }

    /** EBML size at o: {value (-1 = unknown), length} */
    static long[] ebmlSize(byte[] b, int o, int n) throws NeedMore {
        if (o >= n) throw new NeedMore(o + 8);
        int first = b[o] & 0xFF, len = 1;
        while (len <= 8 && (first & (0x80 >> (len - 1))) == 0) len++;
        if (len > 8) return null;
        if (o + len > n) throw new NeedMore(o + len);
        long v = first & (0xFF >> len);
        boolean ones = v == (0xFF >> len);
        for (int i = 1; i < len; i++) { v = (v << 8) | (b[o + i] & 0xFF); if ((b[o + i] & 0xFF) != 0xFF) ones = false; }
        return new long[]{ones ? -1 : v, len};
    }

    static long uint(byte[] b, int o, int len) { long v = 0; for (int i = 0; i < len; i++) v = (v << 8) | (b[o + i] & 0xFF); return v; }

    /**
     * v24: index of a YouTube DASH WebM stream (EBML, Segment: SeekHead, Info, Tracks, Cues, then the Clusters). The
     * Cues give every cluster's time and position, so the clip's clusters are one byte range, exactly like the MP4 sidx.
     * total = the stream's byte length (the last cluster runs to the end). null = not such a stream.
     */
    public static Index parseWebm(byte[] b, int n, long total, Webm wm) throws NeedMore {
        if (!isEbml(b, n)) return null;
        long[] sz = ebmlSize(b, 4, n);
        if (sz == null || sz[0] < 0) return null;
        int o = (int) (4 + sz[1] + sz[0]);
        long[] id = ebmlId(b, o, n);
        if (id == null || id[0] != 0x18538067L) return null;
        o += (int) id[1];
        wm.segSizePos = o;
        long[] ss = ebmlSize(b, o, n);
        if (ss == null) return null;
        wm.segSizeLen = (int) ss[1];
        o += (int) ss[1];
        final long segData = o;
        long scale = 1000000;   // TimecodeScale (ns), Matroska default
        List<long[]> cues = new ArrayList<>();    // {time (ticks), cluster position (segment-relative)}
        long firstCluster = -1;
        while (true) {
            long[] eid = ebmlId(b, o, n);
            if (eid == null) return null;
            long[] es = ebmlSize(b, (int) (o + eid[1]), n);
            if (es == null) return null;
            int hdr = (int) (eid[1] + es[1]);
            long e = eid[0];
            if (e == 0x1F43B675L) { firstCluster = o; break; }   // first Cluster: the header ends here
            if (es[0] < 0) return null;
            long end = o + hdr + es[0];
            if (e == 0x1549A966L || e == 0x1C53BB6BL) {   // Info / Cues are read; others are skipped
                if (end > n) throw new NeedMore(end + 64);
                int p = o + hdr;
                while (p < end) {
                    long[] cid = ebmlId(b, p, n); long[] cs = ebmlSize(b, (int) (p + cid[1]), n);
                    int ch = (int) (cid[1] + cs[1]);
                    int cd = p + ch;
                    if (e == 0x1549A966L && cid[0] == 0x2AD7B1L) scale = uint(b, cd, (int) cs[0]);
                    if (e == 0x1C53BB6BL && cid[0] == 0xBBL) {   // CuePoint: CueTime + CueTrackPositions/CueClusterPosition
                        long t = -1, pos = -1;
                        int q = cd; long qe = cd + cs[0];
                        while (q < qe) {
                            long[] qid = ebmlId(b, q, n); long[] qs = ebmlSize(b, (int) (q + qid[1]), n);
                            int qd = (int) (q + qid[1] + qs[1]);
                            if (qid[0] == 0xB3L) t = uint(b, qd, (int) qs[0]);
                            if (qid[0] == 0xB7L) {
                                int r = qd; long re = qd + qs[0];
                                while (r < re) {
                                    long[] rid = ebmlId(b, r, n); long[] rs = ebmlSize(b, (int) (r + rid[1]), n);
                                    int rd = (int) (r + rid[1] + rs[1]);
                                    if (rid[0] == 0xF1L && pos < 0) pos = uint(b, rd, (int) rs[0]);
                                    r = (int) (rd + rs[0]);
                                }
                            }
                            q = (int) (qd + qs[0]);
                        }
                        if (t >= 0 && pos >= 0) cues.add(new long[]{t, pos});
                    }
                    p = (int) (cd + cs[0]);
                }
                if (e == 0x1C53BB6BL) { wm.cuesPos = o; wm.cuesHdr = hdr; wm.cuesData = es[0]; }
            }
            if (e == 0x114D9B74L) { wm.seekPos = o; wm.seekHdr = hdr; wm.seekData = es[0]; }
            if (end > Integer.MAX_VALUE) return null;
            o = (int) end;
        }
        if (cues.size() < 2 || firstCluster < 0) return null;
        Index ix = new Index();
        ix.initEnd = firstCluster;
        ix.timescale = Math.max(1, Math.round(1e9 / scale));      // ticks per second
        ix.ept = cues.get(0)[0];
        ix.firstByte = segData + cues.get(0)[1];
        int k = cues.size();
        ix.size = new long[k]; ix.dur = new long[k];
        for (int i = 0; i < k; i++) {
            long start = segData + cues.get(i)[1];
            long next = i + 1 < k ? segData + cues.get(i + 1)[1] : (total > start ? total : -1);
            if (next <= start) { if (i + 1 < k) return null; next = start + 1; }
            ix.size[i] = next - start;
            ix.dur[i] = i + 1 < k ? cues.get(i + 1)[0] - cues.get(i)[0] : 10 * ix.timescale;
            if (ix.dur[i] < 0) return null;
        }
        if (ix.firstByte != firstCluster && ix.firstByte < firstCluster) return null;
        return ix;
    }

    /**
     * v24: the header bytes made safe for a file that holds only some clusters: the Segment size becomes "unknown" and
     * the SeekHead + Cues (whose positions point into the full stream) become Void elements, so FFmpeg reads the
     * clusters in order and seeks by their own timestamps.
     */
    static byte[] patchWebmHeader(byte[] head, int len, Webm wm) {
        byte[] h = java.util.Arrays.copyOf(head, len);
        int p = (int) wm.segSizePos, L = wm.segSizeLen;
        h[p] = (byte) (0xFF >> (L - 1));
        for (int i = 1; i < L; i++) h[p + i] = (byte) 0xFF;
        voidOut(h, wm.cuesPos, wm.cuesHdr, wm.cuesData);
        voidOut(h, wm.seekPos, wm.seekHdr, wm.seekData);
        return h;
    }

    static void voidOut(byte[] h, long pos, int hdr, long data) {
        if (pos < 0) return;
        long total = hdr + data;
        int k = (int) Math.min(8, total - 1);           // length of the Void's size field
        long payload = total - 1 - k;
        if (k < 1 || payload < 0) return;
        int p = (int) pos;
        h[p] = (byte) 0xEC;                              // Void
        long v = payload;
        for (int i = k - 1; i >= 1; i--) { h[p + 1 + i] = (byte) (v & 0xFF); v >>= 8; }
        h[p + 1] = (byte) ((0x80 >> (k - 1)) | (v & (0xFF >> k)));
    }

    public static final class NeedMore extends Exception {
        public final long bytes;
        NeedMore(long bytes) { super("need " + bytes + " bytes"); this.bytes = bytes; }
    }

    /** the fragments covering [t0 - pre, t1 + post] seconds */
    public static Pick pick(Index ix, double t0, double t1) {
        Pick p = new Pick();
        double t = ix.ept / (double) ix.timescale;
        long off = ix.firstByte;
        int first = -1, last = -1;
        double fs = 0, fe = 0;
        for (int i = 0; i < ix.size.length; i++) {
            double d = ix.dur[i] / (double) ix.timescale;
            if (first < 0 && t + d > t0) { first = i; fs = t; p.from = off; }
            if (first >= 0) { last = i; fe = t + d; p.to = off + ix.size[i] - 1; }
            if (first >= 0 && t + d >= t1) break;
            t += d;
            off += ix.size[i];
        }
        if (first < 0) return null;
        p.startSec = fs; p.endSec = fe; p.fragments = last - first + 1;
        return p;
    }

    /**
     * Writes init + the fragments covering [t0, t1] (seconds) of the stream at url into out.
     * Returns null when the stream has no usable index (the caller falls back to FFmpeg reading the stream).
     */
    public static Got fetch(OkHttpClient http, String url, double t0, double t1, File out, boolean high, CancelToken ct) throws Exception {
        long t00 = System.currentTimeMillis();
        int want = 96 * 1024;
        byte[] head = null; int n = 0;
        Index ix = null;
        Webm wm = null;
        long[] total = {-1};
        for (int round = 0; round < 3 && ix == null; round++) {
            head = get(http, url, 0, want - 1, total);
            n = head.length;
            try {
                if (isEbml(head, n)) { wm = new Webm(); ix = parseWebm(head, n, total[0], wm); }
                else ix = parse(head, n);
                if (ix == null) return null;
            }
            catch (NeedMore m) {
                if (n < want - 1) return null;   // the stream ended: not a DASH stream
                want = (int) Math.min(4L << 20, Math.max(m.bytes + 4096, want * 4L));
            }
        }
        if (ix == null || ix.initEnd > n) return null;
        if (wm != null) head = patchWebmHeader(head, (int) ix.initEnd, wm);
        Pick p = pick(ix, t0, t1);
        if (p == null) return null;
        File body = new File(out.getPath() + ".part");
        ParallelDownloader.downloadRange(http, url, null, p.from, p.to, body, high, ct, null);
        try (FileOutputStream fo = new FileOutputStream(out); InputStream in = new java.io.FileInputStream(body)) {
            fo.write(head, 0, (int) ix.initEnd);
            byte[] buf = new byte[1 << 16]; int r;
            while ((r = in.read(buf)) > 0) fo.write(buf, 0, r);
        } finally { body.delete(); }
        Got g = new Got();
        g.file = out; g.startSec = p.startSec; g.bytes = out.length();
        g.seconds = (System.currentTimeMillis() - t00) / 1000.0;
        return g;
    }

    static byte[] get(OkHttpClient http, String url, long a, long z) throws Exception { return get(http, url, a, z, null); }

    /** total[0] = the stream's full length from Content-Range (or Content-Length of a 200), -1 unknown */
    static byte[] get(OkHttpClient http, String url, long a, long z, long[] total) throws Exception {
        Request rq = new Request.Builder().url(url).header("Range", "bytes=" + a + "-" + z).build();
        try (Response r = http.newCall(rq).execute()) {
            if ((r.code() != 206 && r.code() != 200) || r.body() == null) throw new Exception("HTTP " + r.code());
            if (total != null) {
                String cr = r.header("Content-Range");
                try {
                    if (cr != null && cr.lastIndexOf('/') >= 0 && !cr.endsWith("*")) total[0] = Long.parseLong(cr.substring(cr.lastIndexOf('/') + 1).trim());
                    else if (r.code() == 200) total[0] = r.body().contentLength();
                } catch (Exception ignored) {}
            }
            byte[] all = r.body().bytes();
            if (r.code() == 200 && all.length > z - a + 1) return java.util.Arrays.copyOf(all, (int) (z - a + 1));
            return all;
        }
    }

    public static String describe(Got g) {
        return String.format(Locale.US, "%.1f MB in %.1f s", g.bytes / 1048576.0, g.seconds);
    }
}
