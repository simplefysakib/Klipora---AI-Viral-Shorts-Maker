package com.viral.shortsmaker.core;

/** Human ETA text: "45 sec", "2 min", "1 hr 5 min". */
public final class MainEta {
    private MainEta() {}
    public static String fmt(int sec) {
        if (sec < 60) return Math.max(5, (sec + 4) / 5 * 5) + " sec";
        int m = (sec + 30) / 60;
        if (m < 60) return m + " min";
        return (m / 60) + " hr " + (m % 60) + " min";
    }
}
