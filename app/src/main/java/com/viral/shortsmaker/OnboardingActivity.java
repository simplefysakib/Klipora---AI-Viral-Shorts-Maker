package com.viral.shortsmaker;

import android.content.Intent;
import android.content.SharedPreferences;
import android.graphics.Color;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.text.Editable;
import android.text.TextWatcher;
import android.view.View;
import android.widget.EditText;
import android.widget.TextView;
import android.widget.Toast;

import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;

import com.google.android.material.button.MaterialButton;
import com.google.android.material.floatingactionbutton.ExtendedFloatingActionButton;

public class OnboardingActivity extends AppCompatActivity {
    /** v17: same compact UI scale on every screen */
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(UiScale.wrap(base)); }


    private static final String HTTPS = "https:" + "//";

    private ExtendedFloatingActionButton btnNext;
    private EditText etGeminiKey, etGroqKey;
    private TextView tvGeminiValidBadge, tvGroqValidBadge, tvOnboardHint;
    private MaterialButton btnGetGeminiKeyOnboard, btnGetGroqKeyOnboard;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        if (!com.viral.shortsmaker.core.Integrity.enforce(this)) return;

        SharedPreferences prefs = getSharedPreferences("AppConfig", MODE_PRIVATE);
        String savedGem = com.viral.shortsmaker.core.Prefs.geminiKey(this);
        String savedGroq = com.viral.shortsmaker.core.Prefs.groqKey(this);
        if (prefs.getBoolean("is_onboarding_done", false) && isValidGeminiKey(savedGem) && isValidGroqKey(savedGroq)) {
            startActivity(new Intent(this, MainActivity.class));
            finish();
            return;
        }

        setContentView(R.layout.activity_onboarding);
        Ui.fitSystemBars(this);
        LegalNotice.showIfNeeded(this);   // one-time notice for new users (Privacy Policy / Terms links)

        btnNext = findViewById(R.id.btnNext);
        nextLabel = btnNext.getText();
        etGeminiKey = findViewById(R.id.etGeminiKey);
        etGroqKey = findViewById(R.id.etGroqKey);
        tvGeminiValidBadge = findViewById(R.id.tvGeminiValidBadge);
        tvGroqValidBadge = findViewById(R.id.tvGroqValidBadge);
        tvOnboardHint = findViewById(R.id.tvOnboardHint);
        btnGetGeminiKeyOnboard = findViewById(R.id.btnGetGeminiKeyOnboard);
        btnGetGroqKeyOnboard = findViewById(R.id.btnGetGroqKeyOnboard);

        if (!savedGem.isEmpty()) etGeminiKey.setText(savedGem);
        if (!savedGroq.isEmpty()) etGroqKey.setText(savedGroq);

        if (btnGetGeminiKeyOnboard != null) {
            btnGetGeminiKeyOnboard.setOnClickListener(v -> openUrlInChrome(HTTPS + "aistudio.google.com/app/apikey"));
        }
        if (btnGetGroqKeyOnboard != null) {
            btnGetGroqKeyOnboard.setOnClickListener(v -> openUrlInChrome(HTTPS + "console.groq.com/keys"));
        }

        updateButtonState();

        TextWatcher watcher = new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int start, int count, int after) {}
            @Override public void onTextChanged(CharSequence s, int start, int before, int count) {
                if (forceNext) { forceNext = false; btnNext.setText(nextLabel); }
                updateButtonState();
            }
            @Override public void afterTextChanged(Editable s) {}
        };
        etGeminiKey.addTextChangedListener(watcher);
        etGroqKey.addTextChangedListener(watcher);

        btnNext.setOnClickListener(v -> {
            String gem = etGeminiKey.getText().toString().trim();
            String groq = etGroqKey.getText().toString().trim();
            if (isValidGeminiKey(gem) && isValidGroqKey(groq)) {
                if (forceNext) { finishWithKeys(gem, groq, null); return; }
                // saved at once (saving is never blocked by the check); the check only decides whether to go on now
                com.viral.shortsmaker.core.Prefs.setKeys(this, gem, groq);
                prefs.edit().putBoolean("is_onboarding_done", true).apply();
                // 5.3: live check (~5 s at most, both at once). A rejected key shows "Key not valid" on its badge and the
                // button becomes "Continue anyway"; no connection never blocks (saved with a "couldn't check" note).
                btnNext.setEnabled(false);
                CharSequence label = btnNext.getText();
                btnNext.setText("Checking…");
                KeyProblemDialog.checkBoth(gem, groq, (vg, vq) -> {
                    if (isFinishing()) return;
                    btnNext.setText(label);
                    btnNext.setEnabled(true);
                    boolean badG = vg.invalid(), badQ = vq.invalid();
                    if (badG || badQ) {
                        if (badG && tvGeminiValidBadge != null) { tvGeminiValidBadge.setText("✗ Key not valid"); tvGeminiValidBadge.setTextColor(Color.parseColor("#F43F5E")); }
                        if (badQ && tvGroqValidBadge != null) { tvGroqValidBadge.setText("✗ Key not valid"); tvGroqValidBadge.setTextColor(Color.parseColor("#F43F5E")); }
                        if (badG) etGeminiKey.setError(com.viral.shortsmaker.core.KeyCheck.fieldMessage(vg));
                        if (badQ) etGroqKey.setError(com.viral.shortsmaker.core.KeyCheck.fieldMessage(vq));
                        if (tvOnboardHint != null) {
                            tvOnboardHint.setText((badG && badQ ? "Both keys are" : badG ? "Your Gemini key is" : "Your Groq key is") + " not valid. Create a new key and paste it.");
                            tvOnboardHint.setTextColor(Color.parseColor("#F43F5E"));
                        }
                        forceNext = true;
                        btnNext.setText("Continue anyway");
                        return;
                    }
                    finishWithKeys(gem, groq, com.viral.shortsmaker.core.KeyCheck.saveSummary(vg, vq));
                });
            } else {
                Toast.makeText(this, "Enter a valid Gemini key (AIza… or AQ.) and Groq key (gsk_…).", Toast.LENGTH_LONG).show();
            }
        });
    }

    /** 5.3: a rejected key was shown; the next tap continues anyway (editing a key checks again) */
    private boolean forceNext;
    private CharSequence nextLabel = "Next";

    private void finishWithKeys(String gem, String groq, String note) {
        com.viral.shortsmaker.core.Prefs.setKeys(this, gem, groq);
        getSharedPreferences(com.viral.shortsmaker.core.Prefs.NAME, MODE_PRIVATE).edit().putBoolean("is_onboarding_done", true).apply();
        if (note != null && !note.equals("Keys saved and checked")) Toast.makeText(this, note, Toast.LENGTH_LONG).show();
        startActivity(new Intent(OnboardingActivity.this, MainActivity.class));
        finish();
    }

    private void openUrlInChrome(String url) {
        try {
            Intent intent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK);
            startActivity(intent);
        } catch (Exception e) {
            Toast.makeText(this, "Could not open browser", Toast.LENGTH_SHORT).show();
        }
    }

    public static boolean isValidGeminiKey(String key) { return com.viral.shortsmaker.core.Prefs.isValidGeminiKey(key); }

    static boolean legacyIsValidGeminiKey(String key) {
        if (key == null) return false;
        String k = key.trim();
        if (k.length() < 30 || k.contains(" ")) return false;
        if (!(k.startsWith("AIza") || k.startsWith("AQ."))) return false;
        return k.matches("^[A-Za-z0-9_\\-\\.]+$");
    }

    public static boolean isValidGroqKey(String key) { return com.viral.shortsmaker.core.Prefs.isValidGroqKey(key); }

    static boolean legacyIsValidGroqKey(String key) {
        if (key == null) return false;
        String k = key.trim();
        if (k.length() < 35 || k.contains(" ")) return false;
        if (!k.startsWith("gsk_")) return false;
        return k.matches("^gsk_[A-Za-z0-9_\\-]+$");
    }

    private void updateButtonState() {
        String gem = etGeminiKey.getText().toString().trim();
        String groq = etGroqKey.getText().toString().trim();

        boolean gemOk = isValidGeminiKey(gem);
        boolean groqOk = isValidGroqKey(groq);

        if (tvGeminiValidBadge != null) {
            if (gem.isEmpty()) {
                tvGeminiValidBadge.setText("Required");
                tvGeminiValidBadge.setTextColor(Color.parseColor("#F59E0B"));
            } else if (gemOk) {
                tvGeminiValidBadge.setText("✓ Valid");
                tvGeminiValidBadge.setTextColor(Color.parseColor("#22C55E"));
            } else {
                tvGeminiValidBadge.setText("Starts with AIza… or AQ.");
                tvGeminiValidBadge.setTextColor(Color.parseColor("#F43F5E"));
            }
        }

        if (tvGroqValidBadge != null) {
            if (groq.isEmpty()) {
                tvGroqValidBadge.setText("Required");
                tvGroqValidBadge.setTextColor(Color.parseColor("#F59E0B"));
            } else if (groqOk) {
                tvGroqValidBadge.setText("✓ Valid");
                tvGroqValidBadge.setTextColor(Color.parseColor("#22C55E"));
            } else {
                tvGroqValidBadge.setText("Starts with gsk_…");
                tvGroqValidBadge.setTextColor(Color.parseColor("#F43F5E"));
            }
        }

        boolean bothValid = gemOk && groqOk;
        btnNext.setEnabled(bothValid);
        btnNext.setAlpha(bothValid ? 1.0f : 0.35f);

        if (tvOnboardHint != null) {
            if (bothValid) {
                tvOnboardHint.setText("✓ Both keys look good. Tap Next.");
                tvOnboardHint.setTextColor(Color.parseColor("#22C55E"));
            } else {
                tvOnboardHint.setText("Add your Gemini and Groq keys to start");
                tvOnboardHint.setTextColor(Color.parseColor("#9B9BB0"));
            }
        }
    }
}
