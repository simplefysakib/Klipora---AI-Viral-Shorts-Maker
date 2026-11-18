package com.viral.shortsmaker.core;

/**
 * v21 (4.0) rewarded-ad preloading rules (pure, unit tested). Unity's guidance: load the ad before the user needs it so
 * every opportunity is filled. 3.1 asked for one ad at start-up and never again after a failed request, so a tap after
 * a no-fill or a network blip always said "Ad not available". Now a failed load is retried with a growing pause, an ad
 * is loaded again right after each show, and a tap while the ad is still loading waits for it (up to WAIT_MS) instead of
 * failing. The number of ads shown never changes: only one ad per tap, only when the user asks for it.
 */
public final class AdRetry {
    private AdRetry() {}

    /** how long a tap waits for an ad that is still loading */
    public static final long WAIT_MS = 15_000;

    /** pause before load attempt n+1 after n consecutive failures: 5 s, 10 s, 20 s, 40 s, then every 60 s */
    public static long delayMs(int failures) {
        if (failures <= 0) return 0;
        long d = 5_000L << Math.min(failures - 1, 4);
        return Math.min(d, 60_000L);
    }

    /** a show that failed because the loaded ad went stale is retried once with a fresh load (same single tap) */
    public static boolean retryShowAfter(String error) {
        if (error == null) return false;
        String e = error.toUpperCase(java.util.Locale.US);
        return e.contains("NOT_READY") || e.contains("NO_CONNECTION") || e.contains("INTERNAL") || e.contains("TIMEOUT");
    }
}
