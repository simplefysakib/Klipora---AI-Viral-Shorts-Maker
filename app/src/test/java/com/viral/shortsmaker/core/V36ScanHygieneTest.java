package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import org.junit.Test;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;

/**
 * 5.4 store-scanner hygiene (Appteka scans every upload with ClamAV, APKiD and Quark Engine). What a scanner reads as
 * "dropper / spyware / packer" and what the app must never have, pinned so a later change cannot bring it back:
 * no install / all-files / package-list permissions, an update notice that only opens a link, no dynamic code loading in
 * the app's own code, readable resource paths, no debugger probe in the tamper check.
 */
public class V36ScanHygieneTest {
    static String read(String... candidates) throws Exception {
        for (String c : candidates) { File f = new File(c); if (f.exists()) return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8); }
        fail("not found: " + candidates[0]);
        return null;
    }

    static String manifest() throws Exception { return read("src/main/AndroidManifest.xml", "app/src/main/AndroidManifest.xml"); }

    @Test public void noDangerousPermissionsInTheAppsOwnManifest() throws Exception {
        String m = manifest();
        for (String p : new String[]{"REQUEST_INSTALL_PACKAGES", "MANAGE_EXTERNAL_STORAGE", "QUERY_ALL_PACKAGES", "READ_SMS", "RECEIVE_SMS", "SEND_SMS",
                "READ_CALL_LOG", "READ_CONTACTS", "ACCESS_FINE_LOCATION", "ACCESS_COARSE_LOCATION", "SYSTEM_ALERT_WINDOW", "BIND_ACCESSIBILITY_SERVICE",
                "READ_PHONE_STATE", "RECORD_AUDIO", "CAMERA", "BIND_DEVICE_ADMIN"})
            assertFalse(p, m.contains(p));
        assertTrue(m.contains("android:usesCleartextTraffic=\"false\""));
        assertFalse(m.contains("android:debuggable=\"true\""));
    }

    @Test public void theUpdateNoticeOnlyOpensALink() throws Exception {
        for (String f : new String[]{"UpdateCheck.java"}) {
            String s = read("src/main/java/com/viral/shortsmaker/core/" + f, "app/src/main/java/com/viral/shortsmaker/core/" + f);
            for (String bad : new String[]{"DownloadManager", "PackageInstaller", "vnd.android.package-archive", "REQUEST_INSTALL_PACKAGES", "ACTION_INSTALL_PACKAGE", "FileProvider"})
                assertFalse(f + " must not contain " + bad, s.contains(bad));
        }
    }

    @Test public void noDynamicCodeLoadingInTheAppsOwnCode() throws Exception {
        File root = new File("src/main/java");
        if (!root.exists()) root = new File("app/src/main/java");
        java.util.List<String> hits = new java.util.ArrayList<>();
        scan(root, hits);
        assertTrue(hits.toString(), hits.isEmpty());
    }

    static void scan(File d, java.util.List<String> hits) throws Exception {
        File[] fs = d.listFiles();
        if (fs == null) return;
        for (File f : fs) {
            if (f.isDirectory()) { scan(f, hits); continue; }
            if (!f.getName().endsWith(".java")) continue;
            String s = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            for (String bad : new String[]{"DexClassLoader", "InMemoryDexClassLoader", "BaseDexClassLoader", "PathClassLoader", "getInstalledPackages", "getInstalledApplications"})
                if (s.contains(bad)) hits.add(f.getName() + ":" + bad);
        }
    }

    @Test public void resourcePathsAreNotShortened() throws Exception {
        String g = read("../gradle.properties", "gradle.properties");
        assertTrue(g.contains("android.enableResourceOptimizations=false"));
    }

    @Test public void theTamperCheckKeepsSignatureAndDebuggableChecksButNoDebuggerProbe() throws Exception {
        String s = read("src/main/java/com/viral/shortsmaker/core/Integrity.java", "app/src/main/java/com/viral/shortsmaker/core/Integrity.java");
        assertFalse(s.contains("isDebuggerConnected()") && s.contains("Debug.isDebuggerConnected();"));
        assertFalse(s.replaceAll("//.*", "").replaceAll("(?s)/\\*.*?\\*/", "").contains("isDebuggerConnected"));
        assertTrue(s.contains("FLAG_DEBUGGABLE"));
        assertTrue(s.contains("certHashes(c)"));
        assertTrue(s.contains("005E19F4C9E5EC1E8BE36FF79287061D"));
    }
}
