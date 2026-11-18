package com.viral.shortsmaker;

import com.viral.shortsmaker.core.*;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Assume;
import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

/**
 * Runs the app's real Java logic against real files produced on a desktop (skipped unless -PharnessDir is given):
 *  plan  : writes the transcription piece plan + the exact ffmpeg args the app would run
 *  merge : reads whisper verbose_json per piece, merges like the app (tempo scaling, overlap ownership)
 *  prompt: writes the exact clip-finding prompt for transcript.json
 *  align : parses the AI answer and snaps the clips like the app
 */
@org.junit.runner.RunWith(org.robolectric.RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 34)
public class DesktopHarnessTest {
    static String rd(File f) throws Exception { return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
    static void wr(File f, String s) throws Exception { Files.write(f.toPath(), s.getBytes(StandardCharsets.UTF_8)); }

    @Test public void harness() throws Exception {
        String d = System.getProperty("harnessDir", "");
        Assume.assumeTrue(d != null && !d.isEmpty());
        File dir = new File(d);
        String mode = System.getProperty("harnessMode", "");
        JSONObject in = new JSONObject(rd(new File(dir, "in.json")));
        if (mode.equals("plan")) {
            double dur = in.getDouble("duration");
            double tempo = Pipeline.speechTempo(dur);
            JSONArray out = new JSONArray();
            List<double[]> plan = Pipeline.piecePlan(dur);
            for (int k = 0; k < plan.size(); k++) {
                double[] p = plan.get(k);
                String[] args = tempo == 1.0
                        ? FfmpegCommands.cutAudioCopy(in.getString("src"), p[0], p[1], new File(dir, "piece" + k + ".m4a").getAbsolutePath())
                        : FfmpegCommands.cutAudioOpus(in.getString("src"), p[0], p[1], new File(dir, "piece" + k + ".ogg").getAbsolutePath(), tempo);
                out.put(new JSONObject().put("cs", p[0]).put("len", p[1]).put("kf", p[2]).put("kt", p[3] == Double.MAX_VALUE ? -1 : p[3])
                        .put("args", new JSONArray(java.util.Arrays.asList(args))));
            }
            wr(new File(dir, "plan.json"), new JSONObject().put("tempo", tempo).put("pieces", out).toString(1));
        } else if (mode.equals("merge")) {
            JSONObject plan = new JSONObject(rd(new File(dir, "plan.json")));
            double tempo = plan.getDouble("tempo");
            JSONArray ps = plan.getJSONArray("pieces");
            int n = ps.length();
            List<Transcript> parts = new ArrayList<>();
            double[] cs = new double[n], kf = new double[n], kt = new double[n];
            for (int k = 0; k < n; k++) {
                JSONObject p = ps.getJSONObject(k);
                Transcript t = Transcript.parseVerboseJson(rd(new File(dir, "piece" + k + ".json")), 0);
                if (tempo > 1.0) t = t.scaled(tempo);
                parts.add(t);
                cs[k] = p.getDouble("cs"); kf[k] = p.getDouble("kf"); kt[k] = p.getDouble("kt") < 0 ? Double.MAX_VALUE : p.getDouble("kt");
            }
            Transcript tr = Transcript.merge(parts, cs, kf, kt);
            tr.punctuateWords();
            wr(new File(dir, "transcript.json"), tr.toJson().toString());
        } else if (mode.equals("prompt")) {
            Transcript tr = Transcript.fromJson(rd(new File(dir, "transcript.json")));
            wr(new File(dir, "prompt.txt"), PromptManager.buildViralPrompt(PromptManager.Category.valueOf(in.optString("category", "OTHER")),
                    in.optString("title", "video"), tr.forPromptCompact(240000), in.optInt("count", 3), in.getDouble("duration")));
        } else if (mode.equals("render")) {
            String v = in.getString("video"), ass = in.optString("ass", null), fonts = in.optString("fonts", null);
            JSONArray out = new JSONArray();
            for (String kind : new String[]{"hw", "x264", "safe", "safe_nocap"}) {
                String o = new File(dir, "r_" + kind + ".mp4").getAbsolutePath();
                String[] a = kind.equals("hw") ? FfmpegCommands.renderClip(v, null, in.getDouble("start"), in.getDouble("dur"), FfmpegCommands.Frame.BLUR, ass, fonts, o, 720, FfmpegCommands.Encoder.HW, 0)
                        : kind.equals("x264") ? FfmpegCommands.renderClip(v, null, in.getDouble("start"), in.getDouble("dur"), FfmpegCommands.Frame.BLUR, ass, fonts, o, 720, FfmpegCommands.Encoder.X264, 0)
                        : FfmpegCommands.renderClipSafe(v, null, in.getDouble("start"), in.getDouble("dur"), kind.equals("safe") ? ass : null, fonts, o, 720);
                out.put(new JSONObject().put("kind", kind).put("args", new JSONArray(java.util.Arrays.asList(a))));
            }
            wr(new File(dir, "render.json"), out.toString(1));
        } else if (mode.equals("v8sent")) {
            // v8: Hindi words of the clip -> the sentences the app would send for English translation
            Transcript tr = Transcript.fromJson(rd(new File(dir, "transcript.json")));
            List<com.viral.shortsmaker.captions.Word> w = tr.wordsForClip(in.getDouble("start"), in.getDouble("end"));
            wr(new File(dir, "sentences.json"), new JSONArray(CaptionScript.sentenceTexts(w)).toString(1));
        } else if (mode.equals("v8cap")) {
            // v8: the clip's real Hindi words -> Hinglish (phone transliteration), हिंदी (as spoken), English (translated
            // sentences on the words' timing) -> the app's caption builder -> ASS + words.json per language
            com.viral.shortsmaker.captions.CaptionBuilder.measurer = new AwtMeasurer(new File("src/main/assets/fonts"));
            Transcript tr = Transcript.fromJson(rd(new File(dir, "transcript.json")));
            double s = in.getDouble("start"), e = in.getDouble("end");
            List<com.viral.shortsmaker.captions.Word> w = tr.wordsForClip(s, e);
            JSONArray en = new JSONArray(rd(new File(dir, "translated.json")));
            List<String> ens = new ArrayList<>();
            for (int i = 0; i < en.length(); i++) ens.add(en.getString(i));
            String[][] langs = {{"hinglish", CaptionScript.HINGLISH}, {"hindi", CaptionScript.HINDI}, {"english", CaptionScript.ENGLISH}};
            JSONObject out = new JSONObject();
            for (String[] l : langs) {
                String act = CaptionScript.action(w, tr.language, l[1]);
                List<com.viral.shortsmaker.captions.Word> cw = act.equals("keep") ? w : act.equals("translit") ? CaptionScript.romanize(w) : CaptionScript.spreadSentences(w, ens);
                JSONArray wj = new JSONArray();
                for (com.viral.shortsmaker.captions.Word x : cw) wj.put(new JSONArray().put(x.text).put(x.start).put(x.end));
                out.put(l[0], new JSONObject().put("action", act).put("words", wj));
                File ass = new File(dir, "cap_" + l[0] + ".ass");
                wr(ass, com.viral.shortsmaker.captions.CaptionBuilder.buildAss(cw, com.viral.shortsmaker.captions.CaptionStyles.byId(in.getString("style")),
                        new com.viral.shortsmaker.captions.CaptionLayout(), e - s));
            }
            wr(new File(dir, "v8cap.json"), out.toString(1));
        } else if (mode.equals("v12box")) {
            // v12: real words -> rounded highlight box ASS (Hinglish + हिंदी), app's own builder and font widths
            com.viral.shortsmaker.captions.CaptionBuilder.measurer = new AwtMeasurer(new File("src/main/assets/fonts"));
            JSONArray ws = new JSONArray(rd(new File(dir, "words.json")));
            List<com.viral.shortsmaker.captions.Word> w = new ArrayList<>();
            for (int i = 0; i < ws.length(); i++) { JSONArray x = ws.getJSONArray(i); w.add(new com.viral.shortsmaker.captions.Word(x.getString(0), x.getDouble(1), x.getDouble(2))); }
            com.viral.shortsmaker.captions.CaptionStyle st = com.viral.shortsmaker.captions.CaptionStyles.byId("boxword");
            st.highlightColor = 0xFF8B3DFF;
            double boxDur = in.getDouble("dur");
            wr(new File(dir, "box_hindi.ass"), com.viral.shortsmaker.captions.CaptionBuilder.buildAss(w, st, new com.viral.shortsmaker.captions.CaptionLayout(), boxDur));
            wr(new File(dir, "box_hinglish.ass"), com.viral.shortsmaker.captions.CaptionBuilder.buildAss(CaptionScript.romanize(w), st, new com.viral.shortsmaker.captions.CaptionLayout(), boxDur));
            st.maxLines = 2; st.maxWordsPerLine = 3;
            wr(new File(dir, "box_hinglish2.ass"), com.viral.shortsmaker.captions.CaptionBuilder.buildAss(CaptionScript.romanize(w), st, new com.viral.shortsmaker.captions.CaptionLayout(), boxDur));
        } else if (mode.equals("captions")) {
            // real transcript words of a clip -> the app's caption builder -> ASS + the app's render args
            com.viral.shortsmaker.captions.CaptionBuilder.measurer = new AwtMeasurer(new File("src/main/assets/fonts"));
            Transcript tr = Transcript.fromJson(rd(new File(dir, "transcript.json")));
            double s = in.getDouble("start"), e = in.getDouble("end");
            JSONArray out = new JSONArray();
            for (String id : in.getString("styles").split(",")) {
                List<com.viral.shortsmaker.captions.Word> w = tr.wordsForClip(s, e);
                File ass = new File(dir, "cap_" + id + ".ass");
                wr(ass, com.viral.shortsmaker.captions.CaptionBuilder.buildAss(w, com.viral.shortsmaker.captions.CaptionStyles.byId(id),
                        new com.viral.shortsmaker.captions.CaptionLayout(), e - s));
                String[] a = FfmpegCommands.renderClip(in.getString("video"), null, s, e - s, FfmpegCommands.Frame.valueOf(in.optString("frame", "BLUR")),
                        ass.getAbsolutePath(), new File("src/main/assets/fonts").getAbsolutePath(), new File(dir, "cap_" + id + ".mp4").getAbsolutePath(), 720,
                        FfmpegCommands.Encoder.X264, 0);
                out.put(new JSONObject().put("id", id).put("args", new JSONArray(java.util.Arrays.asList(a))));
            }
            wr(new File(dir, "captions.json"), out.toString(1));
        } else if (mode.equals("v7")) {
            // before (v6 pipeline: snap + overlap) vs after (v7: snap + hook rule + rank) for the same transcript
            Transcript tr = Transcript.fromJson(rd(new File(dir, "transcript.json")));
            double dur = in.getDouble("duration");
            JSONObject res = new JSONObject();
            List<double[]> ranges = new ArrayList<>();
            JSONArray before = new JSONArray();
            for (AiClient.Candidate c : AiClient.parseForTest(rd(new File(dir, "answer_old.txt")), dur, tr)) {
                if (before.length() >= in.optInt("count", 5)) break;
                double[] r = ClipAligner.align(tr.segments, tr.words, c.startLine, c.endLine, c.start, c.end, dur);
                boolean ov = false;
                for (double[] e : ranges) if (Math.min(r[1], e[1]) - Math.max(r[0], e[0]) > 1.0) ov = true;
                if (ov) continue;
                ranges.add(r);
                String hk = ClipAligner.openingLine(tr.segments, tr.words, r[0], r[1]);
                before.put(new JSONObject().put("start", r[0]).put("end", r[1]).put("score", c.score).put("title", c.title)
                        .put("hook", hk).put("filler", ClipAligner.startsWithFiller(hk)));
            }
            JSONArray after = new JSONArray();
            List<AiClient.Candidate> nc = AiClient.parseForTest(rd(new File(dir, "answer_new.txt")), dur, tr);
            for (Project.Clip c : Pipeline.pickClips(nc, tr, dur, in.optInt("count", 5)))
                after.put(new JSONObject().put("start", c.start).put("end", c.end).put("score", c.score).put("title", c.title)
                        .put("hook", c.hook).put("ai_hook", "").put("filler", ClipAligner.startsWithFiller(c.hook))
                        .put("subs", c.hookScore + "/" + c.standaloneScore + "/" + c.valueScore + "/" + c.payoffScore));
            JSONArray raw = new JSONArray();
            for (AiClient.Candidate c : nc) raw.put(new JSONObject().put("start", c.start).put("end", c.end).put("score", c.score).put("ai_hook", c.hook));
            wr(new File(dir, "v7.json"), res.put("before", before).put("after", after).put("new_raw", raw).toString(1));
        } else if (mode.equals("align")) {
            Transcript tr = Transcript.fromJson(rd(new File(dir, "transcript.json")));
            List<AiClient.Candidate> cands = AiClient.parseForTest(rd(new File(dir, "answer.txt")), in.getDouble("duration"));
            JSONArray out = new JSONArray();
            for (AiClient.Candidate c0 : cands) {
                AiClient.Candidate c = new AiClient.Candidate();
                c.start = c0.start; c.end = c0.end; c.startLine = c0.startLine; c.endLine = c0.endLine; c.hook = c0.hook; c.type = c0.type;
                c.questionLine = c0.questionLine; c.needsContext = c0.needsContext; c.score = c0.score; c.title = c0.title;
                double hint = c.startLine >= 0 && c.startLine < tr.segments.size() ? tr.segments.get(c.startLine).start : c.start;
                double hs = ClipAligner.locateHook(tr.words, c.hook, hint);
                if (hs >= 0 && Math.abs(hs - c.start) > 0.5) { double len = c.end - c.start; c.start = hs; c.startLine = -1; if (c.endLine < 0 && len > 0) c.end = hs + len; }
                double[] r = ClipAligner.align(tr.segments, tr.words, c.startLine, c.endLine, c.start, c.end, in.getDouble("duration"));
                out.put(new JSONObject().put("ai_start", c.start).put("ai_end", c.end).put("start", r[0]).put("end", r[1])
                        .put("score", c.score).put("title", c.title).put("hook", c.hook));
            }
            wr(new File(dir, "aligned.json"), out.toString(1));
        } else if (mode.equals("v12pick")) {
            // v12: the AI answer -> the app's real picker for the video's genre; first + last line of each pick
            Transcript tr = Transcript.fromJson(rd(new File(dir, "transcript.json")));
            double dur = in.getDouble("duration");
            PromptManager.Category cat = PromptManager.Category.valueOf(in.optString("category", "OTHER"));
            List<AiClient.Candidate> cands = AiClient.parseForTest(rd(new File(dir, "answer.txt")), dur, tr);
            JSONArray raw = new JSONArray();
            for (AiClient.Candidate c : cands) raw.put(new JSONObject().put("type", c.type).put("start", c.start).put("end", c.end).put("sl", c.startLine).put("el", c.endLine)
                    .put("score", c.score).put("hook", c.hook).put("ctx", c.needsContext).put("gaali", c.aiProfanity));
            List<Project.Clip> picked = Pipeline.pickClips(cands, tr, dur, in.optInt("count", 5), cat);
            JSONArray out = new JSONArray();
            for (Project.Clip c : picked) out.put(new JSONObject().put("start", c.start).put("end", c.end).put("len", Math.round((c.end - c.start) * 10) / 10.0).put("score", c.score)
                    .put("first_line", c.hook).put("last_line", ClipAligner.closingLine(tr.segments, tr.words, c.start, c.end))
                    .put("ends_on_thought", ClipAligner.endsOnThought(tr.segments, tr.words, c.end - 0.3)).put("gaali", c.profane)
                    .put("gaali_words", new JSONArray(com.viral.shortsmaker.core.Profanity.find(com.viral.shortsmaker.core.Profanity.textIn(tr, c.start, c.end))))
                    .put("reason", c.reason).put("question", ClipAligner.isQuestion(c.hook)));
            wr(new File(dir, "picked.json"), new JSONObject().put("category", cat.name()).put("candidates", raw).put("picked", out).toString(1));
        } else if (mode.equals("v11pick")) {
            // v11: the AI answer -> the app's real picker (question start, hook rule, context filter, ranking, max N)
            Transcript tr = Transcript.fromJson(rd(new File(dir, "transcript.json")));
            double dur = in.getDouble("duration");
            List<AiClient.Candidate> cands = AiClient.parseForTest(rd(new File(dir, "answer.txt")), dur, tr);
            JSONArray raw = new JSONArray();
            for (AiClient.Candidate c0 : cands) {
                AiClient.Candidate c = new AiClient.Candidate();
                c.start = c0.start; c.end = c0.end; c.startLine = c0.startLine; c.endLine = c0.endLine; c.hook = c0.hook; c.type = c0.type;
                c.questionLine = c0.questionLine; c.needsContext = c0.needsContext; c.score = c0.score; c.title = c0.title;
                double hint = c.startLine >= 0 && c.startLine < tr.segments.size() ? tr.segments.get(c.startLine).start : c.start;
                double hs = ClipAligner.locateHook(tr.words, c.hook, hint);
                if (hs >= 0 && Math.abs(hs - c.start) > 0.5) { double len = c.end - c.start; c.start = hs; c.startLine = -1; if (c.endLine < 0 && len > 0) c.end = hs + len; }
                double[] r = ClipAligner.align(tr.segments, tr.words, c.startLine, c.endLine, c.start, c.end, dur);
                if (hs >= 0) r = ClipAligner.startAt(tr.segments, tr.words, hs, r, dur);
                double qs = ClipAligner.questionStart(tr.segments, tr.words, r[0], r[1], c.questionLine, c.isQa());
                double[] h = ClipAligner.hookSafe(tr.segments, tr.words, r, dur);
                raw.put(new JSONObject().put("type", c.type).put("q", c.questionLine).put("ctx", c.needsContext).put("sl", c.startLine).put("el", c.endLine)
                    .put("start", c.start).put("end", c.end).put("score", c.score).put("ai_hook", c.hook).put("title", c.title)
                    .put("aligned", r[0] + "-" + r[1]).put("qs", qs).put("hook", h == null ? "null" : h[0] + "-" + h[1] + " how " + h[2])
                    .put("open", ClipAligner.openingLine(tr.segments, tr.words, r[0], r[1])));
            }
            List<Project.Clip> picked = Pipeline.pickClips(cands, tr, dur, in.optInt("count", 5));
            JSONArray out = new JSONArray();
            for (Project.Clip c : picked) out.put(new JSONObject().put("start", c.start).put("end", c.end).put("score", c.score)
                    .put("first_line", c.hook).put("reason", c.reason).put("question", ClipAligner.isQuestion(c.hook)));
            wr(new File(dir, "picked.json"), new JSONObject().put("candidates", raw).put("picked", out).toString(1));
        }
    }
}
