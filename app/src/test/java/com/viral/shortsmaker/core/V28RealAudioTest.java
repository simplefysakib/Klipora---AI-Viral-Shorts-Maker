package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * code 28: REAL data. Player responses captured from youtube.com on 2026-11-02 (MrBeast "Lose 150 Lbs Together", 21
 * audio languages, English original): the visionOS player JSON the app's direct route reads, and the same response
 * run through yt-dlp 2026.08's own format code (stream URLs replaced by placeholders).
 */
@RunWith(RobolectricTestRunner.class)
public class V28RealAudioTest {

    @After public void reset() { YtEngine.audioLang = ""; }

    static JSONObject res(String name) throws Exception {
        try (InputStream in = V28RealAudioTest.class.getClassLoader().getResourceAsStream("k28/" + name)) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static void checkTracks(List<AudioTracks.Track> l) {
        assertEquals(l.toString(), 21, l.size());
        assertEquals("en", l.get(0).code);
        assertEquals("English (original)", l.get(0).label());
        boolean hindi = false;
        for (AudioTracks.Track t : l) {
            for (char c : t.label().toCharArray()) assertTrue("English-only UI: " + t, c < 0x7f);
            if (t.code.equals("hi")) { hindi = true; assertEquals("Hindi", t.label()); }
        }
        assertTrue(hindi);
        // English names only (yt-dlp's HLS copies are listed first with native names: "Deutsch - dubbed", "polski - dubbed")
        java.util.Map<String, String> want = new java.util.HashMap<>();
        want.put("de", "German"); want.put("it", "Italian"); want.put("pl", "Polish"); want.put("id", "Indonesian");
        want.put("es", "Spanish"); want.put("pt", "Portuguese"); want.put("ja", "Japanese"); want.put("ta", "Tamil");
        for (AudioTracks.Track t : l) if (want.containsKey(t.code)) assertEquals(t.toString(), want.get(t.code), t.label());
    }

    @Test public void dubSpeechAudioIsNot22kHz() throws Exception {
        YtEngine.audioLang = "hi";
        JSONObject a = YtEngine.pickAudio(res("RE3_PGwDwhw_ytdlp.json"));
        assertTrue(a.toString(), a.optInt("asr") >= 44100);
        JSONObject b = YtEngine.pickAudio(AudioRace.fromPlayer(res("RE3_PGwDwhw_visionos.json"), "UA"));
        assertTrue(b.toString(), b.optInt("asr") >= 44100);
        assertTrue(b.optString("url").endsWith("/hi.3"));
    }

    @Test public void ytdlpListOfARealVideo() throws Exception {
        JSONObject info = res("RE3_PGwDwhw_ytdlp.json");
        checkTracks(AudioTracks.list(info));
        // nothing chosen: the original English audio (4.5 could take a dub here)
        assertEquals("en", YtEngine.pickAudio(info).optString("language"));
        String[] s = YtEngine.pickStreams(info, 1080);
        assertEquals("https://media.test/140-20", s[1]);
        // Hindi chosen: Hindi for the transcript and for the render
        YtEngine.audioLang = "hi";
        JSONObject sp = YtEngine.pickAudio(info);
        assertEquals("hi", sp.optString("language"));
        assertTrue(sp.optString("protocol"), sp.optString("protocol").startsWith("http"));
        assertEquals("https://media.test/140-4", YtEngine.pickStreams(info, 1080)[1]);
        AudioRace.Choice c = AudioRace.choose(info);
        assertTrue(c.langOk);
        assertEquals("hi", c.format.optString("language"));
        // a language the video does not have: original + notice
        YtEngine.audioLang = "sw";
        assertEquals("https://media.test/140-20", YtEngine.pickStreams(info, 1080)[1]);
        assertFalse(AudioRace.choose(info).langOk);
    }

    @Test public void directPlayerResponseOfARealVideo() throws Exception {
        JSONObject info = AudioRace.fromPlayer(res("RE3_PGwDwhw_visionos.json"), "UA");
        checkTracks(AudioTracks.list(info));
        assertEquals("https://media.test/140/en.4", YtEngine.pickStreams(info, 1080)[1]);
        YtEngine.audioLang = "hi";
        assertEquals("https://media.test/140/hi.3", YtEngine.pickStreams(info, 1080)[1]);
        assertTrue(YtEngine.pickAudio(info).optString("url").endsWith("/hi.3"));
        YtEngine.audioLang = "ta";
        assertEquals("https://media.test/140/ta.3", YtEngine.pickStreams(info, 1080)[1]);
        AudioRace.Choice c = AudioRace.choose(info);
        assertTrue(c.langOk);
    }
}
