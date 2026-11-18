package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * code 35 (5.3): the shipped FFmpeg (ffmpeg-kit, FFmpeg 8.1.2) starts every filter graph with its input labelled "gbr", so
 * each scaler did a YUV->RGB->YUV detour on every frame (slower, the output labelled "gbr", and the NEON RGB routine that
 * crashed his vivo Y16). Every video command now labels its input BT.709 / TV range first (FfmpegCommands.COLOUR_IN):
 * these tests pin where the tag goes and that nothing else in the commands changed (captions, sizes, encoder, audio).
 */
public class V35ColourTest {
    static final String V = "/v.mp4", A = "/a.webm", ASS = "/c.ass", FONTS = "/fonts", OUT = "/o.mp4";

    static String graphOf(String[] args) {
        for (int i = 0; i + 1 < args.length; i++) if ("-filter_complex".equals(args[i]) || "-vf".equals(args[i])) return args[i + 1];
        return null;
    }

    static int count(String s, String sub) { int n = 0, i = 0; while ((i = s.indexOf(sub, i)) >= 0) { n++; i += sub.length(); } return n; }

    static FaceTrack.Plan plan() {
        FaceTrack.Plan p = new FaceTrack.Plan();
        p.usable = true;
        p.outFps = 30;
        p.cropW = (1080 * 9.0 / 16.0) / 1920.0;
        p.cx = new double[90];
        for (int k = 0; k < p.cx.length; k++) p.cx[k] = 0.42 + 0.16 * k / (p.cx.length - 1);
        return p;
    }

    /** every video command the renderer builds, with the 5.2 graph it must equal once the tag is taken out */
    static List<String[][]> commands() {
        List<String[][]> l = new ArrayList<>();
        FfmpegCommands.Track tr = new FfmpegCommands.Track("/d/ft_0.cmd", plan(), 1080);
        for (int outW : new int[]{720, 1080, FfmpegCommands.Aspect.SQUARE.spec(1080), FfmpegCommands.Aspect.LANDSCAPE.spec(720)})
            for (FfmpegCommands.Encoder enc : new FfmpegCommands.Encoder[]{FfmpegCommands.Encoder.X264, FfmpegCommands.Encoder.HW}) {
                String pf = FfmpegCommands.pixFor(enc, outW);
                for (FfmpegCommands.Frame f : FfmpegCommands.Frame.values())
                    for (String ass : new String[]{ASS, null})
                        l.add(new String[][]{FfmpegCommands.renderClip(V, A, 5, 30, f, ass, FONTS, OUT, outW, enc, 6, null, false, null, 1080),
                                {FfmpegCommands.frameGraph(f, ass, FONTS, outW, pf, 1080)}});
                l.add(new String[][]{FfmpegCommands.renderClip(V, A, 5, 30, FfmpegCommands.Frame.FILL, ASS, FONTS, OUT, outW, enc, 6, tr, false, null, 1080),
                        {FfmpegCommands.trackGraph(tr, ASS, FONTS, outW, pf)}});
                l.add(new String[][]{FfmpegCommands.burnCaptions("/raw.mp4", ASS, FONTS, OUT, enc, outW, 6),
                        {"[0:v]" + FfmpegCommands.subtitles(ASS, FONTS) + ",format=" + pf + "[v]"}});
            }
        for (int outW : new int[]{720, 1080})
            l.add(new String[][]{FfmpegCommands.renderClipSafe(V, A, 5, 30, ASS, FONTS, OUT, outW), null});
        l.add(new String[][]{FfmpegCommands.burnCaptions("/raw.mp4", ASS, FONTS, OUT), {"[0:v]" + FfmpegCommands.subtitles(ASS, FONTS) + ",format=yuv420p[v]"}});
        return l;
    }

    @Test public void everyVideoCommandLabelsItsInputBt709First() {
        for (String[][] c : commands()) {
            String g = graphOf(c[0]);
            assertNotNull(Arrays.toString(c[0]), g);
            assertTrue(g, g.startsWith("[0:v]" + FfmpegCommands.COLOUR_IN + ","));
            assertEquals(g, 1, count(g, "setparams="));
            int sc = g.indexOf("scale=");
            if (sc >= 0) assertTrue(g, g.indexOf(FfmpegCommands.COLOUR_IN) < sc);   // before any scaler
            assertFalse(g, g.contains("gbr"));
        }
    }

    @Test public void theTagIsTheOnlyChangeCaptionsAndEverythingElseStayTheSame() {
        for (String[][] c : commands()) {
            if (c[1] == null) continue;
            String g = graphOf(c[0]);
            assertEquals(c[1][0], g.replace(FfmpegCommands.COLOUR_IN + ",", ""));
            if (c[1][0].contains("ass=")) assertTrue(g, g.contains(FfmpegCommands.subtitles(ASS, FONTS)));
        }
        // the safe-mode render: the same 5.2 graph behind the tag
        String g = graphOf(FfmpegCommands.renderClipSafe(V, A, 5, 30, ASS, FONTS, OUT, 720));
        assertEquals("[0:v]scale=720:1280:force_original_aspect_ratio=decrease:flags=lanczos,pad=720:1280:(ow-iw)/2:(oh-ih)/2:color=black,setsar=1,fps=30,"
                + FfmpegCommands.subtitles(ASS, FONTS) + ",format=yuv420p[v]", g.replace(FfmpegCommands.COLOUR_IN + ",", ""));
    }

    @Test public void theTagSaysBt709TvRangeAndChangesNoPixels() {
        String t = FfmpegCommands.COLOUR_IN;
        assertTrue(t.startsWith("setparams="));
        for (String k : new String[]{"range=tv", "colorspace=bt709", "color_primaries=bt709", "color_trc=bt709"}) assertTrue(t, t.contains(k));
        assertFalse(t.contains("scale") || t.contains("colorspace=gbr") || t.contains(","));
    }

    @Test public void colourInIsIdempotentAndCoversPlainFilterChains() {
        String g = "[0:v]fps=30,scale=720:1280[v]";
        String once = FfmpegCommands.colourIn(g);
        assertEquals("[0:v]" + FfmpegCommands.COLOUR_IN + ",fps=30,scale=720:1280[v]", once);
        assertEquals(once, FfmpegCommands.colourIn(once));
        // the face-tracking frame grab is a -vf chain
        assertEquals(FfmpegCommands.COLOUR_IN + ",fps=5,scale=640:-2:flags=fast_bilinear", FfmpegCommands.colourIn("fps=5,scale=640:-2:flags=fast_bilinear"));
        assertNull(FfmpegCommands.colourIn(null));
    }

    @Test public void theSafetyRungsKeepTheTag() {
        String[] cmd = FfmpegCommands.renderClip(V, A, 5, 30, FfmpegCommands.Frame.BLUR, ASS, FONTS, OUT, 720, FfmpegCommands.Encoder.X264, 6, null, false, null, 1080);
        String g = graphOf(cmd);
        assertEquals(g, graphOf(FfmpegCommands.noNeon(cmd)));
        assertEquals(g, graphOf(FfmpegCommands.cpuSafe(cmd)));
        assertEquals("-cpuflags", FfmpegCommands.noNeon(cmd)[0]);
    }

    @Test public void audioCommandsAreNotTouched() {
        for (String[] a : new String[][]{FfmpegCommands.cutAudioCopy(A, 1, 2, "/o.m4a"), FfmpegCommands.speechAudioOpus(A, "/o.opus"),
                FfmpegCommands.sourceSpeechAudio(V, 1, 2, "/o.flac")})
            for (String s : a) assertFalse(s, s.contains("setparams"));
    }

    @Test public void theFaceFrameGrabUsesTheTag() throws Exception {
        java.io.File f = new java.io.File("src/main/java/com/viral/shortsmaker/core/FaceAnalyzer.java");
        if (!f.exists()) f = new java.io.File("app/src/main/java/com/viral/shortsmaker/core/FaceAnalyzer.java");
        String src = new String(java.nio.file.Files.readAllBytes(f.toPath()), java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(src.contains("\"-vf\", FfmpegCommands.colourIn(\"fps=\""));
    }
}
