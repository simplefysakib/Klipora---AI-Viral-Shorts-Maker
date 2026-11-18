package com.viral.shortsmaker.core;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * v14: is this video a podcast / interview? Decides AI face tracking and the question-start rule even when the user
 * did not pick the Podcast genre. Sources: the user's pick, the clip-picker AI's own vote (asked in the same request,
 * so it costs no extra time), and a local check of the title / YouTube metadata and the transcript (question density,
 * length). Pure Java, unit tested.
 */
public final class PodcastDetector {
    private PodcastDetector() {}

    public static final class Result {
        public boolean podcast;
        public String why = "";
        public int localScore;
    }

    static final Pattern POD = Pattern.compile("(?i)(podcast|पॉडकास्ट|interview|इंटरव्यू|talk ?show|conversation|in conversation|baat ?cheet|बातचीत|"
            + "\\bft\\.?\\s|\\bfeat\\.?\\s|\\bep(isode)?\\.?\\s*\\d+|#\\s?\\d{2,}|\\bshow\\b|the ranveer|\\btrs\\b|\\bq ?& ?a\\b|\\bama\\b)");
    static final Pattern NOT = Pattern.compile("(?i)(challenge|vlog|gameplay|walkthrough|trailer|teaser|official (music )?video|lyric|song|full movie|"
            + "#shorts|\\bprank\\b|unboxing|recipe|tutorial|minecraft|\\bgta\\b|bgmi|free ?fire|highlights|\\bnews\\b)");
    static final Pattern Q = Pattern.compile("(?i)(\\?|\\b(kya|kyun|kyon|kaise|kaun|kab|kahan|kitna|what|why|how|who|when|where|do you|did you|have you)\\b|क्या|क्यों|कैसे|कौन|कब|कहाँ|कितना)");

    /** local evidence only (title / metadata / transcript); higher = more podcast-like */
    public static int localScore(String title, String meta, Transcript t, double duration, StringBuilder why) {
        int s = 0;
        String tm = ((title == null ? "" : title) + " " + (meta == null ? "" : meta));
        if (POD.matcher(tm).find()) { s += 3; why.append("title"); }
        if (NOT.matcher(tm).find()) { s -= 3; if (why.length() > 0) why.append(", "); why.append("title says not a talk"); }
        if (t != null && t.segments.size() >= 20) {
            int q = 0;
            for (Transcript.Segment g : t.segments) if (Q.matcher(g.text).find()) q++;
            double d = q / (double) t.segments.size();
            if (d >= 0.12) { s += d >= 0.2 ? 2 : 1; if (why.length() > 0) why.append(", "); why.append(String.format(Locale.US, "transcript %.0f%% questions", d * 100)); }
        }
        if (duration >= 20 * 60) { s += 1; if (why.length() > 0) why.append(", "); why.append("long video"); }
        return s;
    }

    public static Result decide(boolean userPicked, String title, String meta, Transcript t, double duration, List<AiClient.Candidate> cands) {
        Result r = new Result();
        StringBuilder why = new StringBuilder();
        r.localScore = localScore(title, meta, t, duration, why);
        if (userPicked) { r.podcast = true; r.why = "you picked Podcast"; return r; }
        int yes = 0, no = 0;
        if (cands != null) for (AiClient.Candidate c : cands) if (c.podcastVote != null) { if (c.podcastVote) yes++; else no++; }
        String local = why.length() == 0 ? "" : " + " + why;
        if (yes + no > 0) {
            boolean ai = yes > no;
            if (ai) { r.podcast = r.localScore > -3; r.why = r.podcast ? "AI said podcast" + local : "AI said podcast, but the title says otherwise" + local; }
            else { r.podcast = r.localScore >= 5; r.why = r.podcast ? "title + transcript, though the AI was unsure" + local : "AI read title + transcript" + local; }
        } else {
            r.podcast = r.localScore >= 3;
            r.why = (r.podcast ? "" : "no podcast signs") + (why.length() == 0 ? "" : (r.podcast ? "" : ": ") + why);
        }
        return r;
    }
}
