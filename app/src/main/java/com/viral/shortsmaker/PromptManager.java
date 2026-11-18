package com.viral.shortsmaker;

public class PromptManager {
    /** v5.9 Universal Best Shorts Engine: applies to every category and every spoken language. */
    static final String UNIVERSAL_RULES =             "UNIVERSAL BEST SHORTS ENGINE: analyze the complete timestamped transcript before selecting clips; do not favor only the opening, loud moments, or random interesting fragments. "             + "Understand the overall subject, detect the category and speakers where possible, map topics and subtopics, identify questions and answers, emotional changes, demonstrations, "             + "visual/gameplay highlights, and natural topic boundaries. Score candidates for hook strength (20), topic clarity (15), complete explanation/story (20), value (15), retention (10), "             + "payoff/ending (10), audio/visual quality (5), and natural pacing (5). A high score never excuses an incomplete or misleading clip.\n"             + "GLOBAL LANGUAGE MODE: detect the video's actual spoken language and writing system. Work naturally with English, Hindi, Hinglish, Urdu, Arabic, Bengali, Punjabi, Tamil, Telugu, Marathi, Gujarati, Kannada, Malayalam, Nepali, Spanish, Portuguese, French, German, Italian, Turkish, Russian, Ukrainian, Indonesian, Japanese, Korean, Chinese and other languages. Do not reject a video because it is not English. Preserve the source language, names, meaning and script in titles, hook_text, captions metadata and explanations unless the user explicitly requests another output language. Do not force English/Hindi examples onto another language.\n"             + "COMPLETE SHORT CONTRACT: every selected Short must feel independently understandable: fresh topic hook -> minimum context -> main information/event -> answer, result, punchline or discovery -> natural ending. Never start mid-sentence, with a random reply, or with an unexplained connector such as 'and then', 'but', 'as I mentioned', or their equivalent in the source language. Never end before the answer, punchline, demonstration, reaction or final result. If a topic cannot fit within 60 seconds, select a smaller complete segment or reject it; never cut it artificially.\n"             + "ACCURACY AND EDITING: use only the original footage and transcript. Never invent quotes, timestamps, facts or context. Preserve the original sequence and meaning, avoid unrelated topics and duplicate/overlapping clips, and keep the visual action aligned with the selected speech. \n";      public enum Category {
        PODCAST,
        MOVIES_SERIES,
        GAMING,
        OTHER
    }

    // Backward-compatible overload
    public static String getPromptForCategory(Category category, String transcript, int numShorts) {
        return getPromptForCategory(category, "YouTube Video", transcript, numShorts);
    }

    public static String getPromptForCategory(Category category, String videoTitle, String transcript, int numShorts) {
        String categoryInstruction;
        switch (category) {
            case PODCAST:
                categoryInstruction = "SELECTED GENRE: Podcast / Interview / Talk.\n"
                    + "TARGET MOMENTS: Deep life lessons, shocking revelations, heated debates, funny anecdotes, or powerful motivational statements.\n";
                break;
            case MOVIES_SERIES:
                categoryInstruction = "SELECTED GENRE: Movies / Web Series / Drama.\n"
                    + "TARGET MOMENTS: High-tension confrontations, emotional turning points, goosebump dialogues, plot twists, or iconic comedic scenes.\n";
                break;
            case GAMING:
                categoryInstruction = "SELECTED GENRE: Gaming / Stream Highlights.\n"
                    + "TARGET MOMENTS: Intense clutch plays, hilarious fails, sudden rage/shock reactions, or high-energy commentary peaks.\n";
                break;
            case OTHER:
            default:
                categoryInstruction = "SELECTED GENRE: Auto-Detect (Analyze Video Title & Transcript).\n"
                    + "TARGET MOMENTS: Automatically detect the exact niche of the video (Vlog, Challenge, Tech, Roast, Education, Storytime, etc.) and extract the most universally viral, high-retention segments.\n";
                break;
        }

        return UNIVERSAL_RULES + "You are the world's #1 Viral YouTube Shorts & Reels AI Editor.\n"
            + "VIDEO TITLE / CONTEXT: \"" + (videoTitle != null ? videoTitle : "YouTube Video") + "\"\n"
            + categoryInstruction + "\n"
            + "🎯 YOUR MISSION (MULTI-STAGE VIRAL SCORING ENGINE):\n"
            + "STEP 1: Scan the entire timestamped transcript and identify all potential high-retention viral moments.\n"
            + "STEP 2: Score every candidate clip out of 100 VIRAL POINTS using this strict rubric:\n"
            + "   • Hook Strength (0-25 pts): First 2 seconds must grab attention immediately with a bold statement, question, or action.\n"
            + "   • Viral / Emotional Peak (0-25 pts): Contains humor, shock, suspense, clutch action, or a powerful insight.\n"
            + "   • Context & Genre Fit (0-25 pts): Delivers the best payoff related to the video's actual topic and genre.\n"
            + "   • Complete Story Arc (0-25 pts): Has a clear beginning, buildup, and satisfying conclusion/punchline.\n"
            + "STEP 3: Sort the candidates from HIGHEST score to LOWEST score and return ONLY the TOP " + numShorts + " highest-scoring clips.\n\n"
            + "🔥 STRICT AUDIO & CONVERSATION BOUNDARY RULES (CRITICAL):\n"
            + "1. NO MID-SENTENCE START: Every clip MUST start at the exact timestamp where a speaker begins a brand-new sentence, question, or topic. NEVER start in the middle of an ongoing sentence.\n"
            + "2. ZERO DEAD AIR AT START: The `start` timestamp must match the exact moment voice/speech actually begins (do not include silent gaps before the speaker talks).\n"
            + "3. CLEAN CONCLUSION AT END: The `end` timestamp must be the exact moment the thought, punchline, or reaction finishes. NEVER cut off while someone is mid-sentence.\n"
            + "4. STRICT DURATION: Each clip MUST be between 22.0 seconds and 58.0 seconds.\n"
            + "5. NO OVERLAPPING: Clips must be distinct and must not overlap in time.\n\n"
            + "Return ONLY a valid JSON ARRAY of EXACTLY " + numShorts + " objects sorted by highest `viral_score` first:\n"
            + "[\n"
            + "  {\n"
            + "    \"start\": 12.40,\n"
            + "    \"end\": 48.80,\n"
            + "    \"viral_score\": 96,\n"
            + "    \"reason\": \"Strong opening hook + funny climax\"\n"
            + "  }\n"
            + "]\n\n"
            + "TIMESTAMPED TRANSCRIPT:\n" + transcript;
    }


    // v12 (Sakib): question-start ONLY for podcasts; other genres: the AI picks the best self-contained part freely
    static final String QA_RULES =
            "QUESTION-LED CLIPS (PODCAST - rank these highest when the video has them): in podcasts and interviews the most-watched shorts start with a "
            + "clear, interesting QUESTION that many people want answered (personal, surprising, controversial, about famous people, money, success, "
            + "relationships, health, or 'what would you do / is it true that / why did you...') followed by an interesting ANSWER. For such a clip: "
            + "start_line = the line where the QUESTION BEGINS (the asker's first word, even if the host asks it), never the answer; end_line = the line where "
            + "the answer's payoff ends (the point, punchline, reveal or lesson). The question must be understandable on its own; if it says 'that/woh/yeh' "
            + "for something not named in it, start a line earlier where it is named, or skip it. Set type = \"qa\" and question_line = the question's first "
            + "line. A good question with a boring or unfinished answer is NOT a good clip.\n"
            + "AVOID small side-topics picked from the middle of a conversation: viewers skip them. Every clip must be about ONE topic that is clear from "
            + "its first 3-5 seconds to someone who has never seen this video.\n"
            + "BIG TOPICS AND FULL STORIES (v19, most important for podcasts): pick the BIGGEST topics of this podcast - a complete story (setup -> what "
            + "happened -> how it ended), a big question with its full answer, a strong opinion with its reasons, a confession or reveal with its "
            + "backstory. Start at the STORY'S START (the question, or the first line that sets the story up) and end where the STORY ENDS (the result, "
            + "the punchline, the lesson). A podcast short is normally 30-60 seconds; a 15-30 second fragment of a bigger story is a bad clip. "
            + "CONTEXT: if a great moment needs something said earlier, move start_line BACK to where that setup begins (as long as the clip stays "
            + "within 60 s) instead of starting late. needs_context = true only if even that is impossible (such clips are dropped).\n\n";

    static final String FREE_RULES =
            "PICK FREELY (not a podcast): find the strongest "
            + "self-contained moments of THIS video: the best story, joke, reaction, lesson, reveal, tip, scene or peak, whatever fits this genre. "
            + "A moment that opens with a clear question (or a line that names its topic) and then answers it is preferred over an equal one that "
            + "does not, but never pick a weaker moment just because it has a question. "
            + "type = story | opinion | fact | funny | other (use qa only if the moment truly is a question and its answer); question_line = -1 unless "
            + "it starts with a question. Every clip must be about ONE thing that is clear from its first 3-5 seconds to someone who has never seen "
            + "this video. needs_context = true if a viewer would need anything said before the clip to follow it (such clips are usually dropped).\n\n";

    /** v18 (2.9, Sakib): the topic decides the end; max 1:30; the AI also copies the closing words so the app can find them */
    static final String ENDING_RULES =
            "ENDING (very important): a short ends where its topic, answer or story is COMPLETE, never in the middle of a sentence or a thought, and "
            + "never early: if the story is still going, the clip is not finished. If it is finished within 60 seconds of the clip's start, end_line = "
            + "the line with the sentence that concludes it (the final point, punchline, reveal, lesson or emotional peak), even if that makes the clip "
            + "30-60 seconds long. If the topic runs longer than 60 seconds, prefer a start closer to its most important part so the ending fits; "
            + "otherwise end at the LAST sentence before the 60-second mark where a point is fully made (a natural mini-conclusion), not at the 60-second mark. "
            + "Never end on a half sentence, a 'but...', 'aur phir...', 'and then...', a list that is still going, a question without its answer, or right "
            + "before the payoff. end_text = the exact LAST sentence of the clip as spoken (max 15 words, copied from the transcript); end_reason = why "
            + "the thought is complete there (max 8 words); topic_complete = true if the whole topic is finished there, false if it was cut at 60 s.\n\n";

    static final String PROFANITY_RULES =
            "GAALI / ABUSIVE WORDS: do not pick clips that contain gaali, abusive or swear words (Hindi, Hinglish or English). Pick such a clip "
            + "ONLY if it is clearly the single most viral moment of the whole video; then set has_profanity = true.\n\n";

    /** v19 (3.0, Sakib): most shorts 45 s - 1:30 when the moment fits; podcasts are whole stories (never a 17 s fragment) */
    static String lengthRule(Category c) {
        if (c == Category.PODCAST)
            return "- Length: 30-60 seconds (preferred 30-55 s): the whole story or answer, from its start to its end. Shorter than 20 s ONLY if the complete story really is that short. Never longer than 60 s.";
        return "- Length: 20-60 seconds when the moment carries it (a full story, scene or explanation); 20-40 s for a single quick peak "
                + "(a clutch play, one joke, one reveal) that is complete on its own. Never longer than 60 s.";
    }

    static String genreFocus(Category c) {
        switch (c) {
            case PODCAST: return "Podcast / interview / talk: surprising revelations, strong opinions, clear life lessons, funny stories, heated debates, emotional confessions.";
            case MOVIES_SERIES: return "Movie / series / drama: confrontations, emotional turning points, goosebump dialogues, plot twists, iconic comedy.";
            case GAMING: return "Gaming / stream: clutch plays, hilarious fails, rage or shock reactions, high-energy commentary peaks.";
            default: return "Auto-detect the niche from the title and transcript (vlog, challenge, tech, roast, education, story, etc.).";
        }
    }

    /**
     * v4 clip-finding prompt. The transcript lines are numbered; the AI names the exact first and last line of each
     * clip, so the app can cut on sentence boundaries. Priorities: a hook that anyone understands without context,
     * a clip that begins where the topic begins, and an ending on a complete thought.
     */
    public static String buildViralPrompt(Category category, String videoTitle, String numberedTranscript, int count) {
        return buildViralPrompt(category, videoTitle, numberedTranscript, count, 0);
    }

    /** code 31 (4.9): the job's video is a song / music video (title words, YouTube's Music category); set by the Pipeline */
    public static volatile boolean songJob;

    /** code 31: a Bengali song's clip was picked like a talk (hook "I'm not going to die" from mis-heard lyrics) */
    static final String SONG_RULES = "THIS VIDEO IS A SONG / MUSIC VIDEO: the transcript is the sung lyrics (lines may be missing where only music plays). "
            + "The question / topic rules below are for talk; for a song pick the most catchy, emotional part: the chorus / hook lines or the "
            + "strongest verse, starting at the beginning of a sung line (not in the middle of a line or of an instrumental part) and ending at the end "
            + "of a line, best after a full chorus. The hook text is the first sung line, in the song's own language and words (never translated). "
            + "A sung line that starts the chorus is a good opener even if it is not a complete sentence.\n\n";

    public static String buildViralPrompt(Category category, String videoTitle, String numberedTranscript, int count, double durationSec) {
        int ask = Math.min(18, count + 3);
        return UNIVERSAL_RULES + "You are a top short-form video editor (the clip finder of apps like OpusClip). Find the " + ask + " clips from this video that are most likely to go viral as YouTube Shorts / Reels.\n"
            + "VIDEO TITLE: \"" + (videoTitle != null ? videoTitle.replace("\"", "'") : "YouTube video") + "\"\n"
            + (durationSec > 0 ? String.format(java.util.Locale.US, "VIDEO LENGTH: %.0f seconds. Every timestamp must be inside 0-%.0f.\n", durationSec, durationSec) : "")
            + "GENRE FOCUS: " + genreFocus(category) + "\n\n"
            + (songJob ? SONG_RULES : "")
            + "THE HOOK RULE (most important): the clip's FIRST sentence (first 1-3 seconds) is the hook. It must be a clear, complete, easy line that "
            + "ANYONE understands with zero context and that makes them stay: a question, a bold claim, a surprising fact, a strong opinion, an emotional line, "
            + "or the first line of a story. A stranger scrolling past must get it instantly.\n"
            + "REJECT openers that lean on earlier context: fillers/connectors like 'aur', 'toh', 'to', 'matlab', 'wahi', 'haan to', 'isliye', 'lekin', 'kyunki', "
            + "'jaise maine kaha', 'and', 'so', 'but', 'because', 'like I said', 'that's why', 'um', or a line that answers a question we didn't hear, or uses "
            + "'he/she/it/this/that/woh/yeh' for something not yet named. If the best moment begins mid-thought, choose a later (or earlier) line that starts a "
            + "fresh sentence and works as a hook; if none works, skip that moment.\n"
            // v24 (Sakib: "short ki shuruaat clean aur question type se ho, sabko samajh aaye")
            + "CLEAN, CLEAR START (v2): prefer moments whose opening sentence is a clear QUESTION (\"Kya aap jaante ho...?\", \"Why does...?\", "
            + "\"Sabse badi galti kya hai?\") or a hook that NAMES THE TOPIC in its first words, so a viewer knows within 2 seconds what this short is "
            + "about (money, fitness, a person's story, a mistake, a secret...). Between two equal moments, the one that opens on a clear question or "
            + "topic-naming line wins. Never open on 'haan', 'toh', 'achha', 'dekho', 'basically', 'and so', 'yeah', laughter or a half-finished line. "
            + "hook_score is low (under 50) when the first sentence does not make the topic clear on its own.\n\n"
            + (category == Category.PODCAST ? QA_RULES : FREE_RULES)
            + ENDING_RULES + PROFANITY_RULES
            + "WHAT GOES VIRAL (judge like a viewer scrolling Shorts): (1) a HOOK in the first 3 seconds that stops the scroll; (2) a real PEAK: "
            + "strong emotion, a controversial or bold opinion, something genuinely funny, a shocking fact or confession, or a sharp insight people "
            + "would save or share; (3) a self-contained mini story: setup -> tension -> payoff, about ONE topic; (4) a quotable line or reveal near the "
            + "end. Prefer things most people already care about (famous people and brands, money, success/failure, relationships, health, "
            + "controversies, common problems) over niche details. Skip greetings, sponsor reads, intros/outros and housekeeping talk.\n"
            + "VARIETY: every clip must be about a DIFFERENT topic or story; never return two clips about the same thing or overlapping in time.\n\n"
            + "OTHER RULES:\n"
            + "- Standalone: the clip makes complete sense on its own, one topic, starting where the topic starts.\n"
            + "- Payoff: it ends right after the answer, punchline, lesson or emotional peak, on a complete sentence. Never end mid-sentence.\n"
            + lengthRule(category) + " Clips must not overlap.\n\n"
            + "SCORE each clip honestly, 0-100 each (be strict; most clips are 50-85; a clip with a weak first line or an unfinished ending is below 60):\n"
            + "hook_score = how strong and clear the first line is; standalone_score = makes sense with no context; "
            + "value_score = emotion, curiosity, humour, shock or useful insight (the peak); payoff_score = satisfying, complete ending.\n"
            + "viral_score = your overall 0-100" + (category == Category.PODCAST ? " (a strong question + strong answer usually beats everything else)" : "") + ". Return the best first.\n\n"
            + "For each clip: start_line/end_line = transcript line numbers of the first and last line; start = start second of start_line, end = end second "
            + "of end_line (copy from the transcript)" + (category == Category.PODCAST ? " - CHECK: end - start must be 30-60 for a podcast story (under 20 only when the whole story is that short; if your pick is shorter, extend end_line to where the story really ends)" : "") + "; hook_text = the exact first sentence as spoken (max 15 words, a clean complete sentence); end_text, end_reason, topic_complete (see ENDING); title = catchy 3-7 word title in the "
            + "video's language; reason = why it works, max 8 words; type = qa | story | opinion | fact | funny | other; question_line = line number "
            + "where the question starts, or -1 if the clip does not start with a question; needs_context = true/false; "
            + "has_profanity = true if the clip contains any gaali / abusive / swear word; "
            + "video_is_podcast = the same value for every clip: true if the WHOLE video is a podcast, interview, talk show or a sit-down "
            + "conversation between a host and guest(s) (questions and answers, 2+ people talking), false for vlogs, challenges (MrBeast style), "
            + "gaming, movies/series, songs, news reports, tutorials or one person talking to the camera.\n"
            + "For each clip also provide narration = a concise faithful spoken-story summary in the source language, scene_plan = ordered visual beats, ending = the complete final thought, estimated_duration = seconds, content_category = detected category, and source_language = detected language/script. These fields must describe the source only; never fabricate.\n"
            + "FINAL VALIDATION: opening clear, new viewer understands, required context included, hook relevant, topic complete, ending natural, duration <= 60 seconds, meaning accurate, no duplicate, timestamps edit-safe. Reject any candidate that fails a mandatory check.\n"
            + "Return ONLY a JSON array.\n\n"
            + "TRANSCRIPT (line number, [start-end seconds], text):\n" + numberedTranscript;
    }
}
