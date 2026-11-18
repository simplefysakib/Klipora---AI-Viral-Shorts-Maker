package com.viral.shortsmaker;

import android.app.Activity;
import android.os.Handler;
import android.os.Looper;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;

import com.viral.shortsmaker.core.AppLog;
import com.viral.shortsmaker.core.Prefs;
import com.unity3d.services.banners.BannerErrorInfo;
import com.unity3d.services.banners.BannerView;
import com.unity3d.services.banners.UnityBannerSize;

/** v5.5: a small bottom banner (Unity placement Banner_Android, 320x50) for the free Android edition only.
 *  Never shown to PRO (ad-unlocked) users or the Premium edition; any failure just keeps the bar hidden. */
final class BannerAds {
    static final String PLACEMENT = "Banner_Android";
    private final Activity act;
    private final FrameLayout bar;
    private final Handler h = new Handler(Looper.getMainLooper());
    private BannerView view;
    private boolean destroyed;
    private int tries;
    private static final long[] RETRY_MS = {8000L, 10000L, 15000L, 20000L, 30000L};

    BannerAds(Activity act, FrameLayout bar) { this.act = act; this.bar = bar; }

    private boolean allowed() { return !Prefs.PREMIUM_EDITION && !Prefs.isPremium(act); }

    /** call after Unity Ads is initialised, and again from onResume */
    void refresh() {
        if (destroyed || bar == null) return;
        if (!allowed()) { hide(); return; }
        if (view == null) load();
        else bar.setVisibility(View.VISIBLE);
    }

    private void hide() {
        bar.setVisibility(View.GONE);
        if (view != null) { try { view.destroy(); } catch (Throwable ignored) {} bar.removeAllViews(); view = null; }
    }

    private void load() {
        if (destroyed || act.isFinishing() || act.isDestroyed()) return;
        try {
            bar.removeAllViews();
            bar.setVisibility(View.INVISIBLE);   // measured (50 dp) while loading: Unity's banner needs a laid-out parent, GONE gave NATIVE_ERROR
            final BannerView bv = new BannerView(act, PLACEMENT, new UnityBannerSize(320, 50));
            float d = act.getResources().getDisplayMetrics().density;
            bv.setLayoutParams(new FrameLayout.LayoutParams((int) (320 * d), (int) (50 * d), Gravity.CENTER));
            bv.setListener(new BannerView.IListener() {
                @Override public void onBannerLoaded(BannerView b) {
                    act.runOnUiThread(() -> { if (!destroyed && allowed()) bar.setVisibility(View.VISIBLE); });
                    tries = 0; AppLog.i("Banner: loaded");
                }
                @Override public void onBannerShown(BannerView b) {}
                @Override public void onBannerClick(BannerView b) {}
                @Override public void onBannerFailedToLoad(BannerView b, BannerErrorInfo e) {
                    final long wait = RETRY_MS[Math.min(tries, RETRY_MS.length - 1)]; tries++;
                    AppLog.i("Banner: no fill (" + e.errorCode + "), retry in " + (wait / 1000) + " s");
                    act.runOnUiThread(() -> {
                        bar.setVisibility(tries >= 3 ? View.GONE : View.INVISIBLE);
                        try { b.destroy(); } catch (Throwable ignored) {}
                        bar.removeAllViews();
                        if (view == b) view = null;
                        h.postDelayed(() -> { if (!destroyed && allowed() && view == null) load(); }, wait);
                    });
                }
                @Override public void onBannerLeftApplication(BannerView b) {}
            });
            view = bv;
            bar.addView(bv);
            View shield = new View(act);   // v5.5: banner is display-only; taps are swallowed so ad clicks can't open anything
            shield.setLayoutParams(new FrameLayout.LayoutParams((int) (320 * d), (int) (50 * d), Gravity.CENTER));
            shield.setClickable(true);
            shield.setOnTouchListener((v, ev) -> true);
            bar.addView(shield);
            bv.load();
        } catch (Throwable t) {
            AppLog.e("Banner: " + t.getClass().getSimpleName());
            view = null;
        }
    }

    void destroy() {
        destroyed = true;
        h.removeCallbacksAndMessages(null);
        if (view != null) { try { view.destroy(); } catch (Throwable ignored) {} view = null; }
    }
}
