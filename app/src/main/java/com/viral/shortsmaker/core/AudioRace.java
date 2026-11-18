package com.viral.shortsmaker.core;

import com.yausername.youtubedl_android.YoutubeDLRequest;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import kotlin.Unit;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * v14 audio fast path. 2.4 on Sakib's phone: the stream list had no audio-only stream, yt-dlp's first client hit
 * YouTube's "confirm you're not a bot" wall, the second client returned only one muxed (video+audio) stream and that
 * whole file was downloaded serially: 158 s.
 *
 * Now every route starts at the same moment (no serial waits), each with a short deadline:
 *  - the stream list read while the user picked options,
 *  - a direct InnerTube player request as the VISIONOS client (no JS player, no PO token: yt-dlp 2026's own
 *    no-JS default) in ~1 s without starting Python, over IPv4 and IPv6 separately (the bot wall is per address),
 *  - yt-dlp -J with its default clients, with web_safari/tv_simply/mweb (HLS lists) and with android_vr/ios/tv.
 * The first list with a working audio-only stream (itag 139/140/249/250/251/599/600, checked with a 1-byte range
 * request) wins and the others are stopped. If no route has one, the smallest thing that carries audio is used:
 * audio-only HLS (233/234), then the smallest muxed stream (itag 18, 360p) and only its audio is kept. Never a
 * high-resolution video just for the audio. Every download uses parallel range requests / parallel fragments.
 */
public final class AudioRace {
    private AudioRace() {}

    static final long ROUTE_TIMEOUT_MS = 30_000;
    static final long RACE_DEADLINE_MS = 40_000;
    static final long PROBE_TIMEOUT_MS = 6_000;
    /** code 28: how long the race waits for a route that lists the chosen audio language */
    static final long LANG_WAIT_MS = 15_000;

    /** one route's result */
    static final class Cand {
        final String route;
        final JSONObject info;
        final boolean full;   // a real yt-dlp info JSON (usable with --load-info-json)
        Cand(String route, JSONObject info, boolean full) { this.route = route; this.info = info; this.full = full; }
    }

    public enum Kind { AUDIO_HTTPS, AUDIO_HLS, MUXED_HTTPS, MUXED_HLS }

    /** the chosen format and how to fetch it */
    public static final class Choice {
        public final Kind kind;
        public final JSONObject format;
        /** code 28: false = an audio language was chosen and this list doesn't have it (the original is used instead) */
        public boolean langOk = true;
        Choice(Kind k, JSONObject f) { kind = k; format = f; }
        /** lower is better; v20: a link that needs a PO token ranks half a step lower (it tends to 403 mid-download);
         *  code 28: a list without the chosen audio language ranks below every list that has it */
        public double rank() { return kind.ordinal() + (needsPoToken(format) ? 0.5 : 0) + (langOk ? 0 : 10); }
    }

    // ------------------------------------------------------------------ pure selection (unit-tested)

    static boolean isHttps(JSONObject f) {
        String p = f.optString("protocol", "https");
        return f.optString("url", "").startsWith("http") && (p.equals("https") || p.equals("http"));
    }

    static boolean isHls(JSONObject f) {
        return f.optString("protocol", "").startsWith("m3u8") || f.optString("url", "").contains(".m3u8");
    }

    static boolean hasA(JSONObject f) { String a = f.optString("acodec", "none"); return !a.isEmpty() && !"none".equals(a); }
    static boolean hasV(JSONObject f) { String v = f.optString("vcodec", "none"); return !v.isEmpty() && !"none".equals(v); }

    /** estimated bytes per second of a format (for "smallest") */
    static double rate(JSONObject f) {
        double t = f.optDouble("tbr", 0);
        if (t <= 0) t = f.optDouble("abr", 0) + f.optDouble("vbr", 0);
        if (t <= 0) { int h = f.optInt("height", 0); t = h <= 0 ? 128 : h <= 144 ? 250 : h <= 240 ? 400 : h <= 360 ? 650 : h * 3.0; }
        return t;
    }

    /**
     * best way to get speech audio out of one stream list, or null: an audio-only https stream (smallest >= 32 kbps)
     * beats audio-only HLS, which beats the smallest muxed https stream (itag 18), which beats the smallest muxed HLS.
     */
    public static Choice choose(JSONObject info) {
        JSONArray fs = info == null ? null : info.optJSONArray("formats");
        if (fs == null) return null;
        Choice c = chooseAny(info, fs);
        if (c != null) c.langOk = AudioTracks.has(info, YtEngine.audioLang);
        return c;
    }

    private static Choice chooseAny(JSONObject info, JSONArray fs) {
        JSONObject aud = YtEngine.pickAudio(info);
        if (aud != null) return new Choice(Kind.AUDIO_HTTPS, aud);
        final String key = AudioTracks.keyFor(info, YtEngine.audioLang);   // code 28: the chosen / original language only
        final boolean kd = AudioTracks.keyIsDefault(info, key);
        JSONObject ah = null, mh = null, mx = null;
        for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i);
            if (f == null || !hasA(f)) continue;
            if (f.optString("format_note", "").toLowerCase(Locale.US).contains("drc")) continue;
            if (!hasV(f) && !AudioTracks.allowed(f, key, kd)) continue;
            if (!hasV(f)) {
                if (isHls(f)) { double ab = f.optDouble("abr", f.optDouble("tbr", 0)); if (ab > 0 && ab < 32) continue;
                    if (ah == null || rate(f) < rate(ah)) ah = f; }
            } else if (isHttps(f)) {
                // v20: a muxed link that does not need a PO token beats a smaller one that does
                if (mx == null || (needsPoToken(f) != needsPoToken(mx) ? !needsPoToken(f) : rate(f) < rate(mx))) mx = f;
            } else if (isHls(f)) {
                if (f.optInt("height", 0) > 0 && f.optInt("height", 0) < 144) continue;
                if (mh == null || rate(f) < rate(mh)) mh = f;
            }
        }
        if (ah != null) return new Choice(Kind.AUDIO_HLS, ah);
        if (mx != null) return new Choice(Kind.MUXED_HTTPS, mx);
        if (mh != null) return new Choice(Kind.MUXED_HLS, mh);
        return null;
    }

    /** v20: choose() without one refused format (the next best in the same list), or null */
    static Choice chooseWithout(JSONObject info, JSONObject refused) {
        try {
            JSONObject copy = new JSONObject(info.toString());
            JSONArray fs = copy.optJSONArray("formats"), keep = new JSONArray();
            String id = refused.optString("format_id"), u = refused.optString("url");
            for (int i = 0; fs != null && i < fs.length(); i++) {
                JSONObject f = fs.optJSONObject(i);
                if (f != null && !(id.equals(f.optString("format_id")) && u.equals(f.optString("url")))) keep.put(f);
            }
            copy.put("formats", keep);
            return choose(copy);
        } catch (Exception e) { return null; }
    }

    static String label(Kind k) {
        return k == Kind.AUDIO_HTTPS ? "audio only" : k == Kind.AUDIO_HLS ? "audio only (HLS)" : "video+audio only";
    }

    static String extOfMime(String mime) {
        if (mime.startsWith("audio/mp4")) return "m4a";
        if (mime.startsWith("audio/webm")) return "webm";
        if (mime.startsWith("video/webm")) return "webm";
        return "mp4";
    }

    /** code 32: the video's own audio language like yt-dlp reports it: the original track, else the default one ("" = none) */
    static String audioLanguage(JSONArray fmts) {
        String def = "";
        for (int i = 0; i < fmts.length(); i++) {
            JSONObject f = fmts.optJSONObject(i);
            if (f == null || !f.has("language")) continue;
            String l = f.optString("language", "");
            if (l.isEmpty() || l.endsWith("-desc")) continue;
            int pref = f.optInt("language_preference", 0);
            if (pref == 10) return l;
            if (pref == 5 && def.isEmpty()) def = l;
        }
        return def;
    }

    /** InnerTube player JSON -> the yt-dlp info shape the rest of the app reads (plain-URL formats only) */
    public static JSONObject fromPlayer(JSONObject pl, String ua) throws Exception {
        JSONObject ps = pl.optJSONObject("playabilityStatus");
        String status = ps == null ? "" : ps.optString("status", "");
        if (!"OK".equals(status)) throw new Exception(status.isEmpty() ? "no playability" : status + ": " + (ps.optString("reason", "")));
        JSONObject sd = pl.optJSONObject("streamingData");
        if (sd == null) throw new Exception("no streams");
        JSONObject vd = pl.optJSONObject("videoDetails");
        JSONObject out = new JSONObject();
        JSONArray fmts = new JSONArray();
        JSONObject hdr = new JSONObject().put("User-Agent", ua);
        for (String key : new String[]{"formats", "adaptiveFormats"}) {
            JSONArray a = sd.optJSONArray(key);
            if (a == null) continue;
            for (int i = 0; i < a.length(); i++) {
                JSONObject s = a.optJSONObject(i);
                if (s == null || !s.has("url")) continue;   // ciphered formats need the JS player: skip
                String mime = s.optString("mimeType", "");
                String codecs = "";
                int q = mime.indexOf("codecs=\"");
                if (q >= 0) codecs = mime.substring(q + 8, Math.max(q + 8, mime.indexOf('"', q + 8)));
                boolean audioOnly = mime.startsWith("audio/");
                boolean muxed = "formats".equals(key);
                String[] cs = codecs.split(",\\s*");
                JSONObject f = new JSONObject();
                f.put("format_id", String.valueOf(s.optInt("itag")));
                f.put("url", s.optString("url"));
                f.put("protocol", "https");
                f.put("ext", extOfMime(mime));
                f.put("vcodec", audioOnly ? "none" : cs[0]);
                f.put("acodec", audioOnly ? cs[0] : muxed && cs.length > 1 ? cs[1] : "none");
                double br = s.optDouble("averageBitrate", s.optDouble("bitrate", 0)) / 1000.0;
                f.put("tbr", br);
                if (audioOnly) f.put("abr", br);
                if (s.has("width")) f.put("width", s.optInt("width"));
                if (s.has("height")) f.put("height", s.optInt("height"));
                if (s.has("fps")) f.put("fps", s.optInt("fps"));
                long len = 0;
                try { len = Long.parseLong(s.optString("contentLength", "0")); } catch (Exception ignored) {}
                if (len > 0) f.put("filesize", len);
                if (audioOnly && s.has("audioSampleRate")) { try { f.put("asr", Integer.parseInt(s.optString("audioSampleRate"))); } catch (Exception ignored) {} }
                // code 28 (4.6): every audio language is kept, described the way yt-dlp does it (language, a note with the
                // track's name, language_preference 10 original / 5 default / -1 dub / -10 audio description), so the chosen
                // dub can be fetched; 4.5 dropped every non-default track here
                JSONObject at = s.optJSONObject("audioTrack");
                boolean drc = s.has("isDrc") && s.optBoolean("isDrc");
                if (at != null) {
                    String dn = at.optString("displayName", "");
                    String lid = at.optString("id", "");
                    String code = lid.contains(".") ? lid.substring(0, lid.indexOf('.')) : lid;
                    boolean def = at.optBoolean("audioIsDefault", false);
                    boolean orig = dn.toLowerCase(Locale.US).contains("original");
                    boolean desc = dn.toLowerCase(Locale.US).contains("descriptive");
                    if (!code.isEmpty()) f.put("language", desc ? code + "-desc" : code);
                    f.put("language_preference", orig ? 10 : def ? 5 : desc ? -10 : -1);
                    f.put("format_note", dn + (def ? " (default)" : "") + (drc ? ", drc" : ""));
                    if (!def) f.put("format_id", f.optString("format_id") + "-" + (code.isEmpty() ? "x" : code));
                } else if (drc) f.put("format_note", "drc");
                f.put("http_headers", hdr);
                fmts.put(f);
            }
        }
        out.put("formats", fmts);
        if (vd != null) {
            out.put("title", vd.optString("title", ""));
            try { out.put("duration", Double.parseDouble(vd.optString("lengthSeconds", "0"))); } catch (Exception ignored) {}
            out.put("id", vd.optString("videoId", ""));
            // code 32: the rest of what the player answer carries, in yt-dlp's names. 4.9 kept only title/duration/id, so a
            // job whose audio came from this (fast) route lost YouTube's language, description, channel and tags - the
            // speech-language rules, the song check and the podcast check then depended on which route answered first
            out.put("description", vd.optString("shortDescription", ""));
            out.put("channel", vd.optString("author", ""));
            JSONArray kw = vd.optJSONArray("keywords");
            if (kw != null) out.put("tags", kw);
            if (vd.optBoolean("isLive", false) || vd.optBoolean("isUpcoming", false)) out.put("is_live", true);
        }
        JSONObject mf = pl.optJSONObject("microformat");
        JSONObject pmr = mf == null ? null : mf.optJSONObject("playerMicroformatRenderer");
        if (pmr != null && !pmr.optString("category", "").isEmpty()) out.put("categories", new JSONArray().put(pmr.optString("category")));
        String lang = audioLanguage(fmts);
        if (!lang.isEmpty()) out.put("language", lang);
        return out;
    }

    // ------------------------------------------------------------------ routes

    static final String VISION_UA = "Mozilla/5.0 (Macintosh; Intel Mac OS X 15_7_3) AppleWebKit/605.1.15 (KHTML, like Gecko) Version/26.0 Safari/605.1.15";

    /** direct InnerTube player call (VISIONOS: plain URLs, no JS, no PO token) */
    static JSONObject visionOs(String id, OkHttpClient http) throws Exception { return visionOs(id, http, null); }

    /**
     * v20 (3.1): with visitorData (YouTube's anonymous visitor id, the one yt-dlp sends from the page it reads first).
     * His 3.0 run: the bare player call got "LOGIN_REQUIRED: Sign in to confirm you're not a bot" on both address
     * families while yt-dlp's own visionOS call (which carries a visitor id) passed half a minute later.
     */
    static JSONObject visionOs(String id, OkHttpClient http, String visitor) throws Exception {
        String payload = "{\"videoId\":\"" + id + "\",\"contentCheckOk\":true,\"racyCheckOk\":true,\"context\":{\"client\":{"
                + "\"clientName\":\"VISIONOS\",\"clientVersion\":\"1.02\",\"deviceMake\":\"Apple\",\"deviceModel\":\"RealityDevice17,1\","
                + "\"osName\":\"visionOS\",\"osVersion\":\"26.5.23O471\",\"hl\":\"en\",\"gl\":\"IN\","
                + (visitor != null && !visitor.isEmpty() ? "\"visitorData\":\"" + visitor + "\"," : "")
                + "\"userAgent\":\"" + VISION_UA + "\"}}}";
        Request.Builder rb = new Request.Builder().url("https://www.youtube.com/youtubei/v1/player?prettyPrint=false")
                .header("User-Agent", VISION_UA)
                .header("X-YouTube-Client-Name", "101")
                .header("X-YouTube-Client-Version", "1.02")
                .header("Origin", "https://www.youtube.com");
        if (visitor != null && !visitor.isEmpty()) rb.header("X-Goog-Visitor-Id", visitor);
        Request req = rb.post(RequestBody.create(payload, MediaType.parse("application/json"))).build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new Exception("HTTP " + r.code());
            return fromPlayer(new JSONObject(r.body().string()), VISION_UA);
        }
    }

    private static final java.util.regex.Pattern VISITOR = java.util.regex.Pattern.compile("\"(Cg[A-Za-z0-9_%\\-]{20,})\"");

    /** v20: YouTube's anonymous visitor id from its 3 KB service-worker data (box 2026-10-31: 2.8 KB, one request) */
    static String visitorId(String body) {
        if (body == null) return null;
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"VISITOR_DATA\":\"([^\"]+)\"").matcher(body);
        if (m.find()) return m.group(1);
        m = VISITOR.matcher(body);
        return m.find() ? m.group(1) : null;
    }

    static String fetchVisitor(OkHttpClient http) throws Exception {
        Request req = new Request.Builder().url("https://www.youtube.com/sw.js_data").header("User-Agent", VISION_UA).header("Cookie", "SOCS=CAI").build();
        try (Response r = http.newCall(req).execute()) {
            if (!r.isSuccessful() || r.body() == null) throw new Exception("visitor id: HTTP " + r.code());
            String v = visitorId(r.body().string());
            if (v == null) throw new Exception("visitor id: none in answer");
            return v;
        }
    }

    /**
     * 1-byte range request: true when the stream really serves bytes (PO-token-less URLs can answer 403).
     * v20 (3.1): checked over the address family the link was made for, and (when the size is known) ALSO one byte
     * from the middle: his 3.0 run's format 18 (an ANDROID_VR link, which needs a PO token) answered the first bytes and
     * was refused 6 s into the download.
     */
    static boolean probe(OkHttpClient http, JSONObject f) {
        String url = f.optString("url");
        OkHttpClient c = Net.fastFor(url);
        if (!probeAt(c, f, 0)) return false;
        long size = f.optLong("filesize", (long) f.optDouble("filesize_approx", 0));
        return size < 4_000_000 || probeAt(c, f, size / 2);
    }

    static boolean probeAt(OkHttpClient http, JSONObject f, long at) {
        try {
            Request.Builder b = new Request.Builder().url(f.optString("url")).header("Range", "bytes=" + at + "-" + at);
            ParallelDownloader.addHeaders(b, f.optJSONObject("http_headers"));
            try (Response r = http.newCall(b.build()).execute()) { return r.code() == 206 || r.code() == 200; }
        } catch (Exception e) { return false; }
    }

    /** v20: which YouTube client a link was made for (its "c=" parameter), e.g. VISIONOS, ANDROID_VR; "" unknown */
    static String linkClient(String url) {
        if (url == null) return "";
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("[?&]c=([A-Z_0-9]+)").matcher(url);
        return m.find() ? m.group(1) : "";
    }

    /**
     * v20: links from clients YouTube now wants a PO token for (yt-dlp 2026.08's own table: android_vr, ios, mweb,
     * tv_simply https/dash). Without one they serve a little, then 403 (box 2026-10-31: ANDROID_VR itag 18 -> 403 at
     * byte 0; his phone: 403 six seconds in). Kept only as a last resort.
     */
    static boolean needsPoToken(JSONObject f) {
        String c = linkClient(f.optString("url"));
        return c.equals("ANDROID_VR") || c.equals("IOS") || c.equals("MWEB") || c.equals("TVHTML5_SIMPLY") || c.equals("ANDROID");
    }

    /**
     * v20: whether the race should keep waiting for a better route. Audio-only https ends the race at once. Audio-only
     * HLS settles after 15 s. A muxed video+audio stream (itag 18: ~650 kbps, 13x the 49 kbps of itag 139) never ends
     * it early: every other route gets until the deadline. His 3.0 run stopped at 21 s with itag 18 while yt-dlp's
     * default route - the one that had audio-only 139 - was still answering.
     */
    static boolean keepWaiting(Kind best, long elapsedMs, int answered, int routes) { return keepWaiting(best, true, elapsedMs, answered, routes); }

    /**
     * code 28: a best list without the chosen audio language keeps the race open (up to 15 s) for a route that lists the
     * dub; after that the original audio is used and the user is told.
     */
    static boolean keepWaiting(Kind best, boolean langOk, long elapsedMs, int answered, int routes) {
        if (best != null && !langOk) return answered < routes && elapsedMs < LANG_WAIT_MS;
        if (best == null) return answered < routes;
        if (best == Kind.AUDIO_HTTPS) return false;
        if (answered >= routes) return false;
        if (best == Kind.AUDIO_HLS) return elapsedMs < 15_000;
        return elapsedMs < RACE_DEADLINE_MS;
    }

    static JSONObject ytdlp(String url, String client, boolean ipv4, CancelToken ct, String tag) throws Exception {
        YoutubeDLRequest r = YtEngine.base(url, client);
        if (ipv4) r.addOption("-4");
        r.addOption("--skip-download");
        r.addOption("-J");
        r.addOption("--socket-timeout", "12");
        return new JSONObject(YtEngine.exec(r, tag, ct, (p, e, l) -> Unit.INSTANCE).getOut());
    }

    // ------------------------------------------------------------------ v19: a sharper video list for cropped shorts

    /** v19: tallest https video stream (video-only or muxed) in a list, up to maxH; 0 = none */
    public static int bestVideoHeight(JSONObject info, int maxH) {
        if (info == null) return 0;
        org.json.JSONArray fs = info.optJSONArray("formats");
        if (fs == null) return 0;
        int best = 0;
        for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i);
            if (f == null || !isHttps(f) || !hasV(f)) continue;
            int h = YtEngine.pOf(f);   // code 32: short side (vertical videos)
            if (h <= maxH && h > best) best = h;
        }
        return best;
    }

    /**
     * v19 (3.0): his 2.9 log fetched the tracked short at 720p (itag 136) because the list that won the audio race only
     * carried 720p https video, and a 406x720 crop was enlarged 1.78x. A 9:16 crop needs the sharpest source, so when
     * the list in hand tops out below {@code wantH}, other clients are asked at the same moment (visionOS direct, yt-dlp
     * android_vr and tv - clients that list 1080p https video) and the first list with a working >= wantH video stream
     * wins. Runs beside the transcription, so it costs no wall time; null when nothing better answered in time.
     */
    public static JSONObject sharperList(String url, int wantH, int haveH, long deadlineMs, CancelToken ct) {
        final String id = YtEngine.videoId(url);
        final LinkedBlockingQueue<Object> q = new LinkedBlockingQueue<>();
        final CancelToken sub = new CancelToken();
        ExecutorService ex = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "hd-list"); t.setDaemon(true); return t; });
        int routes = 0;
        try {
            if (!id.isEmpty()) {
                routes += 2;
                ex.submit(() -> { try { q.add(visionOs(id, Net.fast())); } catch (Throwable e) { q.add(e); } });
                ex.submit(() -> { try { q.add(visionOs(id, Net.ipv4())); } catch (Throwable e) { q.add(e); } });
            }
            final String[] clients = {"youtube:player_client=android_vr", "youtube:player_client=tv,web_safari"};
            for (String c : clients) {
                routes++;
                ex.submit(() -> { try { YtEngine.awaitReady(); q.add(ytdlp(url, c, true, sub, "hd")); } catch (Throwable e) { q.add(e); } });
            }
            long end = System.currentTimeMillis() + deadlineMs;
            OkHttpClient http = Net.fast();
            JSONObject best = null;
            int bestH = haveH;
            for (int got = 0; got < routes; got++) {
                if (ct != null && ct.cancelled) return null;
                long left = end - System.currentTimeMillis();
                if (left <= 0) break;
                Object o = q.poll(left, java.util.concurrent.TimeUnit.MILLISECONDS);
                if (o == null) break;
                if (!(o instanceof JSONObject)) continue;
                JSONObject info = (JSONObject) o;
                int h = bestVideoHeight(info, wantH);
                if (h <= bestH) continue;
                String[] s = YtEngine.pickStreams(info, wantH);
                if (s == null) continue;
                JSONObject vf = null;
                org.json.JSONArray fs = info.optJSONArray("formats");
                for (int i = 0; fs != null && i < fs.length(); i++) { JSONObject f = fs.optJSONObject(i); if (f != null && s[0].equals(f.optString("url"))) vf = f; }
                if (vf != null && !probe(http, vf)) continue;   // a URL that answers 403 is no upgrade
                best = info; bestH = vf != null && vf.optInt("height", 0) > 0 ? YtEngine.pOf(vf) : h;
                if (bestH >= wantH) break;
            }
            return best;
        } catch (Throwable t) {
            return null;
        } finally {
            sub.cancelled = true;
            try { YtEngine.cancel(sub); } catch (Throwable ignored) {}
            ex.shutdownNow();
        }
    }

    // ------------------------------------------------------------------ the race

    public static final class Won {
        public JSONObject info;      // stream list (also used for the render streams)
        public Choice choice;
        public String route;
        public boolean fullInfo;
        public double lookupSec;
    }

    /** runs every route at once and returns the best audio source; throws with every route's reason when none works */
    public static Won race(String url, CancelToken ct) throws Exception { return race(url, ct, java.util.Collections.<String>emptySet()); }

    /** v20: same, without the routes in {@code skip} (a route whose link was just refused mid-download) */
    public static Won race(String url, CancelToken ct, java.util.Set<String> skip) throws Exception {
        final String id = YtEngine.videoId(url);
        final long t0 = System.currentTimeMillis();
        final LinkedBlockingQueue<Object> q = new LinkedBlockingQueue<>();
        final CancelToken sub = new CancelToken();   // stops the losing yt-dlp runs without cancelling the job
        ExecutorService ex = Executors.newFixedThreadPool(10, r -> { Thread t = new Thread(r, "audio-race"); t.setDaemon(true); return t; });
        List<String> routes = new ArrayList<>();
        try {
            if (!skip.contains("list read earlier")) {
                routes.add("list read earlier");
                ex.submit(() -> { try { JSONObject i = YtEngine.prefetched(url, sub); q.add(i == null ? new Exception("list read earlier: none") : new Cand("list read earlier", i, true)); } catch (Throwable e) { q.add(new Exception("list read earlier: " + YtEngine.shortMsg(e))); } });
            }
            if (!id.isEmpty()) {
                final Object[][] direct = {
                        {"direct visionOS", Boolean.FALSE, Boolean.FALSE},
                        {"direct visionOS IPv4", Boolean.TRUE, Boolean.FALSE},
                        // v20: the same call with YouTube's visitor id (what yt-dlp sends; passes the bot check more often)
                        {"direct visionOS + visitor id", Boolean.FALSE, Boolean.TRUE},
                        {"direct visionOS + visitor id IPv4", Boolean.TRUE, Boolean.TRUE},
                };
                for (Object[] d : direct) {
                    final String name = (String) d[0];
                    final boolean v4 = (Boolean) d[1], vis = (Boolean) d[2];
                    if (skip.contains(name)) continue;
                    routes.add(name);
                    ex.submit(() -> {
                        try {
                            OkHttpClient h = v4 ? Net.ipv4() : Net.fast();
                            q.add(new Cand(name, visionOs(id, h, vis ? fetchVisitor(h) : null), false));
                        } catch (Throwable e) { q.add(new Exception(name + ": " + YtEngine.shortMsg(e))); }
                    });
                }
            }
            final String[][] yt = {
                    {"yt-dlp default", null, "0"},
                    // v20: yt-dlp's default (visionOS with its visitor id) over IPv4 too: the bot check is per address.
                    // Replaces web_safari/tv_simply/mweb: in yt-dlp 2026.08 those need a PO token or a JS runtime and
                    // returned no stream at all on the box (2026-10-31), so it was a Python process for nothing.
                    {"yt-dlp default IPv4", null, "1"},
                    {"yt-dlp android_vr/ios/tv IPv4", "youtube:player_client=android_vr,ios,tv", "1"},
            };
            for (String[] y : yt) {
                if (skip.contains(y[0])) continue;
                routes.add(y[0]);
                ex.submit(() -> {
                    try { YtEngine.awaitReady(); q.add(new Cand(y[0], ytdlp(url, y[1], "1".equals(y[2]), sub, "race"), true)); }
                    catch (Throwable e) { q.add(new Exception(y[0] + ": " + YtEngine.shortMsg(e))); }
                });
            }
            List<String> errors = new ArrayList<>();
            Won best = null;
            OkHttpClient http = Net.fast();
            int got = 0;
            long deadline = t0 + RACE_DEADLINE_MS;
            while (got < routes.size()) {
                ct.check();
                long now = System.currentTimeMillis();
                if (!keepWaiting(best == null ? null : best.choice.kind, best == null || best.choice.langOk, now - t0, got, routes.size())) break;
                long left = deadline - now;
                if (left <= 0) { errors.add("stopped waiting after " + (RACE_DEADLINE_MS / 1000) + " s"); break; }
                Object o = q.poll(Math.min(left, 500), TimeUnit.MILLISECONDS);
                if (o == null) continue;
                got++;
                if (o instanceof Exception) { errors.add(((Exception) o).getMessage()); continue; }
                Cand c = (Cand) o;
                Choice ch = choose(c.info);
                if (ch == null) { errors.add(c.route + ": no stream with audio"); continue; }
                if (ch.kind == Kind.AUDIO_HTTPS || ch.kind == Kind.MUXED_HTTPS) {
                    if (!probe(http, ch.format)) {
                        // v20: the same list may still carry a working audio-only HLS or muxed stream
                        Choice alt = chooseWithout(c.info, ch.format);
                        if (alt != null) alt.langOk = ch.langOk;
                        errors.add(c.route + ": format " + ch.format.optString("format_id") + " refused (403)");
                        if (alt == null || ((alt.kind == Kind.AUDIO_HTTPS || alt.kind == Kind.MUXED_HTTPS) && !probe(http, alt.format))) continue;
                        ch = alt;
                    }
                }
                if (best == null || ch.rank() < best.choice.rank() || (ch.rank() == best.choice.rank() && c.full && !best.fullInfo)) {
                    if (best != null && best.choice.kind != ch.kind) AppLog.i("Audio: " + c.route + " has " + label(ch.kind) + " (better than " + label(best.choice.kind) + " from " + best.route + ")");
                    best = new Won();
                    best.info = c.info; best.choice = ch; best.route = c.route; best.fullInfo = c.full;
                }
            }
            if (best == null) throw new Exception("every route to the video failed — " + String.join(" | ", errors));
            best.lookupSec = (System.currentTimeMillis() - t0) / 1000.0;
            if (!errors.isEmpty()) AppLog.i("Audio routes that lost: " + String.join(" | ", errors));
            return best;
        } finally {
            sub.cancelled = true;
            YtEngine.cancel(sub);
            ex.shutdownNow();
        }
    }
}
