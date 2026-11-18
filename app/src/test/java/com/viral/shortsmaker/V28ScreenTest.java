package com.viral.shortsmaker;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.app.Activity;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.os.Looper;
import android.view.View;

import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.viral.shortsmaker.core.AudioTracks;
import com.viral.shortsmaker.core.JobManager;
import com.viral.shortsmaker.core.Prefs;

import org.json.JSONObject;
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
import java.io.InputStream;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/** code 28: the audio language picker on the home screen (real 21-language list) and the link-only input */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public class V28ScreenTest {
    private static final File OUT = new File(System.getProperty("shotDir", "build/shots"));
    private void idle(long ms) { shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(ms)); }

    private void shot(Activity a, String name) throws Exception {
        idle(1500);
        View root = a.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        root.draw(new Canvas(b));
        OUT.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(OUT, name + ".png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }

    @Before public void keys() {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit()
                .putString("gemini_key", "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12")
                .putString("groq_key", "gsk_DUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234")
                .putBoolean("is_onboarding_done", true).apply();
        JobManager.get().removeAllListeners();
        JobManager.get().project = null;
        JobManager.get().reset();
    }

    @Test public void audioPickerShowsOriginalFirstThenHindi() throws Exception {
        JSONObject info;
        try (InputStream in = getClass().getClassLoader().getResourceAsStream("k28/RE3_PGwDwhw_ytdlp.json")) {
            info = new JSONObject(new String(in.readAllBytes(), StandardCharsets.UTF_8));
        }
        List<AudioTracks.Track> l = AudioTracks.list(info);
        MainActivity a = Robolectric.buildActivity(MainActivity.class).setup().get();
        idle(500);
        a.findViewById(R.id.layoutVideoInfo).setVisibility(View.VISIBLE);
        a.findViewById(R.id.cardControls).setVisibility(View.VISIBLE);
        View group = a.findViewById(R.id.groupAudioLang);
        assertEquals(View.GONE, group.getVisibility());   // no picker before a link with several languages
        Field u = MainActivity.class.getDeclaredField("srcYtUrl"); u.setAccessible(true);
        u.set(a, "https://youtu.be/RE3_PGwDwhw");
        Field t = MainActivity.class.getDeclaredField("audioTracks"); t.setAccessible(true);
        t.set(a, new ArrayList<>(l));
        Method m = MainActivity.class.getDeclaredMethod("showAudioTracks", String.class); m.setAccessible(true);
        m.invoke(a, "https://youtu.be/RE3_PGwDwhw");
        idle(800);
        assertEquals(View.VISIBLE, group.getVisibility());
        ChipGroup cg = a.findViewById(R.id.chipGroupAudioLang);
        assertEquals(21, cg.getChildCount());
        Chip first = (Chip) cg.getChildAt(0), second = (Chip) cg.getChildAt(1);
        assertEquals("English (original)", first.getText().toString());
        assertTrue(first.isChecked());
        assertEquals("Hindi", second.getText().toString());
        second.performClick();
        idle(300);
        Field p = MainActivity.class.getDeclaredField("audioLangPick"); p.setAccessible(true);
        assertEquals("hi", p.get(a));
        android.widget.TextView hint = a.findViewById(R.id.tvAudioLangHint);
        assertEquals("Shorts use the Hindi audio; captions are made from it", hint.getText().toString());
        // scroll the form to the picker for the picture
        View picker = a.findViewById(R.id.groupAudioLang);
        View sv = picker;
        while (sv != null && !(sv instanceof android.widget.ScrollView || sv instanceof androidx.core.widget.NestedScrollView)) sv = (View) sv.getParent();
        if (sv != null) { int[] xy = new int[2]; picker.getLocationInWindow(xy); sv.scrollTo(0, Math.max(0, xy[1] - 500)); }
        shot(a, "v28_audio_picker");
        first.performClick();
        idle(300);
        assertEquals("", p.get(a));   // original = no language asked for
    }
}
