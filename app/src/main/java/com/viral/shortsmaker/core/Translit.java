package com.viral.shortsmaker.core;

/**
 * v8: offline Devanagari -> Hinglish (Roman) transliteration with Hindi schwa deletion ("करता" -> "karta",
 * "नहीं" -> "nahin", "समझ" -> "samajh"). Used when the AI can't transliterate (busy / free quota used up) and for any
 * word the AI returned in the wrong script, so Hinglish captions are always Roman and always 1:1 with the spoken words.
 */
public final class Translit {
    private Translit() {}

    private static String cons(char c) {
        switch (c) {
            case 'क': return "k"; case 'ख': return "kh"; case 'ग': return "g"; case 'घ': return "gh"; case 'ङ': return "n";
            case 'च': return "ch"; case 'छ': return "chh"; case 'ज': return "j"; case 'झ': return "jh"; case 'ञ': return "n";
            case 'ट': return "t"; case 'ठ': return "th"; case 'ड': return "d"; case 'ढ': return "dh"; case 'ण': return "n";
            case 'त': return "t"; case 'थ': return "th"; case 'द': return "d"; case 'ध': return "dh"; case 'न': return "n";
            case 'प': return "p"; case 'फ': return "ph"; case 'ब': return "b"; case 'भ': return "bh"; case 'म': return "m";
            case 'य': return "y"; case 'र': return "r"; case 'ल': return "l"; case 'ळ': return "l"; case 'व': return "v";
            case 'श': return "sh"; case 'ष': return "sh"; case 'स': return "s"; case 'ह': return "h";
            case '\u0958': return "q"; case '\u0959': return "kh"; case '\u095A': return "g"; case '\u095B': return "z";
            case '\u095C': return "r"; case '\u095D': return "rh"; case '\u095E': return "f"; case '\u095F': return "y";
            default: return null;
        }
    }

    private static String nukta(String base) {
        switch (base) {
            case "k": return "q"; case "kh": return "kh"; case "g": return "g"; case "j": return "z";
            case "d": return "r"; case "dh": return "rh"; case "ph": return "f"; default: return base;
        }
    }

    private static String indepVowel(char c) {
        switch (c) {
            case 'अ': return "a"; case 'आ': return "aa"; case 'इ': return "i"; case 'ई': return "ee"; case 'उ': return "u";
            case 'ऊ': return "oo"; case 'ऋ': return "ri"; case 'ए': return "e"; case 'ऐ': return "ai"; case 'ओ': return "o";
            case 'औ': return "au"; case 'ऑ': return "o"; case 'ऍ': return "e"; default: return null;
        }
    }

    private static String matra(char c) {
        switch (c) {
            case 'ा': return "aa"; case 'ि': return "i"; case 'ी': return "ee"; case 'ु': return "u"; case 'ू': return "oo";
            case 'ृ': return "ri"; case 'े': return "e"; case 'ै': return "ai"; case 'ो': return "o"; case 'ौ': return "au";
            case 'ॉ': return "o"; case 'ॅ': return "e"; default: return null;
        }
    }

    /** one unit of a word: a consonant (with its vowel) or a standalone vowel / sign / other char */
    private static final class U {
        String c;          // consonant letters ("" for a standalone vowel)
        String v;          // vowel: matra / independent vowel; null = inherent schwa; "" = virama (no vowel)
        String nasal = ""; // anusvara / chandrabindu / visarga after it
        String other;      // non-Devanagari / punctuation passthrough
    }

    public static boolean hasDevanagari(String s) {
        if (s == null) return false;
        for (int i = 0; i < s.length(); i++) { char c = s.charAt(i); if (c >= 0x0900 && c <= 0x097F) return true; }
        return false;
    }

    /** a whole caption word (may carry punctuation) */
    public static String word(String w) {
        if (w == null || !hasDevanagari(w)) return w;
        java.util.List<U> us = new java.util.ArrayList<>();
        for (int i = 0; i < w.length(); i++) {
            char ch = w.charAt(i);
            String k = cons(ch);
            if (k != null) { U u = new U(); u.c = k; us.add(u); continue; }
            U last = us.isEmpty() ? null : us.get(us.size() - 1);
            if (ch == '\u093C') { if (last != null && last.c != null) last.c = nukta(last.c); continue; }
            String m = matra(ch);
            if (m != null) { if (last != null && last.c != null && last.v == null) last.v = m; else { U u = new U(); u.c = ""; u.v = m; us.add(u); } continue; }
            if (ch == '\u094D') { if (last != null && last.c != null) last.v = ""; continue; }
            if (k == null && ch == 'ञ') { }
            if (ch == '\u0902' || ch == '\u0901') { if (last != null && last.other == null) last.nasal = "n"; else { U u = new U(); u.other = "n"; us.add(u); } continue; }
            if (ch == '\u0903') { if (last != null && last.other == null) last.nasal = "h"; continue; }
            String iv = indepVowel(ch);
            if (iv != null) {
                // "लिए" -> "liye", "चाहिए" -> "chahiye", "गए" -> "gaye": a glide between i / a and a following e
                if (last != null && last.other == null && iv.equals("e") && (last.v == null || "i".equals(last.v) || "ee".equals(last.v) || "aa".equals(last.v))) iv = "y" + iv;
                U u = new U(); u.c = ""; u.v = iv; us.add(u); continue;
            }
            U u = new U();
            if (ch == '\u0964' || ch == '\u0965') u.other = ".";
            else if (ch >= '\u0966' && ch <= '\u096F') u.other = String.valueOf((char) ('0' + (ch - '\u0966')));
            else if (ch == '\u200C' || ch == '\u200D') continue;
            else u.other = String.valueOf(ch);
            us.add(u);
        }
        // letters only (punctuation units excluded) for the schwa rule
        int n = us.size();
        int firstL = -1, lastL = -1;
        for (int i = 0; i < n; i++) if (us.get(i).other == null) { if (firstL < 0) firstL = i; lastL = i; }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            U u = us.get(i);
            if (u.other != null) { sb.append(u.other); continue; }
            sb.append(u.c);
            String v = u.v;
            if (v == null && !u.c.isEmpty()) {
                // inherent "a": dropped at the end of a word (unless the word is that one letter) and in the middle
                // where Hindi drops it: vowel before, a consonant WITH a vowel after (kar-ta, bol-ne, lag-ta)
                boolean keep = true;
                if (i == lastL) keep = i == firstL || (u.nasal.length() > 0);
                else if (i > firstL && i + 1 <= lastL) {
                    U nx = us.get(i + 1), pv = us.get(i - 1);
                    boolean prevVowel = pv.other == null && (!"".equals(pv.v)
                            || (i >= 2 && us.get(i - 2).other == null && !"".equals(us.get(i - 2).v)));   // after a cluster (bol-d-nes)
                    boolean nextHasVowel = nx.other == null && !nx.c.isEmpty() && !"".equals(nx.v) && (nx.v != null || i + 1 < lastL);
                    if (prevVowel && nextHasVowel && u.nasal.isEmpty()) keep = false;
                }
                v = keep ? "a" : "";
            }
            if (v != null) {
                boolean end = i == lastL;
                if (v.equals("ee") && end) v = "i";
                else if (v.equals("oo") && end && u.nasal.isEmpty()) v = "u";
                else if (v.equals("e") && end && !u.nasal.isEmpty()) v = "ei";      // "में" -> "mein", "बातें" -> "baatein"
                else if (v.equals("aa") && !u.c.isEmpty()) {
                    // "baat", "kaam", "aap" keep the long a in a closed syllable; "karta", "jata" write it short
                    boolean closed = i < lastL && us.get(i + 1).other == null && !us.get(i + 1).c.isEmpty()
                            && (i + 1 == lastL ? us.get(i + 1).v == null : "".equals(us.get(i + 1).v));
                    if (!closed && u.nasal.isEmpty()) v = "a";
                }
                sb.append(v);
            }
            if (!u.nasal.isEmpty()) {
                String nx = "";
                for (int j = i + 1; j < n; j++) if (us.get(j).other == null && !us.get(j).c.isEmpty()) { nx = us.get(j).c; break; }
                sb.append(u.nasal.equals("n") && (nx.startsWith("p") || nx.startsWith("b") || nx.startsWith("m")) ? "m" : u.nasal);
            }
        }
        return sb.toString().replace("jn", "gy");
    }

    public static String text(String s) {
        if (s == null) return null;
        StringBuilder sb = new StringBuilder();
        for (String w : s.split(" ", -1)) { if (sb.length() > 0) sb.append(' '); sb.append(word(w)); }
        return sb.toString();
    }
}
