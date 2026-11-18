package com.viral.shortsmaker.core;

/** Cooperative cancellation shared by the pipeline, yt-dlp and FFmpeg. */
public class CancelToken {
    public static class Cancelled extends RuntimeException {
        public Cancelled() { super("Cancelled"); }
    }

    public volatile boolean cancelled;
    public volatile String processId;
    /** yt-dlp processes running for this job (unique id per attempt) */
    public final java.util.Set<String> ytIds = java.util.concurrent.ConcurrentHashMap.newKeySet();
    public volatile long ffmpegSessionId = -1;
    /** every FFmpeg session running for this job (clips render in parallel) */
    public final java.util.Set<Long> sessions = java.util.concurrent.ConcurrentHashMap.newKeySet();

    /** code 32: AI uploads running for this job (a cancel ends them at once; 4.9 waited for the slowest upload, up to minutes) */
    public final java.util.Set<okhttp3.Call> calls = java.util.concurrent.ConcurrentHashMap.newKeySet();

    public void cancelCalls() { for (okhttp3.Call c : calls) { try { c.cancel(); } catch (Throwable ignored) {} } }

    public void check() { if (cancelled) throw new Cancelled(); }
}
