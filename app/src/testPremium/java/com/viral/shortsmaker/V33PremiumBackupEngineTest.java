package com.viral.shortsmaker;

import static org.junit.Assert.*;

import android.content.Context;

import androidx.test.core.app.ApplicationProvider;

import com.viral.shortsmaker.core.Prefs;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;

/** code 33 (5.1): the backup video engine test switch is Premium only */
@RunWith(RobolectricTestRunner.class)
public class V33PremiumBackupEngineTest {
    @Test public void premiumHasTheBackupEngineSwitch() throws Exception {
        assertTrue(BuildConfig.BACKUP_ENGINE_SWITCH);
        assertTrue(EditionExtras.HAS_BACKUP_ENGINE_SWITCH);
        Context c = ApplicationProvider.getApplicationContext();
        assertEquals("Use backup video engine (test)", c.getString(R.string.backup_engine_test));
        assertFalse(Prefs.backupEngineTest(c));   // off by default
        Prefs.sp(c).edit().putBoolean(Prefs.K_BACKUP_ENGINE_TEST, true).commit();
        assertTrue(Prefs.backupEngineTest(c));
        Prefs.sp(c).edit().putBoolean(Prefs.K_BACKUP_ENGINE_TEST, false).commit();
    }
}
