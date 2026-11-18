package com.viral.shortsmaker.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * v12: abusive words (gaali) in Hindi (Devanagari), Hinglish (Roman) and English, for clip selection only.
 * Nothing is bleeped or masked (Sakib: "Gali sensord na ho"): a clip with gaali is just not picked, unless it is
 * clearly the most viral moment of the video. Whole-word matching on purpose: words that only look alike
 * ("chhod", "gandh", "kutta", "saali") are not on the list, so normal speech is never flagged.
 */
public final class Profanity {
    private Profanity() {}

    /** exact words (lower case, no punctuation) */
    static final Set<String> WORDS = new HashSet<>(Arrays.asList(
            // English
            "fuck", "fucks", "fucked", "fucker", "fuckers", "fucking", "fuckin", "motherfucker", "motherfuckers", "motherfucking",
            "shit", "shits", "shitty", "bullshit", "horseshit", "bitch", "bitches", "bastard", "bastards", "asshole", "assholes",
            "dickhead", "pussy", "cunt", "cunts", "slut", "whore", "wtf", "stfu",
            // Hinglish (Roman)
            "chutiya", "chutiye", "chutia", "chutiyapa", "chutiyap", "chutiyon", "chootiya", "choot",
            "madarchod", "maderchod", "madarchot", "madharchod", "behenchod", "bhenchod", "behnchod", "bhanchod", "benchod", "behenchodd",
            "bhosdi", "bhosdike", "bhosdiwale", "bhosdiwala", "bhosda", "bhosadi", "bhosadike", "bsdk",
            "gandu", "gaandu", "gaand", "lund", "lauda", "laude", "lavde", "lawda", "lode", "lodu", "loda",
            "randi", "randwa", "harami", "haramzada", "haramzadi", "haramkhor", "jhaant", "jhaatu", "jhant", "tatte",
            "chodu", "chudai", "chodna", "mkc", "bkl"
    ));
    /** Devanagari words */
    static final Set<String> DEVA = new HashSet<>(Arrays.asList(
            "\u091A\u0942\u0924\u093F\u092F\u093E", "\u091A\u0941\u0924\u093F\u092F\u093E", "\u091A\u0942\u0924\u093F\u092F\u0947", "\u091A\u0941\u0924\u093F\u092F\u0947", "\u091A\u0942\u0924",
            "\u092E\u093E\u0926\u0930\u091A\u094B\u0926", "\u092C\u0939\u0928\u091A\u094B\u0926", "\u092D\u0947\u0928\u091A\u094B\u0926", "\u092C\u0939\u0928\u091A\u094B\u0926",
            "\u092D\u094B\u0938\u0921\u093C\u0940", "\u092D\u094B\u0938\u0921\u0940", "\u092D\u094B\u0938\u0921\u093C\u0940\u0915\u0947", "\u092D\u094B\u0938\u0921\u0940\u0915\u0947", "\u092D\u094B\u0938\u0921\u093C\u093E",
            "\u0917\u093E\u0902\u0921\u0942", "\u0917\u093E\u0902\u0921\u0941", "\u0917\u093E\u0902\u0921", "\u0917\u093E\u0928\u094D\u0921\u0942",
            "\u0932\u094C\u0921\u093C\u093E", "\u0932\u094C\u0921\u093E", "\u0932\u094C\u0921\u093C\u0947", "\u0932\u0935\u0921\u093C\u093E", "\u0932\u094B\u0921\u093C\u0942", "\u0932\u0902\u0921",
            "\u0930\u0902\u0921\u0940", "\u0939\u0930\u093E\u092E\u0940", "\u0939\u0930\u093E\u092E\u091C\u093C\u093E\u0926\u093E", "\u0939\u0930\u093E\u092E\u091C\u093E\u0926\u093E",
            "\u091D\u093E\u091F", "\u091D\u093E\u091F\u0942", "\u091A\u0941\u0926\u093E\u0908"
    ));
    /** stems: any word starting with these is abusive (long, unambiguous roots only) */
    static final String[] STEMS = {"fuck", "motherf", "chutiy", "madarch", "behench", "bhench", "bhosd", "bhosad", "\u092E\u093E\u0926\u0930\u091A", "\u092D\u094B\u0938\u0921"};

    static String norm(String w) {
        StringBuilder b = new StringBuilder();
        for (char c : w.toLowerCase(Locale.ROOT).toCharArray()) {
            if (Character.isLetterOrDigit(c) || (c >= 0x0900 && c <= 0x097F && c != 0x0964 && c != 0x0965)) b.append(c);
        }
        return b.toString();
    }

    public static boolean isAbusive(String word) {
        String n = norm(word);
        if (n.isEmpty()) return false;
        if (WORDS.contains(n) || DEVA.contains(n)) return true;
        for (String s : STEMS) if (n.startsWith(s)) return true;
        return false;
    }

    /** the abusive words in a text, in order (empty = clean) */
    public static List<String> find(String text) {
        List<String> out = new ArrayList<>();
        if (text == null) return out;
        for (String w : text.split("[\\s\\-/]+")) if (isAbusive(w)) out.add(norm(w));
        return out;
    }

    /** all words spoken between start and end (seconds) */
    public static String textIn(Transcript tr, double start, double end) {
        StringBuilder b = new StringBuilder();
        if (tr == null) return "";
        if (tr.words != null && !tr.words.isEmpty()) {
            for (com.viral.shortsmaker.captions.Word w : tr.words) if (w.end > start + 0.05 && w.start < end - 0.05) b.append(w.text).append(' ');
        } else if (tr.segments != null) {
            for (Transcript.Segment s : tr.segments) if (s.end > start && s.start < end) b.append(s.text).append(' ');
        }
        return b.toString();
    }
}
