package com.viral.shortsmaker.core;

import android.content.Context;

import org.json.JSONObject;

import java.util.Iterator;
import java.util.Locale;

import okhttp3.Response;

/**
 * v7: honest quota view for Settings. Groq: the x-ratelimit-* headers of the latest Groq answer (real numbers from
 * the API). Gemini: Google exposes no remaining-quota endpoint for free keys, so this is the app's own count of
 * today's requests per model (Gemini's free day starts at midnight Pacific = 07:00 UTC), plus the last 429.
 */
public final class Quota {
    private Quota() {}

    static String day() {
        java.text.SimpleDateFormat f = new java.text.SimpleDateFormat("yyyy-MM-dd", Locale.US);
        f.setTimeZone(java.util.TimeZone.getTimeZone("America/Los_Angeles"));
        return f.format(new java.util.Date());
    }

    public static void groq(Context c, Response r) {
        if (c == null || r == null) return;
        try {
            JSONObject o = new JSONObject();
            for (String n : r.headers().names()) if (n.toLowerCase(Locale.US).startsWith("x-ratelimit-")) o.put(n.toLowerCase(Locale.US), r.header(n));
            if (o.length() == 0) return;
            o.put("_at", System.currentTimeMillis());
            Prefs.sp(c).edit().putString("quota_groq", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    public static void gemini(Context c, String model, int code) {
        if (c == null) return;
        try {
            JSONObject o = new JSONObject(Prefs.sp(c).getString("quota_gemini", "{}"));
            String d = day();
            if (!d.equals(o.optString("day"))) o = new JSONObject().put("day", d).put("counts", new JSONObject());
            JSONObject cn = o.optJSONObject("counts");
            if (cn == null) { cn = new JSONObject(); o.put("counts", cn); }
            cn.put(model, cn.optInt(model, 0) + 1);
            if (code == 429) o.put("last429", System.currentTimeMillis()).put("last429model", model);
            Prefs.sp(c).edit().putString("quota_gemini", o.toString()).apply();
        } catch (Exception ignored) {}
    }

    static String ago(long t) {
        long m = Math.max(0, (System.currentTimeMillis() - t) / 60000);
        return m < 1 ? "just now" : m < 60 ? m + " min ago" : (m / 60) + " h ago";
    }

    /** known free-tier daily request limits (Google's published free tier as of Oct 2026); -1 = unknown */
    static int freeDaily(String model) {
        String m = model.toLowerCase(Locale.US);
        if (m.contains("pro")) return 0;
        if (m.contains("flash-lite")) return 500;
        if (m.contains("flash")) return 20;
        return -1;
    }

    public static String summary(Context c) {
        StringBuilder b = new StringBuilder();
        try {
            JSONObject g = new JSONObject(Prefs.sp(c).getString("quota_groq", "{}"));
            b.append("Groq (live from the API");
            if (g.has("_at")) b.append(", updated ").append(ago(g.getLong("_at")));
            b.append(")\n");
            if (!g.has("_at")) b.append("  No Groq answer yet — tap Refresh\n");
            else {
                String[][] rows = {{"requests", "Requests"}, {"tokens", "Tokens"}, {"audio-seconds", "Audio seconds"}};
                boolean any = false;
                for (String[] rw : rows) {
                    String rem = g.optString("x-ratelimit-remaining-" + rw[0], ""), lim = g.optString("x-ratelimit-limit-" + rw[0], "");
                    if (rem.isEmpty() && lim.isEmpty()) continue;
                    any = true;
                    String reset = g.optString("x-ratelimit-reset-" + rw[0], "");
                    b.append("  ").append(rw[1]).append(": ").append(rem.isEmpty() ? "?" : rem).append(" left of ").append(lim.isEmpty() ? "?" : lim)
                            .append(reset.isEmpty() ? "" : " (resets in " + reset + ")").append('\n');
                }
                if (!any) for (Iterator<String> it = g.keys(); it.hasNext(); ) { String k = it.next(); if (!k.startsWith("_")) b.append("  ").append(k.replace("x-ratelimit-", "")).append(": ").append(g.optString(k)).append('\n'); }
            }
            JSONObject m = new JSONObject(Prefs.sp(c).getString("quota_gemini", "{}"));
            b.append("\nGemini (estimate — Google doesn't share exact remaining quota)\n");
            JSONObject cn = day().equals(m.optString("day")) ? m.optJSONObject("counts") : null;
            if (cn == null || cn.length() == 0) b.append("  No requests today\n");
            else for (Iterator<String> it = cn.keys(); it.hasNext(); ) {
                String k = it.next();
                int used = cn.optInt(k), lim = freeDaily(k);
                b.append("  ").append(k).append(": ").append(used).append(" today").append(lim > 0 ? " of ~" + lim + " free" : "").append('\n');
            }
            b.append("  Resets at midnight Pacific (12:30 PM IST)\n");
            if (m.has("last429")) b.append("  Last limit hit: ").append(ago(m.getLong("last429"))).append(" (").append(m.optString("last429model")).append(")\n");
        } catch (Exception e) { b.append("Quota info unavailable"); }
        return b.toString().trim();
    }

    /** tiny Groq call whose headers carry the current limits */
    public static void refreshGroq(Context c) throws Exception {
        String key = Prefs.groqKey(c);
        if (key == null || key.isEmpty()) throw new Exception("No Groq key set");
        okhttp3.Request req = new okhttp3.Request.Builder().url("https://api.groq.com/openai/v1/models").header("Authorization", "Bearer " + key).build();
        try (Response r = Net.fast().newCall(req).execute()) {
            groq(c, r);
            if (!r.isSuccessful()) throw new Exception("Groq answered HTTP " + r.code());
        }
    }
}
