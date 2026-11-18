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
public class V33FreeNoBackupEngineTest {
    @Test public void freeHasNoBackupEngineSetting() throws Exception {
        assertFalse(BuildConfig.BACKUP_ENGINE_SWITCH);
        assertFalse(EditionExtras.HAS_BACKUP_ENGINE_SWITCH);
        // not hidden - absent: no string, no view id in the Free build
        try { R.string.class.getField("backup_engine_test"); fail("Free must not contain the backup engine setting text"); } catch (NoSuchFieldException expected) {}
        try { R.id.class.getField("switchBackupEngine"); fail("Free must not contain the backup engine switch"); } catch (NoSuchFieldException expected) {}
        // a stored value (backup restore, old prefs) changes nothing in Free
        Context c = ApplicationProvider.getApplicationContext();
        Prefs.sp(c).edit().putBoolean(Prefs.K_BACKUP_ENGINE_TEST, true).commit();
        assertFalse(Prefs.backupEngineTest(c));
    }
}
