package com.viral.shortsmaker;

import static org.junit.Assert.*;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.*;
import com.viral.shortsmaker.core.AiClientTestAccess;
import com.viral.shortsmaker.core.PipelineTestAccess;

import org.junit.Test;
import org.robolectric.annotation.Config;

import java.io.File;
import java.util.*;

/** v8: Sakib's v7 run — Hindi interview transcribed as English, clips opening on the tail of a sentence, wrong count. */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@Config(sdk = {26, 34})
public class V8Test {
    android.content.Context ctx() { return ApplicationProvider.getApplicationContext(); }

    // ---------------------------------------------------------------- language bug
    @Test public void hindiCaptionsAlwaysTranscribeHindi() {
        // v7: every part auto-detected "English"; with Hinglish / हिंदी captions the speech is Hindi, samples or not
        assertEquals("hi", Pipeline.decideSpeechLanguage(CaptionScript.HINGLISH, "Rakhi Sawant interview", "", Arrays.asList("english", "english", "english")));
        assertEquals("hi", Pipeline.decideSpeechLanguage(CaptionScript.HINDI, "Rakhi Sawant interview", "en", null));
    }

    @Test public void englishCaptionsStillTranscribeHindiSpeechAsHindi() {
        // English captions = a translation of the real Hindi words, never Whisper's own translation
        assertEquals("hi", Pipeline.decideSpeechLanguage(CaptionScript.ENGLISH, "Podcast", "", Arrays.asList("english", "hindi", "english")));
        assertEquals("hi", Pipeline.decideSpeechLanguage(CaptionScript.ENGLISH, "Podcast", "", Arrays.asList("urdu")));
        assertEquals("hi", Pipeline.decideSpeechLanguage(CaptionScript.ENGLISH, "Time travel | Hindi Podcast EP100", "", null));
        assertEquals("hi", Pipeline.decideSpeechLanguage(null, "समय यात्रा", "", null));
        assertEquals("hi", Pipeline.decideSpeechLanguage(null, "x", "hi", Arrays.asList("english")));
    }

    @Test public void englishVideoStaysEnglish() {
        assertEquals("en", Pipeline.decideSpeechLanguage(CaptionScript.ENGLISH, "How rockets work", "", Arrays.asList("english", "english", "english")));
        assertEquals("en", Pipeline.decideSpeechLanguage(null, "How rockets work", "", null));
        assertEquals("es", Pipeline.decideSpeechLanguage(null, "x", "", Arrays.asList("spanish", "spanish", "english")));
    }

    @Test public void transcriptCacheKeyCarriesLanguageAndMode() throws Exception {
        File d = new File(ctx().getFilesDir(), "tcache_test");
        d.mkdirs();
        File old = new File(d, "yt_abcdefghijk_auto.json");          // v7 name: English "transcript" of Hindi speech
        File keep = new File(d, Pipeline.cacheName("yt_abcdefghijk", "hi"));
        assertTrue(old.createNewFile());
        assertTrue(keep.createNewFile());
        assertEquals("yt_abcdefghijk_t8_hi.json", keep.getName());
        assertNotEquals(Pipeline.cacheName("yt_abcdefghijk", "hi"), Pipeline.cacheName("yt_abcdefghijk", "en"));
        assertEquals(1, Pipeline.dropLegacyCache(d));
        assertFalse(old.exists());
        assertTrue(keep.exists());
    }

    // ---------------------------------------------------------------- part stitching (missing words / duplicates)
    static List<Word> words(double from, double to, String prefix) {
        List<Word> l = new ArrayList<>();
        for (double t = from; t < to; t += 0.5) l.add(new Word(prefix + (int) Math.round(t * 2), t, t + 0.4));
        return l;
    }

    @Test public void stitchingKeepsWordsAPartSkippedAtItsStart() {
        // part 0: audio 0..306 (owns 0..300); part 1: audio 294..600 (owns 300..), but Whisper skipped its first 7 s
        Transcript p0 = new Transcript(), p1 = new Transcript();
        p0.words.addAll(words(0, 305.5, "w"));
        for (Word w : words(301, 600, "w")) p1.words.add(new Word(w.text, w.start - 294, w.end - 294));
        Transcript m = Transcript.merge(Arrays.asList(p0, p1), new double[]{0, 294}, new double[]{0, 300}, new double[]{300, Double.MAX_VALUE});
        // every half second from 0 to 600 exactly once, no gap, no duplicate
        assertEquals(1200, m.words.size());
        for (int i = 1; i < m.words.size(); i++) assertEquals(0.5, m.words.get(i).start - m.words.get(i - 1).start, 1e-6);
    }

    @Test public void stitchingDropsTheSameWordHeardTwiceAtTheCut() {
        Transcript p0 = new Transcript(), p1 = new Transcript();
        p0.words.add(new Word("hello", 299.0, 299.5)); p0.words.add(new Word("duniya", 299.9, 300.3));
        p1.words.add(new Word("hello", 299.0 - 294 + 0.02, 299.5 - 294)); p1.words.add(new Word("duniya", 300.05 - 294, 300.4 - 294));
        p1.words.add(new Word("kaise", 300.5 - 294, 300.9 - 294));
        Transcript m = Transcript.merge(Arrays.asList(p0, p1), new double[]{0, 294}, new double[]{0, 300}, new double[]{300, Double.MAX_VALUE});
        List<String> t = new ArrayList<>();
        for (Word w : m.words) t.add(w.text);
        assertEquals(Arrays.asList("hello", "duniya", "kaise"), t);
    }

    @Test public void clipWordsAreClipRelative() {
        Transcript tr = new Transcript();
        tr.words.add(new Word("a", 1819.2, 1819.5)); tr.words.add(new Word("b", 1820.0, 1820.4));
        List<Word> w = tr.wordsForClip(1819.0, 1865.0);
        assertEquals(0.2, w.get(0).start, 1e-6);
        assertEquals(1.0, w.get(1).start, 1e-6);
    }

    // ---------------------------------------------------------------- hook start
    static void sentence(List<Transcript.Segment> segs, List<Word> ws, double t0, String text) {
        String[] parts = text.split(" ");
        double t = t0;
        for (String p : parts) { ws.add(new Word(p, t, t + 0.35)); t += 0.4; }
        segs.add(new Transcript.Segment(t0, t, text));
    }

    @Test public void clipNeverStartsOnTheTailOfThePreviousSentence() {
        List<Transcript.Segment> segs = new ArrayList<>();
        List<Word> ws = new ArrayList<>();
        sentence(segs, ws, 0, "Rakhi what are you doing?");
        sentence(segs, ws, 2.2, "Have we become decent?");
        double t = 4.0;
        for (int i = 0; i < 12; i++) { sentence(segs, ws, t, "People say a lot of things about me every single day."); t += 4.6; }
        double clipStart = 1.6;   // the AI's start: on "doing?"
        assertTrue(ClipAligner.opensMidSentence(segs, ws, clipStart));
        double[] h = ClipAligner.hookSafe(segs, ws, new double[]{clipStart, 40}, 200);
        assertNotNull(h);
        assertEquals(2.2, h[0], 0.15);
        assertTrue(ClipAligner.openingLine(segs, ws, h[0], h[1]).startsWith("Have we become decent?"));
    }

    @Test public void hindiClipNeverStartsOnTheTailOfThePreviousSentence() {
        List<Transcript.Segment> segs = new ArrayList<>();
        List<Word> ws = new ArrayList<>();
        sentence(segs, ws, 0, "लोग क्या बोल रहे हैं उनका रिस्पॉन्स।");
        sentence(segs, ws, 2.6, "सब क्या कह रहे हैं?");
        double t = 4.8;
        for (int i = 0; i < 12; i++) { sentence(segs, ws, t, "मैं सच बोलती हूँ इसलिए लोग मुझे पसंद करते हैं।"); t += 4.0; }
        double clipStart = 1.95;   // on "रिस्पॉन्स।"
        double[] h = ClipAligner.hookSafe(segs, ws, new double[]{clipStart, 40}, 200);
        assertNotNull(h);
        assertEquals(2.6, h[0], 0.15);
    }

    @Test public void cleanStartIsKept() {
        List<Transcript.Segment> segs = new ArrayList<>();
        List<Word> ws = new ArrayList<>();
        double t = 0;
        for (int i = 0; i < 12; i++) { sentence(segs, ws, t, "If I leave now, I will destroy everyone here."); t += 4.0; }
        double[] h = ClipAligner.hookSafe(segs, ws, new double[]{8.0, 40}, 200);
        assertNotNull(h);
        assertEquals(8.0, h[0], 1e-6);
        assertEquals(0, (int) h[2]);
    }

    // ---------------------------------------------------------------- count, models, captions script
    @Test public void pickedCountIsExact() {
        for (int n : new int[]{1, 2, 3, 4, 5}) assertEquals(n, Pipeline.shortsFor(n, 38 * 60 + 6));
        assertEquals(5, Pipeline.shortsFor(8, 38 * 60 + 6));    // v9: never more than 5
        assertEquals(5, Pipeline.shortsFor(0, 38 * 60 + 6));   // Auto on his 38:06 video (6 -> v9 cap 5)
        assertEquals(5, Pipeline.shortsFor(40, 600));
    }

    @Test public void dailyQuotaResetIsReadFromGoogle() {
        String msg = "You exceeded your current quota ... limit: 20, model: gemini-3.8-flash, description: Generate content requests per day limit exceeded on Free Tier. Please retry in 20h50m39.351582041s.";
        long ms = AiClientTestAccess.reset(msg);
        assertEquals((20 * 3600 + 50 * 60 + 39) * 1000L + 60000, ms, 1000);
        assertEquals("gemini-3.8-flash", AiClientTestAccess.model(msg));
        assertEquals(6L * 3600 * 1000, AiClientTestAccess.reset("quota exceeded"));
    }

    @Test public void hinglishIsAlwaysRoman() {
        assertEquals("nahin", Translit.word("नहीं"));
        assertEquals("karta", Translit.word("करता"));
        assertEquals("bolne", Translit.word("बोलने"));
        assertEquals("samajh", Translit.word("समझ"));
        assertEquals("chahiye", Translit.word("चाहिए"));
        assertEquals("baat", Translit.word("बात"));
        assertEquals("paisa", Translit.word("पैसा"));
        assertEquals("zindgi", Translit.word("ज़िंदगी"));
        assertEquals("hoon", Translit.word("हूँ"));
        List<Word> w = Arrays.asList(new Word("मेरे", 0, 0.3), new Word("को", 0.3, 0.5), new Word("paisa", 0.5, 0.9), new Word("चाहिए।", 0.9, 1.3));
        List<Word> r = CaptionScript.romanize(w);
        assertEquals("mere", r.get(0).text); assertEquals("ko", r.get(1).text); assertEquals("paisa", r.get(2).text); assertEquals("chahiye.", r.get(3).text);
        for (int i = 0; i < w.size(); i++) { assertEquals(w.get(i).start, r.get(i).start, 0); assertEquals(w.get(i).end, r.get(i).end, 0); }
    }

    @Test public void hinglishCaptionsNeverKeepDevanagariWhenTheAiIsUnavailable() {
        // no network in tests: the AI transliteration fails, the phone transliterates
        Transcript tr = new Transcript();
        tr.language = "hi";
        List<Word> w = Arrays.asList(new Word("मैं", 0, 0.3), new Word("सच", 0.3, 0.6), new Word("बोलती", 0.6, 1.0), new Word("हूँ", 1.0, 1.3));
        List<String> warn = new ArrayList<>();
        List<List<Word>> out = CaptionScript.convertAll(ctx(), new CancelToken(), tr, Collections.singletonList(w), CaptionScript.HINGLISH, warn);
        StringBuilder sb = new StringBuilder();
        for (Word x : out.get(0)) sb.append(x.text).append(' ');
        assertEquals("main sach bolti hoon", sb.toString().trim());
        assertTrue(warn.isEmpty());
    }

    @Test public void hindiCaptionsOfHindiSpeechAreTheSpokenWords() {
        List<Word> w = Arrays.asList(new Word("मैं", 0, 0.3), new Word("सच", 0.3, 0.6));
        assertEquals("keep", CaptionScript.action(w, "hi", CaptionScript.HINDI));
        assertEquals("translit", CaptionScript.action(w, "hi", CaptionScript.HINGLISH));
        assertEquals("translate", CaptionScript.action(w, "hi", CaptionScript.ENGLISH));
    }

    @Test public void englishLettersForHindiSpeechAreDetected() {
        Transcript t = new Transcript();
        for (int i = 0; i < 30; i++) t.words.add(new Word("boldness", i, i + 0.5));
        assertTrue(PipelineTestAccess.mostlyLatin(t));
        Transcript h = new Transcript();
        for (int i = 0; i < 30; i++) h.words.add(new Word(i % 5 == 0 ? "screen" : "बोलने", i, i + 0.5));
        assertFalse(PipelineTestAccess.mostlyLatin(h));
    }
}
