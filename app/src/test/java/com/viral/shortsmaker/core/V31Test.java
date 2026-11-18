package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.BackgroundFix;
import com.viral.shortsmaker.PromptManager;
import com.viral.shortsmaker.captions.Word;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * code 31 (4.9): his HONOR ELP-NX9 / Android 17 report (4.8): SIGSEGV 1 s into rendering a 1080p avc1 clip with the
 * hardware decoder (h264_mediacodec) + c2.qti.avc.encoder, on two runs in a row; and a Bengali song transcribed as English
 * ("heard [en, en, en]"). Pins: which commands run in the separate video process, the fallback ladder, the per-phone crash
 * memory (and its reset on a new version), the next-start step-down after an in-process native crash, the encoder /
 * decoder choice following it, the speech language of his video, songs, Bengali captions, the notification fix step.
 */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V31Test {
    Context ctx;

    static final String TITLE = "ভুল করে আমি ভুল মানুষের সঙ্গে 💔 | জীবনটারে সাজাইতে চাইলাম | নদী সরকার | Bangla New Sad Song 2026";

    @Before public void setUp() {
        ctx = ApplicationProvider.getApplicationContext();
        Prefs.sp(ctx).edit().clear().commit();
        RenderSafety.resetForTest();
    }

    @After public void tearDown() {
        Prefs.sp(ctx).edit().clear().commit();
        ctx.getSharedPreferences("face_gpu", Context.MODE_PRIVATE).edit().clear().commit();
        RenderSafety.resetForTest();
        PromptManager.songJob = false;
    }

    // ------------------------------------------------------------------ crash-proof rendering

    @Test public void videoCommandsRunInTheSeparateProcessAudioDoesNot() {
        String[] hwDec = FfmpegCommands.burnCaptions("/r.mp4", "/c.ass", "/f", "/o.mp4", FfmpegCommands.Encoder.X264, 720, 2, "h264_mediacodec");
        String[] sw = FfmpegCommands.burnCaptions("/r.mp4", "/c.ass", "/f", "/o.mp4", FfmpegCommands.Encoder.X264, 720, 2, null);
        assertEquals(RenderSafety.K_HW_DECODE, RenderSafety.kindOf(hwDec));
        assertEquals(RenderSafety.K_SOFTWARE, RenderSafety.kindOf(sw));
        assertEquals(RenderSafety.K_HW_ENCODE, RenderSafety.kindOf(new String[]{"-y", "-i", "/a.mp4", "-vf", "scale=720:1280", "-c:v", "h264_mediacodec", "/o.mp4"}));
        assertEquals("his render: decoder AND encoder in hardware = the decoder is blamed first", RenderSafety.K_HW_DECODE,
                RenderSafety.kindOf(new String[]{"-c:v", "h264_mediacodec", "-ss", "1", "-i", "/s.mp4", "-c:v", "h264_mediacodec", "-codec_name", "c2.qti.avc.encoder", "/o.mp4"}));
        String[] audio = FfmpegCommands.cutAudioOpus("/a.webm", 10, 12, "/o.ogg", 1.0);
        assertEquals(RenderSafety.K_NONE, RenderSafety.kindOf(audio));
        assertFalse(RenderSafety.isolate(audio));
        assertFalse(RenderSafety.isolate(new String[]{"-y", "-i", "/m.mp4", "-map", "0:a:0", "-vn", "-c:a", "copy", "/a.m4a"}));
        assertTrue(RenderSafety.isolate(hwDec));
    }

    @Test public void fallbackLadderNeverGoesBackUp() {
        assertEquals(RenderSafety.SW_DECODE, RenderSafety.levelAfterCrash(RenderSafety.FULL, RenderSafety.K_HW_DECODE));
        assertEquals(RenderSafety.SW_ALL, RenderSafety.levelAfterCrash(RenderSafety.SW_DECODE, RenderSafety.K_HW_ENCODE));
        assertEquals(RenderSafety.SW_ALL, RenderSafety.levelAfterCrash(RenderSafety.FULL, RenderSafety.K_HW_ENCODE));
        assertEquals(RenderSafety.SW_ALL, RenderSafety.levelAfterCrash(RenderSafety.SW_ALL, RenderSafety.K_HW_DECODE));
        assertEquals("a software crash can't go lower", RenderSafety.SW_ALL, RenderSafety.levelAfterCrash(RenderSafety.SW_ALL, RenderSafety.K_SOFTWARE));
        assertEquals(RenderSafety.FULL, RenderSafety.levelAfterCrash(RenderSafety.FULL, RenderSafety.K_SOFTWARE));
        assertTrue(RenderSafety.isCrashReason("CRASH_NATIVE, signal 11"));
        assertTrue(RenderSafety.isCrashReason(""));
        assertFalse(RenderSafety.isCrashReason("LOW_MEMORY"));
        assertFalse(RenderSafety.isCrashReason("USER_REQUESTED"));
    }

    @Test public void crashMemoryPerPhoneResetsOnNewVersion() {
        assertEquals(RenderSafety.FULL, RenderSafety.level(ctx));
        assertEquals(RenderSafety.SW_DECODE, RenderSafety.noteCrash(ctx, RenderSafety.K_HW_DECODE, "CRASH_NATIVE"));
        RenderSafety.resetForTest();   // next app start
        assertEquals("kept across starts", RenderSafety.SW_DECODE, RenderSafety.level(ctx));
        assertFalse(RenderSafety.hwDecodeAllowed(ctx));
        assertTrue(RenderSafety.hwEncodeAllowed(ctx));
        assertTrue(RenderSafety.reportLine(ctx).contains("software decoding + hardware encoder"));
        assertEquals(RenderSafety.SW_ALL, RenderSafety.noteCrash(ctx, RenderSafety.K_HW_ENCODE, "CRASH_NATIVE"));
        assertFalse(RenderSafety.hwEncodeAllowed(ctx));
        assertEquals(2, RenderSafety.crashes(ctx));
        // a new app version starts on the full path again
        assertEquals(RenderSafety.FULL, RenderSafety.effective(RenderSafety.SW_ALL, 30, 31));
        Prefs.sp(ctx).edit().putInt(RenderSafety.K_BUILD, 30).commit();
        RenderSafety.resetForTest();
        assertEquals(RenderSafety.FULL, RenderSafety.level(ctx));
        assertEquals(0, RenderSafety.crashes(ctx));
    }

    @Test public void encoderAndDecoderFollowTheSafePath() {
        Pipeline p = new Pipeline(ctx);
        FfmpegCommands.planSource = (w, h) -> HwEncode.choose(V26Test.qualcomm(), w, h);
        assertEquals("phones that work keep the v4.5 ladder's first choice", FfmpegCommands.Encoder.HW, p.hwEncoder(null, 720));
        RenderSafety.noteCrash(ctx, RenderSafety.K_HW_DECODE, "test");
        assertEquals("software decoding keeps the hardware encoder + quality settings", FfmpegCommands.Encoder.HW, p.hwEncoder(null, 720));
        assertEquals(0, HwDecode.maxHeight("vp9"));
        RenderSafety.noteCrash(ctx, RenderSafety.K_HW_ENCODE, "test");
        assertEquals(FfmpegCommands.Encoder.X264, p.hwEncoder(null, 720));
    }

    @Test public void nativeCrashOfTheAppDuringARenderStepsDownAtNextStart() {
        RenderSafety.inflight(ctx, RenderSafety.K_HW_DECODE);
        RenderSafety.faceInflight(ctx, true);
        // (the app died here) next start, Android said CRASH_NATIVE
        RenderSafety.resetForTest();
        String done = RenderSafety.afterAppCrash(ctx, true);
        assertTrue(done, done.contains("software decoding"));
        assertTrue(done.contains("face tracking runs on the CPU"));
        assertEquals(RenderSafety.SW_DECODE, RenderSafety.level(ctx));
        assertTrue(ctx.getSharedPreferences("face_gpu", Context.MODE_PRIVATE).getBoolean("bad", false));
        assertEquals("markers consumed", "", RenderSafety.afterAppCrash(ctx, true));
        // a swipe / low-memory end with a command in flight changes nothing
        RenderSafety.inflight(ctx, RenderSafety.K_HW_ENCODE);
        assertEquals("", RenderSafety.afterAppCrash(ctx, false));
        assertEquals(RenderSafety.SW_DECODE, RenderSafety.level(ctx));
        // a finished command leaves nothing behind
        RenderSafety.inflight(ctx, RenderSafety.K_HW_ENCODE);
        RenderSafety.inflightDone(ctx);
        assertEquals("", RenderSafety.afterAppCrash(ctx, true));
    }

    @Test public void tombstoneHintNamesTheLibrary() {
        byte[] b = ("\u0001\u0012\u0008signal 11\u0000/apex/com.android.art/lib64/libart.so\u0000\u0007/data/app/x/lib/arm64/libavcodec.so\u0000"
                + "\u0003ff_mediacodec_sw_buffer_copy_yuv420_semi_planar\u0000/vendor/lib64/libcodec2_qti.so\u0002").getBytes(java.nio.charset.StandardCharsets.ISO_8859_1);
        String h = RenderSafety.tombstoneHint(b, b.length);
        assertTrue(h, h.contains("libavcodec.so"));
        assertTrue(h, h.contains("ff_mediacodec_sw_buffer_copy_yuv420_semi_planar"));
        assertTrue(h, h.contains("libcodec2_qti.so"));
        assertFalse(h, h.contains("libart"));
    }

    @Test public void separateProcessIsOffInTestsAndEngineCrashSaysWhat() {
        assertTrue(MediaRunner.remoteOff);
        MediaRunner.EngineCrash e = new MediaRunner.EngineCrash(RenderSafety.K_HW_DECODE, true, "CRASH_NATIVE, signal 11", "libavcodec.so", RenderSafety.SW_DECODE);
        assertTrue(e.getMessage(), e.getMessage().contains("crashed") && e.getMessage().contains("hardware decoder"));
        assertFalse(new MediaRunner.EngineCrash(RenderSafety.K_HW_ENCODE, false, "LOW_MEMORY", "", 0).getMessage().contains("crashed"));
    }

    /** the safe-path render lines, written for the box's own FFmpeg run (v31_cmds.txt next to the screenshots dir) */
    @Test public void safePathRenderCommands() throws Exception {
        RenderSafety.noteCrash(ctx, RenderSafety.K_HW_ENCODE, "test");   // level SW_ALL
        Pipeline p = new Pipeline(ctx);
        FfmpegCommands.planSource = (w, h) -> HwEncode.choose(V26Test.qualcomm(), w, h);
        FfmpegCommands.Encoder enc = p.hwEncoder(null, 720);
        assertEquals(FfmpegCommands.Encoder.X264, enc);
        StringBuilder sb = new StringBuilder();
        for (FfmpegCommands.Frame fr : new FfmpegCommands.Frame[]{FfmpegCommands.Frame.FILL, FfmpegCommands.Frame.BLUR}) {
            String[] c = FfmpegCommands.renderClip("IN", null, 60.0, 20.0, fr, null, null, "OUT_" + fr + ".mp4", 720, enc, 0, null, false, null, 1080);
            assertEquals(RenderSafety.K_SOFTWARE, RenderSafety.kindOf(c));
            sb.append(String.join("\u0001", c)).append('\n');
        }
        // level SW_DECODE: no decoder argument, hardware encoder kept (for the record)
        String[] mid = FfmpegCommands.renderClip("IN", null, 60.0, 20.0, FfmpegCommands.Frame.FILL, null, null, "OUT_hw.mp4", 720, FfmpegCommands.Encoder.HW, 0, null, false, null, 1080);
        assertEquals(RenderSafety.K_HW_ENCODE, RenderSafety.kindOf(mid));
        java.io.File dir = new java.io.File(System.getProperty("shotDir", "/tmp")).getParentFile();
        dir.mkdirs();
        java.nio.file.Files.write(new java.io.File(dir, "v31_cmds.txt").toPath(), sb.toString().getBytes("UTF-8"));
    }

    // ------------------------------------------------------------------ speech language

    @Test public void hisBengaliSongIsBengaliNotEnglish() {
        List<String> heard = Arrays.asList("english", "english", "english");
        assertEquals("bn", Pipeline.decideSpeechLanguage(null, TITLE, "", "", "", heard));
        assertEquals("bn", Pipeline.decideSpeechLanguage(CaptionScript.ENGLISH, TITLE, "", "Music", null, heard));
        assertEquals("Hinglish captions of a Bengali song: still transcribed in Bengali", "bn",
                Pipeline.decideSpeechLanguage(CaptionScript.HINGLISH, TITLE, "", "", "", null));
        assertEquals("bn", Pipeline.scriptLanguage(TITLE));
        assertFalse("the samples are not needed", Pipeline.strongHint(TITLE, "", "").isEmpty());
        assertTrue(Pipeline.looksLikeMusic(TITLE, ""));
    }

    @Test public void otherLanguageHints() {
        List<String> en = Arrays.asList("en", "en", "en");
        // Latin title naming the language, song: Whisper's "English" on music loses
        assertEquals("bn", Pipeline.decideSpeechLanguage(null, "Bangla New Sad Song 2026", "", "", "", en));
        // a talk that names a language but is heard as English stays English
        assertEquals("en", Pipeline.decideSpeechLanguage(null, "Learn Tamil in 10 minutes", "", "Education", "", en));
        // YouTube's own audio language
        assertEquals("ta", Pipeline.decideSpeechLanguage(null, "Vlog day 3", "", "", "ta", en));
        // description in Bengali for a Latin title
        assertEquals("bn", Pipeline.decideSpeechLanguage(null, "Nodi Sarkar - Bhul Kore", "নদী সরকারের নতুন গান", "", "", en));
        assertEquals("te", Pipeline.decideSpeechLanguage(null, "ప్రేమ పాట", "", "", "", null));
        assertEquals("ko", Pipeline.decideSpeechLanguage(null, "사랑해요 노래", "", "", "", en));
        // unchanged v8 rules
        assertEquals("hi", Pipeline.decideSpeechLanguage(null, "दिल की बातें", "", "", "", en));
        assertEquals("hi", Pipeline.decideSpeechLanguage(CaptionScript.HINGLISH, "Podcast ep 4", "", "", "", null));
        assertEquals("en", Pipeline.decideSpeechLanguage(null, "My podcast", "", "", "", en));
        assertEquals("es", Pipeline.decideSpeechLanguage(null, "Mi vida", "", "", "", Arrays.asList("es", "es", "en")));
        assertEquals("en", Pipeline.decideSpeechLanguage(null, "x", "", "", "", null));
        assertEquals("emoji and a few letters are not a script", "", Pipeline.scriptLanguage("💔 Ω"));
    }

    @Test public void songsGetTheSongRules() {
        assertTrue(Pipeline.looksLikeMusic("Tum Hi Ho | Official Video", ""));
        assertTrue(Pipeline.looksLikeMusic("Day 4", "['Music'] ['bangla gaan']"));
        assertFalse(Pipeline.looksLikeMusic("How I built my startup", "['Education']"));
        PromptManager.songJob = true;
        String p = PromptManager.buildViralPrompt(PromptManager.Category.OTHER, TITLE, "[0] line", 1, 300);
        assertTrue(p.contains("SONG / MUSIC VIDEO"));
        PromptManager.songJob = false;
        assertFalse(PromptManager.buildViralPrompt(PromptManager.Category.OTHER, "Talk", "[0] line", 1, 300).contains("SONG / MUSIC VIDEO"));
    }

    // ------------------------------------------------------------------ captions of a Bengali song

    static List<Word> words(String... t) {
        List<Word> w = new ArrayList<>();
        double s = 0;
        for (String x : t) { w.add(new Word(x, s, s + 0.4)); s += 0.5; }
        return w;
    }

    @Test public void bengaliWordsAreConvertedNeverDrawnInAMissingFont() {
        List<Word> bn = words("আমি", "ভুল", "মানুষের", "সঙ্গে", "ভালোবাসা");
        assertEquals("translit", CaptionScript.action(bn, "bn", CaptionScript.HINGLISH));
        assertEquals("translate", CaptionScript.action(bn, "bn", CaptionScript.HINDI));
        assertEquals("translate", CaptionScript.action(bn, "bn", CaptionScript.ENGLISH));
        // unchanged: Hindi / English
        assertEquals("keep", CaptionScript.action(words("this", "is", "fine"), "en", CaptionScript.ENGLISH));
        assertEquals("translit", CaptionScript.action(words("क्या", "बात", "है"), "hi", CaptionScript.HINGLISH));
        // offline fallback: Roman letters, no Bengali left
        List<Word> r = CaptionScript.romanize(bn);
        for (Word w : r) {
            assertFalse(w.text, CaptionScript.hasIndicOther(w.text));
            assertTrue(w.text, w.text.matches("[A-Za-z~'.-]+"));
        }
        assertEquals(bn.get(0).start, r.get(0).start, 1e-9);
        assertTrue(r.get(0).text.toLowerCase().startsWith("a"));
        assertTrue(r.get(0).text.toLowerCase().contains("m"));
        // an "English" translation still in Bengali letters is refused
        assertFalse(CaptionScript.translationOk(Arrays.asList("আমি ভুল"), CaptionScript.ENGLISH));
        assertTrue(CaptionScript.translationOk(Arrays.asList("I was wrong"), CaptionScript.ENGLISH));
        assertFalse(CaptionScript.scriptOk("আমি", CaptionScript.HINGLISH));
    }

    @Test public void clipLanguageKeepsTheJobsLanguage() throws Exception {
        Pipeline p = new Pipeline(ctx);
        java.lang.reflect.Field f = Pipeline.class.getDeclaredField("speechLang");
        f.setAccessible(true);
        f.set(p, "bn");
        assertEquals("each short's own audio is transcribed in Bengali too", "bn", p.clipLanguage(CaptionScript.ENGLISH, null));
        f.set(p, "en");
        assertEquals("", p.clipLanguage(CaptionScript.ENGLISH, null));
        assertEquals("hi", p.clipLanguage(CaptionScript.HINGLISH, null));
    }

    // ------------------------------------------------------------------ notifications off

    @Test public void notificationStepOnlyWhenTheyAreOff() {
        List<BackgroundFix.Step> off = BackgroundFix.steps("huawei", "com.viral.shortsmaker", true, true);
        List<BackgroundFix.Step> on = BackgroundFix.steps("huawei", "com.viral.shortsmaker", true, false);
        assertEquals(on.size() + 1, off.size());
        boolean found = false;
        for (BackgroundFix.Step s : off) if (s.label.startsWith("Notifications")) found = true;
        assertTrue(found);
        for (BackgroundFix.Step s : on) assertFalse(s.label.startsWith("Notifications"));
        assertEquals(BackgroundFix.steps("vivo", "p", true).size(), BackgroundFix.steps("vivo", "p", true, false).size());
    }
}
