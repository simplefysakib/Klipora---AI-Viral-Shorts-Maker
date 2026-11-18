package com.viral.shortsmaker;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;
import android.widget.TextView;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.core.JobManager;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.RenderSafety;
import com.viral.shortsmaker.core.UpdateCheck;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;
import java.time.Duration;

/** code 31 (4.9): the error card on an old build (update line), the report's video path line after a render crash. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public class V31ScreenTest {
    private static final File OUT = new File(System.getProperty("shotDir", "build/shots"));

    private void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    private void shot(Activity a, String name) throws Exception {
        idle(1200);
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }

    @Before public void setUp() {
        Context c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().clear()
                .putString("gemini_key", "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12")
                .putString("groq_key", "gsk_DUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234")
                .putBoolean("is_onboarding_done", true).commit();
        RenderSafety.resetForTest();
        JobManager.get().removeAllListeners();
        JobManager.get().project = null;
        JobManager.get().reset();
    }

    @After public void tearDown() {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().clear().commit();
        RenderSafety.resetForTest();
    }

    @Test public void oldBuildErrorCardOffersTheUpdate() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        boolean free = UpdateCheck.enabled();
        if (free) UpdateCheck.check(c, System.currentTimeMillis(), url -> "{\"versionCode\":99,\"versionName\":\"9.9\",\"notes\":\"Fixes\"}");
        RenderSafety.noteCrash(c, RenderSafety.K_HW_DECODE, "CRASH_NATIVE, signal 11 in libavcodec.so");
        V30ScreenTest.marker(c, 10 * 60_000, null);
        Prefs.sp(c).edit().putLong("app_crash_at", System.currentTimeMillis()).commit();
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        assertEquals(View.VISIBLE, a.findViewById(R.id.cardError).getVisibility());
        TextView up = a.findViewById(R.id.btnErrorUpdate);
        assertEquals(free ? View.VISIBLE : View.GONE, up.getVisibility());
        assertTrue(up.getText().toString().startsWith("You're on an old version"));
        shot(a, "v31_01_old_version_card");
        a.findViewById(R.id.btnErrorReport).performClick();
        idle(300);
        String body = ((ClipboardManager) c.getSystemService(Context.CLIPBOARD_SERVICE)).getPrimaryClip().getItemAt(0).getText().toString();
        System.out.println("V31 REPORT:\n" + body);
        assertTrue(body, body.contains("Video path: software decoding + hardware encoder (safe mode after 1 crash)"));
        assertTrue(body, body.contains("libavcodec.so"));
        assertEquals(free, body.contains("Newer version available: 9.9 (99)"));
    }

    @Test public void newestBuildShowsNoUpdateLine() {
        Context c = ApplicationProvider.getApplicationContext();
        V30ScreenTest.marker(c, 10 * 60_000, null);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        assertEquals(View.GONE, a.findViewById(R.id.btnErrorUpdate).getVisibility());
    }
}
