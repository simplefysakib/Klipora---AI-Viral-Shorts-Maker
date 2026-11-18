package com.viral.shortsmaker.core;

import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * yt-dlp process ids. youtubedl-android refuses to start a process whose id is still registered
 * ("Process ID already exists"); v1 re-used one fixed id on every retry, so a retry after a failed or timed-out
 * attempt died with that error. Every attempt now gets a fresh id, the id is always destroyed/unregistered when the
 * attempt ends (success, error or timeout), and a clash is retried once with a new id.
 */
public final class ProcIds {
    private static final AtomicLong SEQ = new AtomicLong();

    public interface Exec<T> { T run(String id) throws Exception; }
    public interface Killer { void kill(String id); }

    private ProcIds() {}

    public static String next(String tag) {
        return tag + "-" + SEQ.incrementAndGet() + "-" + Long.toString(System.nanoTime() & 0xffffffL, 36);
    }

    public static <T> T run(String tag, Set<String> live, Killer killer, Exec<T> exec) throws Exception {
        Exception last = null;
        for (int i = 0; i < 2; i++) {
            String id = next(tag);
            if (live != null) live.add(id);
            try {
                return exec.run(id);
            } catch (Exception e) {
                last = e;
                if (i == 0 && String.valueOf(e.getMessage()).contains("Process ID already exists")) continue;
                throw e;
            } finally {
                if (live != null) live.remove(id);
                try { if (killer != null) killer.kill(id); } catch (Throwable ignored) {}
            }
        }
        throw last;
    }
}
