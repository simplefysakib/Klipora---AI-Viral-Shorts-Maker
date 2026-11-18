package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * code 29: the audio-language feature re-tested on the three real MrBeast format lists captured Nov 2 (21-23 languages):
 * every language is offered once, original first, Hindi second; picking Hindi or Tamil gives that dub for the speech and
 * the render with the same video stream; a language a video lacks falls back to the original; a one-language video and
 * "original" behave as before.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V29AudioRetestTest {
    static final String[] IDS = {"RE3_PGwDwhw", "_c2e1NcI3Sc", "plN7JMbadRg"};

    static JSONObject info(String id) throws Exception {
        try (InputStream in = V29AudioRetestTest.class.getClassLoader().getResourceAsStream("k29/" + id + "_ytdlp.json")) {
            return new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
    }

    static String langOfUrl(JSONObject info, String url) throws Exception {
        JSONArray f = info.getJSONArray("formats");
        for (int i = 0; i < f.length(); i++) if (url.equals(f.getJSONObject(i).optString("url"))) return f.getJSONObject(i).optString("language", "");
        return null;
    }

    @After public void reset() { YtEngine.audioLang = ""; }

    @Test public void everyRealVideo() throws Exception {
        for (String id : IDS) {
            JSONObject info = info(id);
            List<AudioTracks.Track> l = AudioTracks.list(info);
            assertTrue(id + " " + l.size(), l.size() >= 21);
            assertTrue(l.get(0).original);
            assertEquals("en", l.get(0).code);
            assertEquals("Hindi", l.get(1).name);
            java.util.Set<String> codes = new java.util.HashSet<>();
            for (AudioTracks.Track t : l) assertTrue("listed once: " + t.code, codes.add(t.code.toLowerCase(java.util.Locale.US)));
            assertTrue(codes.contains("ta"));

            // original (nothing picked): the original audio, the best video
            YtEngine.audioLang = "";
            String[] o = YtEngine.pickStreams(info, 1080);
            assertNotNull(o);
            assertEquals("en", langOfUrl(info, o[1]));
            String video = o[0];

            for (String lang : new String[]{"hi", "ta", "es"}) {
                YtEngine.audioLang = lang;
                String[] s = YtEngine.pickStreams(info, 1080);
                assertEquals(id + " " + lang, lang, langOfUrl(info, s[1]));
                assertEquals("same video stream with a dub", video, s[0]);
                assertEquals(lang, YtEngine.pickAudio(info).optString("language"));   // the speech (captions) track
                AudioRace.Choice c = AudioRace.choose(info);
                assertTrue(c.langOk);
                assertEquals(lang, c.format.optString("language"));
            }
            // a language this video does not have: the original, and the user is told (langOk false)
            YtEngine.audioLang = "sw";
            assertEquals("en", langOfUrl(info, YtEngine.pickStreams(info, 1080)[1]));
            assertFalse(AudioRace.choose(info).langOk);
        }
    }
}
