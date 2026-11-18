package com.viral.shortsmaker.core;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * code 32 (5.0): file reading that works on every Android Klipora installs on. java.nio.file (Files.readAllBytes,
 * File.toPath) only exists from Android 8 (API 26); on Android 7 / 7.1 (minSdk 24) those calls throw
 * NoSuchMethodError / NoClassDefFoundError, an Error that the pipeline's "catch (Exception)" does not catch, so every
 * job died at the audio-alignment step (clip endings / caption timing) and Gemini transcription could never run.
 */
public final class IoUtil {
    private IoUtil() {}

    /** the whole file; IOException when it can't be read (never an Error on old Android) */
    public static byte[] readAll(File f) throws IOException {
        long len = f.length();
        if (len > Integer.MAX_VALUE - 16) throw new IOException("File too big to read into memory: " + f.getName());
        try (InputStream in = new FileInputStream(f)) {
            ByteArrayOutputStream out = new ByteArrayOutputStream(len > 0 ? (int) len : 8192);
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
