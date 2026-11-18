package com.viral.shortsmaker;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.res.ColorStateList;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.drawable.GradientDrawable;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.ForegroundColorSpan;
import android.util.LruCache;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.MotionEvent;
import android.view.View;
import android.view.ViewGroup;
import android.view.animation.OvershootInterpolator;
import android.view.inputmethod.EditorInfo;
import android.widget.EditText;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.app.AppCompatDelegate;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;
import androidx.core.content.FileProvider;
import androidx.recyclerview.widget.LinearLayoutManager;
import androidx.recyclerview.widget.RecyclerView;

import com.google.android.material.bottomnavigation.BottomNavigationView;
import com.google.android.material.button.MaterialButton;
import com.google.android.material.card.MaterialCardView;
import com.google.android.material.chip.Chip;
import com.google.android.material.chip.ChipGroup;
import com.google.android.material.dialog.MaterialAlertDialogBuilder;
import com.viral.shortsmaker.core.KeyCheck;
import com.google.android.material.materialswitch.MaterialSwitch;
import com.google.android.material.progressindicator.CircularProgressIndicator;
import com.google.android.material.progressindicator.LinearProgressIndicator;
import com.google.android.material.textfield.TextInputEditText;
import com.google.android.material.textfield.TextInputLayout;
import com.unity3d.ads.IUnityAdsInitializationListener;
import com.unity3d.ads.IUnityAdsLoadListener;
import com.unity3d.ads.IUnityAdsShowListener;
import com.unity3d.ads.UnityAds;
import com.unity3d.ads.UnityAdsShowOptions;
import com.viral.shortsmaker.captions.CaptionStyle;
import com.viral.shortsmaker.core.AppLog;
import com.viral.shortsmaker.core.FfmpegCommands;
import com.viral.shortsmaker.core.GallerySaver;
import com.viral.shortsmaker.core.JobGuard;
import com.viral.shortsmaker.core.JobManager;
import com.viral.shortsmaker.core.Net;
import com.viral.shortsmaker.core.Pipeline;
import com.viral.shortsmaker.core.Prefs;
import com.viral.shortsmaker.core.AdRetry;
import com.viral.shortsmaker.core.CaptionScript;
import com.viral.shortsmaker.core.ProcessingService;
import com.viral.shortsmaker.core.Project;
import com.viral.shortsmaker.core.YtEngine;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import okhttp3.Request;
import okhttp3.Response;

public class MainActivity extends AppCompatActivity implements IUnityAdsInitializationListener, JobManager.Listener, AppLog.Listener {
    /** v17: same compact UI scale on every screen */
    @Override protected void attachBaseContext(android.content.Context base) { super.attachBaseContext(UiScale.wrap(base)); }


    private static final String SUPPORT_EMAIL = "team.simplefy" + "@" + "gmail.com";
    private static final String UNITY_GAME_ID = "6073571";
    private static final String AD_UNIT = "Rewarded_Android";
    private static final int[] COUNT_VALUES = {1, 2, 3, 4, 5, 0};   // v9: max 5 shorts; 0 = Auto (about 1 per 6 min, max 5)
    private static final int MAX_SHORTS = COUNT_VALUES.length;



    // ---- state
    private static final boolean[] sessionCountUnlocked = {true, false, false, false, false, false, false, false, false};
    private String srcYtUrl;
    private String srcTitle;
    private double srcDuration;
    private String srcThumbUrl;
    private Bitmap srcThumb;
    private boolean fetching;
    private boolean updatingHighSpeed;
    private final Handler ui = new Handler(Looper.getMainLooper());
    private final LruCache<String, Bitmap> thumbs = new LruCache<>(12);

    // ---- views
    private View tabHistory, historyEmpty;
    private RecyclerView rvHistory;
    private HistoryAdapter historyAdapter;
    private int historySort = 0;   // 0 newest, 1 oldest, 2 most shorts, 3 A-Z
    private TextView tvEta, tvProcKicker;
    private LinearProgressIndicator progressMain;
    private Pipeline.Request lastRequest;
    private View tabCreate, tabSettings, panelInput, logsPanel, panelResults, groupYt, layoutFetching, cardControls;
    private MaterialCardView layoutVideoInfo;
    private TextInputEditText etUrl;
    private TextInputLayout tilUrl;
    private TextView tvClipboardNotice, tvFetching, tvVideoTitle, tvVideoDuration, tvSourceKind, tvCaptionSummary, tvStatus, tvTip,
            tvPercent, tvProcTitle, tvResultsTitle, tvResultsSub, tvResultsWarn, badgePro;
    private TextView sheetLogs;
    private View cardError;
    private Runnable errorRetry;
    private String errorTitle, errorMsg;
    private ImageView ivThumb;
    private ChipGroup cgCount, cgCategory, cgFrame;
    private final Chip[] countChips = new Chip[MAX_SHORTS];
    private MaterialSwitch swCaptions;
    private MaterialButton btnGenerate;
    private RecyclerView rvClips;
    private ClipAdapter clipAdapter;
    private BottomNavigationView nav;

    // settings
    private TextView tvAdsCount, tvTimer, tvPremiumStatus, tvMasked, tvGemStatus, tvGroqStatus, tvHighSpeedStatus, tvEngineSub;
    private LinearProgressIndicator progressAds;
    private View layoutAdProgress, howToKeys, dotGem, dotGroq, crownHs;
    private MaterialSwitch swHighSpeed;

    private Runnable pendingAfterPermission;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES);
        super.onCreate(savedInstanceState);
        KliporaApp.selfCheckOnce(this);   // code 32: the face engine check runs with a screen up, not on background starts
        if (!com.viral.shortsmaker.core.Integrity.enforce(this)) return;
        if (!Prefs.sp(this).getBoolean("is_onboarding_done", false) || !Prefs.keysValid(this)) {
            startActivity(new Intent(this, OnboardingActivity.class));
            finish();
            return;
        }
        setContentView(R.layout.activity_main);
        Ui.fitSystemBars(this);
        bindViews();
        setupCreate();
        setupSettings();
        // Premium edition: no ads at all, so Unity Ads is never initialised (no ad requests, no ad UI)
        if (Prefs.PREMIUM_EDITION) AppLog.i("Klipora Premium: all features unlocked, no ads");
        else if (!"robolectric".equals(Build.FINGERPRINT)) try { adsInitStart = android.os.SystemClock.elapsedRealtime(); AD_SDK_CALLS.incrementAndGet(); UnityAds.initialize(getApplicationContext(), UNITY_GAME_ID, false, this); } catch (Throwable t) { AppLog.e("Ads init: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
        JobManager.get().ensureLoaded(this);
        checkPreviousCrash();
        startUpdateCheck();   // code 31: "a new version is available" (free edition only; background, silent on failure)
        LegalNotice.showIfNeeded(this);   // one-time notice + Privacy/Terms links; changes nothing in the app
        if (savedInstanceState != null) restoreSource(savedInstanceState);   // code 29
        else handleShareIntent(getIntent());
    }

    /**
     * code 29: the pasted link and its video card survive the screen being rebuilt (dark/light or font-size change,
     * split screen, or the phone closing the app in the background while an ad or another app was open). 4.6 came
     * back to an empty form: the link had to be pasted again and the picks were gone.
     */
    @Override
    protected void onSaveInstanceState(@NonNull Bundle out) {
        super.onSaveInstanceState(out);
        if (srcYtUrl == null) return;
        out.putString("k29_url", srcYtUrl);
        out.putString("k29_title", srcTitle);
        out.putDouble("k29_dur", srcDuration);
        out.putString("k29_thumb", srcThumbUrl);
        out.putString("k29_audio", audioLangPick);
    }

    private void restoreSource(Bundle b) {
        String url = b.getString("k29_url");
        if (url == null || url.isEmpty() || etUrl == null) return;
        srcYtUrl = url;
        srcTitle = b.getString("k29_title");
        srcDuration = b.getDouble("k29_dur", 0);
        srcThumbUrl = b.getString("k29_thumb");
        etUrl.setText(url);
        showSourceCard("VIDEO LINK");   // the count chip picked before is put back by the system right after this
        final String pick = b.getString("k29_audio", "");
        loadAudioTracks(url);
        audioLangPick = pick == null ? "" : pick;   // kept when the language list comes back
        final String id = YtEngine.videoId(url);
        if (!"robolectric".equals(Build.FINGERPRINT)) new Thread(() -> {
            for (String q : new String[]{"maxresdefault", "hqdefault", "mqdefault"}) {
                try (Response r = Net.fast().newCall(new Request.Builder().url("https://i.ytimg.com/vi/" + id + "/" + q + ".jpg").build()).execute()) {
                    if (!r.isSuccessful() || r.body() == null) continue;
                    Bitmap t = BitmapFactory.decodeStream(r.body().byteStream());
                    if (t != null && t.getWidth() > 200) {
                        runOnUiThread(() -> { if (url.equals(srcYtUrl) && !isFinishing()) { srcThumb = t; ivThumb.setImageBitmap(t); } });
                        return;
                    }
                } catch (Exception ignored) {}
            }
        }, "thumb-restore").start();
    }

    @Override
    protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        handleShareIntent(intent);
    }

    /** "Share → Klipora" from the YouTube app fills the link. */
    private void handleShareIntent(Intent i) {
        if (i == null || !Intent.ACTION_SEND.equals(i.getAction())) return;
        String text = i.getStringExtra(Intent.EXTRA_TEXT);
        if (text != null && !YtEngine.videoId(text).isEmpty() && etUrl != null && !JobManager.get().isRunning()) {
            etUrl.setText(YtEngine.cleanUrl(text));
            fetchYt();
        }
    }

    @Override
    protected void onStart() {
        super.onStart();
        JobManager.get().addListener(this);
        AppLog.addListener(this);
        updatePremiumUI();
        checkKilledJob();
        if (fixReshowAt > 0) { boolean recent = System.currentTimeMillis() - fixReshowAt < 180_000; fixReshowAt = 0; if (recent) ui.postDelayed(() -> { if (!isFinishing()) { openBackgroundSettings(); updateBatterySub(); } }, 350); }
        updateApiStatus();
        startTicker();
        loadAd();   // v21: keep a rewarded ad ready (no-op while one is loaded or loading)
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (banner != null) try { banner.refresh(); } catch (Throwable ignored) {}
    }

    @Override
    protected void onDestroy() {
        if (banner != null) banner.destroy();
        adHandler.removeCallbacksAndMessages(null);
        dismissAdWait();
        super.onDestroy();
    }

    @Override
    protected void onStop() {
        JobManager.get().removeListener(this);
        AppLog.removeListener(this);
        ui.removeCallbacksAndMessages(null);
        // code 32: no ad reload retries while the screen is in the background (4.9 kept loading every minute through a
        // whole screen-off render in a no-fill area); onStart loads again
        if (adPending == null) adHandler.removeCallbacks(adRetryRun);
        super.onStop();
    }

    @Override
    public void onWindowFocusChanged(boolean hasFocus) {
        super.onWindowFocusChanged(hasFocus);
        if (hasFocus) checkClipboard();
    }

    // ================================================================== views

    private void bindViews() {
        tabCreate = findViewById(R.id.tabCreate);
        tabSettings = findViewById(R.id.tabSettings);
        panelInput = findViewById(R.id.panelInput);
        logsPanel = findViewById(R.id.logsPanel);
        panelResults = findViewById(R.id.panelEnhance);
        groupYt = findViewById(R.id.groupYt);
        layoutFetching = findViewById(R.id.layoutFetching);
        cardControls = findViewById(R.id.cardControls);
        layoutVideoInfo = findViewById(R.id.layoutVideoInfo);
        etUrl = findViewById(R.id.etYtUrlHome);
        tilUrl = findViewById(R.id.tilUrl);
        tvClipboardNotice = findViewById(R.id.tvClipboardNotice);
        tvFetching = findViewById(R.id.tvFetching);
        tvVideoTitle = findViewById(R.id.tvVideoTitle);
        tvVideoDuration = findViewById(R.id.tvVideoDuration);
        tvSourceKind = findViewById(R.id.tvSourceKind);
        tvCaptionSummary = findViewById(R.id.tvCaptionSummary);
        tvStatus = findViewById(R.id.tvStatus);
        tvTip = findViewById(R.id.tvLoadingTip);
        tvPercent = findViewById(R.id.tvProgressPercent);
        tvProcTitle = findViewById(R.id.tvProcTitle);
        tvResultsWarn = findViewById(R.id.tvResultsWarn);
        cardError = findViewById(R.id.cardError);
        tvResultsTitle = findViewById(R.id.tvResultsTitle);
        tvResultsSub = findViewById(R.id.tvResultsSub);
        badgePro = findViewById(R.id.badgePro);
        ivThumb = findViewById(R.id.ivVideoThumb);
        cgCount = findViewById(R.id.chipGroupShortsCount);
        cgCategory = findViewById(R.id.chipGroupCategory);
        cgFrame = findViewById(R.id.chipGroupFrame);
        countChips[0] = findViewById(R.id.chipCount1);
        countChips[1] = findViewById(R.id.chipCount2);
        countChips[2] = findViewById(R.id.chipCount3);
        countChips[3] = findViewById(R.id.chipCount4);
        countChips[4] = findViewById(R.id.chipCount5);
        countChips[5] = findViewById(R.id.chipCountAuto);
        swCaptions = findViewById(R.id.switchCaptions);
        swFaceTrack = findViewById(R.id.switchFaceTrack);
        tvFaceTrackSummary = findViewById(R.id.tvFaceTrackSummary);
        progressMain = findViewById(R.id.progressMain);
        tvEta = findViewById(R.id.tvEta);
        tvProcKicker = findViewById(R.id.tvProcKicker);
        tabHistory = findViewById(R.id.tabHistory);
        rvHistory = findViewById(R.id.rvHistory);
        historyEmpty = findViewById(R.id.historyEmpty);
        btnGenerate = findViewById(R.id.btnProcessRange);
        rvClips = findViewById(R.id.rvClips);
        nav = findViewById(R.id.bottomNav);

        tvAdsCount = findViewById(R.id.tvAdsCount);
        tvTimer = findViewById(R.id.tvTimerCountdown);
        tvPremiumStatus = findViewById(R.id.tvPremiumStatus);
        tvMasked = findViewById(R.id.tvMaskedKeys);
        tvGemStatus = findViewById(R.id.tvGeminiKeyStatus);
        tvGroqStatus = findViewById(R.id.tvGroqKeyStatus);
        tvHighSpeedStatus = findViewById(R.id.tvHighSpeedStatus);
        tvEngineSub = findViewById(R.id.btnUpdateYtEngineSub);
        progressAds = findViewById(R.id.progressAds);
        layoutAdProgress = findViewById(R.id.layoutAdProgress);
        howToKeys = findViewById(R.id.cardHowToGetKeys);
        dotGem = findViewById(R.id.dotGeminiStatus);
        dotGroq = findViewById(R.id.dotGroqStatus);
        crownHs = findViewById(R.id.iconCrownHighSpeed);
        swHighSpeed = findViewById(R.id.switchHighSpeed);

        nav.setOnItemSelectedListener(item -> {
            int id = item.getItemId();
            View show = id == R.id.nav_create ? tabCreate : id == R.id.nav_history ? tabHistory : tabSettings;
            for (View v : new View[]{tabCreate, tabHistory, tabSettings}) if (v != show) v.setVisibility(View.GONE);
            if (show.getVisibility() != View.VISIBLE) reveal(show);
            haptic(nav);
            if (id == R.id.nav_settings) { updatePremiumUI(); updateApiStatus(); updateBatterySub(); tvEngineSub.setText("yt-dlp version: " + YtEngine.version(this)); }
            if (id == R.id.nav_history) refreshHistory();
            return true;
        });
        nav.setOnItemReselectedListener(item -> {
            if (item.getItemId() == R.id.nav_history) rvHistory.smoothScrollToPosition(0);
            if (item.getItemId() == R.id.nav_create) ((androidx.core.widget.NestedScrollView) tabCreate).smoothScrollTo(0, 0);
        });
    }

    private void haptic(View v) {
        if (v != null && Prefs.haptics(this)) v.performHapticFeedback(android.view.HapticFeedbackConstants.KEYBOARD_TAP);
    }

    private void reveal(View v) {
        if (v == null) return;
        v.setVisibility(View.VISIBLE);
        v.setAlpha(0f);
        v.setTranslationY(24f);
        v.animate().alpha(1f).translationY(0f).setDuration(240).setInterpolator(new OvershootInterpolator(0.7f)).start();
    }

    @SuppressLint("ClickableViewAccessibility")
    private void bouncy(View v) {
        if (v == null) return;
        v.setOnTouchListener((x, e) -> {
            int a = e.getActionMasked();
            if (a == MotionEvent.ACTION_DOWN) x.animate().scaleX(0.96f).scaleY(0.96f).setDuration(90).start();
            else if (a == MotionEvent.ACTION_UP || a == MotionEvent.ACTION_CANCEL)
                x.animate().scaleX(1f).scaleY(1f).setDuration(160).setInterpolator(new OvershootInterpolator(1.6f)).start();
            return false;
        });
    }

    // ================================================================== create tab

    private void setupCreate() {
        // code 28 (Sakib, Nov 2): link input only - the "Upload video" (video from the phone) option was removed
        tilUrl.setEndIconOnClickListener(v -> pasteFromClipboard());
        etUrl.setOnEditorActionListener((v, actionId, e) -> {
            if (actionId == EditorInfo.IME_ACTION_GO || (e != null && e.getKeyCode() == KeyEvent.KEYCODE_ENTER)) { fetchYt(); return true; }
            return false;
        });
        findViewById(R.id.btnFetchYtHome).setOnClickListener(v -> fetchYt());
        findViewById(R.id.btnClearSource).setOnClickListener(v -> clearSource());
        btnGenerate.setOnClickListener(v -> generate());
        bouncy(btnGenerate);
        bouncy(findViewById(R.id.btnFetchYtHome));

        for (int i = 1; i < MAX_SHORTS; i++) {
            final int idx = i;
            countChips[i].setOnClickListener(v -> {
                if (!Prefs.isPremium(this) && !sessionCountUnlocked[idx]) {
                    cgCount.check(R.id.chipCount1);
                    askUnlockCount(idx, null);
                }
            });
        }

        FfmpegCommands.Frame fr = Prefs.frame(this);
        cgFrame.check(fr == FfmpegCommands.Frame.FILL ? R.id.chipFrameFill : fr == FfmpegCommands.Frame.FIT ? R.id.chipFrameFit : R.id.chipFrameBlur);
        cgFrame.setOnCheckedStateChangeListener((g, ids) -> { if (!ids.isEmpty()) Prefs.setFrame(this, selectedFrame()); });
        // v14: output format (9:16 default / 1:1 / 16:9), remembered
        ChipGroup cgAspect = findViewById(R.id.chipGroupAspect);
        FfmpegCommands.Aspect asp = Prefs.aspect(this);
        cgAspect.check(asp == FfmpegCommands.Aspect.SQUARE ? R.id.chipAspect11 : asp == FfmpegCommands.Aspect.LANDSCAPE ? R.id.chipAspect169 : R.id.chipAspect916);
        cgAspect.setOnCheckedStateChangeListener((g, ids) -> { if (!ids.isEmpty()) { Prefs.setAspect(this, selectedAspect()); updateAspectUi(); } });
        // v13: AI face tracking switch, remembered. v14: premium (1 ad = one video, PRO = unlimited), podcasts only
        swFaceTrack.setChecked(Prefs.faceTrack(this) && Prefs.faceTrackUnlocked(this));
        swFaceTrack.setOnCheckedChangeListener((b, on) -> {
            if (on && !Prefs.faceTrackUnlocked(this)) { b.setChecked(false); askUnlockFaceTrack(); return; }
            Prefs.setFaceTrack(this, on);
            updateFaceTrackSummary();
        });
        findViewById(R.id.rowFaceTrack).setOnClickListener(v -> swFaceTrack.toggle());
        updateAspectUi();

        swCaptions.setChecked(Prefs.captionsOn(this) && Prefs.captionsUnlocked(this));
        swCaptions.setOnCheckedChangeListener((b, on) -> {
            if (on && !Prefs.captionsUnlocked(this)) {
                b.setChecked(false);
                askUnlockCaptions(() -> swCaptions.setChecked(true));
                return;
            }
            Prefs.setCaptionsOn(this, on);
            updateCaptionSummary();
        });
        findViewById(R.id.btnCaptionStyle).setOnClickListener(v -> openStudio(-1));
        com.google.android.material.button.MaterialButton capLang = findViewById(R.id.btnCapLang);
        syncCreateLang();
        // v44: one searchable list of all 29 caption languages instead of a row of chips
        capLang.setOnClickListener(v -> LanguagePicker.show(this, Prefs.captionScript(this), s -> {
            Prefs.setCaptionScript(this, s);
            updateCaptionSummary();
        }));
        updateCaptionSummary();

        findViewById(R.id.btnCancel).setOnClickListener(v -> new MaterialAlertDialogBuilder(this)
                .setTitle("Stop processing?").setMessage("The current job will be cancelled.")
                .setPositiveButton("Stop", (d, w) -> ProcessingService.cancel(this))
                .setNegativeButton("Keep going", null).show());
        findViewById(R.id.btnErrorClose).setOnClickListener(v -> hideErrorCard());
        findViewById(R.id.btnErrorRetry).setOnClickListener(v -> { Runnable r = errorRetry; hideErrorCard(); if (r != null) r.run(); });
        findViewById(R.id.btnErrorReport).setOnClickListener(v -> sendBugReport(errorTitle, errorMsg));
        findViewById(R.id.btnErrorFix).setOnClickListener(v -> openBackgroundSettings());
        findViewById(R.id.btnErrorUpdate).setOnClickListener(v -> {
            com.viral.shortsmaker.core.UpdateCheck.Info nk = com.viral.shortsmaker.core.UpdateCheck.newerKnown(this);
            if (nk != null) showUpdateDialog(nk);
        });
        bouncy(findViewById(R.id.btnErrorRetry));
        bouncy(findViewById(R.id.btnErrorReport));
        findViewById(R.id.btnNewProject).setOnClickListener(v -> newProject());
        findViewById(R.id.btnCaptionAll).setOnClickListener(v -> openStudio(-2));
        findViewById(R.id.btnSaveAll).setOnClickListener(v -> saveAll());

        rvClips.setLayoutManager(new LinearLayoutManager(this));
        clipAdapter = new ClipAdapter();
        rvClips.setAdapter(clipAdapter);
        rvClips.setItemAnimator(null);
    }

    private com.google.android.material.materialswitch.MaterialSwitch swFaceTrack;
    private TextView tvFaceTrackSummary;

    private void updateFaceTrackSummary() {
        if (tvFaceTrackSummary == null || swFaceTrack == null) return;
        boolean prem = Prefs.isPremium(this);
        int credits = Prefs.faceTrackCredits(this);
        View lock = findViewById(R.id.ivFaceLock);
        if (lock != null) lock.setVisibility(Prefs.faceTrackUnlocked(this) ? View.GONE : View.VISIBLE);
        boolean wide = selectedAspect() == FfmpegCommands.Aspect.LANDSCAPE;
        String s;
        if (wide) s = "Not used in 16:9 (the whole frame is kept)";
        else if (!Prefs.faceTrackUnlocked(this)) s = "Podcasts only • Premium: 1 ad = your next podcast";
        else if (!swFaceTrack.isChecked()) s = "Off: uses the framing above";
        else s = "On for podcasts (auto-detected): the camera follows the speaker" + (prem ? " • " + proName() : credits > 0 ? " • " + credits + " unlocked" : "");
        tvFaceTrackSummary.setText(s);
    }

    private void askUnlockFaceTrack() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Unlock AI face tracking")
                .setMessage("AI face tracking is a premium feature for podcasts. Watch one short ad to use it on your next podcast. PRO (10 ads) makes it unlimited for 24 hours.")
                .setPositiveButton("Watch ad", (d, w) -> showRewardedAd(() -> {
                    Prefs.addFaceTrackCredit(this);
                    toast("AI face tracking unlocked for your next podcast");
                    swFaceTrack.setChecked(true);
                    updateFaceTrackSummary();
                }))
                .setNegativeButton("Not now", null).show();
    }

    private FfmpegCommands.Aspect selectedAspect() {
        ChipGroup g = findViewById(R.id.chipGroupAspect);
        int id = g == null ? View.NO_ID : g.getCheckedChipId();
        return id == R.id.chipAspect11 ? FfmpegCommands.Aspect.SQUARE : id == R.id.chipAspect169 ? FfmpegCommands.Aspect.LANDSCAPE : FfmpegCommands.Aspect.PORTRAIT;
    }

    /** v14: labels follow the chosen format */
    private void updateAspectUi() {
        TextView fl = findViewById(R.id.tvFrameLabel);
        if (fl != null) fl.setText(selectedAspect().label + " framing");
        updateFaceTrackSummary();
    }

    private FfmpegCommands.Frame selectedFrame() {
        int id = cgFrame.getCheckedChipId();
        return id == R.id.chipFrameFill ? FfmpegCommands.Frame.FILL : id == R.id.chipFrameFit ? FfmpegCommands.Frame.FIT : FfmpegCommands.Frame.BLUR;
    }

    private boolean syncingLang;

    /** v6: caption language chips on Create mirror the saved choice (also changed in Settings / Caption Studio) */
    private void syncCreateLang() {
        android.widget.TextView g = findViewById(R.id.btnCapLang);
        if (g == null) return;
        g.setText(LanguagePicker.labelFor(Prefs.captionScript(this)));   // v44: the picker's label
    }

    private void updateCaptionSummary() {
        syncCreateLang();
        CaptionStyle s = Prefs.captionStyle(this);
        String anim;
        switch (s.anim) {
            case KARAOKE: anim = "karaoke fill"; break;
            case STATIC: anim = "line by line"; break;
            case REVEAL: anim = "word reveal"; break;
            case BOX_WORD: anim = "highlight box"; break;
            default: anim = "word-by-word highlight";
        }
        if (!Prefs.captionsUnlocked(this)) {
            if (swCaptions.isChecked()) swCaptions.setChecked(false);
            tvCaptionSummary.setText("Premium • 1 ad = captions on 1 video");
            View clr = findViewById(R.id.btnCapLang);
            if (clr != null) clr.setVisibility(View.GONE);
            findViewById(R.id.btnCaptionStyle).setEnabled(true);
            return;
        }
        View capLangRow = findViewById(R.id.btnCapLang);
        if (capLangRow != null) capLangRow.setVisibility(swCaptions.isChecked() ? View.VISIBLE : View.GONE);
        int capLeft = Prefs.isPremium(this) ? -1 : Prefs.captionCredits(this);
        tvCaptionSummary.setText(swCaptions.isChecked() ? s.name + " • " + anim + " • " + CaptionScript.label(Prefs.captionScript(this))
                + (capLeft > 0 ? " • " + capLeft + " video" + (capLeft > 1 ? "s" : "") + " unlocked" : "") : "Off — shorts without captions");
        findViewById(R.id.btnCaptionStyle).setEnabled(swCaptions.isChecked());
    }

    private void openStudio(int clip) {
        Project cur = JobManager.get().project;
        if (clip != -1 && !(cur != null && cur.captionsPaid) && !Prefs.captionsUnlocked(this)) { askUnlockCaptions(() -> openStudio(clip)); return; }
        if (clip == -1) {
            CaptionStudioActivity.pendingFrame = srcThumb;
            CaptionStudioActivity.pendingFrameMode = selectedFrame();
            CaptionStudioActivity.pendingAspect = selectedAspect();
        }
        startActivityForResult(new Intent(this, CaptionStudioActivity.class).putExtra(CaptionStudioActivity.EXTRA_CLIP, clip), 77);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode == 77) { swCaptions.setChecked(Prefs.captionsOn(this)); updateCaptionSummary(); }
    }

    /** true when the clipboard holds plain text (never touches images/screenshots, so Android shows no paste toast) */
    private static boolean clipIsText(android.content.ClipDescription d) {
        if (d == null) return false;
        boolean text = d.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_PLAIN) || d.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_HTML)
                || d.hasMimeType(android.content.ClipDescription.MIMETYPE_TEXT_URILIST);
        return text && !d.hasMimeType("image/*") && !d.hasMimeType("video/*");
    }

    private void pasteFromClipboard() {
        try {
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb == null || !cb.hasPrimaryClip()) { toast("Clipboard is empty"); return; }
            if (!clipIsText(cb.getPrimaryClipDescription())) { toast("No link in clipboard. Copy a video link first."); return; }
            ClipData clip = cb.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) { toast("Clipboard is empty"); return; }
            CharSequence t = clip.getItemAt(0).getText();
            String url = t == null ? "" : extractUrl(t.toString());
            if (url.isEmpty() || YtEngine.videoId(url).isEmpty()) { toast("That's not a supported video link"); return; }
            etUrl.setText(YtEngine.cleanUrl(url));
            etUrl.setSelection(etUrl.length());
        } catch (Exception ignored) {}
    }

    /** first http(s) link inside shared/copied text ("Watch this! https://youtu.be/..." → the link) */
    static String extractUrl(String s) {
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("https?://\\S+").matcher(s);
        return m.find() ? m.group() : s.trim();
    }

    private long lastClipStamp = -1;

    /**
     * Auto-fill only a real YouTube link. v2 read every clipboard item on each focus change, so Android announced
     * "pasted from your clipboard" even for screenshots. Now: the clip's description is checked first (no read, no
     * toast), each clip is looked at once, and on Android 12+ text the system did not classify as a link is skipped.
     */
    private void checkClipboard() {
        try {
            if (etUrl == null || etUrl.length() > 0 || srcYtUrl != null || JobManager.get().isRunning()) return;
            if (tabCreate.getVisibility() != View.VISIBLE) return;
            ClipboardManager cb = (ClipboardManager) getSystemService(CLIPBOARD_SERVICE);
            if (cb == null || !cb.hasPrimaryClip()) return;
            android.content.ClipDescription d = cb.getPrimaryClipDescription();
            if (!clipIsText(d)) return;
            if (Build.VERSION.SDK_INT >= 26) {
                long stamp = d.getTimestamp();
                if (stamp == lastClipStamp) return;
                lastClipStamp = stamp;
            }
            if (Build.VERSION.SDK_INT >= 31 && d.getClassificationStatus() == android.content.ClipDescription.CLASSIFICATION_COMPLETE
                    && d.getConfidenceScore(android.view.textclassifier.TextClassifier.TYPE_URL) < 0.3f) return;
            ClipData clip = cb.getPrimaryClip();
            if (clip == null || clip.getItemCount() == 0) return;
            CharSequence t = clip.getItemAt(0).getText();
            if (t != null && YtEngine.isStandaloneYoutubeUrl(t.toString())) {
                etUrl.setText(YtEngine.cleanUrl(t.toString().trim()));
                tvClipboardNotice.setVisibility(View.VISIBLE);
            }
        } catch (Exception ignored) {}
    }

    private void fetchYt() {
        if (fetching) return;
        String raw = etUrl.getText() == null ? "" : etUrl.getText().toString();
        if (YtEngine.videoId(raw).isEmpty()) { tilUrl.setError("Enter a valid video link"); return; }
        tilUrl.setError(null);
        tvClipboardNotice.setVisibility(View.GONE);
        final String url = YtEngine.cleanUrl(raw);
        fetching = true;
        layoutFetching.setVisibility(View.VISIBLE);
        tvFetching.setText("Reading video info…");
        layoutVideoInfo.setVisibility(View.GONE);
        cardControls.setVisibility(View.GONE);
        new Thread(() -> {
            try {
                YtEngine.prefetchStreams(url);   // v8: stream links are read while the user picks options
                YtEngine.Info info = YtEngine.fetchInfo(url);
                Bitmap thumb = null;
                for (String q : new String[]{"maxresdefault", "hqdefault", "mqdefault"}) {
                    try (Response r = Net.fast().newCall(new Request.Builder().url("https://i.ytimg.com/vi/" + YtEngine.videoId(url) + "/" + q + ".jpg").build()).execute()) {
                        if (r.isSuccessful() && r.body() != null) {
                            InputStream in = r.body().byteStream();
                            thumb = BitmapFactory.decodeStream(in);
                            if (thumb != null && thumb.getWidth() > 200) break;
                        }
                    } catch (Exception ignored) {}
                }
                final Bitmap fb = thumb;
                runOnUiThread(() -> {
                    fetching = false;
                    if (isFinishing()) return;
                    layoutFetching.setVisibility(View.GONE);
                    srcYtUrl = url;
                    srcTitle = info.title;
                    srcDuration = info.duration;
                    srcThumbUrl = info.thumbUrl;
                    srcThumb = fb;
                    showSourceCard("VIDEO LINK");
                    loadAudioTracks(url);   // code 28: audio language picker for multi-language videos
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    fetching = false;
                    layoutFetching.setVisibility(View.GONE);
                    showError("Couldn't load this video", e.getMessage());
                });
            }
        }).start();
    }

    // ================================================================== code 28: audio language picker

    /** the audio languages of the current video (fewer than 2 = picker hidden) and the one picked ("" = original) */
    private java.util.List<com.viral.shortsmaker.core.AudioTracks.Track> audioTracks = new ArrayList<>();
    private String audioLangPick = "";
    private com.viral.shortsmaker.core.CancelToken audioLookup = new com.viral.shortsmaker.core.CancelToken();

    /**
     * Reads which audio languages the video has, without holding anything up: the direct player call answers in about
     * a second, the full stream list (read in the background since the link was pasted) a few seconds later and may
     * list more dubs. The section appears only for a video with 2+ languages; Generate never waits for it.
     */
    private void loadAudioTracks(final String url) {
        audioTracks = new ArrayList<>();
        audioLangPick = "";
        showAudioTracks(url);
        audioLookup.cancelled = true;   // a lookup for the previous link stops waiting
        final com.viral.shortsmaker.core.CancelToken tok = new com.viral.shortsmaker.core.CancelToken();
        audioLookup = tok;
        Thread t = new Thread(() -> {
            try {
                java.util.List<com.viral.shortsmaker.core.AudioTracks.Track> q = YtEngine.audioTracksQuick(url);
                if (q.size() >= 2) runOnUiThread(() -> mergeAudioTracks(url, q));
                java.util.List<com.viral.shortsmaker.core.AudioTracks.Track> f = YtEngine.audioTracksFull(url, tok);
                if (f.size() >= 2) runOnUiThread(() -> mergeAudioTracks(url, f));
                AppLog.i("Audio languages of this video: " + Math.max(q.size(), f.size()));
            } catch (Throwable e) {
                AppLog.i("Audio language list unavailable (" + e.getClass().getSimpleName() + "); the original audio will be used");
            }
        }, "audio-langs");
        t.setDaemon(true);
        t.start();
    }

    private void mergeAudioTracks(String url, java.util.List<com.viral.shortsmaker.core.AudioTracks.Track> l) {
        if (isFinishing() || url == null || !url.equals(srcYtUrl)) return;
        if (l.size() <= audioTracks.size()) return;   // keep the longer list (the full one can list more dubs)
        audioTracks = new ArrayList<>(l);
        showAudioTracks(url);
    }

    private void showAudioTracks(String url) {
        View g = findViewById(R.id.groupAudioLang);
        ChipGroup cg = findViewById(R.id.chipGroupAudioLang);
        if (g == null || cg == null) return;
        if (audioTracks.size() < 2 || url == null || !url.equals(srcYtUrl)) { g.setVisibility(View.GONE); cg.removeAllViews(); return; }
        String keep = audioLangPick.isEmpty() ? com.viral.shortsmaker.core.AudioTracks.defaultCode(audioTracks) : audioLangPick;
        cg.setOnCheckedStateChangeListener(null);
        cg.removeAllViews();
        int checkId = View.NO_ID;
        for (com.viral.shortsmaker.core.AudioTracks.Track t : audioTracks) {
            Chip c = new Chip(this);
            c.setId(View.generateViewId());
            c.setText(t.label());
            c.setTag(t.code);
            c.setCheckable(true);
            cg.addView(c);
            if (t.code.equalsIgnoreCase(keep) || checkId == View.NO_ID && t.original) checkId = c.getId();
        }
        if (checkId == View.NO_ID && cg.getChildCount() > 0) checkId = cg.getChildAt(0).getId();
        cg.check(checkId);
        cg.setOnCheckedStateChangeListener((group, ids) -> {
            if (ids.isEmpty()) return;
            View v = group.findViewById(ids.get(0));
            String code = v == null || v.getTag() == null ? "" : v.getTag().toString();
            audioLangPick = com.viral.shortsmaker.core.AudioTracks.isOriginalCode(audioTracks, code) ? "" : code;
            updateAudioLangHint();
        });
        View sel = cg.findViewById(checkId);
        String code = sel == null || sel.getTag() == null ? "" : sel.getTag().toString();
        audioLangPick = com.viral.shortsmaker.core.AudioTracks.isOriginalCode(audioTracks, code) ? "" : code;
        updateAudioLangHint();
        if (g.getVisibility() != View.VISIBLE) reveal(g);
    }

    private void updateAudioLangHint() {
        TextView h = findViewById(R.id.tvAudioLangHint);
        if (h == null) return;
        String n = audioLangName();
        h.setText(audioLangPick.isEmpty()
                ? audioTracks.size() + " audio languages • the shorts use the original audio"
                : "Shorts use the " + n + " audio; captions are made from it");
    }

    /** English name of the picked audio language ("" = original) */
    private String audioLangName() {
        for (com.viral.shortsmaker.core.AudioTracks.Track t : audioTracks) if (t.code.equalsIgnoreCase(audioLangPick)) return t.name;
        return "";
    }

    private void showSourceCard(String kind) {
        tvSourceKind.setText(kind);
        tvVideoTitle.setText(srcTitle);
        tvVideoDuration.setText(fmt(srcDuration));
        if (srcThumb != null) ivThumb.setImageBitmap(srcThumb); else ivThumb.setImageDrawable(null);
        reveal(layoutVideoInfo);
        reveal(cardControls);
        updatePremiumUI();
        cgCount.check(defaultCountChip());
    }

    private void clearSource() {
        srcYtUrl = null; srcThumb = null; srcTitle = null; srcDuration = 0;
        audioTracks = new ArrayList<>(); audioLangPick = "";
        showAudioTracks(null);
        layoutVideoInfo.setVisibility(View.GONE);
        cardControls.setVisibility(View.GONE);
        etUrl.setText("");
    }

    /** the unlock dialog of a 2-5 count chip; {@code after} runs once it is unlocked (e.g. Generate again) */
    private void askUnlockCount(int idx, Runnable after) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Unlock " + countLabel(idx))
                .setMessage("Watch one short ad to unlock " + countLabel(idx) + " for this session. (Watch 10 ads for 24-hour PRO.)")
                .setPositiveButton("Watch ad", (d, w) -> showRewardedAd(() -> {
                    sessionCountUnlocked[idx] = true;
                    updatePremiumUI();
                    cgCount.check(countChips[idx].getId());
                    toast(countLabel(idx) + " unlocked!");
                    if (after != null && !isFinishing() && !isDestroyed()) after.run();
                }))
                .setNegativeButton("Not now", null).show();
    }

    /**
     * code 29: the count chip shown as picked but no longer unlocked (the app was closed by the phone while an ad played,
     * or PRO ran out after it was picked): index of that chip, else -1. 4.6 then quietly made 1 short.
     */
    int lockedCountChip() {
        int id = cgCount.getCheckedChipId();
        if (Prefs.isPremium(this)) return -1;
        for (int i = 1; i < MAX_SHORTS; i++) if (countChips[i].getId() == id && !sessionCountUnlocked[i]) return i;
        return -1;
    }

    private int selectedCount() {
        int id = cgCount.getCheckedChipId();
        for (int i = 0; i < MAX_SHORTS; i++) if (countChips[i].getId() == id) {
            // v21 audit: a 2-5 chip picked while PRO was active stayed picked after PRO ran out = more shorts for free
            if (i > 0 && !Prefs.isPremium(this) && !sessionCountUnlocked[i]) { cgCount.check(R.id.chipCount1); return 1; }
            return COUNT_VALUES[i] <= 0 ? 3 : COUNT_VALUES[i];
        }
        return 1;
    }

    private static String countLabel(int idx) {
        int v = COUNT_VALUES[idx];
        return v == 0 ? "Auto shorts" : v + " shorts";
    }

    /** v8: PRO opens on Auto (more shorts from long videos); free opens on 1 */
    private int defaultCountChip() {
        return Prefs.isPremium(this) ? R.id.chipCount3 : R.id.chipCount1;   // v14: no Auto (max 5, his choice)
    }

    private com.viral.shortsmaker.PromptManager.Category selectedCategory() {
        int id = cgCategory.getCheckedChipId();
        if (id == R.id.chipPodcast) return PromptManager.Category.PODCAST;
        if (id == R.id.chipMovies) return PromptManager.Category.MOVIES_SERIES;
        if (id == R.id.chipGaming) return PromptManager.Category.GAMING;
        return PromptManager.Category.OTHER;
    }

    /** 5.3: true while the Generate tap checks the keys (a second tap waits for the same check) */
    private boolean keyCheckRunning;

    private void generate() {
        if (!Prefs.keysValid(this)) { toast("Add your Gemini and Groq keys first"); showApiKeyDialog(); return; }
        if (srcYtUrl == null) { toast("Paste a video link first"); return; }
        if (JobManager.get().isRunning()) { toast("Already processing"); return; }
        if (!preflightKeys(this::generate)) return;
        int lockedCount = lockedCountChip();   // code 29: never start quietly with 1 short when more are shown as picked
        if (lockedCount > 0) { askUnlockCount(lockedCount, this::generate); return; }
        if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
                && !Prefs.sp(this).getBoolean("asked_notif", false)) {
            Prefs.sp(this).edit().putBoolean("asked_notif", true).apply();
            pendingAfterPermission = this::generate;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.POST_NOTIFICATIONS}, 101);
            return;
        }
        if (maybeAskBattery(this::generate)) return;
        if (swCaptions.isChecked() && !Prefs.captionsUnlocked(this)) {
            new MaterialAlertDialogBuilder(this).setTitle("Captions need 1 ad")
                    .setMessage("1 ad adds captions to this video. Or create the shorts without captions.")
                    .setPositiveButton("Watch ad", (d, w) -> askUnlockCaptions(this::startJob))
                    .setNegativeButton("Without captions", (d, w) -> { swCaptions.setChecked(false); startJob(); })
                    .show();
            return;
        }
        syncQualityUi();
        startJob();
    }

    private void startJob() {
        Pipeline.Request rq = new Pipeline.Request();
        rq.kind = JobManager.Kind.ANALYZE;
        rq.ytUrl = srcYtUrl;
        rq.audioLang = audioLangPick;          // code 28: "" = the original audio track
        rq.audioLangName = audioLangName();
        rq.title = srcTitle;
        rq.duration = srcDuration;
        rq.thumbUrl = srcThumbUrl;
        rq.count = selectedCount();
        rq.category = selectedCategory();
        rq.frame = selectedFrame();
        rq.faceTrack = swFaceTrack != null && swFaceTrack.isChecked() && Prefs.faceTrackUnlocked(this);
        rq.aspect = selectedAspect();
        if (swCaptions.isChecked()) {
            rq.style = Prefs.captionStyle(this);
            rq.layout = Prefs.captionLayout(this, rq.aspect);
            rq.script = Prefs.captionScript(this);
        }
        hideErrorCard();
        lastRequest = rq;
        Prefs.sp(this).edit().putString("last_request", rq.toJson()).apply();
        haptic(btnGenerate);
        if (!ProcessingService.start(this, rq)) toast("Already processing");
        else JobManager.get().title = srcTitle == null ? "" : srcTitle;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        Runnable r = pendingAfterPermission;
        pendingAfterPermission = null;
        // code 32: storage refused (Android 7-9) = stop here. 4.9 re-ran the save, which asked again: an endless dialog,
        // and with "Don't ask again" an instant refusal -> a tight loop on the main thread (frozen screen)
        boolean granted = grantResults.length > 0 && grantResults[0] == android.content.pm.PackageManager.PERMISSION_GRANTED;
        if (requestCode == 102 && !granted) { toast("Allow storage access to save shorts to your gallery"); return; }
        if (r != null) r.run();   // 101 (notifications): the job goes on either way
    }

    private void newProject() {
        JobManager.get().reset();
        clearSource();
    }

    // ================================================================== job state → UI

    private boolean wasRunning;

    @Override
    public void onJobChanged(JobManager jm) {
        if (isFinishing()) return;
        boolean running = jm.isRunning();
        Project pr = jm.project;
        boolean showResults = !running && jm.state == JobManager.State.DONE && pr != null && !pr.clips.isEmpty();
        setVis(logsPanel, running);
        setVis(panelInput, !running && !showResults);
        setVis(panelResults, showResults);
        if (running && Prefs.keepScreenOn(this)) getWindow().addFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        else getWindow().clearFlags(android.view.WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);
        if (running) { renderProgress(jm); hideErrorCard(); }
        if (wasRunning && !running && jm.state == JobManager.State.DONE && Prefs.haptics(this)) nav.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        wasRunning = running;
        if (showResults) {
            int n = pr.clips.size();
            tvResultsTitle.setText(n + (n == 1 ? " short is ready" : " shorts are ready"));
            tvResultsSub.setText(pr.title);
            clipAdapter.setProject(pr);
            String w = jm.warning;
            tvResultsWarn.setText(w == null ? "" : "⚠ " + w);
            tvResultsWarn.setVisibility(w == null || w.isEmpty() ? View.GONE : View.VISIBLE);
            if (jm.kind == JobManager.Kind.CAPTION && jm.error != null && !jm.error.isEmpty()) {
                String err = jm.error;
                jm.error = "";
                showError("Captions failed", err);
            }
        }
        if (!running && tabHistory.getVisibility() == View.VISIBLE) refreshHistory();
        if (jm.state == JobManager.State.FAILED && jm.error != null && !jm.error.isEmpty()) {
            String err = jm.error;
            jm.error = "";
            jm.state = JobManager.State.IDLE;
            // code 32: the saved request when this screen was re-created during the job (4.9: no Retry button)
            final Pipeline.Request rq = lastRequest != null ? lastRequest : jm.kind == JobManager.Kind.ANALYZE ? savedRequest() : null;
            if (rq != null) rq.resumeId = "";
            showErrorCard("Couldn't create shorts", err, rq != null && rq.kind == JobManager.Kind.ANALYZE ? () -> {
                if (!JobManager.get().isRunning() && !ProcessingService.start(this, rq)) toast("Already processing");
            } : null, false);
            if (Prefs.haptics(this)) nav.performHapticFeedback(android.view.HapticFeedbackConstants.LONG_PRESS);
        } else if (jm.state == JobManager.State.CANCELLED) {
            jm.state = JobManager.State.IDLE;
            toast("Cancelled");
        }
    }

    /** v3 processing card: real overall %, ETA, current step text with live numbers, per-step bars and times */
    private void renderProgress(JobManager jm) {
        boolean cap = jm.kind == JobManager.Kind.CAPTION;
        tvProcKicker.setText(cap ? "UPDATING CAPTIONS" : "CREATING YOUR SHORTS");
        String t = jm.title != null && !jm.title.isEmpty() ? jm.title : (srcTitle != null ? srcTitle : "Your video");
        tvProcTitle.setText(t);
        progressMain.setProgressCompat(Math.max(8, jm.percent * 10), true);
        tvPercent.setText(jm.percent + "%");
        String elapsed = "Elapsed " + fmt((System.currentTimeMillis() - jm.startedAt) / 1000.0);
        tvEta.setText(jm.etaSec > 0 ? "About " + com.viral.shortsmaker.core.MainEta.fmt(jm.etaSec) + " left\n" + elapsed : elapsed);
        tvStatus.setText(jm.status);
        String d = jm.detail == null ? "" : jm.detail;
        if (jm.frac[jm.stage] < 0) {
            double s = jm.stepSeconds(jm.stage);
            if (s >= 2) d = (d.isEmpty() ? "" : d + "  ") + "(" + (int) s + "s)";
        }
        tvTip.setText(d);
        tvTip.setVisibility(d.isEmpty() ? View.GONE : View.VISIBLE);
        boolean captionsOn = cap || swCaptions.isChecked();
        for (int i = 0; i < JobManager.N; i++) {
            View row = findViewById(getResources().getIdentifier("step" + i, "id", getPackageName()));
            row.setVisibility(jm.skip[i] ? View.GONE : View.VISIBLE);
            if (jm.skip[i]) continue;
            int state = i < jm.stage ? 2 : i == jm.stage ? 1 : 0;
            setStep(i, state, jm);
        }
        TextView s3 = findViewById(R.id.stepText3);
        s3.setText(captionsOn && !cap ? "Render shorts + captions" : "Render shorts");
        TextView s0 = findViewById(R.id.stepText0);
        s0.setText("Download audio");
        TextView s4 = findViewById(R.id.stepText4);
        s4.setText(JobManager.get().kind == JobManager.Kind.CAPTION && JobManager.get().captionRemove ? "Remove captions" : "Re-render with captions");
    }

    private void setVis(View v, boolean show) {
        if (show && v.getVisibility() != View.VISIBLE) reveal(v);
        else if (!show) v.setVisibility(View.GONE);
    }

    /** 0 = pending, 1 = active, 2 = done. v6: the step's own circle is the progress ring (no spinner on top). */
    private void setStep(int i, int state, JobManager jm) {
        StepRingView ring = findViewById(getResources().getIdentifier("stepDot" + i, "id", getPackageName()));
        View icon = findViewById(getResources().getIdentifier("stepIcon" + i, "id", getPackageName()));
        TextView text = findViewById(getResources().getIdentifier("stepText" + i, "id", getPackageName()));
        TextView meta = findViewById(getResources().getIdentifier("stepMeta" + i, "id", getPackageName()));
        ring.setState(state);
        boolean popNow = state == 2 && icon.getVisibility() != View.VISIBLE;
        icon.setVisibility(state == 2 ? View.VISIBLE : View.INVISIBLE);
        if (popNow) { icon.setScaleX(0f); icon.setScaleY(0f); icon.animate().scaleX(1f).scaleY(1f).setDuration(320).setInterpolator(new OvershootInterpolator(2.2f)).start(); }
        text.setTextColor(state == 2 ? 0xFFF4F4F8 : state == 1 ? 0xFFFFFFFF : 0xFF6B6B80);
        text.setTypeface(null, state == 1 ? android.graphics.Typeface.BOLD : android.graphics.Typeface.NORMAL);
        double f = jm.frac[i];
        if (state == 1) {
            if (f < 0) {
                // no real progress signal (AI step): the ring sweeps on the time this step usually takes on this phone
                long st = jm.t0[i] > 0 ? jm.t0[i] : System.currentTimeMillis();
                ring.setEstimated(st, jm.est[i] > 0 ? jm.est[i] : 20);
                meta.setText("");
            } else {
                ring.setProgress(f);
                meta.setText(Math.round(f * 100) + "%");
            }
            meta.setTextColor(0xFFB9A8FF);
        } else {
            double s = jm.stepSeconds(i);
            meta.setText(state == 2 && s >= 0 ? (s < 10 ? String.format(java.util.Locale.US, "%.1fs", s) : fmt(s)) : "");
            meta.setTextColor(0xFF6B6B80);
        }
    }

    // ================================================================== logs

    @Override
    public void onLog(AppLog.Entry e) {
        TextView t = sheetLogs;
        if (t != null) {
            // code 32: the open Logs sheet keeps the last 400 lines (4.9 appended without end during a job: jank)
            if (++sheetAppended > 200) { sheetAppended = 0; t.setText(logTail(400)); }
            else t.append(line(e));
        }
    }

    private CharSequence line(AppLog.Entry e) {
        SpannableStringBuilder sb = new SpannableStringBuilder(e.time + "  " + e.msg + "\n");
        sb.setSpan(new ForegroundColorSpan(e.error ? 0xFFF43F5E : 0xFF9B9BB0), 0, sb.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        sb.setSpan(new ForegroundColorSpan(0xFF6B6B80), 0, e.time.length(), Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        return sb;
    }

    private int sheetAppended;

    private CharSequence logTail(int max) {
        SpannableStringBuilder sb = new SpannableStringBuilder();
        List<AppLog.Entry> all = AppLog.snapshot();
        for (int i = Math.max(0, all.size() - max); i < all.size(); i++) sb.append(line(all.get(i)));
        return sb;
    }

    /** Settings → Logs: the technical log lives here only (not on the processing screen). */
    private void showLogsSheet() {
        com.google.android.material.bottomsheet.BottomSheetDialog d = new com.google.android.material.bottomsheet.BottomSheetDialog(this);
        View v = LayoutInflater.from(this).inflate(R.layout.sheet_logs, null);
        TextView tv = v.findViewById(R.id.tvSheetLogs);
        SpannableStringBuilder sb = new SpannableStringBuilder();
        List<AppLog.Entry> all = AppLog.snapshot();
        for (int i = Math.max(0, all.size() - 400); i < all.size(); i++) sb.append(line(all.get(i)));
        tv.setText(sb.length() == 0 ? "No logs yet. Logs of your next job will appear here." : sb);
        v.findViewById(R.id.btnLogsCopy).setOnClickListener(x -> { copy("Klipora logs", AppLog.plain()); toast("Logs copied"); });
        v.findViewById(R.id.btnLogsShare).setOnClickListener(x -> {
            try { startActivity(Intent.createChooser(new Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, deviceInfo() + "\n" + AppLog.plain()), "Share logs")); }
            catch (Exception ex) { toast("No app to share with"); }
        });
        v.findViewById(R.id.btnLogsReport).setOnClickListener(x -> sendBugReport("Report from logs", "(describe what happened)"));
        // v17: the log box takes at most ~42% of the screen and the sheet opens fully, so Copy / Share / Report are
        // always on screen (small phones showed them below the fold)
        ScrollView box = v.findViewById(R.id.scrollSheetLogs);
        int maxH = (int) (getResources().getDisplayMetrics().heightPixels * 0.42f);
        if (box.getLayoutParams().height > maxH) box.getLayoutParams().height = maxH;
        d.setContentView(v);
        d.getBehavior().setSkipCollapsed(true);
        d.getBehavior().setState(com.google.android.material.bottomsheet.BottomSheetBehavior.STATE_EXPANDED);
        sheetLogs = tv;
        d.setOnDismissListener(x -> sheetLogs = null);
        d.show();
        ScrollView sc = v.findViewById(R.id.scrollSheetLogs);
        sc.post(() -> sc.fullScroll(View.FOCUS_DOWN));
    }

    // ================================================================== errors (never silent)

    private void showErrorCard(String title, String msg, Runnable retry, boolean backgroundFix) {
        errorTitle = title;
        errorMsg = msg == null ? "Unknown error" : msg;
        errorRetry = retry;
        ((MaterialButton) findViewById(R.id.btnErrorRetry)).setText("Retry");
        stoppedJobReport = null;
        ((TextView) findViewById(R.id.tvErrorTitle)).setText(title);
        ((TextView) findViewById(R.id.tvErrorMsg)).setText(errorMsg);
        findViewById(R.id.btnErrorRetry).setVisibility(retry != null ? View.VISIBLE : View.GONE);
        findViewById(R.id.btnErrorFix).setVisibility(backgroundFix || isBackgroundIssue(errorMsg) ? View.VISIBLE : View.GONE);
        // code 31: an error on an old build: the newer one may already have the fix
        findViewById(R.id.btnErrorUpdate).setVisibility(com.viral.shortsmaker.core.UpdateCheck.newerKnown(this) != null ? View.VISIBLE : View.GONE);
        if (tabCreate.getVisibility() != View.VISIBLE) nav.setSelectedItemId(R.id.nav_create);
        errorToken++;
        errorHiding = false;
        cardError.animate().cancel();
        cardError.setVisibility(View.GONE);
        reveal(cardError);
        ((androidx.core.widget.NestedScrollView) tabCreate).smoothScrollTo(0, 0);
    }

    private int errorToken;

    private void hideErrorCard() {
        if (cardError == null || cardError.getVisibility() != View.VISIBLE || errorHiding) return;
        errorHiding = true;
        final int tok = errorToken;
        cardError.animate().alpha(0f).setDuration(160).withEndAction(() -> {
            errorHiding = false;
            if (tok == errorToken) cardError.setVisibility(View.GONE);
            cardError.setAlpha(1f);
        }).start();
    }

    private boolean errorHiding;

    private static boolean isBackgroundIssue(String m) {
        String l = m == null ? "" : m.toLowerCase(Locale.US);
        return l.contains("background") || l.contains("battery") || l.contains("killed");
    }

    private void sendBugReport(String title, String summary) {
        // code 30: the job's own link/title when the error is about a job the phone stopped (4.7 read the screen's fields,
        // empty after a restart: "Video: N/A, URL: N/A"), Android's exit record, the background state, and that job's log
        // code 32: a screen re-created during the job (Back, then the notification) has no srcTitle: the saved request names it
        Pipeline.Request lr = JobManager.get().kind == JobManager.Kind.ANALYZE ? savedRequest() : null;
        String video = srcTitle != null ? srcTitle : stoppedTitle != null && !stoppedTitle.isEmpty() ? stoppedTitle : lr != null && lr.title != null && !lr.title.isEmpty() ? lr.title : null;
        String url = srcYtUrl != null ? srcYtUrl : stoppedUrl != null && !stoppedUrl.isEmpty() ? stoppedUrl : lr != null && lr.ytUrl != null && !lr.ytUrl.isEmpty() ? lr.ytUrl : null;
        StringBuilder body = new StringBuilder("Hi Klipora Team,\n\nI hit this error:\n\n=== DEVICE & VIDEO ===\n").append(deviceInfo())
                .append("App: ").append(appVersion()).append("\n");
        if (stoppedJobReport != null) body.append(stoppedJobReport);
        else body.append("Video: ").append(video == null ? "N/A" : video).append("\nURL: ").append(url == null ? "N/A" : url).append("\n");
        body.append(backgroundState());
        body.append("\n=== ERROR ===\n").append(title == null ? "" : title + "\n").append(summary == null ? "" : summary)
                .append("\n\n=== LOGS ===\n");
        String jobLog = stoppedJobReport != null && stoppedJobLog != null && !stoppedJobLog.isEmpty()
                ? "\n=== LOGS OF THE STOPPED JOB ===\n" + stoppedJobLog + "\n" : "";
        // code 32: 4.9 put the whole log (up to 1500 lines) into the mail intent twice; after a long session that was
        // over Android's 1 MB limit, the send failed and the app said "No email app found". The mail carries the last
        // 250 lines; the copied report keeps everything (capped well under the limit).
        copy("Bug Report", AppLog.capReport(body + AppLog.plain() + "\n" + jobLog, 200_000));
        email("Klipora - Bug Report", AppLog.capReport(body + AppLog.plainTail(250) + "\n" + jobLog, 40_000));
    }

    /** code 30: the phone's background settings, for the report */
    private String backgroundState() {
        StringBuilder b = new StringBuilder();
        b.append("Battery optimisation: ").append(batteryOptimized() ? "on (restricted)" : "off (unrestricted)").append('\n');
        try {
            if (Build.VERSION.SDK_INT >= 28) {
                android.app.ActivityManager am = (android.app.ActivityManager) getSystemService(ACTIVITY_SERVICE);
                b.append("Background restricted by user: ").append(am.isBackgroundRestricted() ? "yes" : "no").append('\n');
            }
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            b.append("Power saver: ").append(pm.isPowerSaveMode() ? "on" : "off").append('\n');
            boolean notif = androidx.core.app.NotificationManagerCompat.from(this).areNotificationsEnabled();
            // code 31: Android 13+ keeps a job with notifications off running (the progress is only hidden); said in the report
            b.append("Notifications: ").append(notif ? "on" : Build.VERSION.SDK_INT >= 33 ? "off (job progress hidden; Android still keeps the job running)" : "off").append('\n');
        } catch (Throwable ignored) {}
        b.append(com.viral.shortsmaker.core.RenderSafety.reportLine(this));
        com.viral.shortsmaker.core.UpdateCheck.Info nk = com.viral.shortsmaker.core.UpdateCheck.newerKnown(this);
        if (nk != null) b.append("Newer version available: ").append(com.viral.shortsmaker.core.UpdateCheck.label(nk)).append(nk.code > 0 ? " (" + nk.code + ")" : "").append('\n');
        return b.toString();
    }

    private String appVersion() {
        try { PackageInfo pi = getPackageManager().getPackageInfo(getPackageName(), 0); return pi.versionName + " (" + pi.versionCode + ")"; }
        catch (Exception e) { return "?"; }
    }

    /**
     * code 30 (4.8): a job was running when the app's process ended. 4.7 said "closed by battery saver" for every such end
     * (also a swipe from Recent apps, low memory, a crash, an update, a reboot, or a job that had in fact finished), and its
     * report had no link/title and no log of the job. Now the cause comes from Android's own exit record (JobGuard), the
     * message fits it, a stale marker says nothing, and Continue makes only the shorts that are still missing.
     */
    private void checkKilledJob() {
        SharedPreferences sp = Prefs.sp(this);
        JobGuard.Marker m = JobGuard.Marker.read(sp);
        if (!m.alive) return;
        boolean running = JobManager.get().isRunning();
        int[] prog = JobGuard.progressOf(this, m.project);
        boolean projectDone = prog != null && prog[0] >= prog[1] && m.total > 0 && prog[1] >= m.total;
        JobGuard.Exit exit = running ? null : JobGuard.exitOf(this, m);
        long crashAt = sp.getLong("app_crash_at", 0);
        JobGuard.Cause cause = JobGuard.verdict(m, System.currentTimeMillis(), android.os.Process.myPid(), BuildConfig.VERSION_CODE,
                running, exit, crashAt, projectDone, JobGuard.bootTime());
        if (cause == JobGuard.Cause.NONE) return;
        JobGuard.consume(this);
        if (cause.silent()) {
            AppLog.debug("job marker cleared without a message: " + cause + " (started " + m.at + ", last sign " + m.lastSign() + ")");
            if (cause == JobGuard.Cause.FINISHED) {
                Project done = Project.loadFrom(new java.io.File(Project.root(this), m.project));
                if (done != null && !done.clips.isEmpty()) { done.makeCurrent(this); JobGuard.forgetCheckpoint(this); }
            }
            return;
        }
        if (cause == JobGuard.Cause.LOW_MEMORY) JobGuard.setLowMemoryNext(this);
        // code 31 (4.9): a native crash of the app while a video command / the GPU face engine ran in it: the next renders
        // (Retry / Continue included) start on the safer path straight away
        boolean nativeCrash = cause == JobGuard.Cause.CRASH && (exit != null ? exit.reason == JobGuard.R_CRASH_NATIVE : crashAt < m.at);
        String safer = com.viral.shortsmaker.core.RenderSafety.afterAppCrash(this, nativeCrash);
        if (!safer.isEmpty()) AppLog.i("Safer video path for the next job: " + safer);
        AppLog.e("The previous job didn't finish: " + cause.name().toLowerCase(Locale.US).replace('_', ' ')
                + (exit != null ? " (" + JobGuard.reasonName(exit.reason) + ")" : ""));
        // the report about this job: its link, title, step, Android's exit record, and the log of the session that ran it
        String report = "Video: " + (m.title.isEmpty() ? "N/A" : m.title) + "\nURL: " + (m.url.isEmpty() ? "N/A" : m.url) + "\n"
                + JobGuard.reportBlock(m, exit, cause, prog, System.currentTimeMillis());
        String log = AppLog.previousSession(getFilesDir(), 120);
        if (cause == JobGuard.Cause.CRASH) {
            String crash = sp.getString("app_crash_log_data", null);
            if (crash != null) { log = log + "\n\n=== CRASH ===\n" + crash; sp.edit().remove("app_crash_log_data").apply(); }
        }
        // v18: the request is saved, so Retry works even though the phone killed the app; code 30: Continue keeps the
        // finished shorts and the picked moments (no new download, transcript or AI pick; an unlock already used is honoured)
        final Pipeline.Request rq = lastRequest != null ? lastRequest : Pipeline.Request.fromJson(sp.getString("last_request", ""));
        boolean canResume = rq != null && prog != null && prog[0] < prog[1] && !m.project.isEmpty();
        int done = prog == null ? 0 : prog[0], total = prog == null ? 0 : prog[1];
        // code 32: a stopped caption re-render (Caption Studio -> Apply) is not the last link's job: 4.9's Retry started a
        // whole new analysis of the previous video (download, AI, a new project) instead
        final boolean captionJob = "CAPTION".equals(m.kind);
        Runnable retry = rq == null || captionJob ? null : () -> {
            if (canResume) rq.resumeId = m.project; else rq.resumeId = "";
            if (!ProcessingService.start(this, rq)) toast("Already processing");
        };
        String msg = captionJob ? JobGuard.message(cause, Build.MANUFACTURER, 0, 0).replace("\n\nTap Retry to start again.", "") + "\n\nThe new captions weren't finished: open the project in History and apply them again."
                : JobGuard.message(cause, Build.MANUFACTURER, done, total);
        showErrorCard(JobGuard.title(cause), msg, retry, JobGuard.wantsFix(cause));
        stoppedJobReport = report; stoppedJobLog = log; stoppedUrl = m.url; stoppedTitle = m.title;
        if (canResume && done > 0 && !captionJob) ((MaterialButton) findViewById(R.id.btnErrorRetry)).setText("Continue");
    }

    /** code 32: the last ANALYZE request, from memory or from the copy startJob saved */
    private Pipeline.Request savedRequest() {
        if (lastRequest != null) return lastRequest;
        try { return Pipeline.Request.fromJson(Prefs.sp(this).getString("last_request", "")); } catch (Exception e) { return null; }
    }

    /** code 30: what the bug report says about a job the phone stopped (null = the error card is about something else) */
    private String stoppedJobReport, stoppedJobLog, stoppedUrl, stoppedTitle;

    // ================================================================== background reliability (Xiaomi/HyperOS, Oppo, Vivo, Samsung)

    private boolean batteryOptimized() {
        try {
            android.os.PowerManager pm = (android.os.PowerManager) getSystemService(POWER_SERVICE);
            return Build.VERSION.SDK_INT >= 23 && pm != null && !pm.isIgnoringBatteryOptimizations(getPackageName());
        } catch (Exception e) { return false; }
    }

    /** one-time explainer before the first job; returns true if it was shown (the job continues after) */
    private boolean maybeAskBattery(Runnable then) {
        // v21 (4.0): ColorOS (OPPO / realme / OnePlus) stops background apps through its own "Allow background activity"
        // switch even when Android's battery optimisation is already off, so on those phones the tip is shown once too
        boolean coloros = isColorOs();
        if (coloros && !Prefs.sp(this).getBoolean("asked_coloros", false)) {
            Prefs.sp(this).edit().putBoolean("asked_coloros", true).putBoolean("asked_battery", true).apply();
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Keep processing in the background")
                    .setMessage("On OPPO / realme / OnePlus phones, so rendering keeps going with the screen off:\n\n"
                            + "1. App info › Battery usage › turn on \"Allow background activity\" (and \"Allow auto launch\").\n"
                            + "2. In Recent apps, lock Klipora (tap the ⋮ / pull down on its card › Lock).\n\n"
                            + "Tap Open settings, then come back — your job starts right away.")
                    .setPositiveButton("Open settings", (d, w) -> { openBackgroundSettings(); then.run(); })
                    .setNegativeButton("Later", (d, w) -> then.run())
                    .setCancelable(false)
                    .show();
            return true;
        }
        // code 30 (4.8): vivo / iQOO (Funtouch, OriginOS) stop background apps through their own "background power use" and
        // autostart lists even with Android's battery optimisation off (his Android 15 report): shown once, then the fix list
        if ("vivo".equals(BackgroundFix.brand()) && !Prefs.sp(this).getBoolean("asked_vivo", false)) {
            Prefs.sp(this).edit().putBoolean("asked_vivo", true).putBoolean("asked_battery", true).apply();
            new MaterialAlertDialogBuilder(this)
                    .setTitle("Keep processing in the background")
                    .setMessage("vivo phones close apps in the background. So your shorts keep rendering with the screen off, allow Klipora high background power use and autostart, and lock it in Recent apps.")
                    .setPositiveButton("Open settings", (d, w) -> { then.run(); openBackgroundSettings(); })
                    .setNegativeButton("Later", (d, w) -> then.run())
                    .setCancelable(false)
                    .show();
            return true;
        }
        if (Prefs.sp(this).getBoolean("asked_battery", false) || !batteryOptimized()) return false;
        Prefs.sp(this).edit().putBoolean("asked_battery", true).apply();
        boolean xiaomi = isXiaomi();
        new MaterialAlertDialogBuilder(this)
                .setTitle("Keep processing in the background")
                .setMessage("So your shorts keep rendering with the screen off, allow Klipora to run without battery restrictions."
                        + (xiaomi ? "\n\nOn Xiaomi / Redmi / POCO also set Battery saver → \"No restrictions\" and turn on Autostart." : ""))
                .setPositiveButton("Allow", (d, w) -> { openBackgroundSettings(); then.run(); })
                .setNegativeButton("Later", (d, w) -> then.run())
                .setCancelable(false)
                .show();
        return true;
    }

    static boolean isColorOs() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.US);
        return m.contains("oppo") || m.contains("realme") || m.contains("oneplus");
    }

    private static boolean isXiaomi() {
        String m = (Build.MANUFACTURER + " " + Build.BRAND).toLowerCase(Locale.US);
        return m.contains("xiaomi") || m.contains("redmi") || m.contains("poco");
    }

    /**
     * code 30 (4.8): "Fix background settings" / Settings › Background: a short list of the screens that decide it on this
     * phone (BackgroundFix: Android's battery setting + the maker's own lists, e.g. vivo's background power use and
     * autostart). Tapping one opens it; coming back shows the list again with what is done ticked.
     */
    private void openBackgroundSettings() {
        if (isFinishing()) return;
        final List<BackgroundFix.Step> steps = BackgroundFix.steps(this, batteryOptimized());
        String[] items = new String[steps.size()];
        for (int i = 0; i < steps.size(); i++) items[i] = (steps.get(i).done ? "✓  " : (i + 1) + ".  ") + steps.get(i).label;
        new MaterialAlertDialogBuilder(this)
                .setTitle("Keep Klipora working in the background")
                .setItems(items, (d, w) -> {
                    BackgroundFix.Step st = steps.get(w);
                    if (st.tip != null) {
                        new MaterialAlertDialogBuilder(this).setTitle(st.label).setMessage(st.tip)
                                .setPositiveButton("OK", (d2, w2) -> openBackgroundSettings()).show();
                        return;
                    }
                    if (BackgroundFix.open(this, st)) fixReshowAt = System.currentTimeMillis();
                    else toast("Open Settings › Apps › Klipora › Battery › Unrestricted");
                })
                .setNegativeButton("Done", null)
                .show();
    }

    /** code 30: show the fix list again when the user comes back from one of its screens */
    private long fixReshowAt;

    private void updateBatterySub() {
        TextView t = findViewById(R.id.rowBatterySub);
        if (t != null) t.setText(batteryOptimized() ? "Restricted by battery saver — tap to allow" : "Allowed ✓ keeps working with the screen off");
    }

    private void startTicker() {
        ui.post(new Runnable() {
            int n = 0;
            @Override public void run() {
                JobManager jm = JobManager.get();
                if (jm.isRunning()) { jm.tick(); renderProgress(jm); }
                if (tabSettings.getVisibility() == View.VISIBLE || n % 10 == 0) tickPremium();
                n++;
                ui.postDelayed(this, 1000);
            }
        });
    }

    // ================================================================== history tab

    private void refreshHistory() {
        if (historyAdapter == null) {
            rvHistory.setLayoutManager(new LinearLayoutManager(this));
            historyAdapter = new HistoryAdapter();
            rvHistory.setAdapter(historyAdapter);
            findViewById(R.id.btnHistoryCreate).setOnClickListener(v -> nav.setSelectedItemId(R.id.nav_create));
            MaterialButton sort = findViewById(R.id.btnHistorySort);
            sort.setOnClickListener(v -> {
                historySort = (historySort + 1) % 4;
                Prefs.sp(this).edit().putInt("history_sort", historySort).apply();
                haptic(v);
                refreshHistory();
            });
            historySort = Prefs.sp(this).getInt("history_sort", 0);
        }
        ((MaterialButton) findViewById(R.id.btnHistorySort)).setText(new String[]{"Newest", "Oldest", "Most shorts", "A–Z"}[historySort]);
        final int sortMode = historySort;
        new Thread(() -> {
            List<Project> all = Project.listAll(getApplicationContext());
            if (sortMode == 1) java.util.Collections.reverse(all);
            else if (sortMode == 2) java.util.Collections.sort(all, (a, b) -> b.clips.size() - a.clips.size());
            else if (sortMode == 3) java.util.Collections.sort(all, (a, b) -> String.valueOf(a.title).compareToIgnoreCase(String.valueOf(b.title)));
            long bytes = 0;
            for (Project p : all) bytes += p.sizeBytes(getApplicationContext());
            final long fb = bytes;
            ui.post(() -> {
                if (isFinishing()) return;
                historyAdapter.set(all);
                rvHistory.scheduleLayoutAnimation();
                historyEmpty.setVisibility(all.isEmpty() ? View.VISIBLE : View.GONE);
                int shorts = 0;
                for (Project p : all) shorts += p.clips.size();
                ((TextView) findViewById(R.id.tvHistorySub)).setText(all.isEmpty() ? "Your past projects" :
                        all.size() + (all.size() == 1 ? " project" : " projects") + " • " + shorts + " shorts • " + String.format(Locale.US, "%.0f MB", fb / 1048576.0));
            });
        }).start();
    }

    /** opens a past project in the Create tab (watch, re-caption, save, share) */
    private void openProject(Project p) {
        if (JobManager.get().isRunning()) { toast("Wait for the current job to finish"); return; }
        JobManager jm = JobManager.get();
        jm.project = p;
        jm.kind = JobManager.Kind.ANALYZE;
        jm.state = JobManager.State.DONE;
        p.makeCurrent(this);
        nav.setSelectedItemId(R.id.nav_create);
        onJobChanged(jm);
        ((androidx.core.widget.NestedScrollView) tabCreate).post(() -> ((androidx.core.widget.NestedScrollView) tabCreate).smoothScrollTo(0, panelResults.getTop()));
    }

    private void historyMenu(View anchor, Project p) {
        android.widget.PopupMenu m = new android.widget.PopupMenu(this, anchor);
        m.getMenu().add(0, 1, 0, "Open");
        m.getMenu().add(0, 2, 1, "Rename");
        m.getMenu().add(0, 3, 2, "Share all shorts");
        m.getMenu().add(0, 4, 3, "Save all to gallery");
        if (!p.isLocal() && p.url != null && !p.url.isEmpty()) m.getMenu().add(0, 5, 4, "Open source video");
        m.getMenu().add(0, 6, 5, "Delete");
        m.setOnMenuItemClickListener(it -> {
            switch (it.getItemId()) {
                case 1: openProject(p); break;
                case 2: renameProject(p); break;
                case 3: shareAll(p); break;
                case 4: if (JobManager.get().isRunning()) toast("Wait for the current job to finish"); else { openProject(p); saveAll(); } break;
                case 5: openUrl(p.url); break;
                case 6: deleteProject(p); break;
            }
            return true;
        });
        m.show();
    }

    private void renameProject(Project p) {
        TextInputLayout til = new TextInputLayout(this);
        TextInputEditText et = new TextInputEditText(til.getContext());
        et.setText(p.title);
        et.setSingleLine(true);
        til.addView(et);
        int pad = (int) (20 * getResources().getDisplayMetrics().density);
        til.setPadding(pad, pad / 2, pad, 0);
        new MaterialAlertDialogBuilder(this).setTitle("Rename project").setView(til)
                .setPositiveButton("Save", (d, w) -> {
                    String t = et.getText() == null ? "" : et.getText().toString().trim();
                    if (t.isEmpty()) return;
                    p.title = t;
                    p.save(this);
                    Project cur = JobManager.get().project;
                    if (cur != null && cur.id.equals(p.id)) { cur.title = t; tvResultsSub.setText(t); }
                    refreshHistory();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void deleteProject(Project p) {
        // code 32: not while a job runs (4.9 reset the running job to idle when its project was deleted: a second job
        // could start beside it, and a caption job's files were deleted under its own render)
        if (JobManager.get().isRunning()) { toast("Wait for the current job to finish"); return; }
        new MaterialAlertDialogBuilder(this).setTitle("Delete project?")
                .setMessage("“" + p.title + "” and its " + p.clips.size() + (p.clips.size() == 1 ? " short" : " shorts") + " will be deleted from Klipora. Shorts you already saved to the gallery stay there.")
                .setPositiveButton("Delete", (d, w) -> {
                    Project cur = JobManager.get().project;
                    if (cur != null && cur.id.equals(p.id)) { JobManager.get().project = null; JobManager.get().reset(); }
                    p.delete(this);
                    haptic(rvHistory);
                    toast("Deleted");
                    refreshHistory();
                })
                .setNegativeButton("Cancel", null).show();
    }

    private void shareAll(Project p) {
        ArrayList<Uri> uris = new ArrayList<>();
        for (Project.Clip c : p.clips) {
            File f = c.file();
            if (f != null && f.exists()) { try { uris.add(shareUri(f)); } catch (Exception e) { AppLog.e("Share: " + e.getMessage()); } }
        }
        if (uris.isEmpty()) { toast("No shorts to share"); return; }
        Intent i = new Intent(uris.size() == 1 ? Intent.ACTION_SEND : Intent.ACTION_SEND_MULTIPLE).setType("video/mp4");
        if (uris.size() == 1) i.putExtra(Intent.EXTRA_STREAM, uris.get(0)); else i.putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris);
        i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
        try { startActivity(Intent.createChooser(i, "Share shorts")); } catch (Exception e) { toast("Share failed: " + e.getMessage()); }
    }

    /** v7: one FileProvider authority for every share (v6 used two names; one of them did not exist) */
    Uri shareUri(File f) { return FileProvider.getUriForFile(this, getPackageName() + ".files", f); }

    class HistoryAdapter extends RecyclerView.Adapter<HistoryAdapter.VH> {
        final List<Project> items = new ArrayList<>();
        void set(List<Project> l) { items.clear(); items.addAll(l); notifyDataSetChanged(); }

        class VH extends RecyclerView.ViewHolder {
            final ImageView thumb; final TextView title, meta, tag, count; final View more;
            VH(View v) {
                super(v);
                thumb = v.findViewById(R.id.ivHistThumb); title = v.findViewById(R.id.tvHistTitle); meta = v.findViewById(R.id.tvHistMeta);
                tag = v.findViewById(R.id.tvHistTag); count = v.findViewById(R.id.tvHistCount); more = v.findViewById(R.id.btnHistMore);
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull android.view.ViewGroup parent, int viewType) {
            return new VH(getLayoutInflater().inflate(R.layout.item_history, parent, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Project p = items.get(pos);
            if (h.thumb.getParent() instanceof View) sizeThumb((View) h.thumb.getParent(), p, 68, 120);   // v14: per format
            h.title.setText(p.title == null || p.title.isEmpty() ? "Untitled" : p.title);
            int n = p.clips.size();
            double sec = 0;
            for (Project.Clip c : p.clips) sec += c.duration();
            String when = new java.text.SimpleDateFormat("d MMM, h:mm a", Locale.getDefault()).format(new java.util.Date(p.createdAt > 0 ? p.createdAt : System.currentTimeMillis()));
            h.meta.setText(n + (n == 1 ? " short" : " shorts") + " • " + fmt(sec) + "\n" + when);
            boolean cc = false;
            for (Project.Clip c : p.clips) if (c.captionStyle != null && !c.captionStyle.isEmpty()) cc = true;
            boolean cur = JobManager.get().project != null && p.id.equals(JobManager.get().project.id);
            int top = 0;
            for (Project.Clip c : p.clips) top = Math.max(top, c.score);
            h.tag.setText((p.isLocal() ? "Upload" : "Link") + (top > 0 ? " • 🔥 " + top : "") + (cc ? " • Captions" : "") + (cur ? " • Open" : ""));
            h.count.setText(String.valueOf(n));
            File tf = p.thumbFile(MainActivity.this);
            h.thumb.setImageDrawable(null);
            if (tf.exists()) {
                h.thumb.setTag(tf.getAbsolutePath());
                Bitmap cached = thumbs.get(tf.getAbsolutePath());
                if (cached != null) h.thumb.setImageBitmap(cached);
                else new Thread(() -> {
                    Bitmap b = BitmapFactory.decodeFile(tf.getAbsolutePath());
                    ui.post(() -> { if (b != null) { thumbs.put(tf.getAbsolutePath(), b); if (tf.getAbsolutePath().equals(h.thumb.getTag())) h.thumb.setImageBitmap(b); } });
                }).start();
            } else if (n > 0) loadThumb(p.clips.get(0), h.thumb);
            h.itemView.setOnClickListener(v -> { haptic(v); openProject(p); });
            h.more.setOnClickListener(v -> historyMenu(v, p));
            h.itemView.setOnLongClickListener(v -> { historyMenu(h.more, p); return true; });
        }

        @Override public int getItemCount() { return items.size(); }
    }

    // ================================================================== results list

    /** v14: a 9:16 box keeps its 2.4 size; 1:1 is a square of the same area-ish; 16:9 is wide with the same width budget */
    void sizeThumb(View box, Project p, int wDp, int hDp) {
        if (box == null || p == null) return;
        FfmpegCommands.Aspect a = FfmpegCommands.Aspect.of(p.aspect);
        float d = getResources().getDisplayMetrics().density;
        int w = wDp, hh = hDp;
        if (a == FfmpegCommands.Aspect.SQUARE) { w = Math.round(wDp * 1.25f); hh = w; }
        else if (a == FfmpegCommands.Aspect.LANDSCAPE) { w = Math.round(wDp * 1.6f); hh = Math.round(w * 9f / 16f); }
        ViewGroup.LayoutParams lp = box.getLayoutParams();
        if (lp == null) return;
        int pw = Math.round(w * d), ph = Math.round(hh * d);
        if (lp.width != pw || lp.height != ph) { lp.width = pw; lp.height = ph; box.setLayoutParams(lp); }
    }

    class ClipAdapter extends RecyclerView.Adapter<ClipAdapter.VH> {
        Project pr;

        void setProject(Project p) { pr = p; notifyDataSetChanged(); }

        class VH extends RecyclerView.ViewHolder {
            ImageView thumb; TextView score, dur, title, range, reason, cc; MaterialButton bCap, bSave, bShare; View box;
            VH(View v) {
                super(v);
                thumb = v.findViewById(R.id.ivThumb); score = v.findViewById(R.id.tvScore); dur = v.findViewById(R.id.tvDur);
                title = v.findViewById(R.id.tvTitle); range = v.findViewById(R.id.tvRange); reason = v.findViewById(R.id.tvReason);
                cc = v.findViewById(R.id.tvCaptionState); bCap = v.findViewById(R.id.btnCaptions); bSave = v.findViewById(R.id.btnSave);
                bShare = v.findViewById(R.id.btnShare); box = v.findViewById(R.id.thumbBox);
            }
        }

        @NonNull @Override public VH onCreateViewHolder(@NonNull ViewGroup p, int t) {
            return new VH(LayoutInflater.from(p.getContext()).inflate(R.layout.item_clip, p, false));
        }

        @Override public void onBindViewHolder(@NonNull VH h, int pos) {
            Project.Clip c = pr.clips.get(pos);
            sizeThumb(h.box, pr, 104, 185);   // v14: thumbnail box has the short's format
            String why = c.reason == null ? "" : c.reason, head = c.title == null ? "" : c.title;
            int dash = why.indexOf(" — ");
            if (dash > 0) { if (head.isEmpty()) head = why.substring(0, dash); why = why.substring(dash + 3); }
            else if (!head.isEmpty() && why.equals(head)) why = "";
            // v7: hook line + sub-scores (small, under the title)
            TextView hook = h.itemView.findViewById(R.id.tvHook), subs = h.itemView.findViewById(R.id.tvScores);
            boolean hasHook = c.hook != null && !c.hook.isEmpty();
            hook.setText(hasHook ? "\u201C" + c.hook + "\u201D" : "");
            hook.setVisibility(hasHook ? View.VISIBLE : View.GONE);
            String sl = scoreLine(c);
            subs.setText(sl);
            subs.setVisibility(sl.isEmpty() ? View.GONE : View.VISIBLE);
            if (hasHook && !sl.isEmpty()) why = "";   // keep the card compact: hook + scores say why
            h.title.setText(head.isEmpty() ? "Short #" + (pos + 1) : "#" + (pos + 1) + " · " + head);
            TextView note = h.itemView.findViewById(R.id.tvNote);
            note.setText(c.note == null ? "" : "⚠ " + c.note);
            note.setVisibility(c.note == null || c.note.isEmpty() ? View.GONE : View.VISIBLE);
            h.score.setText("🔥 " + c.score);
            h.dur.setText(fmt(c.duration()));
            h.range.setText(fmt(c.start) + " – " + fmt(c.end));
            h.reason.setText(why);
            h.reason.setVisibility(why.isEmpty() ? View.GONE : View.VISIBLE);
            h.cc.setText(c.captionStyle.isEmpty() ? "No captions" : "CC • " + c.captionStyle);
            h.cc.setTextColor(c.captionStyle.isEmpty() ? 0xFF6B6B80 : 0xFF22D3EE);
            h.bSave.setIconResource(c.saved ? R.drawable.ic_v_check : R.drawable.ic_v_save);
            h.bSave.setIconTint(ColorStateList.valueOf(c.saved ? 0xFF22C55E : 0xFFF4F4F8));
            loadThumb(c, h.thumb);
            h.box.setOnClickListener(v -> {
                if (c.file() != null && c.file().exists()) startActivity(new Intent(MainActivity.this, PlayerActivity.class).putExtra(PlayerActivity.EXTRA_PATH, c.outPath));
            });
            h.bCap.setOnClickListener(v -> openStudio(h.getBindingAdapterPosition()));
            h.bSave.setOnClickListener(v -> saveClip(h.getBindingAdapterPosition(), true));
            h.bShare.setOnClickListener(v -> share(c));
            if (h.itemView.getTag() == null) { h.itemView.setTag(1); h.itemView.setAlpha(0f); h.itemView.setTranslationY(40f); h.itemView.animate().alpha(1f).translationY(0).setStartDelay(60L * pos).setDuration(260).start(); }
        }

        @Override public int getItemCount() { return pr == null ? 0 : pr.clips.size(); }
    }

    static String scoreLine(Project.Clip c) {
        if (c.hookScore < 0 && c.standaloneScore < 0 && c.valueScore < 0 && c.payoffScore < 0) return "";
        StringBuilder b = new StringBuilder();
        String[] k = {"Hook", "Clear", "Value", "Payoff"};
        int[] v = {c.hookScore, c.standaloneScore, c.valueScore, c.payoffScore};
        for (int i = 0; i < 4; i++) if (v[i] >= 0) { if (b.length() > 0) b.append("  ·  "); b.append(k[i]).append(' ').append(v[i]); }
        return b.toString();
    }

    private void loadThumb(Project.Clip c, ImageView iv) {
        final String key = c.outPath;
        iv.setTag(key);
        Bitmap cached = key == null ? null : thumbs.get(key);
        if (cached != null) { iv.setImageBitmap(cached); return; }
        iv.setImageDrawable(null);
        new Thread(() -> {
            MediaMetadataRetriever r = new MediaMetadataRetriever();
            Bitmap b = null;
            try {
                r.setDataSource(key);
                b = r.getFrameAtTime(1_500_000, MediaMetadataRetriever.OPTION_CLOSEST_SYNC);
                if (b != null && b.getWidth() > 360) b = Bitmap.createScaledBitmap(b, 360, Math.round(360f * b.getHeight() / b.getWidth()), true);
            } catch (Exception ignored) {
            } finally { try { r.release(); } catch (Exception ignored) {} }
            final Bitmap fb = b;
            runOnUiThread(() -> {
                if (fb != null && key != null) thumbs.put(key, fb);
                if (key != null && key.equals(iv.getTag())) iv.setImageBitmap(fb);
            });
        }).start();
    }

    /**
     * code 32 (5.0): the copy to the gallery runs off the main thread (a 1080p short is ~200 MB; 4.9 copied on the UI
     * thread, and "Save all" with 5 shorts froze the screen for 5-30 s on eMMC phones = "Klipora isn't responding").
     * Tests run it in place.
     */
    static java.util.concurrent.Executor saveExec = "robolectric".equals(Build.FINGERPRINT) ? Runnable::run
            : java.util.concurrent.Executors.newSingleThreadExecutor(r -> { Thread t = new Thread(r, "gallery-save"); t.setDaemon(true); return t; });

    private static String saveName(Project pr, int pos) {
        String safe = (pr.title == null ? "Klipora" : pr.title).replaceAll("[^A-Za-z0-9 _-]", "").trim();
        if (safe.length() > 40) safe = safe.substring(0, 40).trim();
        if (safe.isEmpty()) safe = "Klipora";
        return safe.replace(' ', '_') + "_short" + (pos + 1) + "_" + (System.currentTimeMillis() / 1000) + ".mp4";
    }

    private void saveClip(int pos, boolean toastIt) {
        Project pr = JobManager.get().project;
        if (pr == null || pos < 0 || pos >= pr.clips.size()) return;
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPermission = () -> saveClip(pos, toastIt);
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 102);
            return;
        }
        final Project.Clip c = pr.clips.get(pos);
        final java.io.File src = c.file();
        final String name = saveName(pr, pos);
        final android.content.Context app = getApplicationContext();
        saveExec.execute(() -> {
            boolean ok = src != null && src.exists() && GallerySaver.save(app, src, name);
            runOnUiThread(() -> {
                if (ok) {
                    c.saved = true;
                    try { pr.save(app); } catch (Exception ignored) {}
                    if (!isDestroyed() && clipAdapter != null && JobManager.get().project == pr) clipAdapter.notifyItemChanged(pos);
                }
                if (toastIt) toast(ok ? "Saved to Gallery • Movies/Klipora" : "Couldn't save — check storage space/permission");
            });
        });
    }

    private void saveAll() {
        Project pr = JobManager.get().project;
        if (pr == null) return;
        if (Build.VERSION.SDK_INT < 29 && ContextCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            pendingAfterPermission = this::saveAll;
            ActivityCompat.requestPermissions(this, new String[]{Manifest.permission.WRITE_EXTERNAL_STORAGE}, 102);
            return;
        }
        // code 29: count what this tap saved (4.6 also counted shorts saved earlier, so a failed save could read "3 of 3")
        final List<Project.Clip> list = new ArrayList<>(pr.clips);
        final List<String> names = new ArrayList<>();
        for (int i = 0; i < list.size(); i++) names.add(saveName(pr, i));
        final android.content.Context app = getApplicationContext();
        if (list.size() > 1 && !"robolectric".equals(Build.FINGERPRINT)) toast("Saving " + list.size() + " shorts…");
        saveExec.execute(() -> {
            final boolean[] res = new boolean[list.size()];
            for (int i = 0; i < list.size(); i++) {
                java.io.File src = list.get(i).file();
                res[i] = src != null && src.exists() && GallerySaver.save(app, src, names.get(i));
            }
            runOnUiThread(() -> {
                int ok = 0;
                for (int i = 0; i < res.length; i++) if (res[i]) { list.get(i).saved = true; ok++; }
                try { if (ok > 0) pr.save(app); } catch (Exception ignored) {}
                if (!isDestroyed() && clipAdapter != null && JobManager.get().project == pr) clipAdapter.notifyDataSetChanged();
                toast(ok + " of " + list.size() + " saved to Movies/Klipora");
            });
        });
    }

    private void share(Project.Clip c) {
        try {
            if (c.file() == null || !c.file().exists()) { toast("This short's file is missing. Re-create it from History."); return; }
            Uri uri = shareUri(c.file());
            Intent i = new Intent(Intent.ACTION_SEND).setType("video/mp4").putExtra(Intent.EXTRA_STREAM, uri).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION);
            startActivity(Intent.createChooser(i, "Share short"));
        } catch (Exception e) { toast("Share failed: " + e.getMessage()); }
    }

    // ================================================================== settings tab

    private void setupSettings() {
        findViewById(R.id.btnEditApiKeys).setOnClickListener(v -> showApiKeyDialog());
        findViewById(R.id.rowPrivacy).setOnClickListener(v -> LegalNotice.open(this, LegalNotice.PRIVACY_URL));
        findViewById(R.id.rowTerms).setOnClickListener(v -> LegalNotice.open(this, LegalNotice.TERMS_URL));
        findViewById(R.id.cardGeminiKey).setOnClickListener(v -> showApiKeyDialog());
        findViewById(R.id.cardGroqKey).setOnClickListener(v -> showApiKeyDialog());
        findViewById(R.id.btnOpenGeminiUrl).setOnClickListener(v -> openUrl("https://aistudio.google.com/app/apikey"));
        findViewById(R.id.btnOpenGroqUrl).setOnClickListener(v -> openUrl("https://console.groq.com/keys"));
        // code 32: one ad here counts toward the 10-ad PRO pass (4.9 said "Unlocked", though nothing was unlocked yet)
        findViewById(R.id.btnWatchAdSettings).setOnClickListener(v -> showRewardedAd(() -> {
            int n = Prefs.sp(this).getInt("ads_watched_count", 0);
            if (!Prefs.isPremium(this) || n > 0) toast("Ad counted: " + n + " / 10 for 24-hour PRO");
        }));
        bouncy(findViewById(R.id.btnWatchAdSettings));
        bindPremiumRows();
        findViewById(R.id.btnUpdateYtEngine).setOnClickListener(v -> {
            tvEngineSub.setText("Updating…");
            YtEngine.update(this, (ok, msg) -> { tvEngineSub.setText(msg); toast(msg); });
        });
        findViewById(R.id.rowLogs).setOnClickListener(v -> showLogsSheet());
        EditionExtras.addSettings(this);   // code 33: Premium adds "Use backup video engine (test)"; Free adds nothing
        findViewById(R.id.rowBattery).setOnClickListener(v -> openBackgroundSettings());
        updateBatterySub();
        // v17 (2.8): the "Hardware encoder" and "Keep screen on" switches are gone: both are always on (the hardware
        // encoder still falls back to software by itself on a phone where it fails)
        findViewById(R.id.btnBugReportSettings).setOnClickListener(v -> email("Klipora - Bug Report",
                "Hi Klipora Team,\n\nI would like to report a bug:\n\n" + deviceInfo() + "\n=== RECENT LOGS ===\n" + AppLog.plain()));
        findViewById(R.id.btnHelpSettings).setOnClickListener(v -> email("Klipora - Help & Support", "Hi Klipora Team,\n\nI need help with:\n\n" + deviceInfo()));

        ChipGroup q = findViewById(R.id.chipGroupQuality);
        int[] qIds = {R.id.chipQAuto, R.id.chipQ1080, R.id.chipQ720, R.id.chipQ480};
        q.check(qIds[Math.max(0, Math.min(3, Prefs.videoQuality(this)))]);
        q.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty() || syncingQuality) return;
            for (int i = 0; i < qIds.length; i++) if (qIds[i] == ids.get(0)) {
                if (i == 1 && !Prefs.hdUnlocked(this)) { askUnlockHd(); syncQualityUi(); return; }
                Prefs.sp(this).edit().putInt("video_quality", i).apply();
            }
            syncQualityUi();
        });
        ChipGroup ex = findViewById(R.id.chipGroupExport);
        ex.setOnCheckedStateChangeListener((g, ids) -> {
            if (ids.isEmpty() || syncingQuality) return;
            boolean hd = ids.get(0) == R.id.chipExp1080;
            if (hd && !Prefs.hdUnlocked(this)) { askUnlockHd(); syncQualityUi(); return; }
            int cur = Prefs.videoQuality(this);
            if (hd) Prefs.sp(this).edit().putInt("video_quality", 1).apply();
            else if (cur == 1) Prefs.sp(this).edit().putInt("video_quality", 0).apply();
            syncQualityUi();
        });
        syncQualityUi();
        swHighSpeed.setOnCheckedChangeListener((b, on) -> {
            if (updatingHighSpeed) return;
            if (on && !Prefs.highSpeedUnlocked(this)) {
                setHighSpeedSwitch(false);
                showRewardedAd(() -> {
                    { android.content.SharedPreferences.Editor ed = Prefs.sp(this).edit().putBoolean("high_speed", true); Prefs.stampGrant(this, ed, "high_speed_unlock_time"); ed.apply(); }
                    setHighSpeedSwitch(true);
                    updatePremiumUI();
                    toast("High-speed download unlocked for 1 hour");
                });
                return;
            }
            Prefs.sp(this).edit().putBoolean("high_speed", on).apply();
            updatePremiumUI();
        });
        // code 32: no version name in the app's text (Sakib's rule); the bug report still carries the version
        ((TextView) findViewById(R.id.tvAppVersion)).setText("Klipora • made for creators");
    }

    private boolean syncingQuality;
    private MaterialSwitch swHd;

    /** keeps the Create "Export quality" chips, the Settings quality chips and the lock icons in sync */
    private void syncQualityUi() {
        syncingQuality = true;
        try {
            int qv = Prefs.videoQuality(this);
            if (qv == 1 && !Prefs.hdUnlocked(this)) { qv = 0; Prefs.sp(this).edit().putInt("video_quality", 0).apply(); }
            int[] qIds = {R.id.chipQAuto, R.id.chipQ1080, R.id.chipQ720, R.id.chipQ480};
            ((ChipGroup) findViewById(R.id.chipGroupQuality)).check(qIds[Math.max(0, Math.min(3, qv))]);
            ((ChipGroup) findViewById(R.id.chipGroupExport)).check(qv == 1 ? R.id.chipExp1080 : R.id.chipExp720);
            boolean hd = Prefs.hdUnlocked(this);
            Chip c1 = findViewById(R.id.chipExp1080);
            c1.setChipIconVisible(!hd);
            ((Chip) findViewById(R.id.chipExp720)).setText(qv == 3 ? "480p" : "720p • fast");
            Chip s1 = findViewById(R.id.chipQ1080);
            s1.setText(hd ? "1080p HD" : "1080p HD 🔒");
            if (swHd != null) swHd.setChecked(qv == 1);
        } finally { syncingQuality = false; }
    }

    private void askUnlockHd() {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Unlock 1080p HD")
                .setMessage("Watch one short ad to export in 1080p HD for 1 hour. PRO unlocks it for 24 hours.")
                .setPositiveButton("Watch ad", (d, w) -> showRewardedAd(() -> {
                    Prefs.unlockHd(this);
                    Prefs.sp(this).edit().putInt("video_quality", 1).apply();
                    syncQualityUi();
                    updatePremiumRows();
                    toast("1080p HD unlocked for 1 hour");
                }))
                .setNegativeButton("Not now", null).show();
    }

    private void askUnlockCaptions(Runnable after) {
        new MaterialAlertDialogBuilder(this)
                .setTitle("Unlock captions")
                .setMessage("Captions are a premium feature. Watch one short ad to add captions (every style) to your next video. PRO unlocks them for 24 hours.")
                .setPositiveButton("Watch ad", (d, w) -> showRewardedAd(() -> {
                    Prefs.addCaptionCredit(this);
                    toast("Captions unlocked for your next video");
                    updatePremiumRows();
                    updateCaptionSummary();
                    if (after != null && !isFinishing() && !isDestroyed()) after.run();
                }))
                .setNegativeButton("Not now", null).show();
    }

    private void bindSwitch(int swId, int rowId, String key, boolean dflt, java.util.function.Consumer<Boolean> after) {
        MaterialSwitch sw = findViewById(swId);
        sw.setChecked(Prefs.sp(this).getBoolean(key, dflt));
        sw.setOnCheckedChangeListener((b, on) -> { Prefs.sp(this).edit().putBoolean(key, on).apply(); haptic(b); if (after != null) after.accept(on); });
        findViewById(rowId).setOnClickListener(v -> sw.toggle());
    }

    private void setHighSpeedSwitch(boolean on) {
        updatingHighSpeed = true;
        swHighSpeed.setChecked(on);
        updatingHighSpeed = false;
    }

    private void updatePremiumUI() {
        if (tvAdsCount == null) return;
        boolean prem = Prefs.isPremium(this);
        int ads = Prefs.sp(this).getInt("ads_watched_count", 0);
        tvAdsCount.setText(ads + " / 10 ads");
        progressAds.setProgress(ads);
        layoutAdProgress.setVisibility(prem ? View.GONE : View.VISIBLE);
        tvTimer.setVisibility(prem ? View.VISIBLE : View.GONE);
        if (Prefs.PREMIUM_EDITION) {
            tvAdsCount.setVisibility(View.GONE);
            tvTimer.setVisibility(View.GONE);
            tvPremiumStatus.setText("Premium — all features unlocked");
            tvPremiumStatus.setTextColor(0xFF22C55E);
        } else {
            tvPremiumStatus.setText(prem ? "Klipora PRO is active — up to 5 shorts, AI face tracking, unlimited speed." : "Watch 10 ads to unlock all premium features (incl. AI face tracking) for 24 hours.");
        }
        badgePro.setVisibility(prem ? View.VISIBLE : View.GONE);
        if (Prefs.PREMIUM_EDITION) badgePro.setText("PREMIUM");
        for (int i = 1; i < MAX_SHORTS; i++) {
            boolean unlocked = prem || sessionCountUnlocked[i];
            countChips[i].setChipIconVisible(!unlocked);
            if (!unlocked && countChips[i].isChecked()) cgCount.check(R.id.chipCount1);   // v21: PRO ran out
        }
        ((TextView) findViewById(R.id.tvCountHint)).setText(Prefs.PREMIUM_EDITION ? "Premium: exactly the number you pick (up to 5)" : prem ? "PRO: exactly the number you pick (up to 5)" : "1 free • 2–5 unlock with an ad or PRO");
        if (swFaceTrack != null && swFaceTrack.isChecked() && !Prefs.faceTrackUnlocked(this)) swFaceTrack.setChecked(false);
        updateFaceTrackSummary();
        if (!Prefs.highSpeedUnlocked(this) && Prefs.sp(this).getBoolean("high_speed", false)) Prefs.sp(this).edit().putBoolean("high_speed", false).apply();
        setHighSpeedSwitch(Prefs.highSpeedOn(this));
        tickPremium();
    }

    private void tickPremium() {
        if (tvTimer == null) return;
        boolean prem = Prefs.isPremium(this);
        long left = Prefs.premiumLeftMs(this);
        if (prem && !Prefs.PREMIUM_EDITION) tvTimer.setText(String.format(Locale.US, "PRO active: %02d:%02d:%02d", left / 3600000, (left % 3600000) / 60000, (left % 60000) / 1000));
        long hs = Prefs.highSpeedLeftMs(this);
        crownHs.setVisibility(Prefs.highSpeedUnlocked(this) ? View.GONE : View.VISIBLE);
        if (prem) { tvHighSpeedStatus.setText(swHighSpeed.isChecked() ? "⚡ Active • unlimited (" + proName() + ")" : "Unlocked with " + proName()); tvHighSpeedStatus.setTextColor(0xFF22C55E); }
        else if (hs > 0) { tvHighSpeedStatus.setText(String.format(Locale.US, "⚡ Active: %02dm %02ds left", hs / 60000, (hs % 60000) / 1000)); tvHighSpeedStatus.setTextColor(0xFF22C55E); }
        else {
            tvHighSpeedStatus.setText("Off (up to 3 MB/s) • 1 ad unlocks 1 hour");
            tvHighSpeedStatus.setTextColor(0xFFF59E0B);
            if (swHighSpeed.isChecked()) { setHighSpeedSwitch(false); Prefs.sp(this).edit().putBoolean("high_speed", false).apply(); }
        }
        if (!prem && badgePro.getVisibility() == View.VISIBLE) updatePremiumUI();
        updatePremiumRows();
    }

    /** v17 (2.8): the Premium card on the Create screen: one row per premium feature, its state and what 1 ad unlocks */
    private void updatePremiumRows() {
        if (findViewById(R.id.rowPremHd) == null) return;
        boolean prem = Prefs.isPremium(this);
        StringBuilder counts = new StringBuilder();
        for (int i = 1; i < MAX_SHORTS; i++) if (sessionCountUnlocked[i]) counts.append(counts.length() > 0 ? ", " : "").append(i + 1);
        premRow(R.id.rowPremShorts, prem || counts.length() > 0,
                prem ? "Unlocked with " + proName() : counts.length() > 0 ? "Unlocked now: " + counts + " • others 1 ad each" : "1 ad each, for this session");
        long hd = Prefs.hdLeftMs(this);
        int cc = Prefs.captionCredits(this);
        boolean hdOn = Prefs.videoQuality(this) == 1 && Prefs.hdUnlocked(this);
        premRow(R.id.rowPremHd, Prefs.hdUnlocked(this), prem ? (hdOn ? "On • unlimited with " + proName() : "Unlocked with " + proName())
                : hd > 0 ? (hdOn ? "On • " : "Unlocked • ") + leftText(hd) + " left" : "Off • 1 ad unlocks 1 hour");
        if (swHd != null && swHd.isChecked() != hdOn) { syncingQuality = true; swHd.setChecked(hdOn); syncingQuality = false; }
        premRow(R.id.rowPremCaptions, Prefs.captionsUnlocked(this), prem ? "Unlocked with " + proName() : cc > 0 ? "Unlocked for your next " + (cc > 1 ? cc + " videos" : "video") : "1 ad = captions on 1 video");
        int fc = Prefs.faceTrackCredits(this);
        premRow(R.id.rowPremFace, Prefs.faceTrackUnlocked(this), prem ? "Unlocked with " + proName() + " (unlimited)" : fc > 0 ? fc + " podcast" + (fc > 1 ? "s" : "") + " unlocked" : "1 ad = your next podcast");
    }

    private void premRow(int rowId, boolean unlocked, String sub) {
        TextView t = findViewById(rowId == R.id.rowPremShorts ? R.id.rowPremShortsSub : rowId == R.id.rowPremHd ? R.id.rowPremHdSub
                : rowId == R.id.rowPremCaptions ? R.id.rowPremCaptionsSub : R.id.rowPremFaceSub);
        android.widget.ImageView st = findViewById(rowId == R.id.rowPremShorts ? R.id.rowPremShortsState : rowId == R.id.rowPremHd ? R.id.rowPremHdState
                : rowId == R.id.rowPremCaptions ? R.id.rowPremCaptionsState : R.id.rowPremFaceState);
        if (t != null && !sub.contentEquals(t.getText())) t.setText(sub);
        if (st != null) {
            st.setImageResource(unlocked ? R.drawable.ic_v_check : R.drawable.ic_v_lock);
            st.setImageTintList(android.content.res.ColorStateList.valueOf(unlocked ? 0xFF22C55E : 0xFFF59E0B));
            st.setContentDescription(unlocked ? "Unlocked" : "Locked");
        }
    }

    /** what the unlocked state is called on screen: "Premium" in the premium edition, the 24 h "PRO" pass otherwise */
    static String proName() { return Prefs.PREMIUM_EDITION ? "Premium" : "PRO"; }

    private static String leftText(long ms) {
        long m = Math.max(1, ms / 60000);
        return m >= 60 ? (m / 60) + " h " + (m % 60) + " min" : m + " min";
    }

    private void bindPremiumRows() {
        View r = findViewById(R.id.rowPremHd);
        if (r == null) return;
        swHd = findViewById(R.id.switchHd);
        r.setOnClickListener(v -> swHd.toggle());
        swHd.setOnCheckedChangeListener((b, on) -> {
            if (syncingQuality) return;
            if (on && !Prefs.hdUnlocked(this)) { syncingQuality = true; b.setChecked(false); syncingQuality = false; askUnlockHd(); return; }
            int cur = Prefs.videoQuality(this);
            if (on) Prefs.sp(this).edit().putInt("video_quality", 1).apply();
            else if (cur == 1) Prefs.sp(this).edit().putInt("video_quality", 0).apply();
            haptic(b);
            syncQualityUi();
            updatePremiumRows();
        });
        findViewById(R.id.rowPremCaptions).setOnClickListener(v -> { if (!Prefs.captionsUnlocked(this)) askUnlockCaptions(this::updatePremiumUI); else toast("Captions are unlocked"); });
        findViewById(R.id.rowPremFace).setOnClickListener(v -> { if (!Prefs.faceTrackUnlocked(this)) askUnlockFaceTrack(); else toast("AI face tracking is unlocked"); });
        findViewById(R.id.rowPremShorts).setOnClickListener(v -> {
            if (Prefs.isPremium(this)) { toast(proName() + ": pick up to 5 shorts"); return; }
            if (cardControls != null && cardControls.getVisibility() == View.VISIBLE) {
                View chips = findViewById(R.id.chipGroupShortsCount);
                android.view.ViewParent p = chips.getParent();
                while (p != null && !(p instanceof androidx.core.widget.NestedScrollView)) p = p.getParent();
                if (p != null) ((androidx.core.widget.NestedScrollView) p).smoothScrollTo(0, Math.max(0, topIn(chips, (View) p) - 120));
                toast("Tap 2, 3, 4 or 5 — 1 ad unlocks it");
            } else toast("Paste a link first, then tap 2–5 under \u201cNumber of shorts\u201d");
        });
        updatePremiumRows();
    }

    private static int topIn(View v, View ancestor) {
        int y = 0; View c = v;
        while (c != ancestor && c.getParent() instanceof View) { y += c.getTop(); c = (View) c.getParent(); }
        return y;
    }

    private void updateApiStatus() {
        if (tvGemStatus == null) return;
        String gem = Prefs.geminiKey(this), grq = Prefs.groqKey(this);
        boolean g1 = Prefs.isValidGeminiKey(gem), g2 = Prefs.isValidGroqKey(grq);
        dot(dotGem, g1 ? 0xFF22C55E : 0xFFF43F5E);
        dot(dotGroq, g2 ? 0xFF22C55E : 0xFFF43F5E);
        tvGemStatus.setText(g1 ? "Connected" : "Tap to set up");
        tvGroqStatus.setText(g2 ? "Connected" : "Tap to set up");
        tvMasked.setText("Gemini " + (g1 ? gem.substring(0, 6) + "••••" : "not set") + "   •   Groq " + (g2 ? grq.substring(0, 6) + "••••" : "not set"));
        howToKeys.setVisibility(g1 && g2 ? View.GONE : View.VISIBLE);
    }

    private void dot(View v, int color) {
        GradientDrawable d = new GradientDrawable();
        d.setShape(GradientDrawable.OVAL);
        d.setColor(color);
        v.setBackground(d);
    }

    /**
     * 5.3 pre-flight: before a job starts, both keys are checked live at the same time (tiny models-list request, ~5 s
     * at most; a good answer is remembered 10 min per key so repeated taps are instant). A key Google / Groq clearly
     * reject shows the "not valid" popup and the job does not start. No connection / time-out: the job starts as normal.
     * Returns true when the caller may go on now; false = wait (the check calls {@code again} when it passes).
     */
    boolean preflightKeys(Runnable again) {
        if (preflightPassOnce) return true;
        String gem = Prefs.geminiKey(this), grq = Prefs.groqKey(this);
        if (KeyCheck.bothKnownGood(gem, grq)) return true;
        KeyCheck.Verdict cg = KeyCheck.cached(KeyCheck.Service.GEMINI, gem), cq = KeyCheck.cached(KeyCheck.Service.GROQ, grq);
        if (cg != null && cq != null) {
            KeyCheck.Preflight p = KeyCheck.decide(cg, cq);
            if (p == KeyCheck.Preflight.START) return true;
            showKeyProblem(p);
            return false;
        }
        if (keyCheckRunning) return false;
        keyCheckRunning = true;
        btnGenerate.setEnabled(false);
        Thread t = new Thread(() -> {
            KeyCheck.Preflight p = KeyCheck.preflight(gem, grq);
            runOnUiThread(() -> {
                keyCheckRunning = false;
                btnGenerate.setEnabled(true);
                if (isFinishing()) return;
                AppLog.i("Key check before start: " + p);
                if (p == KeyCheck.Preflight.START) {
                    // checked (or couldn't check: never block on the network): go on without asking again
                    preflightPassOnce = true;
                    try { again.run(); } finally { preflightPassOnce = false; }
                } else showKeyProblem(p);
            });
        }, "key-preflight");
        t.setDaemon(true);
        t.start();
        return false;
    }

    /** set while the Generate flow continues right after a passed check (a key we couldn't check isn't checked twice) */
    private boolean preflightPassOnce;

    androidx.appcompat.app.AlertDialog showKeyProblem(KeyCheck.Preflight p) {
        return KeyProblemDialog.show(this, p, s -> {
            nav.setSelectedItemId(R.id.nav_settings);
            showApiKeyDialog(s, true);
        });
    }

    private void showApiKeyDialog() { showApiKeyDialog(null, false); }

    /** {@code focus} = the field to focus (and mark "not valid" when {@code markInvalid}); null = none */
    androidx.appcompat.app.AlertDialog showApiKeyDialog(KeyCheck.Service focus, boolean markInvalid) {
        View v = LayoutInflater.from(this).inflate(R.layout.dialog_keys, null);
        EditText gem = v.findViewById(R.id.etDlgGemini), grq = v.findViewById(R.id.etDlgGroq);
        gem.setText(Prefs.geminiKey(this));
        grq.setText(Prefs.groqKey(this));
        androidx.appcompat.app.AlertDialog d = new MaterialAlertDialogBuilder(this).setTitle("AI keys").setView(v)
                .setPositiveButton("Save", null).setNegativeButton("Cancel", null).create();
        // 5.3: the save-time live check. A key Google / Groq reject shows "Key not valid" on its field and the button
        // becomes "Save anyway" (never a dead end); no connection = saved with a "couldn't check" note.
        final boolean[] force = {false};
        android.text.TextWatcher reset = new android.text.TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int b, int c) {}
            @Override public void onTextChanged(CharSequence s, int a, int b, int c) {
                if (force[0]) { force[0] = false; android.widget.Button p = d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE); if (p != null) p.setText("Save"); }
            }
            @Override public void afterTextChanged(android.text.Editable s) {}
        };
        d.setOnShowListener(x -> {
            gem.addTextChangedListener(reset);
            grq.addTextChangedListener(reset);
            EditText f = focus == KeyCheck.Service.GROQ ? grq : focus == KeyCheck.Service.GEMINI ? gem : null;
            if (f != null) {
                f.requestFocus();
                f.setSelection(f.getText().length());
                if (markInvalid) f.setError("Key not valid. Create a new key and paste it here.");
            }
            android.widget.Button save = d.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE);
            save.setOnClickListener(b -> {
                String g = gem.getText().toString().trim(), q = grq.getText().toString().trim();
                if (!Prefs.isValidGeminiKey(g)) { gem.setError("Must start with AIza… or AQ."); return; }
                if (!Prefs.isValidGroqKey(q)) { grq.setError("Must start with gsk_…"); return; }
                if (force[0]) { saveKeys(g, q, "Keys saved"); d.dismiss(); return; }
                save.setEnabled(false);
                save.setText("Checking…");
                KeyProblemDialog.checkBoth(g, q, (vg, vq) -> {
                    if (!d.isShowing()) return;
                    save.setEnabled(true);
                    save.setText("Save");
                    String eg = KeyCheck.fieldMessage(vg), eq = KeyCheck.fieldMessage(vq);
                    if (eg != null || eq != null) {
                        if (eg != null) gem.setError(eg);
                        if (eq != null) grq.setError(eq);
                        force[0] = true;
                        save.setText("Save anyway");
                        return;
                    }
                    saveKeys(g, q, KeyCheck.saveSummary(vg, vq));
                    d.dismiss();
                });
            });
        });
        d.show();
        return d;
    }

    private void saveKeys(String g, String q, String note) {
        Prefs.setKeys(this, g, q);
        Prefs.sp(this).edit().remove("cached_fast_gemini_model").remove("gemini_models_v4").remove("gemini_models_v4_at").putBoolean("is_onboarding_done", true).apply();
        updateApiStatus();
        toast(note);
    }

    // ================================================================== ads (same rules as v1)

    // v15 startup self-check: one clear log line for init and one for the first test ad request
    private BannerAds banner;
    private long adsInitStart;
    private volatile boolean adsFirstLoadLogged;
    @Override public void onInitializationComplete() {
        runOnUiThread(() -> {
            AppLog.i("Ads: ready (" + (android.os.SystemClock.elapsedRealtime() - adsInitStart) + " ms, SDK " + UnityAds.getVersion() + ")");
            loadAd();
            try { if (banner == null) banner = new BannerAds(this, findViewById(R.id.adBannerBar)); banner.refresh(); } catch (Throwable t) { AppLog.e("Banner init: " + t.getClass().getSimpleName()); }
        });
    }
    @Override public void onInitializationFailed(UnityAds.UnityAdsInitializationError error, String message) { AppLog.e("Ads unavailable: " + error + ": " + message); }

    // v21 (4.0): the rewarded ad is always preloaded and retried (AdRetry); a tap waits for a loading ad instead of failing
    private volatile boolean adLoaded, adLoading;
    private int adFailures;
    private Runnable adPending;            // the reward of the tap that is waiting for an ad
    private boolean adPendingRetried;
    private androidx.appcompat.app.AlertDialog adWaitDialog;
    private final android.os.Handler adHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable adRetryRun = this::loadAd;
    private final Runnable adWaitTimeout = () -> {
        if (adPending == null) return;
        adPending = null;
        dismissAdWait();
        toast("No ad available right now — try again in a minute");
    };

    /** every call this screen makes into the ad SDK (initialize / load / show); the premium edition must make none (tests) */
    static final java.util.concurrent.atomic.AtomicInteger AD_SDK_CALLS = new java.util.concurrent.atomic.AtomicInteger();

    private void loadAd() {
        if (Prefs.PREMIUM_EDITION) return;   // premium edition: no ads
        if (isFinishing() || isDestroyed()) return;
        try { if (!UnityAds.isInitialized()) return; } catch (Throwable t) { return; }
        if (adLoaded || adLoading) return;
        adLoading = true;
        adHandler.removeCallbacks(adRetryRun);
        final long t0 = android.os.SystemClock.elapsedRealtime();
        try {
            AD_SDK_CALLS.incrementAndGet();
            UnityAds.load(AD_UNIT, new IUnityAdsLoadListener() {
                @Override public void onUnityAdsAdLoaded(String placementId) {
                    runOnUiThread(() -> {
                        adLoading = false; adLoaded = true; adFailures = 0;
                        if (!adsFirstLoadLogged) { adsFirstLoadLogged = true; AppLog.i("Ads: rewarded ad ready (" + placementId + ", loaded in " + (android.os.SystemClock.elapsedRealtime() - t0) + " ms)"); }
                        if (adPending != null) showLoadedAd();
                    });
                }
                @Override public void onUnityAdsFailedToLoad(String placementId, UnityAds.UnityAdsLoadError error, String message) {
                    runOnUiThread(() -> {
                        adLoading = false; adLoaded = false; adFailures++;
                        long d = AdRetry.delayMs(adFailures);
                        AppLog.i("Ads: no ad this time (" + error + "), trying again in " + (d / 1000) + " s");
                        if (!isFinishing() && !isDestroyed()) adHandler.postDelayed(adRetryRun, adPending != null ? Math.min(d, 3000) : d);
                    });
                }
            });
        } catch (Throwable t) { adLoading = false; AppLog.e("Ads load: " + t.getClass().getSimpleName() + ": " + t.getMessage()); }
    }

    private void showRewardedAd(Runnable onSuccess) {
        // premium edition: every gate is already open, so no ad is ever asked for; a stray call just grants the reward
        if (Prefs.PREMIUM_EDITION) { if (onSuccess != null) onSuccess.run(); return; }
        if (adPending != null) { toast("Loading ad…"); return; }   // one ad per tap: a second tap never queues another
        adPending = onSuccess == null ? () -> {} : onSuccess;
        adPendingRetried = false;
        boolean ready;
        try { ready = UnityAds.isInitialized() && adLoaded; } catch (Throwable t) { ready = false; }
        if (ready) { showLoadedAd(); return; }
        showAdWait();
        adHandler.postDelayed(adWaitTimeout, AdRetry.WAIT_MS);
        if (adFailures > 0) { adHandler.removeCallbacks(adRetryRun); adFailures = 0; }
        loadAd();   // no-op while one is already loading (the pending tap is served when it arrives)
    }

    private void showAdWait() {
        try {
            dismissAdWait();
            adWaitDialog = new MaterialAlertDialogBuilder(this).setTitle("Loading ad…").setMessage("The reward unlocks after the ad.")
                    .setNegativeButton("Cancel", (d, w) -> { adPending = null; adHandler.removeCallbacks(adWaitTimeout); })
                    .setCancelable(true).setOnCancelListener(d -> { adPending = null; adHandler.removeCallbacks(adWaitTimeout); }).show();
        } catch (Throwable ignored) {}
    }

    private void dismissAdWait() { try { if (adWaitDialog != null) adWaitDialog.dismiss(); } catch (Throwable ignored) {} adWaitDialog = null; }

    private void showLoadedAd() {
        final Runnable reward = adPending;
        if (reward == null) return;
        adHandler.removeCallbacks(adWaitTimeout);
        dismissAdWait();
        adLoaded = false;   // a loaded ad is shown once
        try {
            AD_SDK_CALLS.incrementAndGet();
            UnityAds.show(this, AD_UNIT, new UnityAdsShowOptions(), new IUnityAdsShowListener() {
                @Override public void onUnityAdsShowFailure(String placementId, UnityAds.UnityAdsShowError error, String message) {
                    runOnUiThread(() -> {
                        AppLog.i("Ads: show failed (" + error + ")");
                        if (!adPendingRetried && AdRetry.retryShowAfter(String.valueOf(error))) {
                            adPendingRetried = true;   // the same tap: one fresh load, then show
                            showAdWait();
                            adHandler.postDelayed(adWaitTimeout, AdRetry.WAIT_MS);
                            loadAd();
                        } else { adPending = null; toast("Ad not available right now"); loadAd(); }
                    });
                }
                @Override public void onUnityAdsShowStart(String placementId) {}
                @Override public void onUnityAdsShowClick(String placementId) {}
                @Override public void onUnityAdsShowComplete(String placementId, UnityAds.UnityAdsShowCompletionState state) {
                    runOnUiThread(() -> {
                        adPending = null;
                        // code 32: this screen may have been destroyed while the full-screen ad played (low-RAM phones,
                        // rotation / split screen): the reward is still saved, but nothing may open a dialog on a dead screen
                        // (4.9: BadTokenException crash right after the user watched the ad)
                        boolean gone = isFinishing() || isDestroyed();
                        if (state == UnityAds.UnityAdsShowCompletionState.COMPLETED) {
                            try { incrementAdCount(); } catch (RuntimeException e) { if (!gone) throw e; }
                            try { reward.run(); } catch (RuntimeException e) { if (!gone) throw e; AppLog.i("Ad reward saved (the screen was closed during the ad)"); }
                        } else if (!gone) toast("Ad skipped");
                        if (!gone) loadAd();   // preload the next one right away
                    });
                }
            });
        } catch (Throwable t) { adPending = null; AppLog.e("Ads show: " + t.getClass().getSimpleName()); toast("Ad not available right now"); loadAd(); }
    }

    private void incrementAdCount() {
        SharedPreferences p = Prefs.sp(this);
        int ads = p.getInt("ads_watched_count", 0) + 1;
        if (ads >= 10) {
            ads = 0;
            { android.content.SharedPreferences.Editor ed = p.edit().putBoolean("high_speed", true); Prefs.stampGrant(this, ed, "premium_unlock_time"); ed.apply(); }
            toast("PRO and high-speed download unlocked for 24 hours");
        }
        p.edit().putInt("ads_watched_count", ads).apply();
        updatePremiumUI();
    }

    // ================================================================== errors / support

    private void showError(String title, String msg) {
        new MaterialAlertDialogBuilder(this).setTitle(title).setMessage(msg == null ? "Unknown error" : msg)
                .setPositiveButton("OK", null)
                .setNeutralButton("Report bug", (d, w) -> email("Klipora - Bug Report", "Hi Klipora Team,\n\n" + title + "\n" + msg + "\n\n" + deviceInfo() + "\n=== LOGS ===\n" + AppLog.plain()))
                .show();
    }

    private void showBugReport(String title, String summary, String logs) {
        if (isFinishing()) return;
        new MaterialAlertDialogBuilder(this).setTitle(title)
                .setMessage(summary + "\n\nTap 'Send report' to email this error and the logs to the Klipora team.")
                .setPositiveButton("Send report", (d, w) -> {
                    String body = "Hi Klipora Team,\n\nI hit this error:\n\n=== DEVICE & VIDEO ===\n" + deviceInfo()
                            + "Video: " + (srcTitle == null ? "N/A" : srcTitle) + "\nURL: " + (srcYtUrl == null ? "N/A" : srcYtUrl)
                            + "\n\n=== ERROR ===\n" + summary + "\n\n=== LOGS ===\n" + logs + "\n";
                    copy("Bug Report", body);
                    email("Klipora - Bug Report", body);
                })
                .setNeutralButton(lastRequest != null && lastRequest.kind == JobManager.Kind.ANALYZE ? "Retry" : null, (d, w) -> {
                    if (lastRequest != null && !JobManager.get().isRunning()) {
                        if (!ProcessingService.start(this, lastRequest)) toast("Already processing");
                    }
                })
                .setNegativeButton("Dismiss", null).show();
    }

    private void checkPreviousCrash() {
        if (Prefs.sp(this).getBoolean("job_alive", false)) return;   // code 30: a crash during a job is told by checkKilledJob's card (with the crash in its report)
        String crash = Prefs.sp(this).getString("app_crash_log_data", null);
        if (crash != null) {
            Prefs.sp(this).edit().remove("app_crash_log_data").apply();
            showBugReport("Klipora crashed last time", "The previous session closed unexpectedly.", crash);
        }
    }

    private String deviceInfo() {
        return "Device: " + Build.MANUFACTURER + " " + Build.MODEL + " (Android " + Build.VERSION.RELEASE + ", SDK " + Build.VERSION.SDK_INT + ")\n"
                + "Chip: " + com.viral.shortsmaker.core.RenderSafety.chipLine() + "\n"
                + "yt-dlp: " + YtEngine.version(this) + "\n";
    }

    private void email(String subject, String body) {
        Uri mailto = Uri.parse("mailto:" + Uri.encode(SUPPORT_EMAIL) + "?subject=" + Uri.encode(subject) + "&body=" + Uri.encode(body));
        try {
            startActivity(Intent.createChooser(new Intent(Intent.ACTION_SENDTO, mailto)
                    .putExtra(Intent.EXTRA_EMAIL, new String[]{SUPPORT_EMAIL}).putExtra(Intent.EXTRA_SUBJECT, subject).putExtra(Intent.EXTRA_TEXT, body), "Send email"));
        } catch (android.content.ActivityNotFoundException e) { toast("No email app found. Email: " + SUPPORT_EMAIL); }
        catch (RuntimeException e) { toast("Report copied - paste it into an email to " + SUPPORT_EMAIL); }
    }

    /** code 31: one offer per app start */
    private static boolean updateOffered;

    private void startUpdateCheck() {
        if (!com.viral.shortsmaker.core.UpdateCheck.enabled() || "robolectric".equals(Build.FINGERPRINT) || updateOffered) return;
        final android.content.Context app = getApplicationContext();
        new Thread(() -> {
            long now = System.currentTimeMillis();
            com.viral.shortsmaker.core.UpdateCheck.Info i = com.viral.shortsmaker.core.UpdateCheck.online(app)
                    ? com.viral.shortsmaker.core.UpdateCheck.check(app, now, com.viral.shortsmaker.core.UpdateCheck.http())
                    : com.viral.shortsmaker.core.UpdateCheck.toOffer(app, now);
            if (i == null) return;
            runOnUiThread(() -> {
                if (isFinishing() || isDestroyed() || updateOffered) return;
                updateOffered = true;
                showUpdateDialog(i);
                if (cardError != null && cardError.getVisibility() == View.VISIBLE) findViewById(R.id.btnErrorUpdate).setVisibility(View.VISIBLE);
            });
        }, "update-check").start();
    }

    /** code 31: Download (this phone's APK from GitHub) / Website / Later (that version hidden 3 days) */
    void showUpdateDialog(com.viral.shortsmaker.core.UpdateCheck.Info i) {
        String notes = i.notes == null || i.notes.trim().isEmpty() ? "" : "\n\n" + i.notes.trim();
        new MaterialAlertDialogBuilder(this)
                .setTitle("A new version of Klipora is available")
                .setMessage("Klipora " + com.viral.shortsmaker.core.UpdateCheck.label(i) + " is ready." + notes
                        + "\n\nIt installs over this app: your projects and settings stay.")
                .setPositiveButton("Download", (d, w) -> openUrl(i.apkFor(com.viral.shortsmaker.core.UpdateCheck.arm64())))
                .setNeutralButton("Website", (d, w) -> openUrl(i.site))
                .setNegativeButton("Later", (d, w) -> com.viral.shortsmaker.core.UpdateCheck.later(this, i, System.currentTimeMillis()))
                .show();
    }

    private void openUrl(String url) {
        try { startActivity(new Intent(Intent.ACTION_VIEW, Uri.parse(url))); } catch (Exception e) { toast("Could not open browser"); }
    }

    private void copy(String label, String text) {
        try { ((ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText(label, text)); } catch (Exception ignored) {}
    }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }

    static String fmt(double sec) {
        long t = (long) Math.max(0, sec);
        return t >= 3600 ? String.format(Locale.US, "%d:%02d:%02d", t / 3600, (t % 3600) / 60, t % 60) : String.format(Locale.US, "%d:%02d", t / 60, t % 60);
    }
}
