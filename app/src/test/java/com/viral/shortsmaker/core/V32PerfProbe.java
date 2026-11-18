package com.viral.shortsmaker.core;

import com.viral.shortsmaker.captions.Word;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

/** code 32: timing of the top-up window search on a long transcript (prints; run with -Dv32perf=1) */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V32PerfProbe {
    static Transcript synth(double hours) {
        Transcript t = new Transcript();
        String[] vocab = {"so", "basically", "the", "thing", "is", "we", "talked", "about", "money", "and", "life", "really", "what", "do", "you", "think"};
        double pos = 0; int k = 0;
        while (pos < hours * 3600) {
            double s0 = pos; StringBuilder sb = new StringBuilder();
            int n = 8 + (k % 7);
            for (int i = 0; i < n; i++) {
                String w = vocab[(k * 7 + i * 3) % vocab.length] + (i == n - 1 ? (k % 3 == 0 ? "?" : ".") : "");
                t.words.add(new Word(w, pos, pos + 0.35)); sb.append(i > 0 ? " " : "").append(w); pos += 0.46;
            }
            t.segments.add(new Transcript.Segment(s0, pos, sb.toString()));
            pos += 0.3; k++;
        }
        return t;
    }

    @Test public void topUpSearchTime() {
        if (System.getProperty("v32perf") == null && System.getenv("V32PERF") == null) return;
        for (double h : new double[]{1, 3}) {
            Transcript t = synth(h);
            long t0 = System.nanoTime();
            double[] w = Pipeline.windowIn(t, 60, h * 3600 - 60, h * 3600, 35, 60, true);
            long ms = (System.nanoTime() - t0) / 1_000_000;
            System.out.println("V32PERF hours=" + h + " words=" + t.words.size() + " segs=" + t.segments.size() + " windowIn_ms=" + ms + " pick=" + (w == null ? "null" : w[0] + "-" + w[1]));
        }
    }
}
