package com.viral.shortsmaker;

import android.app.Activity;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;

import androidx.appcompat.app.AlertDialog;

import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.viral.shortsmaker.captions.CaptionLangs;
import com.viral.shortsmaker.core.CaptionScript;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v44 (5.7): the caption language picker, shared by Create, Caption Studio and Settings. One searchable list of all 29
 * languages (Hinglish, हिंदी, English first, then the 26 others by name), instead of a row of chips.
 */
final class LanguagePicker {
    interface Pick { void picked(String script); }

    private LanguagePicker() {}

    /** the picker's label for a stored caption setting */
    static String labelFor(String script) {
        if (CaptionScript.HINGLISH.equals(script)) return "Hinglish · Roman Hindi";   // short: the label sits on a button at large font sizes
        if (CaptionScript.HINDI.equals(script)) return "हिंदी · Hindi";
        if (CaptionScript.ENGLISH.equals(script)) return "English";
        String l = CaptionLangs.pickerLabel(script);
        return l == null ? CaptionScript.label(script) : l;
    }

    /** every caption setting, in the order the list shows them */
    static List<String> all() {
        List<String> out = new ArrayList<>();
        out.add(CaptionScript.HINGLISH);
        out.add(CaptionScript.HINDI);
        out.add(CaptionScript.ENGLISH);
        for (CaptionLangs.L l : CaptionLangs.all()) out.add(l.script());
        return out;
    }

    static boolean matches(String script, String query) {
        if (query == null || query.trim().isEmpty()) return true;
        String q = query.trim().toLowerCase(Locale.ROOT);
        String label = labelFor(script).toLowerCase(Locale.ROOT);
        CaptionLangs.L l = CaptionLangs.of(script);
        return label.contains(q) || (l != null && l.code.startsWith(q));
    }

    static void show(Activity a, String current, Pick pick) {
        final List<String> shown = new ArrayList<>(all());
        LinearLayout root = new LinearLayout(a);
        root.setOrientation(LinearLayout.VERTICAL);
        int pad = Math.round(16 * a.getResources().getDisplayMetrics().density);
        root.setPadding(pad, pad / 2, pad, 0);
        EditText search = new EditText(a);
        search.setHint("Search language");
        search.setSingleLine(true);
        root.addView(search, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT));
        ListView list = new ListView(a);
        root.addView(list, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, Math.round(420 * a.getResources().getDisplayMetrics().density)));
        final List<String> labels = new ArrayList<>();
        ArrayAdapter<String> adapter = new ArrayAdapter<>(a, android.R.layout.simple_list_item_1, labels);
        list.setAdapter(adapter);
        AlertDialog dlg = new MaterialAlertDialogBuilder(a)
                .setTitle("Caption language")
                .setView(root)
                .setNegativeButton("Cancel", null)
                .create();
        Runnable refill = () -> {
            String q = search.getText() == null ? "" : search.getText().toString();
            shown.clear();
            for (String s : all()) if (matches(s, q)) shown.add(s);
            labels.clear();
            for (String s : shown) labels.add(labelFor(s));
            adapter.notifyDataSetChanged();
        };
        refill.run();
        search.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a1, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a1, int b, int c) { refill.run(); }
            @Override public void afterTextChanged(Editable s) {}
        });
        list.setOnItemClickListener((parent, view, pos, id) -> {
            if (pos < 0 || pos >= shown.size()) return;
            String s = shown.get(pos);
            dlg.dismiss();
            pick.picked(s);
        });
        dlg.show();
    }
}
