package com.viral.shortsmaker.core;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * code 28 (4.6): multi-language audio tracks. Many videos carry dubbed audio (Hindi, Spanish, Portuguese ...) next to
 * the original. yt-dlp lists every track as its own audio-only format with "language" ("hi", "en-US"), a format_note
 * like "Hindi, medium" or "English (US) original (default), medium" and language_preference (10 = original, 5 = default,
 * -1 = other dub, -10 = audio description); the direct player call carries the same in "audioTrack".
 *
 * Pure logic (unit tested): which tracks a video has, which one a render uses, and whether a stream list has it.
 * The wanted language "" means the original track (before 4.6 the smallest / highest-bitrate audio of ANY language
 * could be picked from a multi-language list).
 */
public final class AudioTracks {
    private AudioTracks() {}

    /** one audio language of a video */
    public static final class Track {
        public final String code;     // yt-dlp language code ("hi", "en-US")
        public final String name;     // English name shown in the picker ("Hindi", "English (US)")
        public final boolean original, isDefault;
        Track(String code, String name, boolean original, boolean isDefault) { this.code = code; this.name = name; this.original = original; this.isDefault = isDefault; }
        /** picker label: "English (US) (original)" */
        public String label() { return original ? name + " (original)" : name; }
        @Override public String toString() { return code + "=" + label(); }
    }

    static boolean hasA(JSONObject f) { String a = f.optString("acodec", "none"); return !a.isEmpty() && !"none".equals(a); }
    static boolean hasV(JSONObject f) { String v = f.optString("vcodec", "none"); return !v.isEmpty() && !"none".equals(v); }

    /** the language code of an audio format ("" = none given); audio-description tracks keep their "-desc" suffix */
    public static String langOf(JSONObject f) {
        if (f == null) return "";
        String l = f.optString("language", "");
        if (l == null || l.equals("null")) return "";
        return l.trim();
    }

    static boolean descriptive(JSONObject f) {
        String l = langOf(f).toLowerCase(Locale.US);
        return l.endsWith("desc") || f.optInt("language_preference", 0) <= -10
                || f.optString("format_note", "").toLowerCase(Locale.US).contains("descriptive");
    }

    static boolean isOriginal(JSONObject f) {
        if (f.optInt("language_preference", 0) >= 10) return true;
        return f.optString("format_note", "").toLowerCase(Locale.US).contains("original");
    }

    static boolean isDefault(JSONObject f) {
        if (f.optInt("language_preference", 0) >= 5) return true;
        return f.optString("format_note", "").toLowerCase(Locale.US).contains("(default)");
    }

    /** "English (US) original (default), medium" -> "English (US)"; "" when the note names no language */
    static String nameFromNote(String note) {
        if (note == null) return "";
        // the HLS copies' notes are "<name in its own language> - dubbed" ("Deutsch - dubbed", "हिन्दी - dubbed"): no name
        if (note.matches("(?is).*\\s-\\s*(dubbed|original|default).*")) return "";
        String n = note;
        int c = n.indexOf(',');
        if (c >= 0) n = n.substring(0, c);
        n = n.replace("(default)", "").replace("(original)", "").replaceAll("(?i)\\b(original|dubbed)\\b", "")
                .replaceAll("\\s+-\\s*$", "").replaceAll("\\s+", " ").trim();
        if (n.endsWith("-")) n = n.substring(0, n.length() - 1).trim();
        // code 28: the HLS copies carry the name in the language itself ("हिन्दी - dubbed"); the UI is English only,
        // so such a note gives no name (the English name comes from another format or from the code)
        for (int i = 0; i < n.length(); i++) if (n.charAt(i) > 0x7e) return "";
        String low = n.toLowerCase(Locale.US);
        // a plain quality / codec note is not a language name
        if (low.isEmpty() || low.matches("(tiny|low|medium|high|ultralow|drc|default|audio only|\\d+k?b?p?s?|\\d+p\\d*)")) return "";
        return n;
    }

    /** English display name of a language code ("hi" -> "Hindi", "es-419" -> "Spanish (Latin America)") */
    public static String displayName(String code) {
        if (code == null || code.isEmpty()) return "Unknown";
        try {
            Locale l = Locale.forLanguageTag(code);
            String lang = l.getDisplayLanguage(Locale.ENGLISH);
            if (lang == null || lang.isEmpty() || lang.equalsIgnoreCase(code)) return code;
            String reg = l.getDisplayCountry(Locale.ENGLISH);
            if ((reg == null || reg.isEmpty()) && code.contains("-")) {
                String r = code.substring(code.indexOf('-') + 1);
                if (r.equals("419")) reg = "Latin America";
            }
            return reg == null || reg.isEmpty() ? lang : lang + " (" + reg + ")";
        } catch (Exception e) { return code; }
    }

    /**
     * every audio language in a stream list (audio-description tracks left out), original first, then default, then
     * by name. Fewer than 2 entries = a single-language video: no picker.
     */
    public static List<Track> list(JSONObject info) {
        JSONArray fs = info == null ? null : info.optJSONArray("formats");
        if (fs == null) return Collections.emptyList();
        Map<String, Track> m = new LinkedHashMap<>();
        java.util.Set<String> named = new java.util.HashSet<>();
        for (int i = 0; i < fs.length(); i++) {
            JSONObject f = fs.optJSONObject(i);
            if (f == null || !hasA(f) || hasV(f)) continue;
            String code = langOf(f);
            if (code.isEmpty() || descriptive(f)) continue;
            String key = code.toLowerCase(Locale.US);
            Track old = m.get(key);
            String noteName = nameFromNote(f.optString("format_note", ""));
            String name = noteName.isEmpty() ? displayName(code) : noteName;
            boolean orig = isOriginal(f), def = isDefault(f);
            if (old == null) { m.put(key, new Track(code, name, orig, def)); if (!noteName.isEmpty()) named.add(key); }
            else {
                // a name from yt-dlp's English note wins over the one made from the code; flags add up
                boolean better = !noteName.isEmpty() && !named.contains(key);
                if (better) named.add(key);
                if (better || (orig && !old.original) || (def && !old.isDefault))
                    m.put(key, new Track(old.code, better ? noteName : old.name, old.original || orig, old.isDefault || def));
            }
        }
        List<Track> l = new ArrayList<>(m.values());
        Collections.sort(l, (a, b) -> {
            if (a.original != b.original) return a.original ? -1 : 1;
            if (a.isDefault != b.isDefault) return a.isDefault ? -1 : 1;
            // Klipora's users are mostly Indian: Hindi right after the original, the rest A-Z
            boolean ah = a.code.toLowerCase(Locale.US).startsWith("hi"), bh = b.code.toLowerCase(Locale.US).startsWith("hi");
            if (ah != bh) return ah ? -1 : 1;
            return a.name.compareToIgnoreCase(b.name);
        });
        return l;
    }

    /** does this stream list carry an audio-only track in that language? ("" = always true) */
    public static boolean has(JSONObject info, String wanted) {
        if (wanted == null || wanted.isEmpty()) return true;
        for (Track t : list(info)) if (t.code.equalsIgnoreCase(wanted)) return true;
        return false;
    }

    /**
     * the language whose audio formats a render may use from this list: null = no filtering (one language, or no
     * language info at all - exactly the behaviour before 4.6); the wanted one when the list has it; otherwise the
     * original track (else the default one).
     */
    public static String keyFor(JSONObject info, String wanted) {
        List<Track> l = list(info);
        if (l.size() < 2) return null;
        if (wanted != null && !wanted.isEmpty()) for (Track t : l) if (t.code.equalsIgnoreCase(wanted)) return t.code;
        for (Track t : l) if (t.original) return t.code;
        for (Track t : l) if (t.isDefault) return t.code;
        return null;
    }

    /** is the key the video's own (original / default) track? (muxed video+audio streams only carry that one) */
    public static boolean keyIsDefault(JSONObject info, String key) {
        if (key == null) return true;
        List<Track> l = list(info);
        String orig = null, def = null;
        for (Track t : l) { if (t.original && orig == null) orig = t.code; if (t.isDefault && def == null) def = t.code; }
        String base = orig != null ? orig : def;
        return base == null || base.equalsIgnoreCase(key);
    }

    /**
     * may this format's audio be used under the key? Video-only formats always; audio-only formats when their language
     * is the key (or they carry no language); muxed formats only for the original / default track.
     */
    public static boolean allowed(JSONObject f, String key, boolean keyDefault) {
        if (key == null || f == null || !hasA(f)) return true;
        if (hasV(f)) return keyDefault;
        String l = langOf(f);
        if (l.isEmpty()) return keyDefault;
        return l.equalsIgnoreCase(key);
    }

    /**
     * yt-dlp format selector for the audio in that language: the wanted language first (m4a, then any), then yt-dlp's
     * own best audio (which already prefers the original track).
     */
    public static String ytdlpAudio(String wanted) {
        String base = "ba[ext=m4a]/ba[acodec^=mp4a]/ba";
        if (wanted == null || wanted.isEmpty() || !wanted.matches("[A-Za-z0-9-]{2,16}")) return base;
        return "ba[language=" + wanted + "][ext=m4a]/ba[language=" + wanted + "]/" + base;
    }

    /**
     * the default choice for the picker: the original track. (When the caption language asks for Hindi and the video is
     * not Hindi originally, the Hindi dub is offered as the default only if the caller asks for it.)
     */
    public static String defaultCode(List<Track> l) {
        if (l == null || l.isEmpty()) return "";
        for (Track t : l) if (t.original) return t.code;
        for (Track t : l) if (t.isDefault) return t.code;
        return l.get(0).code;
    }

    /** is code the video's original track (so "" may be stored = no special audio request)? */
    public static boolean isOriginalCode(List<Track> l, String code) {
        if (code == null || code.isEmpty()) return true;
        return code.equalsIgnoreCase(defaultCode(l));
    }
}
