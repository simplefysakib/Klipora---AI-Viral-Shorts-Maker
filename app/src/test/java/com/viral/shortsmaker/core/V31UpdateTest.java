package com.viral.shortsmaker.core;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;

import java.util.ArrayList;
import java.util.List;

/** code 31 (4.9): the "new version available" check: compare, 12 h throttle, 3-day snooze, silence on any failure. */
@RunWith(RobolectricTestRunner.class)
@Config(sdk = 34)
public class V31UpdateTest {
    Context ctx;
    static final long NOW = 1_800_000_000_000L, H = 3600_000L;

    @Before public void setUp() { ctx = ApplicationProvider.getApplicationContext(); Prefs.sp(ctx).edit().clear().commit(); }
    @After public void tearDown() { Prefs.sp(ctx).edit().clear().commit(); }

    static String json(int code, String name) {
        return "{\"versionCode\":" + code + ",\"versionName\":\"" + name + "\",\"url\":\"https://klipora.sites.tab.bot/\",\"notes\":\"Fixes\","
                + "\"apk\":{\"universal\":\"https://github.com/x/releases/latest/download/Klipora_universal.apk\",\"arm64\":\"https://github.com/x/releases/latest/download/Klipora_arm64.apk\"}}";
    }

    @Test public void versionCompare() {
        assertTrue(UpdateCheck.compareNames("4.9", "4.8") > 0);
        assertTrue(UpdateCheck.compareNames("v4.10", "4.9") > 0);
        assertTrue(UpdateCheck.compareNames("4.9.1", "4.9") > 0);
        assertEquals(0, UpdateCheck.compareNames("v4.9", "4.9.0"));
        assertTrue(UpdateCheck.compareNames("4.8", "4.9") < 0);
        UpdateCheck.Info i = new UpdateCheck.Info();
        i.code = 32; i.name = "4.9.1";
        assertTrue(UpdateCheck.newer(i, 31, "4.9"));
        i.code = 31;
        assertFalse("same build", UpdateCheck.newer(i, 31, "4.9"));
        i.code = 30;
        assertFalse("versionCode wins over the name", UpdateCheck.newer(i, 31, "4.9"));
        UpdateCheck.Info g = new UpdateCheck.Info(); g.name = "4.10";
        assertTrue("GitHub tag only: by name", UpdateCheck.newer(g, 31, "4.9"));
        g.name = "latest";
        assertFalse(UpdateCheck.newer(g, 31, "4.9"));
        assertFalse(UpdateCheck.newer(null, 31, "4.9"));
    }

    @Test public void throttleTwelveHours() {
        org.junit.Assume.assumeTrue("Premium edition never checks", UpdateCheck.enabled());
        assertTrue(UpdateCheck.due(NOW, 0));
        assertFalse(UpdateCheck.due(NOW, NOW - 11 * H));
        assertTrue(UpdateCheck.due(NOW, NOW - 12 * H));
        assertTrue("clock set back", UpdateCheck.due(NOW, NOW + 5 * H));
        List<String> asked = new ArrayList<>();
        UpdateCheck.Fetcher f = url -> { asked.add(url); return json(99, "9.9"); };
        assertNotNull(UpdateCheck.check(ctx, NOW, f));
        assertEquals(1, asked.size());
        UpdateCheck.check(ctx, NOW + 2 * H, f);
        assertEquals("no request inside 12 h", 1, asked.size());
        assertNotNull("the known newer build is still offered", UpdateCheck.toOffer(ctx, NOW + 2 * H));
        UpdateCheck.check(ctx, NOW + 13 * H, f);
        assertEquals(2, asked.size());
    }

    @Test public void laterHidesThatVersionThreeDays() {
        org.junit.Assume.assumeTrue("Premium edition never checks", UpdateCheck.enabled());
        UpdateCheck.Info i = UpdateCheck.check(ctx, NOW, url -> json(99, "9.9"));
        assertNotNull(i);
        UpdateCheck.later(ctx, i, NOW);
        assertNull(UpdateCheck.toOffer(ctx, NOW + 2 * 24 * H));
        assertNotNull("after 3 days", UpdateCheck.toOffer(ctx, NOW + 3 * 24 * H + 1));
        // a newer version than the snoozed one shows at once
        UpdateCheck.check(ctx, NOW + 13 * H, url -> json(100, "10.0"));
        assertNotNull(UpdateCheck.toOffer(ctx, NOW + 14 * H));
        assertNotNull("the error card knows a newer build exists even while snoozed", UpdateCheck.newerKnown(ctx));
    }

    @Test public void failuresAreSilentAndGithubIsTheFallback() {
        org.junit.Assume.assumeTrue("Premium edition never checks", UpdateCheck.enabled());
        assertNull(UpdateCheck.check(ctx, NOW, url -> { throw new java.io.IOException("offline"); }));
        assertNull(UpdateCheck.newerKnown(ctx));
        assertFalse("failed: tried again in an hour, not at every start", UpdateCheck.due(NOW + 30 * 60_000L, Prefs.sp(ctx).getLong(UpdateCheck.K_AT, 0)));
        assertTrue(UpdateCheck.due(NOW + H + 1, Prefs.sp(ctx).getLong(UpdateCheck.K_AT, 0)));
        // site down / broken json -> GitHub's latest release tag
        UpdateCheck.Info g = UpdateCheck.check(ctx, NOW + 2 * H, url -> url.equals(UpdateCheck.VERSION_URL) ? "<html>" : "{\"tag_name\":\"v99.0\"}");
        assertNotNull(g);
        assertEquals("99.0", g.name);
        assertEquals("built-in GitHub link", UpdateCheck.APK_ARM64, g.apkFor(true));
        assertEquals(UpdateCheck.APK_UNIVERSAL, g.apkFor(false));
        // garbage everywhere: nothing, no exception
        Prefs.sp(ctx).edit().clear().commit();
        assertNull(UpdateCheck.check(ctx, NOW, url -> "null"));
        // an old version.json (this build or older) offers nothing
        Prefs.sp(ctx).edit().clear().commit();
        assertNull(UpdateCheck.check(ctx, NOW, url -> json(com.viral.shortsmaker.BuildConfig.VERSION_CODE, com.viral.shortsmaker.BuildConfig.VERSION_NAME)));
    }

    @Test public void versionJsonLinks() throws Exception {
        UpdateCheck.Info i = UpdateCheck.parseVersionJson(json(31, "4.9"));
        assertEquals(31, i.code);
        assertEquals("https://github.com/x/releases/latest/download/Klipora_arm64.apk", i.apkFor(true));
        UpdateCheck.Info bad = UpdateCheck.parseVersionJson("{\"versionCode\":31,\"apk\":{\"universal\":\"http://evil\"}}");
        assertEquals("only https links are taken", UpdateCheck.APK_UNIVERSAL, bad.apkUniversal);
    }

    @Test public void premiumNeverChecks() {
        assertEquals(!Prefs.PREMIUM_EDITION, UpdateCheck.enabled());
        if (Prefs.PREMIUM_EDITION) {
            assertNull(UpdateCheck.check(ctx, NOW, url -> json(99, "9.9")));
            assertNull(UpdateCheck.newerKnown(ctx));
        }
    }
}
