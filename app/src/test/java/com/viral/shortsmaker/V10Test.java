package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.view.MotionEvent;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionPreviewView;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.ClipSync;
import com.viral.shortsmaker.core.FfmpegCommands;
import com.viral.shortsmaker.core.Integrity;
import com.viral.shortsmaker.core.Pipeline;
import com.viral.shortsmaker.core.Prefs;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

/** v10: word timing refinement, real highlight duration, single-pass render, caption box gestures, extras, security */
@RunWith(RobolectricTestRunner.class)
public class V10Test {
    static Word w(String t, double s, double e) { return new Word(t, s, e); }

    /** env: speech (3.0) everywhere except the given silent ranges [from,to) in seconds */
    static float[] env(double len, double[]... silent) {
        float[] e = new float[(int) Math.round(len / ClipSync.HOP)];
        Arrays.fill(e, 3f);
        for (double[] s : silent) for (int i = (int) Math.round(s[0] / ClipSync.HOP); i < Math.min(e.length, (int) Math.round(s[1] / ClipSync.HOP)); i++) e[i] = 0.4f;
        return e;
    }

    @Test public void overlongWordIsAnchoredToItsEnd() {
        // his clip: Whisper gave "direction" 2.43-5.02 (an unrecognised bit of speech 2.5-3.1, silence 3.1-4.52, the word 4.52-5.02)
        float[] e = env(8, new double[]{2.08, 2.48}, new double[]{3.1, 4.52});
        List<Word> in = Arrays.asList(w("tak", 1.78, 2.08), w("डाइरेक्शन", 2.08, 5.02), w("का", 5.02, 5.22), w("सवाल", 5.22, 5.6));
        List<Word> out = ClipSync.refineWords(in, e);
        assertEquals(4.52, out.get(1).start, 0.05);     // v9 snapped to 2.48 (the first burst): 2.1 s early
        assertEquals(5.02, out.get(1).end, 1e-9);       // ends are kept
        assertEquals(5.02, out.get(2).start, 1e-9);
    }

    @Test public void normalWordsAreNotMoved() {
        float[] e = env(4, new double[]{1.0, 1.3});
        List<Word> in = Arrays.asList(w("ye", 0.2, 0.45), w("hai", 0.45, 0.9), w("short", 1.3, 1.8));
        List<Word> out = ClipSync.refineWords(in, e);
        assertEquals(0.2, out.get(0).start, 1e-9);
        assertEquals(0.45, out.get(1).start, 1e-9);
        assertEquals(1.3, out.get(2).start, 0.04);
    }

    @Test public void slowWordStaysLongButTrailingSilenceIsTrimmed() {
        // a slowly said word (0.9 s of sound) followed by a pause inside Whisper's span
        float[] e = env(4, new double[]{1.6, 2.4});
        List<Word> in = Arrays.asList(w("haan", 0.1, 0.3), w("bilkuuul", 0.7, 2.3), w("theek", 2.4, 2.8), w("hai", 2.8, 3.0));
        List<Word> out = ClipSync.refineWords(in, e);
        assertEquals(0.7, out.get(1).start, 0.05);
        assertTrue(out.get(1).end <= 1.7 && out.get(1).end >= 1.55);   // ends when the sound ends
        assertTrue(out.get(1).end - out.get(1).start > 0.8);            // still long: it was said slowly
    }

    @Test public void refineNeverMovesStartEarlierOrEndLater() {
        java.util.Random r = new java.util.Random(3);
        float[] e = new float[3000];
        for (int i = 0; i < e.length; i++) e[i] = (float) ((i / 23) % 4 == 0 ? 0.3 : 3 + r.nextDouble());
        List<Word> in = new ArrayList<>();
        double t = 0.1;
        for (int i = 0; i < 60; i++) { double d = 0.1 + r.nextDouble() * 0.8; in.add(w("word" + i, t, t + d)); t += d + (r.nextBoolean() ? 0 : 0.2); }
        List<Word> out = ClipSync.refineWords(in, e);
        assertEquals(in.size(), out.size());
        for (int i = 0; i < in.size(); i++) {
            assertTrue(out.get(i).start >= in.get(i).start - 1e-9);
            assertTrue(out.get(i).end <= in.get(i).end + 1e-9);
            assertTrue(out.get(i).end > out.get(i).start);
            assertEquals(in.get(i).text, out.get(i).text);
        }
    }

    @Test public void lagFindsPrimingDelay() {
        java.util.Random r = new java.util.Random(5);
        float[] a = new float[2000];
        for (int i = 0; i < a.length; i++) a[i] = (float) ((i / 31) % 3 == 0 ? 3 + r.nextDouble() : r.nextDouble() * 0.2);
        float[] b = new float[2000];
        for (int i = 0; i < b.length; i++) b[i] = i >= 2 ? a[i - 2] : 0;   // b is 20 ms later
        double[] l = ClipSync.lag(a, b, 0.5);
        assertEquals(0.02, l[0], 1e-9);
        assertTrue(l[1] > 0.95);
        assertEquals(0.0213, Pipeline.AAC_PRIMING_SEC, 0.0005);   // code 27: 1024 samples at 48 kHz
    }

    @Test public void singlePassSpeechUsesTheRenderSeek() {
        String[] a = FfmpegCommands.sourceSpeechAudio("https://x/a.m4a", 177.26, 37.82, "o.flac");
        String s = String.join(" ", a);
        assertTrue(s.indexOf("-ss 177.260") < s.indexOf("-i https://x/a.m4a"));   // same input seek as renderClip
        assertTrue(s.contains("first_pts=0"));
        assertTrue(s.contains("-reconnect"));
        String r = String.join(" ", FfmpegCommands.renderClip("https://x/v.mp4", "https://x/a.m4a", 177.26, 37.82, FfmpegCommands.Frame.BLUR, "c.ass", "f", "o.mp4", 720));
        assertTrue(r.contains("-ss 177.260 -t 37.820 -i https://x/a.m4a"));
        assertTrue(r.contains("ass="));
    }

    @Test public void highlightEndsWithTheWordAcrossAPause() {
        CaptionStyle st = CaptionStyles.byId("hormozi");
        st.keywords = false;
        List<Word> ws = Arrays.asList(w("one", 0.2, 0.5), w("two", 0.5, 0.8), w("three", 1.3, 1.6));
        String ass = CaptionBuilder.buildAss(ws, st, new CaptionLayout(), 3);
        // "two" ends at 0.80; the next word is 0.5 s later -> its highlight goes out at 0.92, not at 1.30
        assertTrue(ass, ass.contains("0:00:00.50,0:00:00.92"));
    }

    @Test public void keywordsGetAccentColour() {
        List<Word> ws = new ArrayList<>();
        String[] t = {"maine", "10", "lakh", "kamaye", "aur", "kabhi", "nahi", "ruka", "yaar", "sach", "mein"};
        double s = 0;
        for (String x : t) { ws.add(w(x, s, s + 0.3)); s += 0.3; }
        Set<Word> e = CaptionBuilder.emphasis(ws);
        assertTrue(e.contains(ws.get(1)));         // a number
        assertFalse(e.contains(ws.get(2)));        // too close to the last one (max 1 in 4)
        assertTrue(e.contains(ws.get(5)));
        assertTrue(e.size() <= 3);
        CaptionStyle st = CaptionStyles.byId("hormozi");
        String ass = CaptionBuilder.buildAss(ws, st, new CaptionLayout(), 4);
        String acc = "\\c&H" ;
        assertTrue(ass.contains("&H5AFF3D&") || ass.contains("&H00E5FF&"));
        st.keywords = false;
        String off = CaptionBuilder.buildAss(ws, st, new CaptionLayout(), 4);
        assertNotEquals(ass, off);
    }

    @Test public void progressBarAndNoHookTitle() throws Exception {
        CaptionStyle st = CaptionStyles.byId("hormozi");
        st.progressBar = true;
        String ass = CaptionBuilder.buildAss(Arrays.asList(w("hello", 0.2, 0.6), w("world", 0.6, 1.0)), st, new CaptionLayout(), 20);
        // v11: the 3 s hook title is gone (no style, no event), even for a style saved by v10 with hookTitle on
        assertFalse(ass.contains("Hook"));
        assertTrue(ass.contains("\\t(0,20000,\\clip(0,0,720,1280))"));
        org.json.JSONObject old = new org.json.JSONObject(st.toJson().toString()); old.put("hookTitle", true);
        CaptionStyle v10saved = CaptionStyle.fromJson(old);
        assertFalse(CaptionBuilder.buildAss(Arrays.asList(w("hello", 0.2, 0.6)), v10saved, new CaptionLayout(), 20).contains("Hook"));
        CaptionStyle back = CaptionStyle.fromJson(st.toJson());
        assertTrue(back.progressBar && back.keywords);
        assertFalse(back.toJson().has("hookTitle"));
        st.progressBar = false;
        String plain = CaptionBuilder.buildAss(Arrays.asList(w("hello", 0.2, 0.6)), st, new CaptionLayout(), 20);
        assertFalse(plain.contains(",Bar,"));
    }

    @Test public void snapCatchesAndReleasesWithHysteresis() {
        float[] g = {0.18f, 0.5f, 0.70f};
        assertEquals(0.5f, CaptionPreviewView.snap(0.51f, g, 0.02f, 0.035f, -1)[0], 1e-6);
        assertEquals(1, (int) CaptionPreviewView.snap(0.51f, g, 0.02f, 0.035f, -1)[1]);
        assertEquals(0.53f, CaptionPreviewView.snap(0.53f, g, 0.02f, 0.035f, -1)[0], 1e-6);    // too far to catch
        assertEquals(0.5f, CaptionPreviewView.snap(0.53f, g, 0.02f, 0.035f, 1)[0], 1e-6);      // but held once caught
        assertEquals(-1, (int) CaptionPreviewView.snap(0.54f, g, 0.02f, 0.035f, 1)[1]);         // released
    }

    @Test public void dragMovesBoxWithFingerWithoutJumpAndSnapsToCentre() {
        CaptionPreviewView v = new CaptionPreviewView(ApplicationProvider.getApplicationContext());
        v.setCaptionLayout(new CaptionLayout(0.42f, 0.72f, 0.6f, 0.15f));
        v.measure(View.MeasureSpec.makeMeasureSpec(720, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(1300, View.MeasureSpec.EXACTLY));
        v.layout(0, 0, 720, 1300);
        v.draw(new android.graphics.Canvas(android.graphics.Bitmap.createBitmap(720, 1300, android.graphics.Bitmap.Config.ARGB_8888)));
        long t = 0;
        // tap outside the box: v9 teleported the box there; v10 does not move it on a tap
        touch(v, MotionEvent.ACTION_DOWN, 100, 200, t); touch(v, MotionEvent.ACTION_UP, 100, 200, t += 50);
        assertEquals(0.42f, v.getCaptionLayout().cx, 1e-4);
        // drag right by ~ 0.075 of the frame width: lands near 0.495 -> snaps to the centre line
        float fw = 1300 * 9f / 16f - 16 * 9f / 16f;   // frame width inside the 8dp padding (density 1)
        float x0 = 360, y0 = 900;
        touch(v, MotionEvent.ACTION_DOWN, x0, y0, t += 300);
        for (int i = 1; i <= 10; i++) touch(v, MotionEvent.ACTION_MOVE, x0 + i * fw * 0.0075f, y0, t += 16);
        assertEquals(0.5f, v.getCaptionLayout().cx, 1e-4);
        touch(v, MotionEvent.ACTION_UP, x0 + fw * 0.075f, y0, t += 16);
        assertEquals(0.72f, v.getCaptionLayout().cy, 0.02f);
    }

    static void touch(View v, int action, float x, float y, long t) {
        MotionEvent e = MotionEvent.obtain(0, t, action, x, y, 0);
        v.onTouchEvent(e);
        e.recycle();
    }

    @Test public void keysAreStoredAndMigrated() {
        android.content.Context c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().putString("gemini_key", "AIzaOLDPLAINKEY").apply();
        assertEquals("AIzaOLDPLAINKEY", Prefs.geminiKey(c));      // v9 user keeps the key
        Prefs.setKeys(c, "AIzaNEW", "gsk_new");
        assertEquals("AIzaNEW", Prefs.geminiKey(c));
        assertEquals("gsk_new", Prefs.groqKey(c));
    }

    @Test public void debugBuildPassesIntegrity() {
        assertTrue(Integrity.ok(ApplicationProvider.getApplicationContext()));
    }
}
