package com.viral.shortsmaker.core;

import android.content.ContentValues;
import android.content.Context;
import android.media.MediaScannerConnection;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;

/** Saves to Movies/Klipora on every Android version (v1 used RELATIVE_PATH, which only exists on Android 10+). */
public final class GallerySaver {
    private GallerySaver() {}

    public static boolean save(Context c, File src, String displayName) {
        // code 32 (5.0): a copy that fails part-way leaves nothing behind (4.9: an invisible pending MediaStore row keeping
        // its bytes on Android 10+, a broken video in Movies/Klipora on 7-9)
        Uri uri = null;
        File legacyOut = null;
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.Video.Media.DISPLAY_NAME, displayName);
                v.put(MediaStore.Video.Media.MIME_TYPE, "video/mp4");
                v.put(MediaStore.Video.Media.RELATIVE_PATH, Environment.DIRECTORY_MOVIES + "/Klipora");
                v.put(MediaStore.Video.Media.IS_PENDING, 1);
                uri = c.getContentResolver().insert(MediaStore.Video.Media.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
                try (OutputStream out = c.getContentResolver().openOutputStream(uri); InputStream in = new FileInputStream(src)) {
                    if (out == null) throw new java.io.IOException("no output stream");
                    copy(in, out);
                }
                v.clear();
                v.put(MediaStore.Video.Media.IS_PENDING, 0);
                c.getContentResolver().update(uri, v, null, null);
                return true;
            } else {
                File dir = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MOVIES), "Klipora");
                if (!dir.exists() && !dir.mkdirs()) return false;
                File out = new File(dir, displayName);
                legacyOut = out;
                try (InputStream in = new FileInputStream(src); OutputStream o = new FileOutputStream(out)) { copy(in, o); }
                MediaScannerConnection.scanFile(c, new String[]{out.getAbsolutePath()}, new String[]{"video/mp4"}, null);
                return true;
            }
        } catch (Exception e) {
            if (uri != null) { try { c.getContentResolver().delete(uri, null, null); } catch (Exception ignored) {} }
            if (legacyOut != null) legacyOut.delete();
            AppLog.e("Save to gallery failed: " + e.getMessage());
            return false;
        }
    }

    static void copy(InputStream in, OutputStream out) throws Exception {
        byte[] b = new byte[131072]; int r;
        while ((r = in.read(b)) > 0) out.write(b, 0, r);
    }
}
