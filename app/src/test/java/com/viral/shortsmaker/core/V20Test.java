package com.viral.shortsmaker.core;

import static org.junit.Assert.*;


import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import okhttp3.OkHttpClient;

/**
 * v20 (3.1): Sakib's 3.0 run - the bot check blocked the direct route, the winning route only had muxed itag 18
 * (~650 kbps), the parallel download got HTTP 403 halfway, the standard downloader then used the half-written file
 * and every speech part failed with "aac channel element duplicate".
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V20Test {

    static JSONObject res(String name) throws Exception {
        try (InputStream in = V20Test.class.getClassLoader().getResourceAsStream("v20/" + name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            String s = b.toString("UTF-8");
            return name.endsWith(".json") ? new JSONObject(s) : new JSONObject().put("text", s);
        }
    }

    static void report(String line) {
        try { Files.write(new File("build/v20_report.txt").toPath(), (line + "\n").getBytes(StandardCharsets.UTF_8),
                java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND); } catch (Exception ignored) {}
        System.out.println("V20 " + line);
    }

    // ------------------------------------------------------------------ 1. audio-only selection

    /** the list yt-dlp's android_vr/ios/tv route returned on the box (2026-10-31): only muxed itag 18, like his run */
    @Test public void hisRouteListHasOnlyMuxed18() throws Exception {
        JSONObject vr = res("android_vr_ios_tv_list.json");
        AudioRace.Choice c = AudioRace.choose(vr);
        assertNotNull(c);
        assertEquals(AudioRace.Kind.MUXED_HTTPS, c.kind);
        assertEquals("18", c.format.optString("format_id"));
        assertTrue("ANDROID_VR link needs a PO token", AudioRace.needsPoToken(c.format));
        assertEquals("4", Net.linkFamily(c.format.optString("url")));
    }

    /** the default route (yt-dlp's visionOS) on the same video has audio-only 139 (49 kbps), which must win */
    @Test public void audioOnlyFromAnotherRouteBeatsMuxed() throws Exception {
        JSONObject vr = res("android_vr_ios_tv_list.json"), def = res("default_list.json");
        AudioRace.Choice m = AudioRace.choose(vr), a = AudioRace.choose(def);
        assertEquals(AudioRace.Kind.AUDIO_HTTPS, a.kind);
        assertTrue(a.format.optString("format_id"), Arrays.asList("139", "249", "250", "140", "251").contains(a.format.optString("format_id")));
        assertTrue(AudioRace.rate(a.format) < 100);
        assertFalse(AudioRace.needsPoToken(a.format));
        assertTrue(a.rank() < m.rank());
        report(String.format(java.util.Locale.US, "selection: android_vr list -> itag %s ~%.0f kbps (muxed); default list -> itag %s ~%.0f kbps (audio only), %.1fx smaller",
                m.format.optString("format_id"), AudioRace.rate(m.format), a.format.optString("format_id"), AudioRace.rate(a.format), AudioRace.rate(m.format) / AudioRace.rate(a.format)));
    }

    /** his list + HD video-only 299 (what his log showed) still has no audio-only stream: muxed is only a fallback */
    @Test public void hdVideoOnlyDoesNotCountAsAudio() throws Exception {
        JSONObject vr = res("android_vr_ios_tv_list.json");
        JSONObject v299 = new JSONObject().put("format_id", "299").put("url", "https://x.googlevideo.com/videoplayback?c=ANDROID_VR&ip=203.0.113.7")
                .put("protocol", "https").put("ext", "mp4").put("vcodec", "avc1.64002a").put("acodec", "none").put("height", 1080).put("tbr", 4000);
        vr.getJSONArray("formats").put(v299);
        assertEquals(AudioRace.Kind.MUXED_HTTPS, AudioRace.choose(vr).kind);
    }

    /** 3.0 stopped the race at ~21 s once it held itag 18; now a muxed fallback waits for every route until the deadline */
    @Test public void raceKeepsWaitingWhileOnlyMuxedIsInHand() {
        assertTrue(AudioRace.keepWaiting(AudioRace.Kind.MUXED_HTTPS, 21_000, 3, 9));   // his 3.0 stop point
        assertTrue(AudioRace.keepWaiting(AudioRace.Kind.MUXED_HTTPS, 35_000, 8, 9));
        assertFalse(AudioRace.keepWaiting(AudioRace.Kind.MUXED_HTTPS, AudioRace.RACE_DEADLINE_MS, 3, 9));
        assertFalse(AudioRace.keepWaiting(AudioRace.Kind.MUXED_HTTPS, 21_000, 9, 9));   // everyone answered
        assertFalse(AudioRace.keepWaiting(AudioRace.Kind.AUDIO_HTTPS, 1_000, 1, 9));    // audio only: done at once
        assertTrue(AudioRace.keepWaiting(AudioRace.Kind.AUDIO_HLS, 10_000, 4, 9));
        assertFalse(AudioRace.keepWaiting(AudioRace.Kind.AUDIO_HLS, 16_000, 4, 9));
        assertTrue(AudioRace.keepWaiting(null, 30_000, 4, 9));
    }

    @Test public void muxedWithoutTokenBeatsSmallerMuxedThatNeedsOne() throws Exception {
        JSONArray fs = new JSONArray()
                .put(new JSONObject().put("format_id", "18").put("url", "https://a/videoplayback?c=ANDROID_VR").put("protocol", "https").put("ext", "mp4").put("vcodec", "avc1").put("acodec", "mp4a").put("tbr", 500))
                .put(new JSONObject().put("format_id", "18").put("url", "https://a/videoplayback?c=VISIONOS").put("protocol", "https").put("ext", "mp4").put("vcodec", "avc1").put("acodec", "mp4a").put("tbr", 650));
        AudioRace.Choice c = AudioRace.choose(new JSONObject().put("formats", fs));
        assertEquals("VISIONOS", AudioRace.linkClient(c.format.optString("url")));
        AudioRace.Choice alt = AudioRace.chooseWithout(new JSONObject().put("formats", fs), c.format);
        assertEquals("ANDROID_VR", AudioRace.linkClient(alt.format.optString("url")));
    }

    @Test public void linkFamilyFollowsTheSignedAddress() {
        assertEquals("4", Net.linkFamily("https://r1.googlevideo.com/videoplayback?expire=1&ip=49.36.12.7&id=o"));
        assertEquals("6", Net.linkFamily("https://r1.googlevideo.com/videoplayback?expire=1&ip=2409%3A40d0%3A1f%3A%3A1&id=o"));
        assertEquals("", Net.linkFamily("https://r1.googlevideo.com/videoplayback?expire=1"));
        assertEquals("", Net.linkFamily(null));
    }

    @Test public void visitorIdIsReadFromYoutubesSmallAnswer() throws Exception {
        String sw = res("sw_js_data.txt").getString("text");
        String v = AudioRace.visitorId(sw);
        assertNotNull(v);
        assertTrue(v.startsWith("Cg") && v.length() >= 20);
        assertEquals("CgtABC", AudioRace.visitorId("x \"VISITOR_DATA\":\"CgtABC\" y"));
        assertNull(AudioRace.visitorId("nothing here"));
    }

    // ------------------------------------------------------------------ 2. 403 mid-download -> corrupt file (repro)

    static java.net.ServerSocket server;
    static File media;
    static final AtomicLong servedV1 = new AtomicLong();
    static final long FAIL_AFTER = 2_500_000;

    static boolean haveFfmpeg() {
        try { return new ProcessBuilder("ffmpeg", "-version").redirectErrorStream(true).start().waitFor() == 0; } catch (Exception e) { return false; }
    }

    /** a desktop ffmpeg stands in for FFmpegKit */
    static final AudioCheck.Runner SYS = args -> {
        List<String> cmd = new ArrayList<>(Arrays.asList("ffmpeg", "-nostdin"));
        cmd.addAll(Arrays.asList(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] out = readAll(p.getInputStream());
        if (p.waitFor() != 0) throw new Exception("FFmpeg failed (code " + p.exitValue() + "): " + MediaRunner.summarize(new String(out, StandardCharsets.UTF_8)));
    };

    static byte[] readAll(InputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[65536]; int n;
        while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
        return b.toByteArray();
    }

    static String run(List<String> cmd) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        String s = new String(readAll(p.getInputStream()), StandardCharsets.UTF_8);
        return p.waitFor() + "\n" + s;
    }

    @BeforeClass public static void serve() throws Exception {
        if (!haveFfmpeg()) return;
        File dir = new File("build/v20");
        dir.mkdirs();
        media = new File(dir, "muxed18.mp4");
        if (!media.exists() || media.length() < 1_000_000) {
            // a 3-minute muxed mp4 (video + AAC, like itag 18), moov at the front like YouTube's
            String r = run(Arrays.asList("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-f", "lavfi", "-i", "testsrc=size=320x180:rate=25",
                    "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=44100", "-t", "180", "-c:v", "libx264", "-preset", "ultrafast", "-b:v", "200k",
                    "-c:a", "aac", "-b:a", "96k", "-ac", "2", "-movflags", "+faststart", media.getAbsolutePath()));
            assertTrue(r, r.startsWith("0"));
        }
        final byte[] data = Files.readAllBytes(media.toPath());
        server = new java.net.ServerSocket(0, 64, java.net.InetAddress.getByName("127.0.0.1"));
        Thread acc = new Thread(() -> {
            while (!server.isClosed()) {
                final java.net.Socket s;
                try { s = server.accept(); } catch (Exception e) { return; }
                new Thread(() -> handle(s, data)).start();
            }
        }, "v20-http");
        acc.setDaemon(true);
        acc.start();
    }

    /** a minimal HTTP/1.1 range server (one request per connection) */
    static void handle(java.net.Socket s, byte[] data) {
        try (java.net.Socket sock = s) {
            java.io.BufferedReader r = new java.io.BufferedReader(new java.io.InputStreamReader(sock.getInputStream(), StandardCharsets.ISO_8859_1));
            String line = r.readLine();
            if (line == null) return;
            String path = line.split(" ")[1];
            String range = null, h;
            while ((h = r.readLine()) != null && !h.isEmpty()) if (h.toLowerCase(java.util.Locale.US).startsWith("range:")) range = h.substring(6).trim();
            OutputStream o = sock.getOutputStream();
            boolean v1 = path.startsWith("/v1");
            long from = 0;
            if (range != null && range.startsWith("bytes=")) from = Long.parseLong(range.substring(6).split("-")[0]);
            // the first link is refused for every range from FAIL_AFTER on (a signed link refused mid-download)
            if (v1 && from >= FAIL_AFTER) {
                o.write("HTTP/1.1 403 Forbidden\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".getBytes(StandardCharsets.ISO_8859_1));
                o.flush();
                return;
            }
            long a = 0, z = data.length - 1;
            if (range != null && range.startsWith("bytes=")) {
                String[] p = range.substring(6).split("-");
                a = Long.parseLong(p[0]);
                if (p.length > 1 && !p[1].isEmpty()) z = Math.min(z, Long.parseLong(p[1]));
            }
            int len = (int) (z - a + 1);
            if (v1) servedV1.addAndGet(len);
            o.write(("HTTP/1.1 206 Partial Content\r\nContent-Type: video/mp4\r\nContent-Length: " + len + "\r\nContent-Range: bytes " + a + "-" + z + "/" + data.length
                    + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.ISO_8859_1));
            o.write(data, (int) a, len);
            o.flush();
        } catch (Exception ignored) {}
    }

    @AfterClass public static void stop() { try { if (server != null) server.close(); } catch (Exception ignored) {} }

    static String url(String path) { return "http://127.0.0.1:" + server.getLocalPort() + path; }
    /** 3.0's own file pick, verbatim */
    static File oldFindAudio(File dir, String prefix) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName();
            if (n.startsWith(prefix) && !n.endsWith(".part") && !n.endsWith(".ytdl") && !n.endsWith(".json") && f.length() > 1000) return f;
        }
        return null;
    }

    static long zeroBytes(File f) throws Exception {
        long z = 0;
        try (RandomAccessFile r = new RandomAccessFile(f, "r")) {
            byte[] b = new byte[1 << 20]; int n;
            while ((n = r.read(b)) > 0) for (int i = 0; i < n; i++) if (b[i] == 0) z++;
        }
        return z;
    }

    @Test public void old30LeavesABrokenFileAndNewOneAValidOne() throws Exception {
        org.junit.Assume.assumeTrue("needs a desktop ffmpeg", haveFfmpeg());
        OkHttpClient http = new OkHttpClient();
        File dir = new File("build/v20/run_" + System.nanoTime());
        dir.mkdirs();
        double dur = 180;

        // ---- 3.0: fast download refused halfway
        servedV1.set(0);
        String prefix = "audio_1";
        File part = new File(dir, prefix + ".muxed.mp4");
        Exception oldErr = null;
        try { Old30Downloader.download(http, url("/v1/a.mp4"), null, 0, part, false, new CancelToken(), null); }
        catch (Exception e) { oldErr = e; }
        assertNotNull(oldErr);
        assertTrue(oldErr.getMessage(), oldErr.getMessage().contains("403"));
        File picked = oldFindAudio(dir, prefix);   // what 3.0's standard downloader then returned (no other file yet)
        assertNotNull("3.0 left a full-size half-empty file that its file pick accepts", picked);
        assertEquals(media.length(), picked.length());
        long zeros = zeroBytes(picked);
        // 3.0's speech cut on that file (the part the phone failed on)
        File piece = new File(dir, "speech_0.ogg");
        String oldCut = run(new ArrayList<>(Arrays.asList(join(new String[]{"ffmpeg", "-nostdin"}, FfmpegCommands.cutAudioOpus(picked.getAbsolutePath(), 100, 60, piece.getAbsolutePath(), 1.0)))));
        String oldCheck = AudioCheck.problem(picked, dur, SYS);
        report("repro 3.0: 403 after " + servedV1.get() + " bytes; file left: " + picked.getName() + " " + picked.length() + " bytes, " + zeros + " zero bytes; "
                + "3.0 speech cut 100-160 s exit " + oldCut.split("\n")[0] + " (" + (oldCut.contains("channel element") ? "aac channel element errors" : "no aac message") + "); decode check: " + oldCheck);
        assertNotNull("the broken file must be caught", oldCheck);
        assertNotEquals("0", oldCut.split("\n")[0]);

        // ---- 3.1: same failure, nothing left behind, refused link detected, fresh link -> valid file
        servedV1.set(0);
        String prefix2 = "audio_2";
        File out = new File(dir, prefix2 + ".muxed.mp4");
        Exception newErr = null;
        long t0 = System.currentTimeMillis();
        try { ParallelDownloader.download(http, url("/v1/a.mp4"), null, 0, out, false, new CancelToken(), null); }
        catch (Exception e) { newErr = e; }
        long failMs = System.currentTimeMillis() - t0;
        assertNotNull(newErr);
        assertTrue("a refused link is recognised", ParallelDownloader.linkRefused(newErr));
        assertFalse("no half-written file is left", out.exists());
        assertFalse(ParallelDownloader.tempOf(out).exists());
        assertNull("nothing for the standard downloader to pick up", YtEngine.findAudio(dir, prefix2));
        assertNull(YtEngine.findAudio(dir, prefix2 + "_r1"));

        File fresh = new File(dir, prefix2 + "_r1.muxed.mp4");
        ParallelDownloader.download(http, url("/fresh/a.mp4"), null, 0, fresh, false, new CancelToken(), null);
        assertEquals(media.length(), fresh.length());
        assertArrayEquals(Files.readAllBytes(media.toPath()), Files.readAllBytes(fresh.toPath()));
        String newCheck = AudioCheck.problem(fresh, dur, SYS);
        assertNull(newCheck, newCheck);
        File piece2 = new File(dir, "speech_1.ogg");
        String newCut = run(new ArrayList<>(Arrays.asList(join(new String[]{"ffmpeg", "-nostdin", "-xerror"}, FfmpegCommands.cutAudioOpus(fresh.getAbsolutePath(), 100, 60, piece2.getAbsolutePath(), 1.0)))));
        assertEquals(newCut, "0", newCut.split("\n")[0]);
        report("repro 3.1: 403 detected in " + failMs + " ms, no file left behind; fresh link -> " + fresh.length() + " bytes, identical to the source; decode check clean; speech cut exit 0");

        // ---- tolerant cut: the damaged file still yields speech instead of failing the part
        // 60-120 s straddles the point where the link died: the plain cut fails, the tolerant one keeps the good part
        File piece4 = new File(dir, "speech_3.ogg");
        String plain = run(new ArrayList<>(Arrays.asList(join(new String[]{"ffmpeg", "-nostdin"}, FfmpegCommands.cutAudioOpus(picked.getAbsolutePath(), 60, 60, piece4.getAbsolutePath(), 1.0)))));
        File piece3 = new File(dir, "speech_2.ogg");
        String tol = run(new ArrayList<>(Arrays.asList(join(new String[]{"ffmpeg", "-nostdin"}, FfmpegCommands.tolerant(FfmpegCommands.cutAudioOpus(picked.getAbsolutePath(), 60, 60, piece3.getAbsolutePath(), 1.0))))));
        String kept = run(Arrays.asList("ffprobe", "-v", "error", "-show_entries", "format=duration", "-of", "csv=p=0", piece3.getAbsolutePath())).split("\n")[1].trim();
        report("cut 60-120 s of the 3.0 broken file: plain exit " + plain.split("\n")[0] + "; tolerant exit " + tol.split("\n")[0] + ", kept " + kept + " s of audio (" + piece3.length() + " bytes)");
        assertEquals(tol, "0", tol.split("\n")[0]);
        assertTrue(piece3.length() > 500);
    }

    static String[] join(String[] a, String[] b) { String[] r = Arrays.copyOf(a, a.length + b.length); System.arraycopy(b, 0, r, a.length, b.length); return r; }

    @Test public void findAudioOnlyTakesThisAttemptsFinishedFile() throws Exception {
        File dir = new File("build/v20/find_" + System.nanoTime()); dir.mkdirs();
        byte[] k = new byte[5000];
        for (String n : new String[]{"audio_9.muxed.mp4", "audio_9.m4a.dl", "audio_9.m4a.part", "audio_9.info.json", "audio_9_std.m4a"})
            Files.write(new File(dir, n).toPath(), k);
        assertNull(YtEngine.findAudio(dir, "audio_9"));
        assertEquals("audio_9_std.m4a", YtEngine.findAudio(dir, "audio_9_std").getName());
        Files.write(new File(dir, "audio_9.m4a").toPath(), k);
        assertEquals("audio_9.m4a", YtEngine.findAudio(dir, "audio_9").getName());
        YtEngine.deleteAll(dir, "audio_9");
        assertFalse(new File(dir, "audio_9.muxed.mp4").exists());
        assertFalse(new File(dir, "audio_9.m4a.dl").exists());
        assertTrue(new File(dir, "audio_9_std.m4a").exists());
    }

    // ------------------------------------------------------------------ 3. one plain line instead of an FFmpeg wall

    @Test public void ffmpegWallBecomesOneLine() {
        String wall = "ror while decoding stream #0:1: Invalid data found when processing input\n"
                + "[aac @ 0xb4000077bf0dda00] channel element 0.0 duplicate\n"
                + "Error while decoding stream #0:1: Invalid data found when processing input\n"
                + "[aac @ 0xb4000077bf0dda00] channel element 0.0 duplicate\n"
                + "Error while decoding stream #0:1: Invalid data found when processing input\n";
        String s = MediaRunner.summarize(wall);
        assertFalse(s, s.contains("0xb4"));
        assertFalse(s, s.contains("\n"));
        assertTrue(s, s.length() <= 140);
        assertTrue(s, s.contains("damaged"));
        assertEquals("unknown error", MediaRunner.summarize(""));
        report("his FFmpeg wall (" + wall.length() + " chars per part) -> \"" + s + "\" (" + s.length() + " chars)");
    }

    @Test public void tolerantDecodeFlagsGoBeforeTheInput() {
        String[] t = FfmpegCommands.tolerant(FfmpegCommands.cutAudioOpus("in.m4a", 10, 60, "out.ogg", 1.0));
        List<String> l = Arrays.asList(t);
        int i = l.indexOf("-i");
        assertTrue(l.indexOf("-err_detect") >= 0 && l.indexOf("-err_detect") < i);
        assertEquals("ignore_err", l.get(l.indexOf("-err_detect") + 1));
        assertTrue(l.indexOf("-fflags") < i && l.get(l.indexOf("-fflags") + 1).contains("discardcorrupt"));
        assertEquals("in.m4a", l.get(i + 1));
    }

    @Test public void checkSpotsCoverTheWholeFile() {
        double[] s = AudioCheck.spots(1435);   // his 23:55 video
        assertEquals(5, s.length);
        assertEquals(0, s[0], 0.001);
        assertTrue(s[4] > 1400);
        assertEquals(1, AudioCheck.spots(0).length);
    }
}
