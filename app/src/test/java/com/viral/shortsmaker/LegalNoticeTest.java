package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.app.Dialog;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.view.View;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.core.Prefs;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.Shadows;
import org.robolectric.annotation.GraphicsMode;
import org.robolectric.annotation.LooperMode;

import java.io.File;
import java.io.FileOutputStream;

/** Nov 1 2026 legal notice: shown once until "I Agree", links open the website pages, Settings has the links. */
@RunWith(RobolectricTestRunner.class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
public class LegalNoticeTest {
    private final V11ScreenTest v11 = new V11ScreenTest();

    @Before public void reset() {
        v11.reset();
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().remove(LegalNotice.KEY_ACCEPTED).apply();
        LegalNotice.showInTests = true;
        LegalNotice.current = null;
    }

    @After public void off() { LegalNotice.showInTests = false; LegalNotice.current = null; }

    @Test public void shownOnceUntilAgreedAndNotCancelable() throws Exception {
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        V11ScreenTest.idle(500);
        AlertDialog d = LegalNotice.current;
        assertNotNull("notice shown on first start", d);
        assertTrue(d.isShowing());
        View root = d.getWindow().getDecorView();
        TextView body = root.findViewById(R.id.tvLegalBody);
        assertEquals(LegalNotice.BODY, body.getText().toString());
        // back press must not dismiss it
        d.onBackPressed();
        V11ScreenTest.idle(200);
        assertTrue("not cancelable", d.isShowing());
        // render for the report
        V11ScreenTest.idle(400);
        Bitmap b = Bitmap.createBitmap(root.getWidth(), root.getHeight(), Bitmap.Config.ARGB_8888);
        b.eraseColor(0xFF000000);
        root.draw(new Canvas(b));
        File dir = new File(System.getProperty("shotDir", "build/shots"), "legal");
        dir.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(dir, "consent_dialog.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
        // full screen with the dialog on top
        V11ScreenTest.shotTo(m, "legal", "consent_screen");
        // privacy link opens the browser
        root.findViewById(R.id.btnLegalPrivacy).performClick();
        Intent i = Shadows.shadowOf(m).getNextStartedActivity();
        assertEquals(LegalNotice.PRIVACY_URL, i.getData().toString());
        root.findViewById(R.id.btnLegalTerms).performClick();
        i = Shadows.shadowOf(m).getNextStartedActivity();
        assertEquals(LegalNotice.TERMS_URL, i.getData().toString());
        assertFalse(LegalNotice.accepted(m));
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        V11ScreenTest.idle(300);
        assertFalse(d.isShowing());
        assertTrue(LegalNotice.accepted(m));
        // never again
        LegalNotice.current = null;
        Robolectric.buildActivity(MainActivity.class).setup().get();
        V11ScreenTest.idle(300);
        assertNull("shown only once", LegalNotice.current);
    }


    /** phone-size render of the notice on top of the dimmed home screen, for the report */
    @Test @org.robolectric.annotation.Config(qualifiers = "w393dp-h852dp-xxhdpi")
    public void renderPhoneSize() throws Exception {
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        V11ScreenTest.idle(800);
        AlertDialog d = LegalNotice.current;
        assertNotNull(d);
        View screen = m.getWindow().getDecorView();
        View dlg = d.getWindow().getDecorView();
        Bitmap b = Bitmap.createBitmap(screen.getWidth(), screen.getHeight(), Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(b);
        screen.draw(c);
        c.drawColor(0x99000000);
        c.save();
        c.translate((screen.getWidth() - dlg.getWidth()) / 2f, (screen.getHeight() - dlg.getHeight()) / 2f);
        dlg.draw(c);
        c.restore();
        File dir = new File(System.getProperty("shotDir", "build/shots"), "legal");
        dir.mkdirs();
        try (FileOutputStream fo = new FileOutputStream(new File(dir, "consent_phone.png"))) { b.compress(Bitmap.CompressFormat.PNG, 100, fo); }
    }

    @Test public void settingsHasPrivacyTermsAndDisclaimer() {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().putBoolean(LegalNotice.KEY_ACCEPTED, true).apply();
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        V11ScreenTest.idle(300);
        assertNull(LegalNotice.current);
        assertEquals(LegalNotice.DISCLAIMER, ((TextView) m.findViewById(R.id.tvLegalDisclaimer)).getText().toString());
        m.findViewById(R.id.rowPrivacy).performClick();
        assertEquals(LegalNotice.PRIVACY_URL, Shadows.shadowOf(m).getNextStartedActivity().getData().toString());
        m.findViewById(R.id.rowTerms).performClick();
        assertEquals(LegalNotice.TERMS_URL, Shadows.shadowOf(m).getNextStartedActivity().getData().toString());
    }

    @Test public void noAppBrandedAsYouTubeDownloader() {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().putBoolean(LegalNotice.KEY_ACCEPTED, true).apply();
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        V11ScreenTest.idle(300);
        String all = allText(m.getWindow().getDecorView()).toLowerCase();
        assertFalse(all.contains("youtube downloader"));
        assertFalse(all.contains("paste youtube link"));
    }

    private static String allText(View v) {
        StringBuilder sb = new StringBuilder();
        if (v instanceof TextView) sb.append(((TextView) v).getText()).append(' ').append(((TextView) v).getHint()).append('\n');
        if (v instanceof android.view.ViewGroup) for (int i = 0; i < ((android.view.ViewGroup) v).getChildCount(); i++) sb.append(allText(((android.view.ViewGroup) v).getChildAt(i)));
        return sb.toString();
    }
}
