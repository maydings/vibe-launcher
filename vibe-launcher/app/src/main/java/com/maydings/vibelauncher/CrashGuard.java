package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.provider.Settings;

/**
 * Escape hatch for when this is the only home screen and it keeps crashing. An app can't re-enable
 * another (that needs a system permission), so on the second crash within two minutes this opens the
 * App info page of Google's TV launcher (whichever this TV has), where one press of Enable gives the TV
 * a working home again.
 * A single crash just restarts as usual.
 */
final class CrashGuard {

    /** The TV's own launchers, newest first: Google TV, Android TV and its older version, and the plain one. */
    private static final String[] STOCK_LAUNCHERS = {"com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher", "com.google.android.leanbacklauncher", "com.android.tv.launcher"};
    private static final long WINDOW_MS = 2 * 60 * 1000;
    /**
     * Once per process. Home is recreated (a new tiles-per-row choice, a language change) without the
     * process restarting; a second install wrapped the first, so one crash was counted twice and went
     * straight to the stock launcher's App info.
     */
    private static boolean installed;
    private static final java.util.concurrent.atomic.AtomicBoolean crashed =
            new java.util.concurrent.atomic.AtomicBoolean();

    private CrashGuard() {
    }

    @SuppressLint("ApplySharedPref")   // on disk at once: the process is dying
    static void install(Context context) {
        if (installed) return;
        installed = true;
        Context app = context.getApplicationContext();
        Thread.UncaughtExceptionHandler previous = Thread.getDefaultUncaughtExceptionHandler();
        Thread.setDefaultUncaughtExceptionHandler((thread, error) -> {
            if (crashed.compareAndSet(false, true)) {
                try {
                    SharedPreferences prefs = app.getSharedPreferences("crashes", Context.MODE_PRIVATE);
                    long now = System.currentTimeMillis();
                    long elapsed = now - prefs.getLong("last", 0);
                    int count = elapsed >= 0 && elapsed < WINDOW_MS ? prefs.getInt("count", 0) + 1 : 1;
                    prefs.edit().putLong("last", now).putInt("count", count).commit();   // the process is dying
                    if (count >= 2) openFallbackInfo(app);
                } catch (Throwable ignored) {
                }
            }
            if (previous != null) previous.uncaughtException(thread, error);
        });
    }

    /**
     * Started straight from the crash handler: the system carries out the request even though this
     * process is about to die. (An alarm doesn't work here: plain alarms are inexact and can be
     * deferred for minutes.)
     */
    private static void openFallbackInfo(Context app) {
        // The one that was turned off for Vibe Launcher, which Enable brings back; else the first there is.
        String stock = null;
        java.util.List<String> off = TvControl.stockOff(app);
        if (!off.isEmpty()) stock = off.get(0);
        for (String pkg : STOCK_LAUNCHERS) {
            if (stock != null) break;
            try {
                app.getPackageManager().getApplicationInfo(pkg, 0);   // also finds disabled apps
                stock = pkg;
            } catch (PackageManager.NameNotFoundException ignored) {
            }
        }
        if (stock == null) stock = STOCK_LAUNCHERS[1];
        app.startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:" + stock))
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_CLEAR_TASK));
    }
}
