package com.viral.shortsmaker;

import static org.junit.Assert.*;

import com.viral.shortsmaker.captions.CaptionBuilder;
import com.viral.shortsmaker.captions.CaptionLayout;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.captions.CaptionStyles;
import com.viral.shortsmaker.captions.Word;
import com.viral.shortsmaker.core.FaceTrack;
import com.viral.shortsmaker.core.FfmpegCommands;

import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

/**
 * v14 desktop render check (skipped unless -Pv14Dir=<dir>): the app's own FfmpegCommands / FaceTrack / CaptionBuilder
 * build every command, a desktop ffmpeg runs it on real 16:9 podcast clips and a vertical clip, in all 3 formats,
 * tracking on and off, captions with highlight box + progress bar. Each output is checked for size, frame count and
 * caption timing (the same caption frames with tracking on and off).
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V14RenderTest {
    static String dir() { return System.getProperty("v14Dir", ""); }

    static List<Word> words(double dur) {
        String[] t = {"Yeh", "sawaal", "sabse", "bada", "hai", "aur", "iska", "jawaab", "1000", "crore", "logon", "ko", "nahi", "pata", "dekhiye", "kaise"};
        List<Word> l = new ArrayList<>();
        int i = 0;
        for (double s = 0.3; s < dur - 0.5; s += 0.42, i++) l.add(new Word(t[i % t.length], s, s + 0.36));
        return l;
    }

    static void run(List<String> cmd, File log) throws Exception {
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(log).start();
        if (p.waitFor() != 0) throw new AssertionError("ffmpeg failed: " + new String(Files.readAllBytes(log.toPath())));
    }

    static String probe(File f) throws Exception {
        Process p = new ProcessBuilder("ffprobe", "-v", "error", "-select_streams", "v:0", "-count_frames", "-show_entries", "stream=width,height,nb_read_frames",
                "-of", "csv=p=0", f.getAbsolutePath()).redirectErrorStream(true).start();
        String s = new String(p.getInputStream().readAllBytes()).trim();
        p.waitFor();
        return s;
    }

    String one(String name, String src, double start, double dur, FfmpegCommands.Aspect asp, int shortSide, FfmpegCommands.Frame frame,
               String samples, int sw, int sh, boolean track, StringBuilder report) throws Exception {
        File d = new File(dir());
        int spec = asp.spec(shortSide);
        CaptionStyle st = CaptionStyles.byId("boxword");
        st.progressBar = true;
        CaptionLayout lay = CaptionLayout.defaultFor((float) asp.ratio());
        File ass = new File(d, name + ".ass");
        Files.write(ass.toPath(), CaptionBuilder.buildAss(words(dur), st, lay, dur).getBytes("UTF-8"));
        FfmpegCommands.Track tr = null;
        String note = "normal framing";
        if (track) {
            List<FaceTrack.Sample> s = FaceTrack.samplesFromText(new String(Files.readAllBytes(Paths.get(samples))));
            FaceTrack.Plan p = FaceTrack.plan(s, 5, sw, sh, dur, 30, asp.ratio());
            if (p.usable) {
                File cmd = new File(d, name + ".cmd");
                Files.write(cmd.toPath(), p.sendcmd().getBytes());
                tr = new FfmpegCommands.Track(cmd.getAbsolutePath(), p);
                double[] j = p.jitter(FfmpegCommands.dims(spec)[0]);
                note = String.format(Locale.US, "tracking: %s, steadiness %.2f px", p.summary(), j[0]);
            } else note = "tracking not used: " + p.why;
        }
        File out = new File(d, name + ".mp4");
        String[] a = FfmpegCommands.renderClip(src, null, start, dur, frame, ass.getAbsolutePath(), System.getProperty("fontsDir", null), out.getAbsolutePath(),
                spec, FfmpegCommands.Encoder.X264, 0, tr);
        List<String> cmd = new ArrayList<>(Arrays.asList("ffmpeg", "-nostdin", "-loglevel", "error"));
        cmd.addAll(Arrays.asList(a));
        long t0 = System.currentTimeMillis();
        run(cmd, new File(d, name + ".log"));
        double sec = (System.currentTimeMillis() - t0) / 1000.0;
        String pr = probe(out);
        int[] want = FfmpegCommands.dims(spec);
        String[] p = pr.split(",");
        assertEquals(name, want[0], Integer.parseInt(p[0].trim()));
        assertEquals(name, want[1], Integer.parseInt(p[1].trim()));
        int frames = Integer.parseInt(p[2].trim());
        assertEquals(name + " frames", (int) Math.round(dur * 30), frames, 2);
        report.append(String.format(Locale.US, "%-14s %-5s %4dx%-4d %3d frames %5.1f MB-per-min render %.1f s  %s%n", name, asp.label, want[0], want[1], frames,
                out.length() / 1048576.0 / dur * 60, sec, note));
        return out.getAbsolutePath();
    }

    @Test public void allFormats() throws Exception {
        Assume.assumeFalse("needs -Pv14Dir", dir().isEmpty());
        String jre = "/tmp/ft/src/jre.mp4", norm = "/tmp/ft/src/norm.mp4", vert = dir() + "/vert_src.mp4";
        if (!new File(vert).exists())
            run(Arrays.asList("ffmpeg", "-nostdin", "-loglevel", "error", "-y", "-ss", "30", "-t", "16", "-i", jre, "-vf", "crop=608:1080", "-c:v", "libx264", "-preset", "veryfast", "-c:a", "aac", vert), new File(dir(), "vert.log"));
        StringBuilder r = new StringBuilder();
        FfmpegCommands.Aspect P = FfmpegCommands.Aspect.PORTRAIT, S = FfmpegCommands.Aspect.SQUARE, L = FfmpegCommands.Aspect.LANDSCAPE;
        FfmpegCommands.Frame B = FfmpegCommands.Frame.BLUR;
        one("jre_916_on", jre, 30, 16, P, 720, B, "/tmp/ft/h/jreA.txt", 1920, 1080, true, r);
        one("jre_916_off", jre, 30, 16, P, 720, B, null, 0, 0, false, r);
        one("jre_11_on", jre, 30, 16, S, 720, B, "/tmp/ft/h/jreA.txt", 1920, 1080, true, r);
        one("jre_11_off", jre, 30, 16, S, 720, B, null, 0, 0, false, r);
        one("jre_169", jre, 30, 16, L, 720, B, "/tmp/ft/h/jreA.txt", 1920, 1080, true, r);
        one("norm_916_on", norm, 20, 24, P, 720, B, "/tmp/ft/h/normA.txt", 1280, 720, true, r);
        one("norm_11_on", norm, 20, 24, S, 720, B, "/tmp/ft/h/normA.txt", 1280, 720, true, r);
        one("norm_169", norm, 20, 24, L, 720, B, null, 0, 0, false, r);
        one("vert_169_blur", vert, 0, 16, L, 720, B, null, 0, 0, false, r);
        one("vert_11_blur", vert, 0, 16, S, 720, B, null, 0, 0, false, r);
        one("vert_916", vert, 0, 16, P, 720, B, null, 0, 0, false, r);
        one("jre_11_fill", jre, 30, 16, S, 720, FfmpegCommands.Frame.FILL, null, 0, 0, false, r);
        one("jre_169_fit", jre, 30, 16, L, 720, FfmpegCommands.Frame.FIT, null, 0, 0, false, r);
        one("jre_11_1080", jre, 30, 16, S, 1080, B, "/tmp/ft/h/jreA.txt", 1920, 1080, true, r);
        one("jre_169_1080", jre, 30, 16, L, 1080, B, null, 0, 0, false, r);
        one("jre_916_1080", jre, 30, 16, P, 1080, B, "/tmp/ft/h/jreA.txt", 1920, 1080, true, r);
        Files.write(Paths.get(dir(), "report.txt"), r.toString().getBytes());
        System.out.println(r);
    }
}
