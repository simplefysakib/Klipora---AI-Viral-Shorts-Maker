package com.viral.shortsmaker;

import static org.junit.Assert.*;
import static org.robolectric.Shadows.shadowOf;

import android.content.Context;
import android.content.Intent;
import android.os.Looper;
import android.widget.Button;
import android.widget.EditText;
import android.widget.TextView;

import androidx.appcompat.app.AlertDialog;
import androidx.test.core.app.ApplicationProvider;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.viral.shortsmaker.core.FakeAiServer;
import com.viral.shortsmaker.core.JobManager;
import com.viral.shortsmaker.core.KeyCheck;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.RenderSafety;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.robolectric.annotation.LooperMode;
import org.robolectric.shadows.ShadowDialog;
import org.robolectric.shadows.ShadowToast;

/**
 * 5.3 (Sakib, Nov 4): tapping Generate with a key Google / Groq reject shows an English popup — title, the fix in 3
 * steps, and exactly three buttons: "Get new key" (the provider's key page), "Open Settings" (straight to the key
 * field), "Cancel". Plus the save-time check in the Settings keys dialog.
 */
@RunWith(RobolectricTestRunner.class)
@LooperMode(LooperMode.Mode.PAUSED)
@Config(sdk = 34, qualifiers = "w393dp-h852dp-xxhdpi")
public class V35KeyDialogTest {
    static final String GEM = "AIzaSyDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY12", GROQ = "gsk_DUMMYDUMMYDUMMYDUMMYDUMMYDUMMYDUMMY1234";
    FakeAiServer srv;

    @Before public void setUp() throws Exception {
        Context c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().clear().putString("gemini_key", GEM).putString("groq_key", GROQ).putBoolean("is_onboarding_done", true).commit();
        RenderSafety.resetForTest();
        JobManager.get().removeAllListeners();
        JobManager.get().project = null;
        JobManager.get().reset();
        KeyCheck.forget();
        srv = new FakeAiServer();
        KeyCheck.setBasesForTest(srv.base(), srv.base(), 1500);
    }

    @After public void tearDown() {
        KeyCheck.setBasesForTest(null, null, 0);
        KeyCheck.forget();
        srv.close();
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().clear().commit();
        RenderSafety.resetForTest();
    }

    MainActivity main() {
        MainActivity m = Robolectric.buildActivity(MainActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        return m;
    }

    static String text(AlertDialog d, int id) {
        TextView t = d.findViewById(id);
        return t == null ? null : t.getText().toString();
    }

    /** waits for the background check and runs what it posted to the main thread */
    static void settle(java.util.function.BooleanSupplier done) throws Exception {
        long end = System.currentTimeMillis() + 8000;
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idle();
            if (done.getAsBoolean()) return;
            Thread.sleep(20);
        }
        shadowOf(Looper.getMainLooper()).idle();
    }

    void checkPopup(MainActivity m, AlertDialog d, String title, String url, String stepOne) {
        assertTrue(d.isShowing());
        assertEquals(title, text(d, androidx.appcompat.R.id.alertTitle));
        String body = text(d, android.R.id.message);
        assertTrue(body, body.contains("1) " + stepOne));
        assertTrue(body, body.contains("2) Create API key and copy it"));
        assertTrue(body, body.contains("3) Paste it in Klipora Settings"));
        Button get = d.getButton(AlertDialog.BUTTON_POSITIVE), set = d.getButton(AlertDialog.BUTTON_NEUTRAL), cancel = d.getButton(AlertDialog.BUTTON_NEGATIVE);
        assertEquals("Get new key", get.getText().toString());
        assertEquals("Open Settings", set.getText().toString());
        assertEquals("Cancel", cancel.getText().toString());
        // "Get new key" opens the provider's key page
        get.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        Intent i = shadowOf(m).getNextStartedActivity();
        assertNotNull(i);
        assertEquals(Intent.ACTION_VIEW, i.getAction());
        assertEquals(url, i.getDataString());
    }

    @Test public void geminiPopup_titleStepsButtonsAndLinks() {
        MainActivity m = main();
        AlertDialog d = m.showKeyProblem(KeyCheck.Preflight.GEMINI_INVALID);
        shadowOf(Looper.getMainLooper()).idle();
        checkPopup(m, d, "Your Gemini API key is not valid", "https://aistudio.google.com/app/apikey", "Open Google AI Studio");
    }

    @Test public void groqPopup_titleStepsButtonsAndLinks() {
        MainActivity m = main();
        AlertDialog d = m.showKeyProblem(KeyCheck.Preflight.GROQ_INVALID);
        shadowOf(Looper.getMainLooper()).idle();
        checkPopup(m, d, "Your Groq API key is not valid", "https://console.groq.com/keys", "Open the GroqCloud console");
        assertTrue(KeyProblemDialog.message(KeyCheck.Preflight.BOTH_INVALID).contains("Groq API key is not valid either"));
    }

    @Test public void openSettings_goesStraightToTheKeyField() {
        MainActivity m = main();
        AlertDialog d = m.showKeyProblem(KeyCheck.Preflight.GROQ_INVALID);
        shadowOf(Looper.getMainLooper()).idle();
        d.getButton(AlertDialog.BUTTON_NEUTRAL).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(R.id.nav_settings, ((BottomNavigationView) m.findViewById(R.id.bottomNav)).getSelectedItemId());
        AlertDialog keys = (AlertDialog) ShadowDialog.getLatestDialog();
        assertNotSame(d, keys);
        assertTrue(keys.isShowing());
        EditText groq = keys.findViewById(R.id.etDlgGroq);
        assertTrue("the Groq field is focused", groq.isFocused());
        assertEquals("Key not valid. Create a new key and paste it here.", String.valueOf(groq.getError()));
    }

    @Test public void cancel_doesNotStartTheJob() {
        MainActivity m = main();
        AlertDialog d = m.showKeyProblem(KeyCheck.Preflight.GEMINI_INVALID);
        shadowOf(Looper.getMainLooper()).idle();
        d.getButton(AlertDialog.BUTTON_NEGATIVE).performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertFalse(d.isShowing());
        assertFalse(JobManager.get().isRunning());
        assertNull(shadowOf(m).getNextStartedActivity());
    }

    @Test public void generatePreflight_badGeminiKey_showsPopup_goodKeys_goOn() throws Exception {
        srv.on("GET /v1beta/models", 400, FakeAiServer.geminiKeyInvalid());
        srv.on("GET /openai/v1/models", 200, "{\"data\":[]}");
        MainActivity m = main();
        boolean[] went = {false};
        assertFalse("the job waits for the check", m.preflightKeys(() -> went[0] = true));
        settle(() -> ShadowDialog.getLatestDialog() != null && ShadowDialog.getLatestDialog().isShowing());
        AlertDialog d = (AlertDialog) ShadowDialog.getLatestDialog();
        assertEquals("Your Gemini API key is not valid", text(d, androidx.appcompat.R.id.alertTitle));
        assertFalse(went[0]);
        d.dismiss();
        // a second tap within 10 min answers from memory (no new request) and shows the popup again
        int calls = srv.count("GET /v1beta/models");
        assertFalse(m.preflightKeys(() -> went[0] = true));
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals(calls, srv.count("GET /v1beta/models"));

        // both keys fine: the job goes on (and the next tap is instant: no wait at all)
        KeyCheck.forget();
        srv.on("GET /v1beta/models", 200, "{}");
        assertFalse(m.preflightKeys(() -> went[0] = true));
        settle(() -> went[0]);
        assertTrue(went[0]);
        assertTrue("remembered as good: instant", m.preflightKeys(() -> {}));
    }

    @Test public void generatePreflight_noConnection_startsAsNormal() throws Exception {
        java.net.ServerSocket s = new java.net.ServerSocket(0);
        int port = s.getLocalPort();
        s.close();
        KeyCheck.setBasesForTest("http://127.0.0.1:" + port, "http://127.0.0.1:" + port, 800);
        MainActivity m = main();
        boolean[] went = {false};
        m.preflightKeys(() -> went[0] = true);
        settle(() -> went[0]);
        assertTrue("no popup, the job starts", went[0]);
        android.app.Dialog last = ShadowDialog.getLatestDialog();
        assertTrue(last == null || !last.isShowing());
    }

    @Test public void settingsSave_invalidKey_showsKeyNotValid_thenSaveAnyway() throws Exception {
        srv.on("GET /v1beta/models", 400, FakeAiServer.geminiKeyInvalid());
        srv.on("GET /openai/v1/models", 200, "{\"data\":[]}");
        MainActivity m = main();
        AlertDialog d = m.showApiKeyDialog(null, false);
        shadowOf(Looper.getMainLooper()).idle();
        EditText gem = d.findViewById(R.id.etDlgGemini);
        String newGem = "AIzaSyNEWNEWNEWNEWNEWNEWNEWNEWNEWNEW99";
        gem.setText(newGem);
        Button save = d.getButton(AlertDialog.BUTTON_POSITIVE);
        save.performClick();
        settle(() -> "Save anyway".contentEquals(save.getText()));
        assertEquals("Key not valid. Create a new key and paste it here.", String.valueOf(gem.getError()));
        assertTrue(d.isShowing());
        assertEquals("not saved yet", GEM, Prefs.geminiKey(m));
        save.performClick();
        shadowOf(Looper.getMainLooper()).idle();
        assertEquals("never a dead end: Save anyway saves", newGem, Prefs.geminiKey(m));
    }

    @Test public void settingsSave_noConnection_savesWithANote() throws Exception {
        java.net.ServerSocket s = new java.net.ServerSocket(0);
        int port = s.getLocalPort();
        s.close();
        KeyCheck.setBasesForTest("http://127.0.0.1:" + port, "http://127.0.0.1:" + port, 800);
        MainActivity m = main();
        AlertDialog d = m.showApiKeyDialog(null, false);
        shadowOf(Looper.getMainLooper()).idle();
        String newGroq = "gsk_NEWNEWNEWNEWNEWNEWNEWNEWNEWNEWNEWNEW99";
        ((EditText) d.findViewById(R.id.etDlgGroq)).setText(newGroq);
        d.getButton(AlertDialog.BUTTON_POSITIVE).performClick();
        settle(() -> !d.isShowing());
        assertFalse(d.isShowing());
        assertEquals(newGroq, Prefs.groqKey(m));
        assertEquals("Keys saved. Couldn't check the keys (no connection)", ShadowToast.getTextOfLatestToast());
    }

    @Test public void onboarding_invalidKey_showsKeyNotValid() throws Exception {
        Prefs.sp(ApplicationProvider.getApplicationContext()).edit().clear().commit();
        srv.on("GET /v1beta/models", 200, "{}");
        srv.on("GET /openai/v1/models", 401, FakeAiServer.groqKeyInvalid());
        OnboardingActivity ob = Robolectric.buildActivity(OnboardingActivity.class).setup().get();
        shadowOf(Looper.getMainLooper()).idle();
        ((EditText) ob.findViewById(R.id.etGeminiKey)).setText(GEM);
        ((EditText) ob.findViewById(R.id.etGroqKey)).setText(GROQ);
        TextView badge = ob.findViewById(R.id.tvGroqValidBadge);
        ob.findViewById(R.id.btnNext).performClick();
        settle(() -> badge.getText().toString().contains("not valid"));
        assertEquals("✗ Key not valid", badge.getText().toString());
        assertNull("stays on onboarding", shadowOf(ob).getNextStartedActivity());
        ob.findViewById(R.id.btnNext).performClick();   // "Continue anyway"
        shadowOf(Looper.getMainLooper()).idle();
        assertNotNull(shadowOf(ob).getNextStartedActivity());
    }
}
