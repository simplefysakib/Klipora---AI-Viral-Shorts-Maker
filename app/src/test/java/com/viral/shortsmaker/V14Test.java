package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.AiClient;
import com.viral.shortsmaker.core.AudioRace;
import com.viral.shortsmaker.core.CancelToken;
import com.viral.shortsmaker.core.FaceAnalyzer;
import com.viral.shortsmaker.core.FaceTrack;
import com.viral.shortsmaker.core.FfmpegCommands;
import com.viral.shortsmaker.core.Net;
import com.viral.shortsmaker.core.ParallelDownloader;
import com.viral.shortsmaker.core.Pipeline;
import com.viral.shortsmaker.core.PodcastDetector;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.Project;
import com.viral.shortsmaker.core.Transcript;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.File;
import java.io.OutputStream;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;

/** v14 (2.5): output formats, face-engine diagnostics, audio race selection, podcast detection, premium tracking. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V14Test {
    static Context ctx() { return ApplicationProvider.getApplicationContext(); }

    // ------------------------------------------------------------------ formats
    @Test public void formatSizes() {
        assertArrayEquals(new int[]{720, 1280}, FfmpegCommands.dims(720));      // 2.4 callers unchanged
        assertArrayEquals(new int[]{1080, 1920}, FfmpegCommands.dims(1080));
        FfmpegCommands.Aspect P = FfmpegCommands.Aspect.PORTRAIT, S = FfmpegCommands.Aspect.SQUARE, L = FfmpegCommands.Aspect.LANDSCAPE;
        assertArrayEquals(new int[]{1080, 1920}, FfmpegCommands.dims(P.spec(1080)));
        assertArrayEquals(new int[]{1080, 1080}, FfmpegCommands.dims(S.spec(1080)));
        assertArrayEquals(new int[]{1920, 1080}, FfmpegCommands.dims(L.spec(1080)));
        assertArrayEquals(new int[]{720, 720}, FfmpegCommands.dims(S.spec(720)));
        assertArrayEquals(new int[]{1280, 720}, FfmpegCommands.dims(L.spec(720)));
        assertArrayEquals(new int[]{480, 854}, FfmpegCommands.dims(P.spec(480)));
        assertEquals(S, FfmpegCommands.aspectOf(S.spec(1080)));
        assertEquals(P, FfmpegCommands.Aspect.of(null));
        assertEquals(L, FfmpegCommands.Aspect.of("16:9"));
        // every size is even (H.264 needs it)
        for (FfmpegCommands.Aspect a : FfmpegCommands.Aspect.values()) for (int s : new int[]{480, 720, 1080}) {
            int[] d = FfmpegCommands.dims(a.spec(s));
            assertEquals(0, d[0] % 2); assertEquals(0, d[1] % 2);
        }
    }

    @Test public void graphsFollowTheFormat() {
        String sq = FfmpegCommands.frameGraph(FfmpegCommands.Frame.FILL, null, null, FfmpegCommands.Aspect.SQUARE.spec(1080), "yuv420p");
        assertTrue(sq, sq.contains("crop=w='min(iw,trunc(ih*1/2)*2)'") && sq.contains("scale=1080:1080"));
        String wb = FfmpegCommands.frameGraph(FfmpegCommands.Frame.BLUR, null, null, FfmpegCommands.Aspect.LANDSCAPE.spec(1080), "yuv420p");
        assertTrue(wb, wb.contains("256:144") && wb.contains("1920") && wb.contains("1080"));
        String pt = FfmpegCommands.frameGraph(FfmpegCommands.Frame.FILL, null, null, 720, "yuv420p");
        assertTrue(pt, pt.contains("trunc(ih*9/16/2)") && pt.contains("scale=720:1280"));   // 9:16 exactly as 2.4
        String fit = FfmpegCommands.frameGraph(FfmpegCommands.Frame.FIT, null, null, FfmpegCommands.Aspect.SQUARE.spec(720), "yuv420p");
        assertTrue(fit, fit.contains("720") && !fit.contains("1280"));
        String[] safe = FfmpegCommands.renderClipSafe("in.mp4", null, 0, 10, null, null, "o.mp4", FfmpegCommands.Aspect.LANDSCAPE.spec(720));
        assertTrue(String.join(" ", safe).contains("scale=1280:720"));
    }

    @Test public void bitrateByPixels() {
        String[] a = FfmpegCommands.renderClip("in.mp4", null, 0, 10, FfmpegCommands.Frame.BLUR, null, null, "o.mp4", 1080, FfmpegCommands.Encoder.HW, 0);
        assertTrue(String.join(" ", a).contains("-b:v 18M"));   // v19: 8M -> 10M, v23: 12M, code 27: 18M
        String[] b = FfmpegCommands.renderClip("in.mp4", null, 0, 10, FfmpegCommands.Frame.BLUR, null, null, "o.mp4", FfmpegCommands.Aspect.SQUARE.spec(1080), FfmpegCommands.Encoder.HW, 0);
        assertTrue(String.join(" ", b).contains("-b:v 12M"));   // v19: 6M -> 7M, v23: 8M, code 27: 12M
        String[] c = FfmpegCommands.renderClip("in.mp4", null, 0, 10, FfmpegCommands.Frame.BLUR, null, null, "o.mp4", 720, FfmpegCommands.Encoder.HW, 0);
        assertTrue(String.join(" ", c).contains("-b:v 10M"));   // v16: 4M -> 5M, v19: 5M -> 6M, code 27: 10M
    }

    static List<FaceTrack.Sample> oneSpeaker(double sec, double cx) {
        Random r = new Random(3);
        List<FaceTrack.Sample> s = new ArrayList<>();
        for (int i = 0; i < sec * 5; i++) {
            double h = 0.4, w = h * 9 / 16.0;
            List<FaceTrack.Face> f = new ArrayList<>();
            f.add(new FaceTrack.Face((float) (cx - w / 2), 0.2f, (float) (cx + w / 2), 0.6f, (float) (0.05 + 0.03 * Math.abs(Math.sin(i)))));
            s.add(new FaceTrack.Sample(f, 0.3f, 2f + r.nextFloat()));
        }
        return s;
    }

    @Test public void trackingWindowFollowsTheFormat() {
        FaceTrack.Plan v = FaceTrack.plan(oneSpeaker(10, 0.3), 5, 1920, 1080, 10, 30, 9.0 / 16.0);
        FaceTrack.Plan sq = FaceTrack.plan(oneSpeaker(10, 0.3), 5, 1920, 1080, 10, 30, 1.0);
        FaceTrack.Plan wide = FaceTrack.plan(oneSpeaker(10, 0.3), 5, 1920, 1080, 10, 30, 16.0 / 9.0);
        assertEquals(1080 * 9.0 / 16 / 1920, v.cropW, 1e-6);
        assertEquals(1080.0 / 1920, sq.cropW, 1e-6);
        assertTrue(v.usable && sq.usable);
        assertFalse("16:9 of a 16:9 video keeps the whole frame", wide.usable);
        // the speaker at x=0.3 is followed in both formats (window centre near the face, clamped to the frame)
        assertEquals(0.3, v.cx[v.cx.length / 2], 0.08);
        assertEquals(Math.max(0.3, sq.cropW / 2), sq.cx[sq.cx.length / 2], 0.08);
        String g = FfmpegCommands.trackGraph(new FfmpegCommands.Track("ft.cmd", sq), null, null, FfmpegCommands.Aspect.SQUARE.spec(1080), "yuv420p");
        assertTrue(g, g.contains("trunc(ih*1/2)") && g.contains("scale=1080:1080"));
    }

    @Test public void trackingOnlyForPodcastsAndNotInWide() {
        Project p = new Project();
        p.faceTrack = true; p.category = "OTHER"; p.podcast = false; p.aspect = "PORTRAIT";
        assertFalse(Pipeline.trackingWanted(p));                 // MrBeast-style video: no tracking
        p.podcast = true;
        assertTrue(Pipeline.trackingWanted(p));                  // auto-detected podcast
        p.aspect = "SQUARE"; assertTrue(Pipeline.trackingWanted(p));
        p.aspect = "LANDSCAPE"; assertFalse(Pipeline.trackingWanted(p));
        p.aspect = "PORTRAIT"; p.faceTrack = false; assertFalse(Pipeline.trackingWanted(p));
        p.faceTrack = true; p.podcast = false; p.category = "PODCAST"; assertTrue(Pipeline.trackingWanted(p));
    }

    @Test public void projectKeepsFormatAndOldProjectsArePortrait() throws Exception {
        Project p = new Project();
        p.aspect = "SQUARE"; p.podcast = true; p.podcastWhy = "title";
        Project q = Project.fromJson(p.toJson());
        assertEquals("SQUARE", q.aspect); assertTrue(q.podcast);
        JSONObject old = p.toJson(); old.remove("aspect"); old.remove("podcast"); old.put("category", "PODCAST");
        Project o = Project.fromJson(old);
        assertEquals("PORTRAIT", o.aspect); assertTrue(o.podcast);
    }

    // ------------------------------------------------------------------ captions per format
    static List<Word> words(int n) {
        List<Word> l = new ArrayList<>();
        String[] t = {"yeh", "bahut", "badi", "baat", "hai", "aur", "sab", "log", "sunte", "hain", "1000", "crore"};
        for (int i = 0; i < n; i++) l.add(new Word(t[i % t.length], i * 0.4, i * 0.4 + 0.35));
        return l;
    }

    @Test public void captionsUseTheFormatsCanvas() {
        CaptionStyle st = CaptionStyles.byId("boxword"); st.progressBar = true;
        for (FfmpegCommands.Aspect a : FfmpegCommands.Aspect.values()) {
            CaptionLayout l = CaptionLayout.defaultFor((float) a.ratio());
            String ass = CaptionBuilder.buildAss(words(12), st, l, 5);
            int W = l.frameW(), H = l.frameH();
            assertTrue(a + ass.substring(0, 120), ass.contains("PlayResX: " + W + "\nPlayResY: " + H));
            assertTrue("progress bar spans the width", ass.contains("l " + W + " 0 l " + W + " 10"));
            assertTrue("bar sits at the bottom", ass.contains("\\pos(0," + (H - 10) + ")"));
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\\\pos\\((\\d+),(\\d+)\\)").matcher(ass);
            while (m.find()) { int x = Integer.parseInt(m.group(1)), y = Integer.parseInt(m.group(2)); assertTrue(x >= 0 && x <= W && y >= 0 && y <= H); }
        }
        assertEquals(720, new CaptionLayout().frameW()); assertEquals(1280, new CaptionLayout().frameH());
        CaptionLayout sq = CaptionLayout.defaultFor(1f), wd = CaptionLayout.defaultFor(16f / 9f);
        assertEquals(720, sq.frameW()); assertEquals(720, sq.frameH());
        assertEquals(1280, wd.frameW()); assertEquals(720, wd.frameH());
        assertEquals(1f / 1f, sq.copy().aspect, 1e-6);   // copy keeps the format
    }

    @Test public void layoutsAreRememberedPerFormat() {
        Context c = ctx();
        CaptionLayout sq = Prefs.captionLayout(c, FfmpegCommands.Aspect.SQUARE);
        sq.cy = 0.4f; sq.clamp();
        Prefs.saveCaption(c, Prefs.captionStyle(c), sq);
        assertEquals(0.4f, Prefs.captionLayout(c, FfmpegCommands.Aspect.SQUARE).cy, 1e-4);
        assertNotEquals(0.4f, Prefs.captionLayout(c, FfmpegCommands.Aspect.PORTRAIT).cy, 1e-4);   // 9:16 box untouched
        assertEquals(1f, Prefs.captionLayout(c, FfmpegCommands.Aspect.SQUARE).aspect, 1e-6);
        assertEquals(FfmpegCommands.Aspect.PORTRAIT, Prefs.aspect(c));
        Prefs.setAspect(c, FfmpegCommands.Aspect.LANDSCAPE);
        assertEquals(FfmpegCommands.Aspect.LANDSCAPE, Prefs.aspect(c));
        Project pr = new Project(); pr.aspect = "SQUARE";
        assertEquals(1f, Pipeline.layoutFor(pr, null).aspect, 1e-6);
        assertEquals(1f, Pipeline.layoutFor(pr, new CaptionLayout()).aspect, 1e-6);
    }

    // ------------------------------------------------------------------ premium tracking
    @Test public void faceTrackingIsPremium() {
        org.junit.Assume.assumeFalse("rewarded-ad unlock rule of the normal app; premium edition: PremiumEditionTest", com.viral.shortsmaker.core.Prefs.PREMIUM_EDITION);
        Context c = ctx();
        Prefs.sp(c).edit().putLong("premium_unlock_time", 0).putInt("face_track_credits", 0).commit();
        assertFalse(Prefs.faceTrackUnlocked(c));
        assertFalse(Prefs.useFaceTrack(c));
        Prefs.addFaceTrackCredit(c);                   // 1 ad
        assertTrue(Prefs.faceTrackUnlocked(c));
        assertTrue(Prefs.useFaceTrack(c));             // used by one podcast
        assertFalse(Prefs.faceTrackUnlocked(c));       // "ek baar"
        Prefs.sp(c).edit().putLong("premium_unlock_time", System.currentTimeMillis()).commit();   // 24 h pass
        for (int i = 0; i < 5; i++) assertTrue(Prefs.useFaceTrack(c));
        assertTrue(Prefs.faceTrackUnlocked(c));
    }

    // ------------------------------------------------------------------ podcast detection
    static Transcript tr(String[] lines) {
        Transcript t = new Transcript();
        for (int i = 0; i < lines.length; i++) t.segments.add(new Transcript.Segment(i * 5, i * 5 + 4.5, lines[i]));
        return t;
    }

    static List<AiClient.Candidate> votes(Boolean... v) {
        List<AiClient.Candidate> l = new ArrayList<>();
        for (Boolean b : v) { AiClient.Candidate c = new AiClient.Candidate(); c.podcastVote = b; l.add(c); }
        return l;
    }

    @Test public void podcastDetection() {
        String[] qa = new String[40], talk = new String[40];
        for (int i = 0; i < 40; i++) {
            qa[i] = i % 3 == 0 ? "Aapne yeh business kaise shuru kiya?" : "Dekhiye, shuru mein bahut struggle tha aur paise nahi the";
            talk[i] = "I am going to give this guy one million dollars if he survives in this box";
        }
        PodcastDetector.Result a = PodcastDetector.decide(false, "Ep 112 ft. Ankur Warikoo | The Ranveer Show", "", tr(qa), 3600, votes(true, true, true));
        assertTrue(a.why, a.podcast);
        PodcastDetector.Result b = PodcastDetector.decide(false, "I Survived 100 Days In A Box", "MrBeast Entertainment", tr(talk), 1100, votes(false, false));
        assertFalse(b.why, b.podcast);
        PodcastDetector.Result c = PodcastDetector.decide(false, "Untitled", "", tr(qa), 2700, votes());   // no AI vote: local check
        assertTrue(c.why, c.podcast);
        PodcastDetector.Result d = PodcastDetector.decide(true, "Minecraft gameplay", "", tr(talk), 60, votes(false));
        assertTrue(d.podcast);   // the user's pick wins
        PodcastDetector.Result e = PodcastDetector.decide(false, "Funny moments compilation", "", tr(talk), 600, votes(false, true, false));
        assertFalse(e.why, e.podcast);
    }

    @Test public void aiIsAskedAboutPodcastInTheSameRequest() throws Exception {
        String p = PromptManager.buildViralPrompt(PromptManager.Category.OTHER, "t", "0 [0-5] hi", 3, 600);
        assertTrue(p.contains("video_is_podcast"));
        String raw = "[{\"start\":10,\"end\":40,\"viral_score\":80,\"video_is_podcast\":true},{\"start\":60,\"end\":90,\"viral_score\":70}]";
        List<AiClient.Candidate> cs = AiClient.parseCandidatesForTest(raw, 600);
        assertEquals(Boolean.TRUE, cs.get(0).podcastVote);
        assertNull(cs.get(1).podcastVote);
    }

    // ------------------------------------------------------------------ face engine diagnostics + R8 rule
    @Test public void causeChainNamesTheRealReason() {
        Throwable t = new ExceptionInInitializerError(new ArrayIndexOutOfBoundsException("Index 5 out of bounds for length 5"));
        String s = FaceAnalyzer.causeChain(t);
        assertTrue(s, s.startsWith("ExceptionInInitializerError <- ArrayIndexOutOfBoundsException: Index 5"));
        assertTrue(FaceAnalyzer.statusLine().startsWith("Face engine"));
    }

    @Test public void releaseRulesKeepFlogger() throws Exception {
        File pro = new File("proguard-rules.pro");
        if (!pro.exists()) pro = new File("app/proguard-rules.pro");
        String r = new String(Files.readAllBytes(pro.toPath()));
        assertTrue(r.contains("-keep class com.google.common.flogger.** { *; }"));
        assertTrue(r.contains("-keep class com.google.mediapipe.** { *; }"));
        assertTrue(r.contains("-keep class com.google.protobuf.** { *; }"));
    }

    // ------------------------------------------------------------------ audio
    static JSONObject fmt(String id, String proto, String vc, String ac, double tbr, int h) throws Exception {
        JSONObject f = new JSONObject().put("format_id", id).put("protocol", proto).put("vcodec", vc).put("acodec", ac).put("tbr", tbr)
                .put("url", proto.startsWith("m3u8") ? "https://x/" + id + ".m3u8" : "https://x/" + id).put("ext", vc.equals("none") ? "m4a" : "mp4");
        if (vc.equals("none")) f.put("abr", tbr);
        if (h > 0) f.put("height", h);
        return f;
    }

    static JSONObject info(JSONObject... fs) throws Exception { JSONArray a = new JSONArray(); for (JSONObject f : fs) a.put(f); return new JSONObject().put("formats", a); }

    @Test public void audioChoicePrefersSmallAudioNeverBigVideo() throws Exception {
        JSONObject v1080 = fmt("137", "https", "avc1", "none", 4000, 1080);
        AudioRace.Choice c1 = AudioRace.choose(info(v1080, fmt("140", "https", "none", "mp4a", 129, 0), fmt("249", "https", "none", "opus", 50, 0), fmt("599", "https", "none", "mp4a", 30, 0)));
        assertEquals(AudioRace.Kind.AUDIO_HTTPS, c1.kind);
        assertEquals("249", c1.format.optString("format_id"));   // smallest >= 32 kbps
        AudioRace.Choice c2 = AudioRace.choose(info(fmt("18", "https", "avc1", "mp4a", 600, 360), fmt("234", "m3u8_native", "none", "mp4a", 128, 0), fmt("233", "m3u8_native", "none", "mp4a", 48, 0)));
        assertEquals(AudioRace.Kind.AUDIO_HLS, c2.kind);
        assertEquals("233", c2.format.optString("format_id"));
        AudioRace.Choice c3 = AudioRace.choose(info(fmt("22", "https", "avc1", "mp4a", 2000, 720), fmt("18", "https", "avc1", "mp4a", 600, 360), fmt("96", "m3u8_native", "avc1", "mp4a", 5000, 1080)));
        assertEquals(AudioRace.Kind.MUXED_HTTPS, c3.kind);
        assertEquals("18", c3.format.optString("format_id"));     // lowest-res muxed, never 720p/1080p
        AudioRace.Choice c4 = AudioRace.choose(info(fmt("96", "m3u8_native", "avc1", "mp4a", 5000, 1080), fmt("91", "m3u8_native", "avc1", "mp4a", 270, 144), fmt("93", "m3u8_native", "avc1", "mp4a", 1000, 360)));
        assertEquals(AudioRace.Kind.MUXED_HLS, c4.kind);
        assertEquals("91", c4.format.optString("format_id"));
        assertNull(AudioRace.choose(info(v1080)));
    }

    @Test public void playerResponseToFormats() throws Exception {
        String pl = "{\"playabilityStatus\":{\"status\":\"OK\"},\"videoDetails\":{\"title\":\"Pod\",\"lengthSeconds\":\"3600\",\"videoId\":\"abc\"},"
                + "\"streamingData\":{\"formats\":[{\"itag\":18,\"url\":\"https://g/18\",\"mimeType\":\"video/mp4; codecs=\\\"avc1.42001E, mp4a.40.2\\\"\",\"bitrate\":600000,\"width\":640,\"height\":360}],"
                + "\"adaptiveFormats\":[{\"itag\":140,\"url\":\"https://g/140\",\"mimeType\":\"audio/mp4; codecs=\\\"mp4a.40.2\\\"\",\"bitrate\":130000,\"averageBitrate\":129000,\"contentLength\":\"58000000\"},"
                + "{\"itag\":251,\"signatureCipher\":\"s=x\",\"mimeType\":\"audio/webm; codecs=\\\"opus\\\"\",\"bitrate\":150000},"
                + "{\"itag\":137,\"url\":\"https://g/137\",\"mimeType\":\"video/mp4; codecs=\\\"avc1.640028\\\"\",\"bitrate\":4000000,\"width\":1920,\"height\":1080,\"fps\":30}]}}";
        JSONObject info = AudioRace.fromPlayer(new JSONObject(pl), "UA");
        assertEquals("Pod", info.getString("title"));
        assertEquals(3600, info.getDouble("duration"), 0);
        JSONArray f = info.getJSONArray("formats");
        assertEquals(3, f.length());   // the ciphered 251 is skipped
        AudioRace.Choice c = AudioRace.choose(info);
        assertEquals(AudioRace.Kind.AUDIO_HTTPS, c.kind);
        assertEquals("140", c.format.getString("format_id"));
        assertEquals(58000000L, c.format.getLong("filesize"));
        String[] streams = com.viral.shortsmaker.core.YtEngine.pickStreams(info, 1080);
        assertEquals("https://g/137", streams[0]); assertEquals("https://g/140", streams[1]);   // HD video + audio for the render
        try { AudioRace.fromPlayer(new JSONObject("{\"playabilityStatus\":{\"status\":\"LOGIN_REQUIRED\",\"reason\":\"Sign in to confirm you're not a bot\"}}"), "UA"); fail(); }
        catch (Exception e) { assertTrue(e.getMessage().contains("not a bot")); }
    }

    /** parallel ranges against a local server that throttles each connection to ~1 MB/s, like YouTube does */
    @Test public void parallelRangesBeatOneConnection() throws Exception {
        byte[] data = new byte[12 << 20];
        new Random(1).nextBytes(data);
        java.net.ServerSocket ss = new java.net.ServerSocket(0, 50, java.net.InetAddress.getByName("127.0.0.1"));
        Thread acceptor = new Thread(() -> {
            while (!ss.isClosed()) {
                try {
                    java.net.Socket s = ss.accept();
                    new Thread(() -> {
                        try (java.net.Socket so = s) {
                            java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(so.getInputStream()));
                            String line; long a = 0, z = data.length - 1; boolean ranged = false;
                            while ((line = in.readLine()) != null && !line.isEmpty()) {
                                if (line.toLowerCase().startsWith("range:")) {
                                    String[] p = line.substring(6).trim().replace("bytes=", "").split("-");
                                    a = Long.parseLong(p[0]); if (p.length > 1 && !p[1].isEmpty()) z = Long.parseLong(p[1]); ranged = true;
                                }
                            }
                            OutputStream o = so.getOutputStream();
                            o.write(((ranged ? "HTTP/1.1 206 Partial Content" : "HTTP/1.1 200 OK") + "\r\nContent-Length: " + (z - a + 1)
                                    + "\r\nContent-Range: bytes " + a + "-" + z + "/" + data.length + "\r\nConnection: close\r\n\r\n").getBytes());
                            long t0 = System.nanoTime();
                            for (long p = a; p <= z; p += 65536) {
                                int n = (int) Math.min(65536, z - p + 1);
                                o.write(data, (int) p, n);
                                long ahead = (long) ((p - a + n) / (1024.0 * 1024) * 1e9) - (System.nanoTime() - t0);   // 1 MB/s per connection
                                if (ahead > 0) Thread.sleep(ahead / 1_000_000);
                            }
                            o.flush();
                        } catch (Exception ignored) {}
                    }).start();
                } catch (Exception e) { return; }
            }
        });
        acceptor.setDaemon(true);
        acceptor.start();
        try {
            String url = "http://127.0.0.1:" + ss.getLocalPort() + "/a";
            File out = File.createTempFile("pdl", ".bin");
            long t0 = System.currentTimeMillis();
            ParallelDownloader.download(Net.download(), url, null, 0, out, true, new CancelToken(), (d, t, b) -> {});
            double sec = (System.currentTimeMillis() - t0) / 1000.0;
            assertArrayEquals(data, Files.readAllBytes(out.toPath()));
            System.out.println(String.format("V14 parallel download: 12 MB in %.1f s with 12 connections (one throttled connection: ~12 s)", sec));
            assertTrue("parallel ranges should be several times faster than one 1 MB/s connection: " + sec, sec < 5);
        } finally { ss.close(); }
    }
}
