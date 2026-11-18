package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * code 28 (4.6): multi-language audio tracks (format parsing, which track a render / the transcript uses, the race
 * preferring a list that has the chosen dub, yt-dlp selectors), the removed "upload from phone" input and the
 * background-service helpers.
 */
@RunWith(RobolectricTestRunner.class)
public class V28AudioTest {

    @After public void reset() { YtEngine.audioLang = ""; }

    /** one audio-only format shaped exactly like yt-dlp 2026.08 writes it for a multi-language video */
    static JSONObject aud(String id, String lang, String note, int pref, double abr, String ext) throws Exception {
        JSONObject f = new JSONObject().put("format_id", id).put("url", "https://x/" + id).put("protocol", "https")
                .put("vcodec", "none").put("acodec", ext.equals("m4a") ? "mp4a.40.2" : "opus").put("abr", abr).put("tbr", abr).put("ext", ext);
        if (lang != null) f.put("language", lang).put("language_preference", pref);
        if (note != null) f.put("format_note", note);
        return f;
    }

    static JSONObject vid(String id, int h, String vc, String ext) throws Exception {
        return new JSONObject().put("format_id", id).put("url", "https://x/" + id).put("protocol", "https").put("vcodec", vc)
                .put("acodec", "none").put("height", h).put("width", h * 16 / 9).put("ext", ext).put("fps", 30);
    }

    /** a MrBeast-style list: English original + Hindi, Spanish (Latin America), Portuguese dubs + an audio description */
    static JSONObject multi() throws Exception {
        JSONArray a = new JSONArray();
        a.put(vid("137", 1080, "avc1.640028", "mp4"));
        a.put(vid("248", 1080, "vp09.00.40.08", "webm"));
        a.put(vid("134", 360, "avc1.4d401e", "mp4"));
        // dubs listed BEFORE the original and with smaller / larger bitrates, as yt-dlp does
        a.put(aud("139-0", "hi", "Hindi, low", -1, 48.8, "m4a"));
        a.put(aud("140-0", "hi", "Hindi, medium", -1, 129.5, "m4a"));
        a.put(aud("251-0", "hi", "Hindi, medium", -1, 140.1, "webm"));
        a.put(aud("140-1", "es-419", "Spanish (Latin America), medium", -1, 129.6, "m4a"));
        a.put(aud("140-2", "pt-BR", "Portuguese (Brazil), medium", -1, 129.6, "m4a"));
        a.put(aud("140-3", "en-US-desc", "English (US) descriptive, medium", -10, 129.6, "m4a"));
        a.put(aud("139-4", "en-US", "English (US) original (default), low", 10, 48.9, "m4a"));
        a.put(aud("140-4", "en-US", "English (US) original (default), medium", 10, 129.4, "m4a"));
        a.put(aud("140-4-drc", "en-US", "English (US) original (default), medium, DRC", 10, 129.4, "m4a"));
        // muxed 360p (carries the original audio only)
        a.put(new JSONObject().put("format_id", "18").put("url", "https://x/18").put("protocol", "https").put("vcodec", "avc1.42001E")
                .put("acodec", "mp4a.40.2").put("height", 360).put("ext", "mp4").put("tbr", 650));
        return new JSONObject().put("formats", a).put("language", "en-US");
    }

    /** a normal single-language video (no language fields, as before 4.6) */
    static JSONObject single() throws Exception {
        JSONArray a = new JSONArray();
        a.put(vid("137", 1080, "avc1.640028", "mp4"));
        a.put(aud("139", null, "low", 0, 48.8, "m4a"));
        a.put(aud("140", null, "medium", 0, 129.5, "m4a"));
        a.put(aud("251", null, "medium", 0, 140, "webm"));
        return new JSONObject().put("formats", a);
    }

    @Test public void listsTracksOriginalFirstWithEnglishNames() throws Exception {
        List<AudioTracks.Track> l = AudioTracks.list(multi());
        assertEquals(l.toString(), 4, l.size());   // the audio-description track is not offered
        assertEquals("en-US", l.get(0).code);
        assertTrue(l.get(0).original);
        assertEquals("English (US) (original)", l.get(0).label());
        assertEquals("Hindi", l.get(1).name);
        assertEquals("Portuguese (Brazil)", l.get(2).name);
        assertEquals("Spanish (Latin America)", l.get(3).name);
        assertEquals("en-US", AudioTracks.defaultCode(l));
        assertTrue(AudioTracks.isOriginalCode(l, "en-US"));
        assertTrue(AudioTracks.isOriginalCode(l, ""));
        assertFalse(AudioTracks.isOriginalCode(l, "hi"));
    }

    @Test public void singleLanguageVideoHasNoPickerAndUnchangedPicks() throws Exception {
        assertTrue(AudioTracks.list(single()).isEmpty());
        assertNull(AudioTracks.keyFor(single(), "hi"));
        YtEngine.audioLang = "hi";   // even with a language asked for, a one-track video behaves exactly as before
        assertEquals("139", YtEngine.pickAudio(single()).optString("format_id"));
        assertEquals("https://x/140", YtEngine.pickStreams(single(), 1080)[1]);
        assertTrue(AudioRace.choose(single()).langOk == false);   // reported as not available (the user gets one notice)
        YtEngine.audioLang = "";
        assertTrue(AudioRace.choose(single()).langOk);
    }

    @Test public void originalIsUsedWhenNothingChosen() throws Exception {
        // 4.5 bug: the smallest audio of ANY language won (a Hindi dub transcribed for an English video)
        JSONObject sp = YtEngine.pickAudio(multi());
        assertEquals("139-4", sp.optString("format_id"));
        String[] s = YtEngine.pickStreams(multi(), 1080);
        assertEquals("https://x/140-4", s[1]);   // the original m4a, not a dub, not the DRC copy
        AudioRace.Choice c = AudioRace.choose(multi());
        assertEquals(AudioRace.Kind.AUDIO_HTTPS, c.kind);
        assertEquals("en-US", c.format.optString("language"));
        assertTrue(c.langOk);
    }

    @Test public void chosenDubIsUsedForTranscriptAndRender() throws Exception {
        YtEngine.audioLang = "hi";
        assertEquals("139-0", YtEngine.pickAudio(multi()).optString("format_id"));   // smallest Hindi stream for speech
        String[] s = YtEngine.pickStreams(multi(), 1080);
        assertEquals("https://x/140-0", s[1]);                                        // Hindi m4a for the render
        assertTrue(s[3], s[3].contains("1080"));                                      // video unchanged
        AudioRace.Choice c = AudioRace.choose(multi());
        assertEquals("hi", c.format.optString("language"));
        assertTrue(c.langOk);
        YtEngine.audioLang = "es-419";
        assertEquals("https://x/140-1", YtEngine.pickStreams(multi(), 1080)[1]);
        YtEngine.audioLang = "ES-419";   // case does not matter
        assertEquals("https://x/140-1", YtEngine.pickStreams(multi(), 1080)[1]);
    }

    @Test public void missingDubFallsBackToOriginalAndSaysSo() throws Exception {
        YtEngine.audioLang = "fr";
        assertFalse(AudioTracks.has(multi(), "fr"));
        assertEquals("en-US", AudioTracks.keyFor(multi(), "fr"));
        assertEquals("https://x/140-4", YtEngine.pickStreams(multi(), 1080)[1]);
        AudioRace.Choice c = AudioRace.choose(multi());
        assertFalse(c.langOk);
        assertEquals("en-US", c.format.optString("language"));
    }

    @Test public void raceWaitsForAListWithTheDub() {
        // a best list without the chosen language keeps the race open up to 15 s, then settles
        assertTrue(AudioRace.keepWaiting(AudioRace.Kind.AUDIO_HTTPS, false, 3_000, 2, 8));
        assertFalse(AudioRace.keepWaiting(AudioRace.Kind.AUDIO_HTTPS, false, 16_000, 2, 8));
        assertFalse(AudioRace.keepWaiting(AudioRace.Kind.AUDIO_HTTPS, false, 3_000, 8, 8));
        // with the language (or none asked) audio-only https still ends the race at once, as in 4.5
        assertFalse(AudioRace.keepWaiting(AudioRace.Kind.AUDIO_HTTPS, true, 1_000, 1, 8));
        assertFalse(AudioRace.keepWaiting(AudioRace.Kind.AUDIO_HTTPS, 1_000, 1, 8));
    }

    @Test public void rankPutsMissingLanguageLast() throws Exception {
        YtEngine.audioLang = "hi";
        AudioRace.Choice withHindi = AudioRace.choose(multi());
        AudioRace.Choice without = AudioRace.choose(single());
        assertTrue(withHindi.rank() < without.rank());
    }

    @Test public void directPlayerListKeepsEveryTrack() throws Exception {
        // InnerTube player JSON (the direct route): 4.5 dropped every non-default track here
        JSONArray af = new JSONArray();
        af.put(new JSONObject().put("itag", 137).put("url", "https://v/137").put("mimeType", "video/mp4; codecs=\"avc1.640028\"").put("bitrate", 4000000).put("width", 1920).put("height", 1080).put("fps", 30));
        af.put(new JSONObject().put("itag", 140).put("url", "https://a/en").put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"").put("bitrate", 130000)
                .put("audioTrack", new JSONObject().put("displayName", "English (US) original").put("id", "en-US.4").put("audioIsDefault", true)));
        af.put(new JSONObject().put("itag", 140).put("url", "https://a/hi").put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"").put("bitrate", 130000)
                .put("audioTrack", new JSONObject().put("displayName", "Hindi").put("id", "hi.3").put("audioIsDefault", false)));
        af.put(new JSONObject().put("itag", 140).put("url", "https://a/desc").put("mimeType", "audio/mp4; codecs=\"mp4a.40.2\"").put("bitrate", 130000)
                .put("audioTrack", new JSONObject().put("displayName", "English (US) descriptive").put("id", "en-US.5").put("audioIsDefault", false)));
        JSONObject pl = new JSONObject().put("playabilityStatus", new JSONObject().put("status", "OK"))
                .put("streamingData", new JSONObject().put("adaptiveFormats", af))
                .put("videoDetails", new JSONObject().put("title", "t").put("lengthSeconds", "600").put("videoId", "abc"));
        JSONObject info = AudioRace.fromPlayer(pl, "UA");
        List<AudioTracks.Track> l = AudioTracks.list(info);
        assertEquals(l.toString(), 2, l.size());
        assertEquals("English (US) (original)", l.get(0).label());
        assertEquals("Hindi", l.get(1).label());
        assertEquals("https://a/en", YtEngine.pickStreams(info, 1080)[1]);
        YtEngine.audioLang = "hi";
        assertEquals("https://a/hi", YtEngine.pickStreams(info, 1080)[1]);
        assertEquals("https://a/hi", YtEngine.pickAudio(info).optString("url"));
    }

    @Test public void ytdlpSelectors() {
        assertEquals("ba[ext=m4a]/ba[acodec^=mp4a]/ba", AudioTracks.ytdlpAudio(""));
        assertEquals("ba[language=hi][ext=m4a]/ba[language=hi]/ba[ext=m4a]/ba[acodec^=mp4a]/ba", AudioTracks.ytdlpAudio("hi"));
        assertEquals("ba[ext=m4a]/ba[acodec^=mp4a]/ba", AudioTracks.ytdlpAudio("hi];rm -rf"));   // never injected
        assertEquals("ba[abr>=32]/ba/wa/worst", YtEngine.speechAudioFormat(""));
        assertEquals("ba[language=pt-BR][abr>=32]/ba[language=pt-BR]/ba[abr>=32]/ba/wa/worst", YtEngine.speechAudioFormat("pt-BR"));
        assertEquals("lang,+abr", YtEngine.SPEECH_SORT);
        YtEngine.audioLang = "hi";
        assertTrue(YtEngine.audioFormat().startsWith("ba[language=hi]"));
    }

    @Test public void namesForCodes() {
        assertEquals("Hindi", AudioTracks.displayName("hi"));
        assertEquals("Spanish (Latin America)", AudioTracks.displayName("es-419"));
        assertEquals("English (United States)", AudioTracks.displayName("en-US"));
        assertEquals("", AudioTracks.nameFromNote("medium"));
        assertEquals("", AudioTracks.nameFromNote("low, DRC"));
        assertEquals("English (US)", AudioTracks.nameFromNote("English (US) original (default), medium (original)"));
    }

    @Test public void requestAndProjectKeepTheLanguage() throws Exception {
        Pipeline.Request rq = new Pipeline.Request();
        rq.ytUrl = "https://youtu.be/abcdefghijk"; rq.audioLang = "hi"; rq.audioLangName = "Hindi";
        Pipeline.Request back = Pipeline.Request.fromJson(rq.toJson());
        assertEquals("hi", back.audioLang);
        assertEquals("Hindi", back.audioLangName);
        Project p = new Project(); p.audioLang = "es-419"; p.audioLangName = "Spanish (Latin America)";
        Project q = Project.fromJson(p.toJson());
        assertEquals("es-419", q.audioLang);
        assertEquals("Spanish (Latin America)", q.audioLangName);
        assertEquals("", Project.fromJson(new JSONObject().put("title", "old project")).audioLang);   // old saved projects
    }

    // ------------------------------------------------------------------ removed "upload from phone" input

    static String read(String path) throws Exception {
        File f = new File(path);
        if (!f.exists()) f = new File("app/" + path);
        return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
    }

    @Test public void uploadOptionRemoved() throws Exception {
        String layout = read("src/main/res/layout/activity_main.xml");
        assertFalse(layout.contains("groupUpload"));
        assertFalse(layout.contains("btnSrcUpload"));
        assertFalse(layout.contains("toggleSource"));
        assertFalse(layout.contains("Choose a video from your phone"));
        assertTrue(layout.contains("@+id/etYtUrlHome"));          // the link input stays
        assertTrue(layout.contains("@+id/chipGroupAudioLang"));   // new audio language picker
        String main = read("src/main/java/com/viral/shortsmaker/MainActivity.java");
        assertFalse(main.contains("PickVisualMedia"));
        assertFalse(main.contains("ACTION_OPEN_DOCUMENT"));
        assertFalse(main.contains("onLocalPicked"));
        assertFalse(main.contains("upload a video"));
        assertTrue(main.contains("GallerySaver.save"));           // saving shorts to the gallery still works
        String mf = read("src/main/AndroidManifest.xml");
        assertFalse(mf.contains("READ_EXTERNAL_STORAGE"));
        assertFalse(mf.contains("READ_MEDIA_VIDEO"));
        assertTrue(mf.contains("WRITE_EXTERNAL_STORAGE"));        // gallery save on Android 9 and older
        assertTrue(mf.contains("FOREGROUND_SERVICE_DATA_SYNC"));
        assertTrue(mf.contains("POST_NOTIFICATIONS"));
        assertTrue(mf.contains("WAKE_LOCK"));
        try { com.viral.shortsmaker.R.id.class.getField("groupUpload"); fail("upload view id still generated"); } catch (NoSuchFieldException expected) {}
    }

    // ------------------------------------------------------------------ background service helpers

    @Test public void serviceNotificationAndWakeLock() {
        assertEquals("Creating shorts • 42%", ProcessingService.progressTitle(JobManager.Kind.ANALYZE, 42));
        assertEquals("Adding captions • 7%", ProcessingService.progressTitle(JobManager.Kind.CAPTION, 7));
        assertEquals("Creating shorts", ProcessingService.progressTitle(JobManager.Kind.ANALYZE, 0));
        assertEquals("Adding captions • 100%", ProcessingService.progressTitle(JobManager.Kind.CAPTION, 130));
        assertFalse(ProcessingService.wakeRenewDue(0, 10_000_000));
        assertFalse(ProcessingService.wakeRenewDue(1_000_000, 1_000_000 + 9 * 60_000));
        assertTrue(ProcessingService.wakeRenewDue(1_000_000, 1_000_000 + 10 * 60_000));
        assertTrue(ProcessingService.WAKE_HOLD_MS > ProcessingService.WAKE_RENEW_MS);
    }
}
