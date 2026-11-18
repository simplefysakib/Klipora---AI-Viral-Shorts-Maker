package com.viral.shortsmaker.core;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * v21 (4.0) ad "Install" / link clicks that really open.
 *
 * Unity Ads opens the advertised app's store page with startActivity(VIEW market://... or https://...). On his OPPO
 * (ColorOS) and other phones that launch can fail: Unity 4.12 started it from the app (Application) context with no
 * fallback, so when the phone refused it nothing happened. Here every such VIEW launch that reaches Klipora's own
 * Application is (1) started from the screen in front (the ad's own Activity - never a "background start"), and if
 * that fails (2) tried again without the forced package, then (3) as the Play Store web page, then (4) in any browser.
 * Nothing is clicked or opened that the user did not tap; this only makes the user's own tap work.
 */
public final class LinkOpener {
    private LinkOpener() {}

    public static final String PLAY_WEB = "https://play.google.com/store/apps/details?id=";

    /** true for the user-visible link launches an ad SDK makes (VIEW of market:, http(s):, intent: links) */
    public static boolean isAdLink(Intent i) {
        if (i == null || !Intent.ACTION_VIEW.equals(i.getAction()) || i.getData() == null) return false;
        if (i.getComponent() != null) return false;   // an explicit component (e.g. Unity's own ad screen) is never touched
        String s = i.getData().getScheme();
        if (s == null) return false;
        s = s.toLowerCase(Locale.US);
        return s.equals("market") || s.equals("http") || s.equals("https");
    }

    /** the Play Store web address for a market://details?id=... link (null when it has no id) */
    public static String playWebUrl(String url) {
        if (url == null) return null;
        String u = url.trim();
        String low = u.toLowerCase(Locale.US);
        if (!low.startsWith("market://")) return null;
        int q = u.indexOf('?');
        if (q < 0) return null;
        return "https://play.google.com/store/apps/details" + u.substring(q);
    }

    /** the ordered alternatives tried after the original launch failed (pure, unit tested) */
    public static List<Intent> fallbacks(Intent original) {
        List<Intent> out = new ArrayList<>();
        if (original == null || original.getData() == null) return out;
        String url = original.getData().toString();
        int flags = original.getFlags() | Intent.FLAG_ACTIVITY_NEW_TASK;
        if (original.getPackage() != null) {
            Intent a = new Intent(Intent.ACTION_VIEW, original.getData());
            a.setFlags(flags);
            out.add(a);
        }
        String web = playWebUrl(url);
        if (web != null) {
            Intent play = new Intent(Intent.ACTION_VIEW, Uri.parse(web));
            play.setPackage("com.android.vending");
            play.setFlags(flags);
            out.add(play);
            Intent browser = new Intent(Intent.ACTION_VIEW, Uri.parse(web));
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            browser.setFlags(flags);
            out.add(browser);
        } else if (url.startsWith("http")) {
            Intent browser = new Intent(Intent.ACTION_VIEW, original.getData());
            browser.addCategory(Intent.CATEGORY_BROWSABLE);
            browser.setFlags(flags);
            out.add(browser);
        }
        return out;
    }

    public interface Starter { void start(Context from, Intent i) throws RuntimeException; }

    /**
     * Opens an ad link: first from the front Activity (if any), else from the given context; then the fallbacks.
     * Throws the first error when nothing could open it (so the ad SDK knows and can try its own way).
     */
    public static void open(Context app, Activity front, Intent i, Starter starter) {
        RuntimeException first = null;
        Context primary = front != null && !front.isFinishing() ? front : app;
        try { starter.start(primary, i); log("Ad link opened" + (primary == front ? " from the ad screen" : "")); return; }
        catch (RuntimeException e) { first = e; }
        if (primary != app) {
            try { starter.start(app, i); log("Ad link opened"); return; } catch (RuntimeException e) { /* keep the first */ }
        }
        for (Intent f : fallbacks(i)) {
            try { starter.start(primary, f); log("Ad link opened via " + describe(f)); return; }
            catch (RuntimeException e) { /* next */ }
        }
        log("Ad link could not be opened on this phone (" + first.getClass().getSimpleName() + ")");
        throw first;
    }

    static String describe(Intent f) {
        if ("com.android.vending".equals(f.getPackage())) return "the Play Store page";
        if (f.hasCategory(Intent.CATEGORY_BROWSABLE)) return "the browser";
        return "any app that opens it";
    }

    static void log(String s) { try { AppLog.i(s); } catch (Throwable ignored) {} }

    /** true when the error means "no app/phone refused" (worth a fallback), as opposed to a programming error */
    public static boolean isLaunchFailure(Throwable t) {
        return t instanceof ActivityNotFoundException || t instanceof SecurityException || t instanceof IllegalStateException;
    }
}
