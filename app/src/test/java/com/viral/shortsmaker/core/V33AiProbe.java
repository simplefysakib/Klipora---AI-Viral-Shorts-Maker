package com.viral.shortsmaker.core;

import static org.junit.Assume.assumeTrue;

import com.viral.shortsmaker.PromptManager;

import org.json.JSONObject;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** 5.1 probe (skipped where the cached real transcripts are missing): prompt sizes and the on-phone work after the AI answer. */
@RunWith(RobolectricTestRunner.class)
public class V33AiProbe {
    static String read(File f) throws Exception { return new String(java.nio.file.Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }

    @Test public void probe() throws Exception {
        File root = new File("/home/user/tab/work/k18ends");
        assumeTrue(root.isDirectory());
        for (String k : new String[]{"hi2", "his", "his_s", "en", "hi_pod", "long_en"}) {
            File d = new File(root, k);
            JSONObject in = new JSONObject(read(new File(d, "in.json")));
            double dur = in.getDouble("duration");
            Transcript t = Transcript.fromJson(read(new File(d, "transcript.json")));
            PromptManager.Category cat = PromptManager.Category.valueOf(in.optString("category", "AUTO"));
            String compact = t.forPromptCompact(240000);
            int want = Math.max(1, in.optInt("count", 3));
            int count = Math.min(8, want + Math.max(3, want / 2));
            String prompt = PromptManager.buildViralPrompt(cat, in.optString("title"), compact, count, dur);
            List<AiClient.Candidate> cands = AiClient.parseCandidatesForTest(read(new File(d, "answer.txt")), dur);
            long t0 = System.nanoTime();
            List<Project.Clip> picked = null;
            for (int i = 0; i < 3; i++) picked = Pipeline.pickClips(cands, t, dur, want);
            double pickMs = (System.nanoTime() - t0) / 3e6;
            t0 = System.nanoTime();
            PodcastDetector.decide(cat == PromptManager.Category.PODCAST, in.optString("title"), "", t, dur, cands);
            double podMs = (System.nanoTime() - t0) / 1e6;
            String ends = AiClient.buildEndPrompt(picked, t, dur);
            System.out.println(String.format(Locale.US, "AIPROBE %s: %.0f s video, %d lines, %d words | pick prompt %.1f KB (transcript %.1f KB, rules %.1f KB), %d moments asked | end prompt %.1f KB for %d clips | pickClips %.0f ms, podcast check %.0f ms",
                    k, dur, t.segments.size(), t.words.size(), prompt.length() / 1024.0, compact.length() / 1024.0, (prompt.length() - compact.length()) / 1024.0,
                    Math.min(18, count + 3), ends.length() / 1024.0, picked.size(), pickMs, podMs));
        }
    }
}
