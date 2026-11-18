package com.viral.shortsmaker;

import com.viral.shortsmaker.core.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

/**
 * v18 harness (skipped unless -Dh18Dirs=dir1,dir2 is given): real transcripts -> the app's exact clip prompt (prompt.txt);
 * the AI's answer (answer.txt) -> the app's clip picking (picked.json: start/end, first and last sentence, the words right
 * after the end) so endings can be judged before/after.
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class V18HarnessTest {
    static String rd(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    static void wr(File f, String s) throws Exception { Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8)); }

    @Test public void harness() throws Exception {
        String dirs = System.getProperty("h18Dirs", "");
        Assume.assumeTrue(dirs != null && !dirs.isEmpty());
        String mode = System.getProperty("h18Mode", "prompt");
        for (String d : dirs.split(",")) {
            File dir = new File(d);
            JSONObject in = new JSONObject(rd(new File(dir, "in.json")));
            Transcript tr = Transcript.fromJson(rd(new File(dir, "transcript.json")));
            double dur = in.getDouble("duration");
            PromptManager.Category cat = PromptManager.Category.valueOf(in.optString("category", "PODCAST"));
            int count = in.optInt("count", 5);
            if (mode.equals("prompt")) {
                wr(new File(dir, "prompt.txt"), PromptManager.buildViralPrompt(cat, in.optString("title", "video"), tr.forPromptCompact(240000), count + Math.max(2, count / 3), dur));
            } else if (mode.equals("endprompt") || mode.equals("endapply")) {
                List<AiClient.Candidate> cands = AiClient.parseCandidatesForTest(rd(new File(dir, "answer.txt")), dur);
                List<Project.Clip> picked = Pipeline.pickClips(cands, tr, dur, count, cat);
                if (mode.equals("endprompt")) {
                    wr(new File(dir, "picked_rule.json"), dump(picked, tr).toString(1));
                    wr(new File(dir, "ends_prompt.txt"), AiClient.buildEndPrompt(picked, tr, dur));
                } else {
                    int moved = AiClient.applyEnds(picked, tr, dur, AiClient.parseEnds(rd(new File(dir, "ends_answer.txt"))));
                    JSONArray out = dump(picked, tr);
                    wr(new File(dir, System.getProperty("h18Out", "picked_v18.json")), out.toString(1));
                    System.out.println("H18 " + dir.getName() + " moved " + moved);
                }
            } else if (mode.equals("pick")) {
                List<AiClient.Candidate> cands = AiClient.parseCandidatesForTest(rd(new File(dir, "answer.txt")), dur);
                List<Project.Clip> picked = Pipeline.pickClips(cands, tr, dur, count, cat);
                wr(new File(dir, System.getProperty("h18Out", "picked.json")), dump(picked, tr).toString(1));
            }
        }
    }

    static JSONArray dump(List<Project.Clip> picked, Transcript tr) throws Exception {
        JSONArray out = new JSONArray();
        for (Project.Clip c : picked) {
            StringBuilder after = new StringBuilder(), tail = new StringBuilder();
            int n = 0;
            for (com.viral.shortsmaker.captions.Word w : tr.words) if (w.start >= c.end - 0.05 && n < 12) { after.append(w.text.trim()).append(' '); n++; }
            java.util.List<String> last = new java.util.ArrayList<>();
            for (com.viral.shortsmaker.captions.Word w : tr.words) if (w.end <= c.end + 0.05 && w.start >= c.end - 25) last.add(w.text.trim());
            for (String s : last) tail.append(s).append(' ');
            out.put(new JSONObject().put("start", c.start).put("end", c.end).put("len", c.end - c.start).put("score", c.score)
                    .put("hook", c.hook).put("title", c.title).put("reason", c.reason)
                    .put("last25s", tail.toString().trim()).put("after", after.toString().trim())
                    .put("closing", ClipAligner.closingLine(tr.segments, tr.words, c.start, c.end))
                    .put("endsOnThought", ClipAligner.endsOnThought(tr.segments, tr.words, c.end)));
        }
        return out;
    }
}
