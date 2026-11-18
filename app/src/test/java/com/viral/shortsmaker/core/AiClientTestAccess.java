package com.viral.shortsmaker.core;

/** test access to package-private helpers */
public final class AiClientTestAccess {
    public static long reset(String m) { return AiClient.dailyResetMs(m); }
    public static String model(String m) { return AiClient.quotaModel(m); }
}
