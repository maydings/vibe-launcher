package com.maydings.vibelauncher;

import android.Manifest;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * What Settings > Apps does to the TV beyond turning apps off (the adb build only), through its ADB (Adb) or
 * through WRITE_SECURE_SETTINGS, which ADB hands to Vibe Launcher once, the first time it connects.
 * <p>
 * WRITE_SECURE_SETTINGS lets an app change Android's Secure and Global settings, which no app can be given any
 * other way. Vibe Launcher only ever changes three things with it, each when asked: debugging on and off
 * (adb_enabled: off when Settings closes if "Turn debugging off when done" is on, and back on by itself when it
 * next needs ADB), and the animation speeds (Appearance). Nothing else, in particular not the accessibility
 * services or the keyboard.
 * <p>
 * Home: the TV's own launchers turned off and Vibe Launcher set as Home in one shell command, which turns them
 * back on if any step fails; then SettingsScreen checks Android really sends Home here. Back to the TV's own home
 * screen turns them on again and gives Home back to the first. Not on Google's Chromecast with Google TV and
 * Google TV Streamer, where another Home stops casting (as Clean Your TV found).
 */
final class TvControl {

    /** The stock launchers Make Vibe Launcher Home turns off, and Google TV's setup app, which can take Home back. */
    private static final String[] STOCK_HOME = {"com.google.android.apps.tv.launcherx",
            "com.google.android.tvlauncher", "com.google.android.leanbacklauncher", "com.android.tv.launcher"};
    private static final String GOOGLE_TV_SETUP = "com.google.android.tungsten.setupwraith";

    private TvControl() {
    }

    // ---- WRITE_SECURE_SETTINGS -------------------------------------------------------------------

    /** Whether ADB has given Vibe Launcher WRITE_SECURE_SETTINGS. */
    static boolean canWrite(Context context) {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED;
    }

    /** The shell command that gives it, run once over ADB. */
    static String grantCommand(Context context) {
        return "pm grant " + context.getPackageName() + " " + Manifest.permission.WRITE_SECURE_SETTINGS;
    }

    /** Debugging on or off (USB/ADB debugging, which opens port 5555 too on most TVs); false without the permission. */
    static boolean setDebugging(Context context, boolean on) {
        if (!canWrite(context)) return false;
        try {
            return Settings.Global.putInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, on ? 1 : 0);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** The animation speed the TV uses now: 1 normal, 0.5 faster, 0 off (the window animations' scale stands for all three). */
    static float animationScale(Context context) {
        return Settings.Global.getFloat(context.getContentResolver(), Settings.Global.ANIMATOR_DURATION_SCALE, 1f);
    }

    /** All three animation scales at once, as Developer options sets them; false without the permission. */
    static boolean setAnimationScale(Context context, float scale) {
        if (!canWrite(context)) return false;
        try {
            android.content.ContentResolver cr = context.getContentResolver();
            return Settings.Global.putFloat(cr, Settings.Global.ANIMATOR_DURATION_SCALE, scale)
                    & Settings.Global.putFloat(cr, Settings.Global.TRANSITION_ANIMATION_SCALE, scale)
                    & Settings.Global.putFloat(cr, Settings.Global.WINDOW_ANIMATION_SCALE, scale);
        } catch (SecurityException e) {
            return false;
        }
    }

    // ---- Home ------------------------------------------------------------------------------------

    /** Google's own players, where another Home costs casting: Make Vibe Launcher Home isn't offered there. */
    static boolean homeUnsupported() {
        String model = (Build.MODEL + " " + Build.DEVICE).toLowerCase(Locale.ROOT);
        return "google".equalsIgnoreCase(Build.MANUFACTURER)
                && (model.contains("chromecast") || model.contains("streamer") || model.contains("sabrina")
                || model.contains("boreal") || model.contains("kirkwood"));
    }

    /** The stock launchers (and Google TV's setup app) installed here and still on: what goes for Vibe Launcher to be Home. */
    static List<String> stockOn(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String> on = new ArrayList<>();
        for (String pkg : STOCK_HOME) if (installed(pm, pkg) && TvApps.isOn(pm, pkg)) on.add(pkg);
        if (!on.isEmpty() && installed(pm, GOOGLE_TV_SETUP) && TvApps.isOn(pm, GOOGLE_TV_SETUP)) on.add(GOOGLE_TV_SETUP);
        return on;
    }

    /** The stock launchers installed here and turned off: what Back to the TV's own home screen turns on. */
    static List<String> stockOff(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String> off = new ArrayList<>();
        for (String pkg : STOCK_HOME) if (installed(pm, pkg) && !TvApps.isOn(pm, pkg)) off.add(pkg);
        if (!off.isEmpty() && installed(pm, GOOGLE_TV_SETUP) && !TvApps.isOn(pm, GOOGLE_TV_SETUP)) off.add(GOOGLE_TV_SETUP);
        return off;
    }

    /**
     * One shell command: these off, then Vibe Launcher as Home; if any step fails, these back on. Android forgets
     * the chosen Home whenever a Home app is turned on or off, so it's set after them.
     */
    static String makeHomeCommand(Context context, List<String> stock) {
        StringBuilder off = new StringBuilder(), on = new StringBuilder();
        for (String pkg : stock) {
            off.append(TvApps.command(pkg, false)).append(" && ");
            on.append(TvApps.command(pkg, true)).append("; ");
        }
        String us = context.getPackageName() + "/" + MainActivity.class.getName();
        return "{ " + off + "cmd package set-home-activity " + us + "; } || { " + on + "echo FAILED; }";
    }

    /**
     * These back on, and Home given to the first stock launcher among them that has a Home screen (it can only be
     * named once it's on, so it's looked up from what Android lists, turned-off apps included).
     */
    static String restoreHomeCommand(Context context, List<String> stock) {
        StringBuilder cmd = new StringBuilder();
        for (String pkg : stock) cmd.append(TvApps.command(pkg, true)).append("; ");
        String home = stockHomeActivity(context, stock);
        if (home != null) cmd.append("cmd package set-home-activity ").append(home);
        return cmd.toString();
    }

    private static String stockHomeActivity(Context context, List<String> stock) {
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        for (ResolveInfo ri : context.getPackageManager().queryIntentActivities(home, flags)) {
            if (ri.activityInfo != null && stock.contains(ri.activityInfo.packageName)
                    && !GOOGLE_TV_SETUP.equals(ri.activityInfo.packageName)) {
                return ri.activityInfo.packageName + "/" + ri.activityInfo.name;
            }
        }
        return null;
    }

    private static boolean installed(PackageManager pm, String pkg) {
        try {
            pm.getApplicationInfo(pkg, PackageManager.MATCH_DISABLED_COMPONENTS);
            return true;
        } catch (PackageManager.NameNotFoundException e) {
            return false;
        }
    }
}
