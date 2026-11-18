package com.viral.shortsmaker;

import android.app.Activity;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.net.Uri;
import android.os.Build;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * code 30 (4.8): "Fix background settings" opens the screens that really decide whether a phone lets Klipora work in the
 * background. 4.7 opened Android's battery-optimisation prompt, a Xiaomi screen or App info - nothing for vivo / iQOO,
 * whose Funtouch / OriginOS stops apps through its own "background power consumption" and "autostart" lists even when
 * Android's battery optimisation is off (his report: vivo V2321, Android 15). Each step lists the known screens of that
 * maker in order (they move between ROM versions); the first one the phone opens is used, App info is the last fallback.
 */
public final class BackgroundFix {
    private BackgroundFix() {}

    public static final class Step {
        public final String label;
        public final List<Intent> intents = new ArrayList<>();
        public boolean done;
        /** no screen to open: shows this tip instead */
        public String tip;
        Step(String label) { this.label = label; }
        Step add(Intent i) { intents.add(i); return this; }
        Step cmp(String pkg, String cls) { return add(new Intent().setComponent(new ComponentName(pkg, cls))); }
    }

    public static String brand(String manufacturer, String brand) {
        String m = ((manufacturer == null ? "" : manufacturer) + " " + (brand == null ? "" : brand)).toLowerCase(Locale.US);
        if (m.contains("vivo") || m.contains("iqoo")) return "vivo";
        if (m.contains("xiaomi") || m.contains("redmi") || m.contains("poco")) return "xiaomi";
        if (m.contains("oppo") || m.contains("realme") || m.contains("oneplus")) return "oppo";
        if (m.contains("samsung")) return "samsung";
        if (m.contains("huawei") || m.contains("honor")) return "huawei";
        return "other";
    }

    public static String brand() { return brand(Build.MANUFACTURER, Build.BRAND); }

    static Intent appDetails(String pkg) {
        return new Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + pkg));
    }

    /** the steps for this maker; batteryOptimized = Android still optimises (restricts) Klipora */
    public static List<Step> steps(String brand, String pkg, boolean batteryOptimized) { return steps(brand, pkg, batteryOptimized, false); }

    /**
     * code 31 (4.9): notificationsOff = Android 13+ with Klipora's notifications off (his HONOR report). The job still runs
     * (Android keeps a foreground service with hidden notifications), but its progress is invisible and some makers' cleaners
     * (Honor / Huawei, Xiaomi) treat an app with no visible notification as idle: one step to allow them, only then.
     */
    public static List<Step> steps(String brand, String pkg, boolean batteryOptimized, boolean notificationsOff) {
        List<Step> out = new ArrayList<>();
        Step bat = new Step("Battery: allow unrestricted use");
        bat.done = !batteryOptimized;
        if (batteryOptimized) bat.add(new Intent(android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:" + pkg)))
                .add(new Intent(android.provider.Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS));
        bat.add(appDetails(pkg));
        out.add(bat);
        if (notificationsOff) {
            out.add(new Step("Notifications: allow (shows the job's progress)")
                    .add(new Intent(android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, pkg))
                    .add(appDetails(pkg)));
        }
        switch (brand) {
            case "vivo":
                out.add(new Step("Background power use: allow high use")
                        .cmp("com.vivo.abe", "com.vivo.applicationbehaviorengine.ui.ExcessivePowerManagerActivity")
                        .cmp("com.iqoo.powersaving", "com.iqoo.powersaving.PowerSavingManagerActivity")
                        .add(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
                        .add(appDetails(pkg)));
                out.add(new Step("Autostart: turn on for Klipora")
                        .cmp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.BgStartUpManagerActivity")
                        .cmp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.BgStartUpManager")
                        .cmp("com.iqoo.secure", "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity")
                        .cmp("com.vivo.permissionmanager", "com.vivo.permissionmanager.activity.PurviewTabActivity")
                        .add(appDetails(pkg)));
                break;
            case "xiaomi":
                Step x = new Step("Battery saver: No restrictions");
                x.add(new Intent().setComponent(new ComponentName("com.miui.powerkeeper", "com.miui.powerkeeper.ui.HiddenAppsConfigActivity"))
                        .putExtra("package_name", pkg).putExtra("package_label", "Klipora"));
                x.add(appDetails(pkg));
                out.add(x);
                out.add(new Step("Autostart: turn on for Klipora")
                        .cmp("com.miui.securitycenter", "com.miui.permcenter.autostart.AutoStartManagementActivity")
                        .add(appDetails(pkg)));
                break;
            case "oppo":
                out.add(new Step("Battery usage: allow background activity").add(appDetails(pkg)));
                out.add(new Step("Auto launch: turn on for Klipora")
                        .cmp("com.coloros.safecenter", "com.coloros.safecenter.startupapp.StartupAppListActivity")
                        .cmp("com.coloros.safecenter", "com.coloros.safecenter.permission.startup.StartupAppListActivity")
                        .cmp("com.oplus.safecenter", "com.oplus.safecenter.startupapp.StartupAppListActivity")
                        .cmp("com.oppo.safe", "com.oppo.safe.permission.startup.StartupAppListActivity")
                        .add(appDetails(pkg)));
                break;
            case "samsung":
                out.add(new Step("Sleeping apps: keep Klipora awake")
                        .cmp("com.samsung.android.lool", "com.samsung.android.sm.battery.ui.BatteryActivity")
                        .cmp("com.samsung.android.lool", "com.samsung.android.sm.ui.battery.BatteryActivity")
                        .add(new Intent(Intent.ACTION_POWER_USAGE_SUMMARY))
                        .add(appDetails(pkg)));
                break;
            case "huawei":
                out.add(new Step("App launch: manage manually, allow all")
                        .cmp("com.huawei.systemmanager", "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                        .cmp("com.hihonor.systemmanager", "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity")
                        .cmp("com.huawei.systemmanager", "com.huawei.systemmanager.optimize.process.ProtectActivity")
                        .add(appDetails(pkg)));
                break;
            default:
                break;
        }
        if (!"other".equals(brand)) {
            Step lock = new Step("Lock Klipora in Recent apps");
            lock.tip = "Open Recent apps, then pull Klipora's card down (or tap its menu) and choose Lock. Clearing Recent apps then leaves it running.";
            out.add(lock);
        }
        return out;
    }

    public static List<Step> steps(Context c, boolean batteryOptimized) {
        boolean off = false;
        try { off = Build.VERSION.SDK_INT >= 33 && !androidx.core.app.NotificationManagerCompat.from(c).areNotificationsEnabled(); } catch (Throwable ignored) {}
        return steps(brand(), c.getPackageName(), batteryOptimized, off);
    }

    /** opens the first screen of the step this phone has; false if none opened */
    public static boolean open(Activity a, Step s) {
        for (Intent i : s.intents) {
            try { a.startActivity(i); return true; } catch (Exception ignored) {}
        }
        return false;
    }
}
