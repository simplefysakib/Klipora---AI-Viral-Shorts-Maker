package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;

/** v21 (4.0): ads that open, 1080p source choice, encoder tiers, unlock clock, face tracking centring */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V21Test {

    static JSONObject res(String name) throws Exception {
        try (InputStream in = V21Test.class.getClassLoader().getResourceAsStream("v21/" + name)) {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[65536]; int n;
            while ((n = in.read(buf)) > 0) b.write(buf, 0, n);
            return new JSONObject(b.toString("UTF-8"));
        }
    }

    /** the real format list of a YouTube video (yt-dlp, Oct 31 2026), minus every format matching drop */
    static JSONObject without(JSONObject info, String... drop) throws Exception {
        JSONArray in = info.getJSONArray("formats"), out = new JSONArray();
        outer:
        for (int i = 0; i < in.length(); i++) {
            JSONObject f = in.getJSONObject(i);
            for (String d : drop) if (f.optString("format_id").equals(d)) continue outer;
            out.put(f);
        }
        return new JSONObject().put("formats", out);
    }

    // ------------------------------------------------------------------ quality: 1080p exact, else next lower

    @Test public void picks1080pExactlyNeverHigher() throws Exception {
        String[] s = YtEngine.pickStreams(res("formats_real.json"), 1080);
        assertNotNull(s);
        assertEquals("1920x1080 avc1 itag 137", s[3]);
        assertEquals("https://rr.example/140", s[1]);   // m4a audio
        assertEquals(1080, com.viral.shortsmaker.core.Pipeline.heightOf(s));
    }

    @Test public void without1080pH264TakesVp9At1080NotAv1Or720() throws Exception {
        String[] s = YtEngine.pickStreams(without(res("formats_real.json"), "137", "270"), 1080);
        assertEquals("1920x1080 vp9 itag 248", s[3]);
    }

    @Test public void no1080pAtAllTakesTheNextLower() throws Exception {
        String[] s = YtEngine.pickStreams(without(res("formats_real.json"), "137", "270", "614", "248", "399"), 1080);
        assertEquals("1280x720 avc1 itag 136", s[3]);
        s = YtEngine.pickStreams(without(res("formats_real.json"), "137", "270", "614", "248", "399", "136", "232", "609", "247", "398"), 1080);
        assertEquals("854x480 avc1 itag 135", s[3]);
    }

    @Test public void cap720Takes720() throws Exception {
        assertEquals("1280x720 avc1 itag 136", YtEngine.pickStreams(res("formats_real.json"), 720)[3]);
    }

    // ------------------------------------------------------------------ encoder tiers

    @Test public void encoderTiers() {
        String hw = String.join(" ", FfmpegCommands.encodeTail(false, FfmpegCommands.Encoder.HW, 1080, 0));
        assertTrue(hw, hw.contains("h264_mediacodec") && hw.contains("-profile:v high") && hw.contains("-bitrate_mode cbr") && hw.contains("-operating_rate 1000"));
        String basic = String.join(" ", FfmpegCommands.encodeTail(false, FfmpegCommands.Encoder.HW_BASIC, 1080, 0));
        assertTrue(basic.contains("h264_mediacodec"));
        assertFalse("3.1 settings exactly", basic.contains("-profile:v") || basic.contains("bitrate_mode") || basic.contains("operating_rate"));
        String sw = String.join(" ", FfmpegCommands.encodeTail(false, FfmpegCommands.Encoder.X264, 1080, 0));
        assertTrue(sw.contains("libx264") && sw.contains("-preset superfast") && sw.contains("-crf 14"));   // v23: crf 21 -> 19; code 27: 14
        assertFalse(sw.contains("fastdecode"));
        assertTrue(hw, hw.contains("-b:v 18M"));   // v23: 10M -> 12M; code 27: 18M
    }

    // ------------------------------------------------------------------ ad links

    @Test public void adLinksAreRecognisedAndExplicitScreensAreNot() {
        assertTrue(LinkOpener.isAdLink(new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.game"))));
        assertTrue(LinkOpener.isAdLink(new Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.game"))));
        Intent explicit = new Intent(Intent.ACTION_VIEW, Uri.parse("https://x.y")).setClassName("com.viral.shortsmaker", "com.unity3d.ads.adplayer.FullScreenWebViewDisplay");
        assertFalse(LinkOpener.isAdLink(explicit));
        assertFalse(LinkOpener.isAdLink(new Intent(Intent.ACTION_SEND)));
        assertEquals("https://play.google.com/store/apps/details?id=com.game&referrer=abc", LinkOpener.playWebUrl("market://details?id=com.game&referrer=abc"));
        assertNull(LinkOpener.playWebUrl("https://x.y"));
    }

    @Test public void storeLinkFallsBackToPlayWebThenBrowser() {
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.game")).setPackage("com.android.vending");
        List<Intent> f = LinkOpener.fallbacks(i);
        assertEquals(3, f.size());
        assertNull(f.get(0).getPackage());
        assertEquals("market://details?id=com.game", f.get(0).getData().toString());
        assertEquals("com.android.vending", f.get(1).getPackage());
        assertEquals("https://play.google.com/store/apps/details?id=com.game", f.get(1).getData().toString());
        assertTrue(f.get(2).hasCategory(Intent.CATEGORY_BROWSABLE));
        for (Intent x : f) assertTrue((x.getFlags() & Intent.FLAG_ACTIVITY_NEW_TASK) != 0);
    }

    @Test public void openTriesFrontScreenThenFallbacksAndRethrowsFirstError() {
        android.content.Context app = androidx.test.core.app.ApplicationProvider.getApplicationContext();
        Intent i = new Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.game")).setPackage("com.android.vending");
        List<Intent> tried = new ArrayList<>();
        // the phone refuses the first two launches, the third (no forced package) opens
        LinkOpener.open(app, null, i, (from, x) -> { tried.add(x); if (tried.size() < 2) throw new android.content.ActivityNotFoundException("no"); });
        assertEquals(2, tried.size());
        assertNull(tried.get(1).getPackage());
        // nothing opens: the first error goes back to the ad SDK
        tried.clear();
        RuntimeException first = new android.content.ActivityNotFoundException("first");
        try {
            LinkOpener.open(app, null, i, (from, x) -> { tried.add(x); throw tried.size() == 1 ? first : new SecurityException("later"); });
            fail();
        } catch (RuntimeException e) { assertSame(first, e); }
        assertEquals(4, tried.size());
    }

    @Test public void adRetryBacksOff() {
        assertEquals(5_000, AdRetry.delayMs(1));
        assertEquals(10_000, AdRetry.delayMs(2));
        assertEquals(20_000, AdRetry.delayMs(3));
        assertEquals(40_000, AdRetry.delayMs(4));
        assertEquals(60_000, AdRetry.delayMs(5));
        assertEquals(60_000, AdRetry.delayMs(50));
    }

    // ------------------------------------------------------------------ unlock clock (premium audit)

    static final long H = 3_600_000L, DAY = 24 * H;

    @Test public void clockSetBackDoesNotStretchAnUnlock() {
        SharedPreferences p = new MemPrefs();
        long t0 = 1_800_000_000_000L;
        long granted = Prefs.nowForTest(p, t0);                       // PRO granted now
        assertEquals(DAY, Prefs.timedLeftForTest(Prefs.nowForTest(p, t0), granted, DAY));
        long later = t0 + 20 * H;
        assertEquals(4 * H, Prefs.timedLeftForTest(Prefs.nowForTest(p, later), granted, DAY));
        // 3.1: clock set back 10 h -> 14 h left again. Now: still 4 h
        assertEquals(4 * H, Prefs.timedLeftForTest(Prefs.nowForTest(p, later - 10 * H), granted, DAY));
        // and it keeps running out
        assertEquals(0, Prefs.timedLeftForTest(Prefs.nowForTest(p, later + 5 * H), granted, DAY));
    }

    @Test public void aClockFixedFromFarInTheFutureDoesNotLockForDays() {
        SharedPreferences p = new MemPrefs();
        long real = 1_800_000_000_000L;
        Prefs.nowForTest(p, real + 30 * DAY);                       // the phone had a wrong date
        long now = Prefs.nowForTest(p, real);                       // fixed
        assertEquals(real, now);
        long granted = Prefs.nowForTest(p, real);                   // an ad watched now gets its full hour
        assertEquals(H, Prefs.timedLeftForTest(Prefs.nowForTest(p, real), granted, H));
    }

    // ------------------------------------------------------------------ face tracking: speaker kept centred

    @Test public void aSpeakerSittingOffCentreIsRecentred() {
        // one talking face that settles 0.045 of the frame width (~14% of a 9:16 window) right of where the camera was
        Random r = new Random(3);
        List<FaceTrack.Sample> s = new ArrayList<>();
        int n = 8 * 10;
        for (int i = 0; i < n; i++) {
            double t = i / 8.0, cx = t < 3 ? 0.40 : 0.445, h = 0.35, w = h * 9 / 16.0;
            double m = 0.05 + 0.05 * Math.abs(Math.sin(i * 1.7));
            List<FaceTrack.Face> fs = new ArrayList<>();
            fs.add(new FaceTrack.Face((float) (cx - w / 2), (float) (0.4 - h / 2), (float) (cx + w / 2), (float) (0.4 + h / 2), (float) m));
            s.add(new FaceTrack.Sample(fs, 0.1f, 2f + r.nextFloat()));
        }
        FaceTrack.Plan p = FaceTrack.plan(s, 8, 1920, 1080, 10, 30);
        assertEquals(0.40, p.cx[60], 0.01);
        assertEquals("recentred on the speaker", 0.445, p.cx[9 * 30], 0.012);   // 3.1 (dead zone 0.16) left it at 0.40
    }

    // ------------------------------------------------------------------
    static final class MemPrefs implements SharedPreferences {
        final Map<String, Object> m = new HashMap<>();
        public Map<String, ?> getAll() { return m; }
        public String getString(String k, String d) { Object v = m.get(k); return v == null ? d : (String) v; }
        public Set<String> getStringSet(String k, Set<String> d) { return d; }
        public int getInt(String k, int d) { Object v = m.get(k); return v == null ? d : (Integer) v; }
        public long getLong(String k, long d) { Object v = m.get(k); return v == null ? d : (Long) v; }
        public float getFloat(String k, float d) { Object v = m.get(k); return v == null ? d : (Float) v; }
        public boolean getBoolean(String k, boolean d) { Object v = m.get(k); return v == null ? d : (Boolean) v; }
        public boolean contains(String k) { return m.containsKey(k); }
        public void registerOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        public void unregisterOnSharedPreferenceChangeListener(OnSharedPreferenceChangeListener l) {}
        public Editor edit() {
            return new Editor() {
                final Map<String, Object> e = new HashMap<>();
                public Editor putString(String k, String v) { e.put(k, v); return this; }
                public Editor putStringSet(String k, Set<String> v) { return this; }
                public Editor putInt(String k, int v) { e.put(k, v); return this; }
                public Editor putLong(String k, long v) { e.put(k, v); return this; }
                public Editor putFloat(String k, float v) { e.put(k, v); return this; }
                public Editor putBoolean(String k, boolean v) { e.put(k, v); return this; }
                public Editor remove(String k) { e.put(k, null); return this; }
                public Editor clear() { m.clear(); return this; }
                public boolean commit() { apply(); return true; }
                public void apply() { for (Map.Entry<String, Object> x : e.entrySet()) { if (x.getValue() == null) m.remove(x.getKey()); else m.put(x.getKey(), x.getValue()); } }
            };
        }
    }
}
