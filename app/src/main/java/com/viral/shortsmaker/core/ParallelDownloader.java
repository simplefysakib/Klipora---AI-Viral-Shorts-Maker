package com.viral.shortsmaker.core;

import org.json.JSONObject;

import java.io.File;
import java.io.InputStream;
import java.io.RandomAccessFile;
import java.util.Iterator;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

/**
 * v7: downloads one direct stream URL with several HTTP range requests at once (like aria2). YouTube slows every
 * single connection down (v6 on a real phone: 23.9 MB in 48 s = 0.5 MB/s even in high-speed mode); parallel ranges
 * add up. Free mode: 4 connections capped at a steady 3 MB/s in total. High speed (ad / premium): 8 connections, no cap.
 */
public final class ParallelDownloader {
    private ParallelDownloader() {}

    public interface Progress { void on(long done, long total, double bytesPerSec); }

    static final long CHUNK = 1L << 20;          // 1 MB ranges: small enough to never trigger per-request throttling
    public static final long FREE_BPS = 3L * 1024 * 1024;

    /** v8: 12 separate connections in high speed (each its own TCP connection, see Net.download()) */
    public static int threads(boolean high) { return high ? 12 : 4; }

    /** total size from the format's filesize or a 1-byte range probe */
    static long probeSize(OkHttpClient http, String url, JSONObject headers, long hint) throws Exception {
        if (hint > 0) return hint;
        Request.Builder b = new Request.Builder().url(url).header("Range", "bytes=0-0");
        addHeaders(b, headers);
        try (Response r = http.newCall(b.build()).execute()) {
            String cr = r.header("Content-Range", "");
            int slash = cr.lastIndexOf('/');
            if (r.code() == 206 && slash > 0) return Long.parseLong(cr.substring(slash + 1).trim());
            if (r.code() == 403 || r.code() == 410) throw new HttpStatus(r.code());
            throw new Exception("server did not accept ranges (HTTP " + r.code() + ")");
        }
    }

    static void addHeaders(Request.Builder b, JSONObject headers) {
        if (headers == null) return;
        for (Iterator<String> it = headers.keys(); it.hasNext(); ) {
            String k = it.next();
            if (k.equalsIgnoreCase("Accept-Encoding")) continue;   // keep bytes raw for ranged writes
            b.header(k, headers.optString(k, ""));
        }
    }

    public static void download(OkHttpClient http, String url, JSONObject headers, long sizeHint, File out, boolean high,
                                CancelToken ct, Progress p) throws Exception {
        final long total = probeSize(http, url, headers, sizeHint);
        downloadRange(http, url, headers, 0, total - 1, out, high, ct, p);
    }

    /** v20: an HTTP answer that is not the bytes asked for; 403 = the signed link was refused (expired, other IP, PO token) */
    public static final class HttpStatus extends Exception {
        public final int code;
        public HttpStatus(int code) { super("HTTP " + code); this.code = code; }
    }

    /** v20: true when e (or its cause) is a refused link (HTTP 403/410): a fresh link is needed, retrying the same one is useless */
    public static boolean linkRefused(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof HttpStatus && (((HttpStatus) t).code == 403 || ((HttpStatus) t).code == 410)) return true;
            if (t.getCause() == t) break;
        }
        return false;
    }

    /** v20: the temp file a download writes before it is complete and verified */
    public static File tempOf(File out) { return new File(out.getPath() + ".dl"); }

    /**
     * v16: bytes [from, to] of the url into out (out byte 0 = from), with the same parallel 1-2 MB range requests and
     * the same speed cap as a whole download. Used to fetch only the part of a YouTube stream a short needs.
     */
    public static void downloadRange(OkHttpClient http, String url, JSONObject headers, final long from, final long to, File out, boolean high,
                                     CancelToken ct, Progress p) throws Exception {
        final long total = to - from + 1;
        if (total <= 0) throw new Exception("empty range");
        final int threads = threads(high);
        final long CHUNK = high ? 2L << 20 : ParallelDownloader.CHUNK;
        final long cap = high ? 0 : FREE_BPS;
        final long chunks = (total + CHUNK - 1) / CHUNK;
        final AtomicLong next = new AtomicLong(), done = new AtomicLong();
        final AtomicReference<Exception> err = new AtomicReference<>();
        final long t0 = System.currentTimeMillis();
        // v20 (3.1): bytes go to a temp file that becomes `out` only when every byte arrived. 3.0 wrote straight into
        // `out` pre-sized to the full length: a 403 halfway left a full-size file with holes of zeros, the standard
        // downloader then picked that file up as "the audio" and every speech part failed ("aac channel element
        // duplicate", his 3.0 log). Any failure now deletes the temp file, so a broken file can never be found later.
        final File tmp = tempOf(out);
        out.delete();
        boolean ok = false;
        try {
        try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) { raf.setLength(total); }
        Thread[] ws = new Thread[(int) Math.min(threads, Math.max(1, chunks))];
        for (int w = 0; w < ws.length; w++) {
            ws[w] = new Thread(() -> {
                try (RandomAccessFile raf = new RandomAccessFile(tmp, "rw")) {
                    byte[] buf = new byte[64 * 1024];
                    long c;
                    while (err.get() == null && !ct.cancelled && (c = next.getAndIncrement()) < chunks) {
                        long a = c * CHUNK, z = Math.min(total, a + CHUNK) - 1;   // offsets in the file
                        final long ra = from + a, rz = from + z;                     // offsets in the stream
                        Exception last = null;
                        for (int attempt = 0; attempt < 4; attempt++) {
                            long wrote = 0;
                            try {
                                Request.Builder b = new Request.Builder().url(url).header("Range", "bytes=" + ra + "-" + rz);
                                addHeaders(b, headers);
                                try (Response r = http.newCall(b.build()).execute()) {
                                    if (r.code() != 206 || r.body() == null) throw new HttpStatus(r.code());
                                    InputStream in = r.body().byteStream();
                                    raf.seek(a);
                                    int n;
                                    while ((n = in.read(buf)) > 0) {
                                        if (ct.cancelled) throw new CancelToken.Cancelled();
                                        raf.write(buf, 0, n);
                                        wrote += n;
                                        long d = done.addAndGet(n);
                                        if (cap > 0) {   // steady total rate: sleep while ahead of cap
                                            long ahead = d * 1000L / cap - (System.currentTimeMillis() - t0);
                                            if (ahead > 20) Thread.sleep(Math.min(ahead, 500));
                                        }
                                    }
                                }
                                if (wrote != z - a + 1) throw new Exception("short read " + wrote + "/" + (z - a + 1));
                                last = null;
                                break;
                            } catch (CancelToken.Cancelled cc) {
                                throw cc;
                            } catch (Exception e) {
                                done.addAndGet(-wrote);
                                last = e;
                                if (linkRefused(e)) break;   // v20: a refused link stays refused: stop at once, get a fresh one
                                Thread.sleep(400L * (attempt + 1));
                            }
                        }
                        if (last != null) throw last;
                    }
                } catch (Exception e) {
                    err.compareAndSet(null, e);
                }
            }, "dl-" + w);
            ws[w].start();
        }
        long lastBytes = 0, lastT = t0;
        double bps = 0;
        while (true) {
            boolean alive = false;
            for (Thread t : ws) if (t.isAlive()) alive = true;
            long now = System.currentTimeMillis(), d = done.get();
            if (now - lastT >= 500) {
                double inst = (d - lastBytes) * 1000.0 / (now - lastT);
                bps = bps == 0 ? inst : bps * 0.6 + inst * 0.4;
                lastBytes = d; lastT = now;
            }
            if (p != null) p.on(d, total, bps);
            if (!alive) break;
            if (ct.cancelled) { for (Thread t : ws) t.interrupt(); throw new CancelToken.Cancelled(); }
            Thread.sleep(250);
        }
        if (ct.cancelled) throw new CancelToken.Cancelled();
        if (err.get() != null) throw err.get();
        if (done.get() != total) throw new Exception("download incomplete (" + done.get() + " of " + total + " bytes)");
        if (tmp.length() != total) throw new Exception("download size mismatch (" + tmp.length() + " of " + total + " bytes)");
        if (!tmp.renameTo(out)) throw new Exception("could not finish the downloaded file");
        ok = true;
        } finally {
            if (!ok) { tmp.delete(); out.delete(); }
        }
    }
}
