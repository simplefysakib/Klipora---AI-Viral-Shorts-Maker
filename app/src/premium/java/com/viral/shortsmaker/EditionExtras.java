package com.viral.shortsmaker;

import android.app.Activity;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.view.ViewGroup;
import android.widget.LinearLayout;
import android.widget.TextView;

import com.google.android.material.materialswitch.MaterialSwitch;
import com.viral.shortsmaker.core.AppLog;
import com.viral.shortsmaker.core.Prefs;

/** code 33 (5.1): Premium-edition settings. The Free edition's EditionExtras adds nothing. */
public final class EditionExtras {
    private EditionExtras() {}

    public static final boolean HAS_BACKUP_ENGINE_SWITCH = true;

    /** adds "Use backup video engine (test)" under Logs in Settings */
    public static void addSettings(Activity a) {
        View logs = a.findViewById(R.id.rowLogs);
        if (logs == null || !(logs.getParent() instanceof ViewGroup) || a.findViewById(R.id.switchBackupEngine) != null) return;
        ViewGroup parent = (ViewGroup) logs.getParent();
        float d = a.getResources().getDisplayMetrics().density;
        LinearLayout row = new LinearLayout(a);
        row.setId(R.id.rowBackupEngine);
        row.setOrientation(LinearLayout.HORIZONTAL);
        row.setGravity(Gravity.CENTER_VERTICAL);
        row.setPadding(0, (int) (10 * d), 0, (int) (10 * d));
        LinearLayout texts = new LinearLayout(a);
        texts.setOrientation(LinearLayout.VERTICAL);
        LinearLayout.LayoutParams tl = new LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        tl.setMarginStart((int) (36 * d));
        TextView title = new TextView(a);
        title.setText(R.string.backup_engine_test);
        title.setTextSize(TypedValue.COMPLEX_UNIT_SP, 14);
        title.setTextColor(a.getResources().getColor(R.color.kl_text, a.getTheme()));
        TextView sub = new TextView(a);
        sub.setText(R.string.backup_engine_test_sub);
        sub.setTextSize(TypedValue.COMPLEX_UNIT_SP, 11);
        sub.setTextColor(a.getResources().getColor(R.color.kl_text_dim, a.getTheme()));
        texts.addView(title);
        texts.addView(sub);
        row.addView(texts, tl);
        MaterialSwitch sw = new MaterialSwitch(a);
        sw.setId(R.id.switchBackupEngine);
        sw.setContentDescription(a.getString(R.string.backup_engine_test));
        sw.setChecked(Prefs.sp(a).getBoolean(Prefs.K_BACKUP_ENGINE_TEST, false));
        sw.setOnCheckedChangeListener((b, on) -> {
            Prefs.sp(a).edit().putBoolean(Prefs.K_BACKUP_ENGINE_TEST, on).apply();
            AppLog.i("Backup video engine (test): " + (on ? "on" : "off"));
        });
        row.addView(sw, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.WRAP_CONTENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        row.setOnClickListener(v -> sw.toggle());
        parent.addView(row, parent.indexOfChild(logs) + 1, new ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
    }
}
