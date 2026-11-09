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
 * other way. Vibe Launcher only ever changes these with it, each when asked: debugging on and off (adb_enabled,
 * and on Android 11 and up adb_wifi_enabled, Wireless debugging, once paired: off when Settings closes if "Turn
 * debugging off when done" is on, and back on by itself when it next needs ADB), and the animation speeds
 * (Appearance), and turning on Vibe Launcher's own Home button service (HomeKeyService), after the window saying
 * what it does and only when OK is pressed there (HomeButtonLink.offer). Nothing else: no other accessibility
 * service, never turning one off, and not the keyboard.
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

    /**
     * Vibe Launcher's Home button service (HomeKeyService) added to Android's list of accessibility services that
     * are on, keeping every other one as it was, and accessibility switched on; false without the permission or if
     * Android doesn't list it as on afterwards. Only after the user agreed in HomeButtonLink.offer's window.
     */
    static boolean turnOnHomeButton(Context context) {
        if (!canWrite(context)) return false;
        if (DefaultHome.homeButtonOn(context)) return true;
        android.content.ContentResolver resolver = context.getContentResolver();
        String me = new android.content.ComponentName(context, HomeKeyService.class).flattenToString();
        try {
            String on = Settings.Secure.getString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
            String list = on == null || on.trim().isEmpty() ? me : on + ":" + me;
            if (!Settings.Secure.putString(resolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES, list)) return false;
            Settings.Secure.putInt(resolver, Settings.Secure.ACCESSIBILITY_ENABLED, 1);
        } catch (SecurityException e) {
            return false;
        }
        return DefaultHome.homeButtonOn(context);
    }

    /**
     * Wireless debugging on or off (Android 11 and up; adb_wifi_enabled); false without the permission. Turned on
     * on a Wi-Fi network the TV hasn't been told to trust, the TV asks whether to allow it there.
     */
    static boolean setWirelessDebugging(Context context, boolean on) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R || !canWrite(context)) return false;
        try {
            return Settings.Global.putInt(context.getContentResolver(), "adb_wifi_enabled", on ? 1 : 0);
        } catch (SecurityException e) {
            return false;
        }
    }

    /** Whether Vibe Launcher reaches this TV through Wireless debugging: Android 11 and up, paired. */
    static boolean pairedForWireless(Context context) {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && AdbTlsKey.paired(context);
    }

    /** Whether the TV is on Wi-Fi now (Wireless debugging works on nothing else). */
    static boolean onWifi(Context context) {
        android.net.ConnectivityManager cm = (android.net.ConnectivityManager)
                context.getSystemService(Context.CONNECTIVITY_SERVICE);
        if (cm == null) return false;
        android.net.NetworkCapabilities caps = cm.getNetworkCapabilities(cm.getActiveNetwork());
        return caps != null && caps.hasTransport(android.net.NetworkCapabilities.TRANSPORT_WIFI);
    }

    /**
     * A player's own network debugging switch, apart from USB debugging: the Shield's Network debugging
     * (adb_tcp_enabled), which opens port 5555 while USB debugging (only its USB port there) stays off. 1 on, 0 off,
     * -1 when the TV has no such switch.
     */
    static int networkDebugging(Context context) {
        return Settings.Global.getInt(context.getContentResolver(), "adb_tcp_enabled", -1);
    }

    /** Waits for adbd to answer, once debugging has been turned on, up to ms; whether it did. Worker thread. */
    static boolean waitForAdb(long ms) {
        long end = System.currentTimeMillis() + ms;
        while (System.currentTimeMillis() < end) {
            if (Adb.check(false).status != Adb.OFF) return true;
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return false;
            }
        }
        return false;
    }

    /**
     * Port 5555 closed, paired for Wireless debugging and it's off: turned on (with the permission, on Wi-Fi) and
     * waited for. Whether it was turned on here. Worker thread.
     */
    static boolean bringUpWireless(Context context) {
        if (!pairedForWireless(context) || Adb.wirelessOn(context) || !onWifi(context)) return false;
        if (!setWirelessDebugging(context, true)) return false;
        waitForAdb(15000);
        return true;
    }

    /**
     * One shell command from outside Settings > Apps (the top bar's Free memory), never asking the TV: Vibe Launcher
     * has to have been allowed there before. Nothing answering with debugging off, it's turned on for it (with the
     * permission) and, with Turn debugging off when done, off again after. Worker thread.
     */
    static Adb.Result shellQuietly(Context context, String command) {
        Adb.use(context);
        boolean turnedOn = false;
        // Tried as it is first: USB debugging off, a player's own switch (the Shield's Network debugging) may have
        // the port open anyway. Only if nothing answers is USB debugging turned on, and never on a player with a
        // switch of its own: USB debugging there is only its USB port, and changing it restarts adbd.
        Adb.Result r = Adb.shell(command, false);
        if (r.status == Adb.OFF && networkDebugging(context) == -1
                && Settings.Global.getInt(context.getContentResolver(), Settings.Global.ADB_ENABLED, 0) != 1
                && setDebugging(context, true)) {
            turnedOn = true;
            waitForAdb(10000);   // its port
            r = Adb.shell(command, false);
        }
        boolean wirelessOn = false;
        if (r.status == Adb.OFF && bringUpWireless(context)) {
            wirelessOn = true;
            r = Adb.shell(command, false);
        }
        if (new VibeSettings(context).adbOffWhenDone()) {
            if (turnedOn) setDebugging(context, false);
            if (wirelessOn) setWirelessDebugging(context, false);
        }
        return r;
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
