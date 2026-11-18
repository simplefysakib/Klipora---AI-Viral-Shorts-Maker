package com.viral.shortsmaker.core;

import android.content.Context;

import com.yausername.youtubedl_android.YoutubeDL;
import com.yausername.youtubedl_android.YoutubeDLRequest;
import com.yausername.youtubedl_android.YoutubeDLResponse;

import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import kotlin.Unit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/** yt-dlp wrapper: one-time init, self-update, robust client fallbacks, cancellation. */
public final class YtEngine {
    public interface Progress { void on(float percent); }

    public static final class Info {
        public String title;
        public int duration;
        public String thumbUrl;
    }

    private static final CountDownLatch ready = new CountDownLatch(1);
    private static volatile boolean ok = false;
    private static volatile String initError = null;
    private static volatile boolean updating = false;

    /**
     * Player-client fallbacks. v1 forced android/tv_embedded/web_embedded first; tv_embedded no longer exists and
     * android needs PO tokens, so attempt 1 now uses yt-dlp's own maintained defaults.
     */
    static final String[] CLIENTS = {
            null,
            "youtube:player_client=tv,web_safari,mweb",
            "youtube:player_client=android_vr,ios,web",
    };

    private YtEngine() {}

    public static void initAsync(Context ctx) {
        final Context app = ctx.getApplicationContext();
        new Thread(() -> {
            try {
                long t = System.currentTimeMillis();
                YoutubeDL.getInstance().init(app);
                ok = true;
                AppLog.i("Video engine ready (" + (System.currentTimeMillis() - t) + " ms)");
            } catch (Throwable e) {
                initError = String.valueOf(e.getMessage());
                AppLog.e("Video engine init failed: " + initError);
            } finally {
                ready.countDown();
            }
            if (ok) {
                long last = Prefs.sp(app).getLong("last_ytdlp_update", 0);
                if (System.currentTimeMillis() - last > 24L * 3600 * 1000) update(app, null);
            }
        }, "yt-init").start();
    }

    public static boolean isReady() { return ok; }

    public static void awaitReady() throws Exception {
        if (!ready.await(90, TimeUnit.SECONDS)) throw new Exception("Video engine is still starting, try again in a moment");
        if (!ok) throw new Exception("Video engine failed to start: " + initError);
    }

    public interface UpdateCallback { void done(boolean success, String message); }

    /** Updates yt-dlp through the library (keeps its version bookkeeping); falls back to a direct download. */
    public static void update(Context ctx, UpdateCallback cb) {
        final Context app = ctx.getApplicationContext();
        if (updating) { if (cb != null) cb.done(false, "Update already running"); return; }
        updating = true;
        new Thread(() -> {
            String msg; boolean success;
            try {
                awaitReady();
                try {
                    YoutubeDL.UpdateStatus st = YoutubeDL.getInstance().updateYoutubeDL(app, YoutubeDL.UpdateChannel._STABLE);
                    msg = (st == YoutubeDL.UpdateStatus.ALREADY_UP_TO_DATE ? "Already latest: " : "Updated to ") + YoutubeDL.getInstance().version(app);
                } catch (Throwable libErr) {
                    AppLog.e("Library updater failed (" + libErr.getMessage() + "), downloading yt-dlp directly…");
                    File f = new File(app.getNoBackupFilesDir(), "youtubedl-android/yt-dlp/yt-dlp");
                    File parent = f.getParentFile();
                    if (parent != null && !parent.exists()) parent.mkdirs();
                    File tmp = new File(f.getAbsolutePath() + ".tmp");
                    OkHttpClient c = Net.client();
                    try (Response r = c.newCall(new Request.Builder().url("https://github.com/yt-dlp/yt-dlp/releases/latest/download/yt-dlp").build()).execute()) {
                        if (!r.isSuccessful() || r.body() == null) throw new Exception("HTTP " + r.code());
                        try (InputStream in = r.body().byteStream(); FileOutputStream out = new FileOutputStream(tmp)) {
                            byte[] buf = new byte[65536]; int n;
                            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
                        }
                    }
                    if (tmp.length() < 500_000) throw new Exception("download incomplete");
                    if (!tmp.renameTo(f)) throw new Exception("could not replace engine file");
                    f.setExecutable(true, false);
                    msg = "Updated to " + YoutubeDL.getInstance().version(app);
                }
                success = true;
                Prefs.sp(app).edit().putLong("last_ytdlp_update", System.currentTimeMillis()).apply();
                AppLog.i("yt-dlp: " + msg);
            } catch (Throwable e) {
                success = false;
                msg = "Update failed: " + e.getMessage();
                AppLog.e(msg);
            } finally {
                updating = false;
            }
            if (cb != null) { final boolean s = success; final String m = msg; Net.main(() -> cb.done(s, m)); }
        }, "yt-update").start();
    }

    public static String version(Context c) {
        try { return ok ? YoutubeDL.getInstance().version(c.getApplicationContext()) : "starting…"; } catch (Throwable e) { return "unknown"; }
    }

    // ------------------------------------------------------------------ URL helpers

    private static final Pattern ID = Pattern.compile("(?:v=|/shorts/|/live/|youtu\\.be/|/embed/|/v/)([A-Za-z0-9_-]{11})");

    public static String videoId(String url) {
        if (url == null) return "";
        Matcher m = ID.matcher(url);
        return m.find() ? m.group(1) : "";
    }

    public static String cleanUrl(String input) {
        if (input == null) return "";
        String s = input.trim();
        if (s.startsWith("[") && s.contains("](") && s.endsWith(")")) s = s.substring(s.indexOf("](") + 2, s.length() - 1).trim();
        String id = videoId(s);
        return id.isEmpty() ? s : "https://www.youtube.com/watch?v=" + id;
    }

    public static boolean isStandaloneYoutubeUrl(String text) {
        if (text == null) return false;
        String s = text.trim();
        if (s.isEmpty() || s.length() > 200 || s.contains(" ") || s.contains("\n")) return false;
        return s.matches("^https?://(www\\.|m\\.|music\\.)?(youtube\\.com/(watch\\?v=|shorts/|live/)|youtu\\.be/)[A-Za-z0-9_\\-]{11}([?&][A-Za-z0-9_\\-&=%+.]*)?$");
    }

    // ------------------------------------------------------------------ info

    static boolean validTitle(String t) {
        return t != null && !t.trim().isEmpty() && !t.trim().equalsIgnoreCase("YouTube") && !t.trim().equalsIgnoreCase("- YouTube");
    }

    /** v45: the anonymous visitor id (YouTube's own), or null when it does not answer */
    static String visitorIdOrNull(OkHttpClient http) {
        try { return AudioRace.fetchVisitor(http); } catch (Exception e) { return null; }
    }

    /** v45: the InnerTube client's visitorData field, or "" when there is no id (the call stays as it was) */
    static String visitorField(String visitor) {
        return visitor == null || visitor.isEmpty() ? "" : ",\"visitorData\":\"" + visitor + "\"";
    }

    /** Fast metadata: InnerTube player API + oEmbed, yt-dlp only as a fallback. Never invents a duration. */
    public static Info fetchInfo(String url) throws Exception {
        String id = videoId(url);
        if (id.isEmpty()) throw new Exception("That's not a supported video link");
        Info info = new Info();
        info.thumbUrl = "https://i.ytimg.com/vi/" + id + "/hqdefault.jpg";
        OkHttpClient fast = Net.fast();
        // v45: the anonymous visitor id goes with the player call. The bare call is answered "Sign in to confirm you're
        // not a bot" on some networks; AudioRace's visionOS call WITH the id passed on Sakib's phone (3.0 run)
        String visitor = visitorIdOrNull(fast);
        try {
            String payload = "{\"videoId\":\"" + id + "\",\"context\":{\"client\":{\"clientName\":\"WEB\",\"clientVersion\":\"2.20250214.01.00\",\"hl\":\"en\"" + visitorField(visitor) + "}}}";
            Request.Builder rb = new Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                    .header("User-Agent", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36");
            if (visitor != null) rb.header("X-Goog-Visitor-Id", visitor);
            Request req = rb.post(RequestBody.create(payload, MediaType.parse("application/json"))).build();
            try (Response r = fast.newCall(req).execute()) {
                if (r.isSuccessful() && r.body() != null) {
                    JSONObject vd = new JSONObject(r.body().string()).optJSONObject("videoDetails");
                    if (vd != null) {
                        // code 32: a stream that is live now (or a premiere not started) has no length yet: say so at once
                        // (4.9 then ran three yt-dlp lookups and ended with "Video download failed")
                        if (vd.optBoolean("isLive", false) || vd.optBoolean("isUpcoming", false)) throw new LiveException();
                        if (validTitle(vd.optString("title"))) info.title = vd.optString("title").trim();
                        try { info.duration = Integer.parseInt(vd.optString("lengthSeconds", "0")); } catch (Exception ignored) {}
                    }
                }
            }
        } catch (LiveException live) { throw new Exception(live.getMessage()); } catch (Exception ignored) {}
        if (!validTitle(info.title)) {
            try (Response r = fast.newCall(new Request.Builder().url("https://www.youtube.com/oembed?format=json&url=https://www.youtube.com/watch?v=" + id).build()).execute()) {
                if (r.isSuccessful() && r.body() != null) {
                    String t = new JSONObject(r.body().string()).optString("title", "");
                    if (validTitle(t)) info.title = t.trim();
                }
            } catch (Exception ignored) {}
        }
        if (info.duration <= 0) {
            // v45: the visionOS player call with the visitor id (the call AudioRace races for the audio) before yt-dlp's clients
            try {
                JSONObject vj = AudioRace.visionOs(id, fast, visitor);
                if (info.duration <= 0) info.duration = vj.optInt("duration", 0);
                if (!validTitle(info.title) && validTitle(vj.optString("title"))) info.title = vj.optString("title").trim();
            } catch (Exception ignored) {}
        }
        if (info.duration <= 0) {
            awaitReady();
            Exception last = null;
            for (String client : CLIENTS) {
                try {
                    YoutubeDLRequest r = base(url, client);
                    r.addOption("--dump-json");
                    r.addOption("--skip-download");
                    YoutubeDLResponse resp = exec(r, "info", null, (p, e, l) -> Unit.INSTANCE);
                    JSONObject j = new JSONObject(resp.getOut());
                    if (isLive(j)) throw new LiveException();
                    if (!validTitle(info.title)) info.title = j.optString("title", null);
                    info.duration = j.optInt("duration", 0);
                    if (info.duration > 0) break;
                } catch (LiveException live) { throw new Exception(live.getMessage());
                } catch (Exception e) { last = e; }
            }
            if (info.duration <= 0) throw new Exception(friendlyYtError(last));
        }
        if (!validTitle(info.title)) info.title = "Video (" + id + ")";
        return info;
    }

    /** code 32: a live stream / upcoming premiere (no fixed length: shorts can't be cut from it yet) */
    static final class LiveException extends Exception {
        LiveException() { super("This is a live stream or premiere that hasn't finished. Try again after it ends."); }
    }

    /** code 32: yt-dlp's info says the video is live now or not started */
    public static boolean isLive(JSONObject j) {
        if (j == null) return false;
        String ls = j.optString("live_status", "");
        return j.optBoolean("is_live", false) || "is_live".equals(ls) || "is_upcoming".equals(ls);
    }

    static YoutubeDLRequest base(String url, String client) {
        YoutubeDLRequest r = new YoutubeDLRequest(url);
        r.addOption("--no-warnings");
        r.addOption("--no-playlist");
        r.addOption("--geo-bypass");
        r.addOption("--no-mtime");
        r.addOption("--socket-timeout", "20");
        if (client != null) r.addOption("--extractor-args", client);
        return r;
    }

    static void speed(YoutubeDLRequest r, boolean high) {
        if (high) {
            r.addOption("--concurrent-fragments", "16");
            r.addOption("--http-chunk-size", "10M");
        } else {
            r.addOption("--limit-rate", "3M");
            r.addOption("--concurrent-fragments", "4");
            r.addOption("--http-chunk-size", "2M");
        }
    }

    public static String friendlyYtError(Throwable e) {
        String m = e == null ? "" : String.valueOf(e.getMessage());
        String l = m.toLowerCase(java.util.Locale.ROOT);
        // code 32: the age check first - YouTube's age gate says "Sign in to confirm your age", which 4.9 read as the bot check
        if (l.contains("age-restricted") || l.contains("confirm your age") || l.contains("inappropriate for some users")) return "This video is age-restricted and can't be downloaded without login.";
        if (l.contains("sign in to confirm") || l.contains("not a bot")) return "The video site is asking for a bot check on this network. Switch Wi-Fi/mobile data, or tap Settings → Update video engine, then retry.";
        if (l.contains("private video")) return "This video is private.";
        if (l.contains("live event") || l.contains("is live") || l.contains("premieres in")) return "This is a live stream or premiere that hasn't finished. Try again after it ends.";
        if (l.contains("unknownhost") || l.contains("unable to resolve host") || l.contains("no address associated")) return "No internet connection. Check Wi-Fi / mobile data and retry.";
        if (l.contains("unavailable")) return "This video is unavailable in your region or was removed.";
        if (l.contains("unable to download") || l.contains("timed out") || l.contains("network")) return "Network problem while reading the video. Check your internet and retry.";
        if (l.contains("requested format")) return "No downloadable format found. Try Settings → Update video engine.";
        return m.isEmpty() ? "Video download failed" : ("Video download: " + (m.length() > 220 ? m.substring(0, 220) : m));
    }

    /** YouTube's age gate is an authentication restriction, not a transient client or network failure. */
    public static boolean isAgeRestricted(Throwable e) {
        String m = e == null ? "" : String.valueOf(e.getMessage());
        String l = m.toLowerCase(java.util.Locale.ROOT);
        return l.contains("age-restricted") || l.contains("confirm your age")
                || l.contains("inappropriate for some users") || l.contains("age restricted");
    }

    private static File findWithPrefix(File dir, String prefix) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) if (f.getName().startsWith(prefix) && !f.getName().endsWith(".part") && !f.getName().endsWith(".ytdl") && f.length() > 1000) return f;
        return null;
    }

    public interface LineProgress { void on(float percent, String line); }

    /** Result of the audio download: the file plus yt-dlp's info (title, duration, direct stream URLs). */
    public static final class AudioResult {
        public File file;
        public JSONObject info;
        public double lookupSec, downloadSec;   // v8: the two halves of the audio step, logged once
    }

    // ------------------------------------------------------------------ v8: stream list fetched while the user picks options
    private static final java.util.concurrent.ConcurrentHashMap<String, java.util.concurrent.FutureTask<JSONObject>> INFO = new java.util.concurrent.ConcurrentHashMap<>();
    private static final java.util.concurrent.ConcurrentHashMap<String, Long> INFO_AT = new java.util.concurrent.ConcurrentHashMap<>();
    static final long INFO_TTL_MS = 2L * 3600 * 1000;   // YouTube's direct links live ~6 h

    /** v8: start reading the stream list as soon as a link is pasted (v7 spent ~25 s on it after Generate). */
    public static void prefetchStreams(String url) {
        String id = videoId(url);
        if (id.isEmpty()) return;
        Long at = INFO_AT.get(id);
        if (INFO.containsKey(id) && at != null && System.currentTimeMillis() - at < INFO_TTL_MS) return;
        java.util.concurrent.FutureTask<JSONObject> t = new java.util.concurrent.FutureTask<>(() -> {
            awaitReady();
            return lighten(streamInfo(url, null));
        });
        // code 32: only the newest link (+ the one before it) stays in memory. 4.9 kept every pasted link's yt-dlp answer
        // (1-3 MB of JSON each, several times that parsed) for the life of the app = memory a 2-3 GB phone's job needs
        if (INFO.size() >= 2) {
            String oldest = null; long oa = Long.MAX_VALUE;
            for (java.util.Map.Entry<String, Long> e : INFO_AT.entrySet()) if (e.getValue() < oa) { oa = e.getValue(); oldest = e.getKey(); }
            if (oldest != null) { INFO.remove(oldest); INFO_AT.remove(oldest); }
        }
        INFO.put(id, t);
        INFO_AT.put(id, System.currentTimeMillis());
        Thread th = new Thread(t, "yt-prefetch");
        th.setDaemon(true);
        th.start();
    }

    /** code 32: the parts of yt-dlp's answer Klipora never reads (caption track lists for ~150 languages, thumbnails, heatmap) */
    static JSONObject lighten(JSONObject info) {
        if (info != null) for (String k : new String[]{"automatic_captions", "subtitles", "thumbnails", "heatmap"}) info.remove(k);
        return info;
    }

    /** the prefetched stream list (waits for a prefetch still running), or null */
    static JSONObject prefetched(String url, CancelToken ct) {
        String id = videoId(url);
        java.util.concurrent.FutureTask<JSONObject> t = id.isEmpty() ? null : INFO.get(id);
        Long at = id.isEmpty() ? null : INFO_AT.get(id);
        if (t == null || at == null || System.currentTimeMillis() - at > INFO_TTL_MS) return null;
        try {
            while (true) {
                if (ct != null) ct.check();
                try { return t.get(500, java.util.concurrent.TimeUnit.MILLISECONDS); } catch (java.util.concurrent.TimeoutException ignored) {}
            }
        } catch (CancelToken.Cancelled c) { throw c; } catch (Exception e) { INFO.remove(id); return null; }
    }

    /** yt-dlp -J over the client list; null when every client failed */
    static JSONObject streamInfo(String url, CancelToken ct) throws Exception {
        JSONObject info = null;
        Exception last = null;
        for (int i = 0; i < CLIENTS.length && info == null; i++) {
            if (ct != null) ct.check();
            try {
                YoutubeDLRequest r = base(url, CLIENTS[i]);
                if (i == 2) r.addOption("-4");
                r.addOption("--skip-download");
                r.addOption("-J");
                YoutubeDLResponse resp = exec(r, "info", ct, null);
                String out = resp == null ? null : resp.getOut();
                int a = out == null ? -1 : out.indexOf('{');
                if (a >= 0) info = new JSONObject(out.substring(a));
            } catch (CancelToken.Cancelled c) { throw c; } catch (Exception e) { last = e; }
        }
        if (info == null && last != null) throw last;
        return info;
    }

    public static File downloadAudio(String url, File dir, boolean high, Progress p, CancelToken ct) throws Exception {
        return downloadAudioWithInfo(url, dir, high, (pct, line) -> p.on(pct), ct).file;
    }

    /**
     * Downloads the smallest decent audio-only stream and, in the same yt-dlp run, writes the info JSON. The info
     * holds every format's direct URL, so clip rendering can stream the video without a second yt-dlp extraction.
     */
    public static AudioResult downloadAudioWithInfo(String url, File dir, boolean high, LineProgress p, CancelToken ct) throws Exception {
        awaitReady();
        final String base = "audio_" + System.currentTimeMillis();
        // v7 fast path: read the stream list, then fetch the audio with parallel range requests (YouTube throttles
        // a single connection to ~0.5 MB/s). Any problem falls back to yt-dlp's own download below.
        // v20 (3.1): every attempt has its own file names and its leftovers are deleted before the next one starts
        // (3.0: the standard downloader picked up the fast path's half-written file -> "aac channel element duplicate"
        // on every speech part). A link refused mid-download (HTTP 403) gets ONE fresh race without that route, and
        // every finished file is decode-checked before the speech is cut from it.
        java.util.Set<String> skip = new java.util.HashSet<>();
        for (int attempt = 0; attempt < 2; attempt++) {
            final String prefix = base + (attempt == 0 ? "" : "_r" + attempt);
            final String[] route = {null};
            try {
                AudioResult fast = fastAudio(url, dir, prefix, high, p, ct, skip, route);
                if (fast != null) {
                    double dur = fast.info == null ? 0 : fast.info.optDouble("duration", 0);
                    String bad = AudioCheck.problem(fast.file, dur, AudioCheck.ffmpeg(ct));
                    if (bad == null) return fast;
                    AppLog.i("Audio download got corrupted (" + bad + "), fetching it again from another route…");
                    deleteAll(dir, prefix);
                    if (route[0] != null) skip.add(route[0]);
                    continue;
                }
            } catch (CancelToken.Cancelled c) {
                deleteAll(dir, prefix);
                throw c;
            } catch (Exception e) {
                if (ct.cancelled) throw new CancelToken.Cancelled();
                deleteAll(dir, prefix);
                if (isAgeRestricted(e)) {
                    AppLog.e("Age-restricted YouTube video: authenticated access is required; skipping downloader retries");
                    throw new Exception(friendlyYtError(e));
                }
                if (attempt == 0 && route[0] != null && ParallelDownloader.linkRefused(e)) {
                    AppLog.i("YouTube refused the audio link mid-download (HTTP 403), getting a fresh link from another route…");
                    skip.add(route[0]);
                    continue;
                }
                AppLog.i("Fast audio download didn't finish (" + shortMsg(e) + "), using the standard downloader");
                break;
            }
        }
        final String prefix = base + "_std";
        String tpl = new File(dir, prefix + ".%(ext)s").getAbsolutePath();
        Exception last = null;
        for (int i = 0; i < CLIENTS.length; i++) {
            ct.check();
            try {
                if (i > 0) AppLog.i("Retrying audio with backup YouTube client (" + (i + 1) + "/" + CLIENTS.length + ")…");
                YoutubeDLRequest r = base(url, CLIENTS[i]);
                if (i == 2) r.addOption("-4");
                r.addOption("-f", speechAudioFormat(audioLang));   // code 28: the chosen (else original) language, smallest stream of it
                r.addOption("-S", SPEECH_SORT);
                r.addOption("--retries", "4");
                r.addOption("--fragment-retries", "4");
                r.addOption("--write-info-json");
                r.addOption("--no-write-playlist-metafiles");
                r.addOption("-o", tpl);
                speed(r, high);
                exec(r, "audio", ct, (prog, eta, line) -> { p.on(prog == null ? 0 : prog, line); return Unit.INSTANCE; });
                File f = findAudio(dir, prefix);
                if (f != null) {
                    String bad = AudioCheck.problem(f, 0, AudioCheck.ffmpeg(ct));
                    if (bad != null && i < CLIENTS.length - 1) {
                        // v20: never hand a damaged file to the speech cutter; the next client gets a clean start
                        AppLog.i("Audio from the standard downloader is damaged (" + bad + "), trying the next YouTube client…");
                        deleteAll(dir, prefix);
                        last = new Exception("the downloaded audio was damaged");
                        continue;
                    }
                    if (bad != null) AppLog.i("Audio may be damaged (" + bad + "); damaged spots will be skipped when the speech is cut");
                    AudioResult res = new AudioResult();
                    res.file = f;
                    File ij = new File(dir, prefix + ".info.json");
                    try {
                        if (ij.exists()) res.info = new JSONObject(Project.readText(ij));
                    } catch (Exception e) { AppLog.e("Info JSON unreadable: " + e.getMessage()); }
                    ij.delete();
                    return res;
                }
            } catch (Exception e) {
                if (ct.cancelled) throw new CancelToken.Cancelled();
                if (isAgeRestricted(e)) {
                    AppLog.e("Age-restricted YouTube video: authenticated access is required; skipping backup clients");
                    throw new Exception(friendlyYtError(e));
                }
                last = e;
                AppLog.e("Audio attempt " + (i + 1) + " failed: " + shortMsg(e));
            }
        }
        throw new Exception(friendlyYtError(last));
    }

    /** smallest decent audio-only https format (same preference as the yt-dlp -f string: >=32 kbps, smallest) */
    static JSONObject pickAudio(JSONObject info) {
        org.json.JSONArray fs = info == null ? null : info.optJSONArray("formats");
        if (fs == null) return null;
        // code 28: in a multi-language list only the chosen language (else the original track); before, the smallest
        // audio of ANY language could win (a dub instead of the original on MrBeast-style videos)
        final String lkey = AudioTracks.keyFor(info, audioLang);
        final boolean kd = AudioTracks.keyIsDefault(info, lkey);
        JSONObject best = null;
        double bestKey = Double.MAX_VALUE;
        for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i);
            if (f == null) continue;
            String u = f.optString("url", ""), proto = f.optString("protocol", "https");
            String vc = f.optString("vcodec", "none"), ac = f.optString("acodec", "none");
            if (!u.startsWith("http") || !(proto.equals("https") || proto.equals("http"))) continue;
            if (!"none".equals(vc) && !vc.isEmpty()) continue;
            if ("none".equals(ac) || ac.isEmpty()) continue;
            if (f.optString("format_note", "").toLowerCase(java.util.Locale.US).contains("drc")) continue;
            if (!AudioTracks.allowed(f, lkey, kd)) continue;
            double abr = f.optDouble("abr", f.optDouble("tbr", 0));
            if (abr > 0 && abr < 32) continue;
            String ext = f.optString("ext", "");
            if (!ext.equals("m4a") && !ext.equals("webm") && !ext.equals("mp4") && !ext.equals("opus")) continue;
            double key = (abr > 0 ? abr : 1000) + (AudioRace.needsPoToken(f) ? 100_000 : 0);   // v20: token-free links first
            // code 28: dubbed tracks' smallest stream (itag 139) is 22 kHz HE-AAC: take the 44/48 kHz ones for speech
            int asr = f.optInt("asr", 0);
            if (asr > 0 && asr < 32000) key += 10_000;
            if (key < bestKey) { bestKey = key; best = f; }
        }
        return best;
    }

    /**
     * v14: every YouTube route at once (AudioRace), then the smallest audio source found, fetched with parallel
     * range requests (or parallel HLS fragments). A muxed stream is only used when no route has audio alone, and then
     * the smallest one (360p) is fetched and only its audio track kept.
     */
    private static AudioResult fastAudio(String url, File dir, String prefix, boolean high, LineProgress p, CancelToken ct,
                                         java.util.Set<String> skip, String[] routeOut) throws Exception {
        p.on(0, null);
        AudioRace.Won w = AudioRace.race(url, ct, skip);
        routeOut[0] = w.route;
        JSONObject f = w.choice.format;
        AppLog.i(String.format(java.util.Locale.US, "YouTube links: %.1f s via %s (all routes tried at once)", w.lookupSec, w.route));
        String kind = w.choice.kind == AudioRace.Kind.AUDIO_HTTPS ? "audio only" : w.choice.kind == AudioRace.Kind.AUDIO_HLS ? "audio only, HLS"
                : w.choice.kind == AudioRace.Kind.MUXED_HTTPS ? "no audio-only stream: smallest video+audio, audio kept" : "no audio-only stream: smallest HLS video+audio, audio kept";
        final long t0 = System.currentTimeMillis();
        AppLog.i(String.format(java.util.Locale.US, "Audio: format %s (%s, %s, ~%.0f kbps)%s", f.optString("format_id"), f.optString("ext"), kind,
                AudioRace.rate(f), high ? ", no speed cap" : ", capped at 3 MB/s"));
        File out;
        long fetched;
        if (w.choice.kind == AudioRace.Kind.AUDIO_HTTPS || w.choice.kind == AudioRace.Kind.MUXED_HTTPS) {
            boolean muxed = w.choice.kind == AudioRace.Kind.MUXED_HTTPS;
            File dl = new File(dir, prefix + (muxed ? ".muxed." : ".") + f.optString("ext", "m4a"));
            ParallelDownloader.download(Net.downloadFor(f.getString("url")), f.getString("url"), f.optJSONObject("http_headers"), f.optLong("filesize", 0), dl, high, ct, (done, total, bps) -> {
                float pct = total > 0 ? (float) (done * 100.0 / total) : 0f;
                p.on(Math.max(0.1f, pct * (muxed ? 0.95f : 1f)), String.format(java.util.Locale.US, "[download] %.1f%% of %.2fMiB at %.2fMiB/s", pct, total / 1048576.0, bps / 1048576.0));
            });
            fetched = dl.length();
            out = muxed ? keepAudio(dl, new File(dir, prefix + ".m4a"), ct) : dl;
        } else {
            boolean muxed = w.choice.kind == AudioRace.Kind.MUXED_HLS;
            final float share = muxed ? 0.95f : 1f;
            File got = null;
            if (w.fullInfo) {
                // yt-dlp re-uses the list it already read (no second YouTube request, no bot wall) + 16 parallel fragments
                File ij = new File(dir, prefix + ".race.info.json");
                Project.writeText(ij, w.info.toString());
                try {
                    YoutubeDLRequest r = new YoutubeDLRequest(java.util.Collections.<String>emptyList());
                    r.addOption("--load-info-json", ij.getAbsolutePath());
                    r.addOption("--no-warnings");
                    r.addOption("-f", f.optString("format_id"));
                    r.addOption("--concurrent-fragments", high ? "16" : "6");
                    r.addOption("--fragment-retries", "4");
                    r.addOption("-o", new File(dir, prefix + (muxed ? ".muxed" : "") + ".%(ext)s").getAbsolutePath());
                    exec(r, "audio", ct, (prog, eta, line) -> { p.on(prog == null ? 0 : prog * share, line); return Unit.INSTANCE; });
                    got = findAudio(dir, prefix + (muxed ? ".muxed" : ""));
                } catch (CancelToken.Cancelled c) { throw c; } catch (Exception e) {
                    AppLog.e("Parallel HLS download failed (" + shortMsg(e) + "), reading it directly");
                } finally { ij.delete(); }
            }
            if (got == null) {   // ffmpeg reads the playlist itself, audio only
                got = new File(dir, prefix + ".m4a");
                MediaRunner.run(new String[]{"-y", "-hide_banner", "-i", f.getString("url"), "-map", "0:a:0", "-vn", "-c:a", "copy", got.getAbsolutePath()}, 0, null, ct);
                muxed = false;
            }
            fetched = got.length();
            out = muxed ? keepAudio(got, new File(dir, prefix + ".m4a"), ct) : got;
        }
        double sec = (System.currentTimeMillis() - t0) / 1000.0;
        AppLog.i(String.format(java.util.Locale.US, "Audio: %.1f MB in %.1f s (%.1f MB/s)%s", fetched / 1048576.0, sec, fetched / 1048576.0 / Math.max(0.1, sec),
                out.length() != fetched ? String.format(java.util.Locale.US, ", speech file %.1f MB", out.length() / 1048576.0) : ""));
        AudioResult res = new AudioResult();
        res.file = out;
        res.info = w.info;
        res.lookupSec = w.lookupSec;
        res.downloadSec = sec;
        return res;
    }

    /** v14: muxed download -> its audio track only (stream copy, ~1 s), the video file deleted */
    static File keepAudio(File muxed, File out, CancelToken ct) throws Exception {
        try {
            MediaRunner.run(new String[]{"-y", "-hide_banner", "-i", muxed.getAbsolutePath(), "-map", "0:a:0", "-vn", "-sn", "-c:a", "copy", out.getAbsolutePath()}, 0, null, ct);
            if (!out.exists() || out.length() < 1024) throw new Exception("no audio track in the stream");
            return out;
        } finally { muxed.delete(); }
    }

    /** v20: a finished audio file of exactly this attempt (never a temp, partial, muxed or info file) */
    static File findAudio(File dir, String prefix) {
        File[] fs = dir.listFiles();
        if (fs == null) return null;
        for (File f : fs) {
            String n = f.getName();
            if (!n.startsWith(prefix + ".")) continue;
            String rest = n.substring(prefix.length() + 1);
            if (rest.contains(".") || rest.contains("muxed") || rest.equals("part") || rest.equals("ytdl") || rest.equals("json") || rest.equals("dl")) continue;
            if (f.length() > 1000) return f;
        }
        return null;
    }

    /** v20: deletes every file of one download attempt (partials, temps, muxed leftovers) */
    static void deleteAll(File dir, String prefix) {
        File[] fs = dir.listFiles();
        if (fs == null) return;
        for (File f : fs) { String n = f.getName(); if (n.startsWith(prefix + ".") && !n.endsWith(".json")) f.delete(); }
    }

    /** "[download]  42.1% of ~ 5.40MiB at 1.20MiB/s ETA 00:03" -> "2.3 of 5.4 MB • 1.2 MB/s" */
    public static String describeDownload(float pct, String line) {
        if (line == null) return null;
        java.util.regex.Matcher m = Pattern.compile("of\\s+~?\\s*([0-9.]+)\\s*([KMG])iB.*?at\\s+([0-9.]+)\\s*([KMG])iB/s").matcher(line);
        if (!m.find()) return null;
        try {
            double total = Double.parseDouble(m.group(1)) * unit(m.group(2));
            double speed = Double.parseDouble(m.group(3)) * unit(m.group(4));
            double done = total * Math.max(0, Math.min(100, pct)) / 100.0;
            return String.format(java.util.Locale.US, "%.1f of %.1f MB • %.1f MB/s", done / 1048576, total / 1048576, speed / 1048576);
        } catch (Exception e) { return null; }
    }

    private static double unit(String u) { return "G".equals(u) ? 1073741824.0 : "M".equals(u) ? 1048576.0 : 1024.0; }

    /**
     * Picks direct stream URLs {video, audio-or-null} from yt-dlp's info JSON with the same preference as
     * {@link #resolveStreams}: H.264 video-only (fast to decode) up to maxH + m4a audio, else a progressive stream.
     */
    public static String[] pickStreams(JSONObject info, int maxH) { return pickStreams(info, maxH, null); }

    /** code 33: HDR / 10-bit video stream (yt-dlp "dynamic_range", or the codec string says 10-bit) */
    static boolean isHdr(JSONObject f) {
        String dr = f.optString("dynamic_range", "");
        if (!dr.isEmpty() && !"SDR".equalsIgnoreCase(dr) && !"null".equals(dr)) return true;
        String vc = f.optString("vcodec", "");
        return vc.startsWith("vp09.02") || vc.startsWith("vp09.03") || (vc.startsWith("av01.") && vc.contains(".10."));
    }

    /** v24: may a video-only stream above 1080p be used (codec family "vp9"/"av1", height, fps)? */
    public interface HiOk { boolean ok(String family, int height, double fps); }

    /**
     * v24: the same pick with streams above 1080p allowed when hi says so (a hardware decoder for that codec + size on
     * this phone). Without hi (or for 1080p and below) nothing changes.
     */
    public static String[] pickStreams(JSONObject info, int maxH, HiOk hi) {
        if (info == null) return null;
        org.json.JSONArray fs = info.optJSONArray("formats");
        if (fs == null) return null;
        JSONObject bestV = null, bestA = null, bestP = null, small = null;
        int bvScore = -1, baScore = -1, bpScore = -1, smScore = Integer.MAX_VALUE;
        final String key = AudioTracks.keyFor(info, audioLang);   // code 28: the chosen audio language (else the original)
        final boolean kd = AudioTracks.keyIsDefault(info, key);
        for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i);
            if (f == null) continue;
            String u = f.optString("url", "");
            String proto = f.optString("protocol", "https");
            if (!u.startsWith("http") || !(proto.equals("https") || proto.equals("http"))) continue;
            String vc = f.optString("vcodec", "none"), ac = f.optString("acodec", "none");
            // code 32: quality = the SHORT side ("1080p" of a vertical video is 1080x1920). 4.9 compared the pixel height,
            // so a youtube.com/shorts link (1080x1920, 720x1280) only passed at 480x854 = soft, upscaled shorts
            int h = pOf(f);
            boolean portrait = f.optInt("height", 0) > f.optInt("width", 0) && f.optInt("width", 0) > 0;
            boolean hasV = !"none".equals(vc) && !vc.isEmpty(), hasA = !"none".equals(ac) && !ac.isEmpty();
            if (hasV && h > maxH) continue;
            if (hasV && h > 1080) {
                if (portrait) continue;   // above 1080p only landscape (the hardware decoder checks are made for it)
                // v24: above 1080p only adaptive VP9 / AV1 in a container the clip fetch can cut (mp4 sidx / webm cues)
                String ext = f.optString("ext");
                if (hasA || hi == null || !("mp4".equals(ext) || "webm".equals(ext))) continue;
                if (!hi.ok(HwDecode.family(vc), h, f.optDouble("fps", 30))) continue;
            }
            if (hasV && !hasA) {
                // v6: at the same height prefer H.264 (cheap to decode) and <=30 fps (a 60 fps stream costs twice the
                // decoding for a 30 fps short)
                double fps = f.optDouble("fps", 30);
                // v21: at the same height VP9 before AV1 (both decode in software here; VP9 decodes faster on phone CPUs). The
                // height always wins: 1080p in any codec beats 720p H.264, and nothing above the cap is taken.
                boolean vp9 = vc.startsWith("vp09") || vc.startsWith("vp9");
                // code 33 (5.1): an HDR stream (HDR10 / HLG, 10-bit VP9 profile 2 / AV1 10-bit) is never tone-mapped by the
                // render: it came out washed out / grey. At the same height it now loses to every SDR stream (5.0: list order)
                int s = h * 100 + (vc.startsWith("avc1") ? 50 : vp9 ? 20 : 0) + (fps <= 31 ? 30 : 0) + ("mp4".equals(f.optString("ext")) ? 5 : 0)
                        - (isHdr(f) ? 90 : 0);
                if (s > bvScore) { bvScore = s; bestV = f; }
                // v16: a small H.264 copy (240-480p) for face analysis: cheap to fetch and to decode
                if (vc.startsWith("avc1") && "mp4".equals(f.optString("ext")) && h >= 240 && h <= 480) {
                    int ss = Math.abs(h - 360) * 10 + (fps <= 31 ? 0 : 5);
                    if (ss < smScore) { smScore = ss; small = f; }
                }
            } else if (hasA && !hasV) {
                if (!AudioTracks.allowed(f, key, kd)) continue;
                if (f.optString("format_note", "").toLowerCase(java.util.Locale.US).contains("drc")) continue;
                int s = (int) f.optDouble("abr", 0) + ("m4a".equals(f.optString("ext")) ? 1000 : 0);
                if (s > baScore) { baScore = s; bestA = f; }
            } else if (hasV) {
                int s = h;
                if (s > bpScore) { bpScore = s; bestP = f; }
            }
        }
        // {video, audio-or-null, small analysis video-or-null, "WxH codec" of the video}
        if (bestV != null && bestA != null && pOf(bestV) >= Math.min(maxH, 480))
            return new String[]{bestV.optString("url"), bestA.optString("url"), small == null || small == bestV ? null : small.optString("url"), describe(bestV)};
        if (bestP != null) return new String[]{bestP.optString("url"), null, null, describe(bestP)};
        if (bestV != null && bestA != null) return new String[]{bestV.optString("url"), bestA.optString("url"), null, describe(bestV)};
        return null;
    }

    /** code 32: a format's quality class: the short side of its size (1920x1080 and 1080x1920 are both 1080p) */
    public static int pOf(JSONObject f) {
        int w = f.optInt("width", 0), h = f.optInt("height", 0);
        return w > 0 && h > 0 ? Math.min(w, h) : h;
    }

    static String describe(JSONObject f) {
        String vc = f.optString("vcodec", "");
        int dot = vc.indexOf('.');
        return f.optInt("width", 0) + "x" + f.optInt("height", 0) + " " + (dot > 0 ? vc.substring(0, dot) : vc) + " itag " + f.optString("format_id", "?");
    }

    /** max source height for a quality setting: 0 auto, 1 = 1080p, 2 = 720p, 3 = 480p */
    public static int maxHeight(int quality, double durationSec) {
        switch (quality) {
            case 1: return 1080;
            case 3: return 480;
            case 2: return 720;
            // v16 (2.7): always 1080p. 2.6 took 480p for videos over 25 min: a 9:16 crop of that is ~270x480 pixels,
            // stretched to 720x1280 = the blurry tracked shorts in his log (3 MB for 43 s). Shorts now fetch only
            // their own few seconds, so a long video no longer costs a big download.
            default: return 1080;
        }
    }

    /**
     * Video-only H.264 (fastest to decode on low-end phones) + separate AAC audio, with a progressive fallback.
     * v1 asked for progressive mp4 only, which YouTube now serves at 360p (format 18) - so every short was 360p.
     */
    public static String videoFormat(int h) {
        // code 32: a vertical video (youtube.com/shorts) is measured by its width: 1080x1920 is 1080p. 4.9's height<= only let
        // its 480x854 stream through. (aspect_ratio is yt-dlp's width/height; box-checked with yt-dlp's own selector.)
        String p = "bv*[aspect_ratio<1][width<=" + h + "]", l = "bv*[height<=" + h + "]";
        return p + "[vcodec^=avc1]/" + l + "[vcodec^=avc1]/" + p + "[ext=mp4]/" + l + "[ext=mp4]/" + p + "/" + l + "/b[height<=" + h + "]/b";
    }
    public static final String AUDIO_FORMAT = "ba[ext=m4a]/ba[acodec^=mp4a]/ba";

    /**
     * code 28 (4.6): the audio language picked for this job ("" = the video's original track). Set by the pipeline when
     * a job starts (one job runs at a time); every stream pick and yt-dlp format string follows it.
     */
    public static volatile String audioLang = "";

    /**
     * code 28: the audio languages of a video for the picker, read fast with the direct player call (about a second;
     * empty when it doesn't answer or the video has one language). The full list follows from {@link #audioTracksFull}.
     */
    public static java.util.List<AudioTracks.Track> audioTracksQuick(String url) {
        String id = videoId(url);
        if (id.isEmpty()) return java.util.Collections.emptyList();
        // code 28: with the anonymous visitor id first (box 2026-11-02, residential IP: the bare call answered
        // LOGIN_REQUIRED on 3 of 3 videos, the same call with visitorData answered OK every time), bare as the fallback
        try { okhttp3.OkHttpClient h = Net.fast(); return AudioTracks.list(AudioRace.visionOs(id, h, AudioRace.fetchVisitor(h))); }
        catch (Throwable e) {
            try { return AudioTracks.list(AudioRace.visionOs(id, Net.fast())); }
            catch (Throwable e2) { return java.util.Collections.emptyList(); }
        }
    }

    /** code 28: the audio languages from the stream list read in the background after the link was pasted (waits for it) */
    public static java.util.List<AudioTracks.Track> audioTracksFull(String url, CancelToken ct) {
        try { return AudioTracks.list(prefetched(url, ct)); } catch (Throwable e) { return java.util.Collections.emptyList(); }
    }

    /** code 28: yt-dlp audio selector for the render (the chosen language first) */
    public static String audioFormat() { return AudioTracks.ytdlpAudio(audioLang); }

    /**
     * code 28: the small speech-audio selector of the standard downloader. "worstaudio" sorts the original track LAST
     * in a multi-language list (yt-dlp sorts by language preference first), so the speech of a dub was transcribed;
     * now the wanted (or original) language comes first and the smallest stream of it is taken via -S lang,+abr.
     */
    public static String speechAudioFormat(String wanted) {
        String any = "ba[abr>=32]/ba/wa/worst";
        if (wanted == null || wanted.isEmpty() || !wanted.matches("[A-Za-z0-9-]{2,16}")) return any;
        return "ba[language=" + wanted + "][abr>=32]/ba[language=" + wanted + "]/" + any;
    }
    public static final String SPEECH_SORT = "lang,+abr";

    /** Direct media URLs {video, audio-or-null} for seek-rendering clips without downloading the whole video. */
    public static String[] resolveStreams(String url, int quality, double duration, CancelToken ct) {
        return resolveStreamsH(url, maxHeight(quality, duration), ct);
    }

    /** v19: the same with an explicit top height (a cropped 9:16 short fetches up to 1080p whatever the export size) */
    public static String[] resolveStreamsH(String url, int h, CancelToken ct) {
        String fmt = "(" + videoFormat(h).replace("/b[height<=" + h + "]/b", "") + ")+(" + audioFormat() + ")/b[height<=" + h + "]/b";
        for (int i = 0; i < CLIENTS.length; i++) {
            if (ct.cancelled) return null;
            try {
                awaitReady();
                YoutubeDLRequest r = base(url, CLIENTS[i] == null ? "youtube:player_skip=hls" : CLIENTS[i] + ";player_skip=hls");
                r.addOption("-f", fmt);
                r.addOption("-g");
                YoutubeDLResponse resp = exec(r, "resolve", ct, (p, e, l) -> Unit.INSTANCE);
                String out = resp.getOut() == null ? "" : resp.getOut().trim();
                String[] lines = out.split("\n");
                if (lines.length >= 1 && lines[0].trim().startsWith("http")) {
                    String v = lines[0].trim();
                    String a = lines.length >= 2 && lines[1].trim().startsWith("http") ? lines[1].trim() : null;
                    return new String[]{v, a};
                }
            } catch (Exception e) {
                if (ct.cancelled) return null;
                AppLog.e("Stream URL attempt " + (i + 1) + " failed: " + shortMsg(e));
            }
        }
        return null;
    }

    /** Fallback: download video-only + audio-only files (no merge needed, FFmpeg takes both inputs). */
    public static File[] downloadStreams(String url, File dir, int quality, double duration, boolean high, Progress p, CancelToken ct) throws Exception {
        awaitReady();
        int h = maxHeight(quality, duration);
        File v = new File(dir, "source_video.mp4"), a = new File(dir, "source_audio.m4a");
        if (v.exists() && v.length() > 10000) return new File[]{v, a.exists() && a.length() > 1000 ? a : null};
        Exception last = null;
        for (int i = 0; i < CLIENTS.length; i++) {
            ct.check();
            try {
                YoutubeDLRequest r = base(url, CLIENTS[i]);
                if (i == 2) r.addOption("-4");
                r.addOption("-f", videoFormat(h));
                r.addOption("--retries", "4");
                r.addOption("--fragment-retries", "4");
                r.addOption("-o", v.getAbsolutePath());
                speed(r, high);
                exec(r, "video", ct, (prog, eta, line) -> { if (prog != null && prog > 0) p.on(prog * 0.85f); return Unit.INSTANCE; });
                if (!v.exists() || v.length() < 10000) continue;
                try {
                    YoutubeDLRequest ra = base(url, CLIENTS[i]);
                    ra.addOption("-f", audioFormat());   // code 28: the chosen audio language
                    ra.addOption("-o", a.getAbsolutePath());
                    speed(ra, high);
                    exec(ra, "vaudio", ct, (prog, eta, line) -> { if (prog != null && prog > 0) p.on(85 + prog * 0.15f); return Unit.INSTANCE; });
                } catch (Exception ae) {
                    if (ct.cancelled) throw new CancelToken.Cancelled();
                    AppLog.e("Separate audio download failed, using the video's own audio: " + shortMsg(ae));
                }
                return new File[]{v, a.exists() && a.length() > 1000 ? a : null};
            } catch (CancelToken.Cancelled c) {
                throw c;
            } catch (Exception e) {
                if (ct.cancelled) throw new CancelToken.Cancelled();
                last = e;
                AppLog.e("Video attempt " + (i + 1) + " failed: " + shortMsg(e));
            }
        }
        throw new Exception(friendlyYtError(last));
    }

    public static void cancel(String processId) {
        try { if (processId != null) YoutubeDL.getInstance().destroyProcessById(processId); } catch (Throwable ignored) {}
    }

    /** stops every yt-dlp process of this job */
    public static void cancel(CancelToken ct) {
        if (ct == null) return;
        for (String id : new java.util.ArrayList<>(ct.ytIds)) cancel(id);
    }

    /** yt-dlp run with a fresh process id per attempt (fixes "Process ID already exists" on retries). */
    static YoutubeDLResponse exec(YoutubeDLRequest r, String tag, CancelToken ct,
                                  kotlin.jvm.functions.Function3<Float, Long, String, Unit> cb) throws Exception {
        return ProcIds.run(tag, ct == null ? null : ct.ytIds, YtEngine::killQuiet, id -> {
            if (ct != null) { ct.processId = id; if (ct.cancelled) throw new CancelToken.Cancelled(); }
            return YoutubeDL.getInstance().execute(r, id, cb);
        });
    }

    private static void killQuiet(String id) {
        try { YoutubeDL.getInstance().destroyProcessById(id); } catch (Throwable ignored) {}
    }

    static String shortMsg(Throwable e) {
        String m = String.valueOf(e.getMessage());
        int i = m.indexOf("ERROR:");
        if (i >= 0) m = m.substring(i);
        return m.length() > 200 ? m.substring(0, 200) : m;
    }
}
