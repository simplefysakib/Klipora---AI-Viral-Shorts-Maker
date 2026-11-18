package com.viral.shortsmaker;

import com.viral.shortsmaker.core.FaceTrack;
import org.junit.Assume;
import org.junit.Test;
import java.nio.file.*;
import java.util.*;

/** v19 real-sample check (skipped unless -PharnessDir=<dir with norm18.txt / jre18.txt>): planner summary + split parts */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V19RealTrackTest {
    @Test public void real() throws Exception {
        String d = System.getProperty("harnessDir", "");
        Assume.assumeTrue(!d.isEmpty() && Files.exists(Paths.get(d, "norm18.txt")));
        StringBuilder out = new StringBuilder();
        for (String[] c : new String[][]{{"norm18.txt", "1280", "720"}, {"jre18.txt", "1920", "1080"}}) {
            List<FaceTrack.Sample> s = FaceTrack.samplesFromText(new String(Files.readAllBytes(Paths.get(d, c[0]))));
            double dur = s.size() / 5.0;
            FaceTrack.Plan p = FaceTrack.plan(s, 5, Integer.parseInt(c[1]), Integer.parseInt(c[2]), dur, 30);
            double[] j = p.jitter(720);
            out.append(c[0]).append(": ").append(p.summary()).append(", ").append(p.steadiness()).append(String.format(Locale.US, " jitter %.2f px%n", j[0]));
            for (FaceTrack.Split sp : p.splits) out.append(String.format(Locale.US, "   split %.1f-%.1f s%n", sp.start, sp.end));
        }
        Files.write(Paths.get(d, "v19_track_report.txt"), out.toString().getBytes());
        System.out.println(out);
    }
}
