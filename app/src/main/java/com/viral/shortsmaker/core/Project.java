package com.viral.shortsmaker.core;

import android.content.Context;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.ArrayList;
import java.util.List;

/**
 * A project: source + picked clips. v3: every project lives in its own folder (files/projects/&lt;id&gt;) so past
 * projects stay available in the History tab. Persisted so results survive the app being killed.
 */
public class Project {
    public static class Clip {
        public int index;
        public double start, end;
        public int score;
        public transient boolean profane;   // v12: has gaali (kept only when it is the most viral moment)
        public String reason = "";
        /** v7: the opening line (hook) as spoken, the AI's short title, and the 4 sub-scores (-1 = unknown) */
        public String hook = "", title = "";
        public int hookScore = -1, standaloneScore = -1, valueScore = -1, payoffScore = -1;
        /** v18: why the clip ends where it ends (AI), and whether the whole topic finished inside it (null = unknown) */
        public transient String endReason = "";
        public transient Boolean topicComplete = null;
        /** v19: the shortest this clip may be (35 s for podcast stories, 20 s otherwise); 0 = the old 20 s floor */
        public transient double minLen = 0;
        public String outPath;
        public String captionStyle = "";   // name of the style burned in, "" = none
        public boolean saved;
        /** shown under the clip when something was degraded (e.g. captions could not be drawn) */
        public String note = "";
        /** v9: the same short without captions (captions are burned on it), and what it was rendered for */
        public String rawPath = "", rawKey = "";
        /** v9: the words spoken in this short, timed on its own timeline (JSON), their language and validity key */
        public String spokenJson = "", spokenLang = "", spokenKey = "";

        public double duration() { return end - start; }
        public File file() { return outPath == null ? null : new File(outPath); }
    }

    public String sourceType = "yt";      // "yt" | "local"
    public String url = "";
    public String localPath = "";         // copied local video (or cached full YouTube download)
    public String title = "";
    public double duration;
    public String thumbUrl = "";
    public String category = "OTHER";
    public String frame = "BLUR";
    /** v13: AI face tracking (projects made before 2.4 load as off = their 2.3 framing) */
    public boolean faceTrack = false;
    /** v18: this video already used its caption unlock (re-styling its shorts is free) */
    public boolean captionsPaid = false;
    /** code 30: the job burned captions in / the export quality it used (-1 = unknown): a continued job matches them */
    public boolean captionsWanted = false;
    public int quality = -1;
    /** v14 output format: PORTRAIT 9:16 (default, and every project made before 2.5), SQUARE 1:1, LANDSCAPE 16:9 */
    public String aspect = "PORTRAIT";
    /** v14: the video is a podcast/interview (picked by the user or detected from title + transcript) */
    public boolean podcast = false;
    public String podcastWhy = "";
    public long createdAt = System.currentTimeMillis();
    public String id = String.valueOf(createdAt);
    /** caption language of the transcript (whisper name) */
    public String language = "";
    /** code 28: audio language of a multi-language video (yt-dlp code, "" = original track) and its English name */
    public String audioLang = "", audioLangName = "";
    public final List<Clip> clips = new ArrayList<>();
    /**
     * code 32: the picked moments not rendered yet, as loaded from disk (loadFrom shows only finished shorts). They are
     * written back by save(): 4.9 dropped them when History renamed a stopped job's project or saved one of its shorts,
     * so Continue then found "2 of 2 done" and the other moments of the job were lost.
     */
    public final transient List<Clip> unfinished = new ArrayList<>();

    public boolean isLocal() { return "local".equals(sourceType); }

    public static File root(Context c) {
        File d = new File(c.getFilesDir(), "projects");
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public File dir(Context c) {
        File d = new File(root(c), id);
        if (!d.exists()) d.mkdirs();
        return d;
    }

    public File transcriptFile(Context c) { return new File(dir(c), "transcript.json"); }
    public File thumbFile(Context c) { return new File(dir(c), "thumb.jpg"); }

    public long sizeBytes(Context c) {
        long n = 0;
        File[] fs = dir(c).listFiles();
        if (fs != null) for (File f : fs) n += f.length();
        return n;
    }

    public JSONObject toJson() {
        JSONObject o = new JSONObject();
        try {
            o.put("sourceType", sourceType).put("url", url).put("localPath", localPath).put("title", title)
                    .put("duration", duration).put("thumbUrl", thumbUrl).put("category", category)
                    .put("frame", frame).put("faceTrack", faceTrack).put("captionsPaid", captionsPaid).put("captionsWanted", captionsWanted).put("quality", quality).put("aspect", aspect).put("podcast", podcast).put("podcastWhy", podcastWhy).put("createdAt", createdAt).put("id", id).put("language", language).put("audioLang", audioLang).put("audioLangName", audioLangName);
            JSONArray a = new JSONArray();
            List<Clip> all = new ArrayList<>(clips);
            for (Clip u : unfinished) { boolean dup = false; for (Clip c : clips) if (c.index == u.index) { dup = true; break; } if (!dup) all.add(u); }
            if (!unfinished.isEmpty()) java.util.Collections.sort(all, (x, y) -> Integer.compare(x.index, y.index));
            for (Clip c : all) {
                a.put(new JSONObject().put("index", c.index).put("start", c.start).put("end", c.end)
                        .put("score", c.score).put("reason", c.reason).put("outPath", c.outPath == null ? "" : c.outPath)
                        .put("captionStyle", c.captionStyle).put("saved", c.saved).put("note", c.note == null ? "" : c.note)
                        .put("hook", c.hook == null ? "" : c.hook).put("ctitle", c.title == null ? "" : c.title)
                        .put("sHook", c.hookScore).put("sAlone", c.standaloneScore).put("sValue", c.valueScore).put("sPayoff", c.payoffScore)
                        .put("rawPath", c.rawPath == null ? "" : c.rawPath).put("rawKey", c.rawKey == null ? "" : c.rawKey)
                        .put("spoken", c.spokenJson == null ? "" : c.spokenJson).put("spokenLang", c.spokenLang == null ? "" : c.spokenLang)
                        .put("spokenKey", c.spokenKey == null ? "" : c.spokenKey));
            }
            o.put("clips", a);
        } catch (Exception ignored) {}
        return o;
    }

    public static Project fromJson(JSONObject o) {
        Project p = new Project();
        p.sourceType = o.optString("sourceType", "yt");
        p.url = o.optString("url", "");
        p.localPath = o.optString("localPath", "");
        p.title = o.optString("title", "");
        p.duration = o.optDouble("duration", 0);
        p.thumbUrl = o.optString("thumbUrl", "");
        p.category = o.optString("category", "OTHER");
        p.frame = o.optString("frame", "BLUR");
        p.faceTrack = o.optBoolean("faceTrack", false);
        p.captionsPaid = o.optBoolean("captionsPaid", false);
        p.captionsWanted = o.optBoolean("captionsWanted", false);
        p.quality = o.optInt("quality", -1);
        p.aspect = o.optString("aspect", "PORTRAIT");
        p.podcast = o.optBoolean("podcast", "PODCAST".equals(p.category));
        p.podcastWhy = o.optString("podcastWhy", "");
        p.createdAt = o.optLong("createdAt", 0);
        p.id = o.optString("id", String.valueOf(p.createdAt));
        p.language = o.optString("language", "");
        p.audioLang = o.optString("audioLang", "");
        p.audioLangName = o.optString("audioLangName", "");
        JSONArray a = o.optJSONArray("clips");
        if (a != null) for (int i = 0; i < a.length(); i++) {
            JSONObject j = a.optJSONObject(i);
            if (j == null) continue;
            Clip c = new Clip();
            c.index = j.optInt("index", i);
            c.start = j.optDouble("start");
            c.end = j.optDouble("end");
            c.score = j.optInt("score", 90);
            c.reason = j.optString("reason", "");
            c.outPath = j.optString("outPath", "");
            c.captionStyle = j.optString("captionStyle", "");
            c.note = j.optString("note", "");
            c.saved = j.optBoolean("saved", false);
            c.hook = j.optString("hook", "");
            c.title = j.optString("ctitle", "");
            c.hookScore = j.optInt("sHook", -1); c.standaloneScore = j.optInt("sAlone", -1);
            c.valueScore = j.optInt("sValue", -1); c.payoffScore = j.optInt("sPayoff", -1);
            c.rawPath = j.optString("rawPath", ""); c.rawKey = j.optString("rawKey", "");
            c.spokenJson = j.optString("spoken", ""); c.spokenLang = j.optString("spokenLang", ""); c.spokenKey = j.optString("spokenKey", "");
            p.clips.add(c);
        }
        return p;
    }

    public synchronized void save(Context c) {
        // code 30: written to a temp file and renamed, so a phone that ends the process mid-write can't leave a broken
        // project.json (the project is the checkpoint a stopped job continues from, and the History entry)
        File d = dir(c), tmp = new File(d, "project.json.tmp"), dst = new File(d, "project.json");
        try (FileOutputStream fo = new FileOutputStream(tmp)) {
            fo.write(toJson().toString().getBytes("UTF-8"));
            fo.getFD().sync();
        } catch (Exception e) { AppLog.e("Could not save project: " + e.getMessage()); return; }
        if (!tmp.renameTo(dst)) { dst.delete(); if (!tmp.renameTo(dst)) AppLog.e("Could not save project"); }
    }

    /** marks this project as the one the Create tab shows */
    public void makeCurrent(Context c) { Prefs.sp(c).edit().putString("current_project", id).apply(); }

    public static Project loadFrom(File dir) {
        File f = new File(dir, "project.json");
        if (!f.exists()) return null;
        try {
            String s = readText(f);
            if (s == null) return null;
            Project p = fromJson(new JSONObject(s));
            p.id = dir.getName();
            List<Clip> alive = new ArrayList<>();
            for (Clip cl : p.clips) { if (cl.file() != null && cl.file().exists()) alive.add(cl); else p.unfinished.add(cl); }
            p.clips.clear();
            p.clips.addAll(alive);
            return p;
        } catch (Exception e) { return null; }
    }

    /** the current project (last opened / created) */
    public static Project load(Context c) {
        migrateLegacy(c);
        String id = Prefs.sp(c).getString("current_project", null);
        if (id == null) return null;
        File d = new File(root(c), id);
        return d.exists() ? loadFrom(d) : null;
    }

    /** every saved project, newest first (projects with no surviving shorts are skipped) */
    public static List<Project> listAll(Context c) {
        migrateLegacy(c);
        List<Project> out = new ArrayList<>();
        File[] ds = root(c).listFiles();
        if (ds != null) for (File d : ds) {
            if (!d.isDirectory()) continue;
            Project p = loadFrom(d);
            if (p != null && !p.clips.isEmpty()) out.add(p);
        }
        java.util.Collections.sort(out, (a, b) -> Long.compare(b.createdAt, a.createdAt));
        return out;
    }

    public void delete(Context c) {
        File d = new File(root(c), id);
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) f.delete();
        d.delete();
        if (id.equals(Prefs.sp(c).getString("current_project", ""))) Prefs.sp(c).edit().remove("current_project").apply();
    }

    /**
     * Source videos are big; only the newest {@code keep} projects keep theirs (needed to re-caption uploaded
     * videos). YouTube projects can always re-stream their source.
     */
    public static void pruneSources(Context c, int keep) {
        List<Project> all = listAll(c);
        for (int i = keep; i < all.size(); i++) {
            File d = new File(root(c), all.get(i).id);
            for (String n : new String[]{"source_local.mp4", "source_video.mp4", "source_audio.m4a"}) {
                File f = new File(d, n);
                if (f.exists()) f.delete();
            }
        }
    }

    /**
     * code 32 (5.0): folders of jobs that failed, were cancelled or crashed before any short was finished. They are hidden
     * from History (no short), so nobody could delete them, and each kept the whole video's speech audio plus 1080p source
     * clips (25-150 MB per attempt). Only folders older than an hour with no finished short go; the job's own folder and
     * the stopped job Continue can resume are kept.
     */
    public static int dropOrphans(Context c, java.util.Set<String> keep, long now) {
        File[] ds = root(c).listFiles();
        if (ds == null) return 0;
        int n = 0;
        for (File d : ds) {
            if (!d.isDirectory() || (keep != null && keep.contains(d.getName()))) continue;
            if (now - newest(d) < 3600_000L) continue;
            Project p = loadFrom(d);
            if (p != null && !p.clips.isEmpty()) continue;
            if (p == null && new File(d, "project.json").exists()) continue;   // unreadable right now: leave it
            deleteTree(d);
            n++;
        }
        return n;
    }

    private static long newest(File d) {
        long t = d.lastModified();
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) t = Math.max(t, f.lastModified());
        return t;
    }

    private static void deleteTree(File d) {
        File[] fs = d.listFiles();
        if (fs != null) for (File f : fs) { if (f.isDirectory()) deleteTree(f); else f.delete(); }
        d.delete();
    }

    /** v2 kept one project in files/project; move it into the history folder once */
    static synchronized void migrateLegacy(Context c) {
        File old = new File(c.getFilesDir(), "project");
        File oj = new File(old, "project.json");
        if (!oj.exists()) return;
        try {
            String s = readText(oj);
            Project p = s == null ? null : fromJson(new JSONObject(s));
            if (p == null) return;
            if (p.createdAt <= 0) p.createdAt = oj.lastModified();
            p.id = String.valueOf(p.createdAt);
            File nd = new File(root(c), p.id);
            nd.mkdirs();
            File[] fs = old.listFiles();
            if (fs != null) for (File f : fs) f.renameTo(new File(nd, f.getName()));
            String oldPrefix = old.getAbsolutePath(), newPrefix = nd.getAbsolutePath();
            for (Clip cl : p.clips) if (cl.outPath != null && cl.outPath.startsWith(oldPrefix)) cl.outPath = newPrefix + cl.outPath.substring(oldPrefix.length());
            if (p.localPath != null && p.localPath.startsWith(oldPrefix)) p.localPath = newPrefix + p.localPath.substring(oldPrefix.length());
            p.save(c);
            new File(nd, "project.json.old").delete();
            old.delete();
            if (Prefs.sp(c).getString("current_project", null) == null) p.makeCurrent(c);
        } catch (Exception e) { AppLog.e("History migration: " + e.getMessage()); }
    }

    public static String readText(File f) {
        try (FileInputStream in = new FileInputStream(f)) {
            byte[] b = new byte[(int) f.length()];
            int off = 0, r;
            while (off < b.length && (r = in.read(b, off, b.length - off)) > 0) off += r;
            return new String(b, "UTF-8");
        } catch (Exception e) { return null; }
    }

    public static void writeText(File f, String s) {
        try (FileOutputStream fo = new FileOutputStream(f)) { fo.write(s.getBytes("UTF-8")); } catch (Exception ignored) {}
    }
}
