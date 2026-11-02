package com.maydings.vibelauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.provider.Settings;

import java.text.Collator;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The apps on the TV for Settings > Apps, and turning them off and on through the TV's own ADB (Adb): "pm
 * disable-user", as a computer would, which leaves the app installed but stops it running, showing cards and
 * updating until "pm enable" turns it back on.
 * <p>
 * What the TV needs to work is listed apart, grayed, and can't be turned off from here: Android itself, Google Play
 * and its services, Settings, the keyboard in use, the remote and Bluetooth, Wi-Fi and the network, the TV's
 * inputs and the app that plays them, the setup app (which an update can need), and the launchers (turning the
 * TV's own off to make Vibe Launcher Home is a step of its own). Apps the system keeps running at all times
 * (persistent) too. Kept on the safe side: more is left out than strictly has to be.
 * <p>
 * What's turned off here is remembered (VibeSettings.appsOff, so a backup keeps it too): Turn all back on
 * undoes exactly that, whatever else was turned off by other means.
 */
final class TvApps {

    /** One app: its name, its package, whether it's on, and whether the TV needs it (never turned off here). */
    static final class App {
        final String pkg;
        final String label;
        final boolean needed;
        boolean on;

        App(String pkg, String label, boolean needed, boolean on) {
            this.pkg = pkg;
            this.label = label;
            this.needed = needed;
            this.on = on;
        }
    }

    /** Packages left out whole. */
    private static final Set<String> KEEP = new HashSet<>(java.util.Arrays.asList(
            "android", "com.android.systemui", "com.android.settings", "com.android.tv.settings",
            "com.android.vending", "com.google.android.gms", "com.google.android.gsf", "com.android.shell",
            "com.android.packageinstaller", "com.google.android.packageinstaller",
            "com.android.permissioncontroller", "com.google.android.permissioncontroller",
            "com.android.externalstorage", "com.android.keychain", "com.android.certinstaller",
            "com.android.location.fused", "com.android.se", "com.android.phone", "com.android.nfc",
            "com.android.inputdevices", "com.android.bluetooth", "com.android.bluetoothmidiservice",
            "com.google.android.tvlauncher", "com.google.android.apps.tv.launcherx",
            "com.google.android.leanbacklauncher", "com.android.tv.launcher",
            // Sony: what plays the inputs and the remote's own keys (as Clean Your TV's Sony lists keep)
            "com.sony.dtv.tvx", "com.sony.dtv.sonysystemservice", "com.sony.dtv.imeproxy",
            "com.sony.dtv.uinputservice", "com.sony.dtv.rcpowerkey"));

    /** Package names containing any of these: families of system parts, and anything for the remote or inputs. */
    private static final Pattern KEEP_LIKE = Pattern.compile(
            "^com\\.android\\.providers\\.|^com\\.android\\.server\\.|^com\\.android\\.networkstack|"
                    + "^com\\.google\\.android\\.networkstack|^com\\.android\\.wifi|^com\\.google\\.android\\.wifi|"
                    + "webview|inputmethod|\\.ime$|tvinput|remote|pairing|accessoryserver|bluetooth|"
                    + "settingsprovider|setupwizard|setupwraith|tungsten\\.setup|^com\\.mediatek\\.|mtktvapi|"
                    + "^com\\.droidlogic\\.|^com\\.amlogic\\.|braviasync|^com\\.android\\.tv\\.inputs?");

    private TvApps() {
    }

    /**
     * The apps that may be turned off (needed false), or those the TV needs (true, shown but never switched), by
     * name. Vibe Launcher itself is in neither.
     */
    static List<App> list(Context context, boolean needed) {
        PackageManager pm = context.getPackageManager();
        Set<String> keep = alsoKept(context);
        List<App> apps = new ArrayList<>();
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        for (ApplicationInfo ai : pm.getInstalledApplications(flags)) {
            if (ai.packageName.equals(context.getPackageName()) || kept(ai, keep) != needed) continue;
            String label = String.valueOf(ai.loadLabel(pm));
            apps.add(new App(ai.packageName, label.isEmpty() ? ai.packageName : label, needed,
                    isOn(pm, ai.packageName)));
        }
        Collator byName = Collator.getInstance();
        byName.setStrength(Collator.PRIMARY);
        Collections.sort(apps, (a, b) -> byName.compare(a.label, b.label));
        return apps;
    }

    /** On, as far as the TV says now (an app "disabled until used" counts as on: it starts when opened). */
    static boolean isOn(PackageManager pm, String pkg) {
        try {
            int state = pm.getApplicationEnabledSetting(pkg);
            return state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED
                    && state != PackageManager.COMPONENT_ENABLED_STATE_DISABLED_USER;
        } catch (IllegalArgumentException e) {
            return false;   // gone meanwhile
        }
    }

    private static boolean kept(ApplicationInfo ai, Set<String> keep) {
        String p = ai.packageName;
        return KEEP.contains(p) || keep.contains(p) || KEEP_LIKE.matcher(p).find()
                || (ai.flags & ApplicationInfo.FLAG_PERSISTENT) != 0;
    }

    /** What this TV needs in particular: the keyboard in use, the Home apps, and the apps behind its inputs. */
    private static Set<String> alsoKept(Context context) {
        Set<String> keep = new HashSet<>();
        String ime = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.DEFAULT_INPUT_METHOD);
        if (ime != null && ime.contains("/")) keep.add(ime.substring(0, ime.indexOf('/')));
        Intent home = new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
        for (ResolveInfo r : context.getPackageManager().queryIntentActivities(home, PackageManager.MATCH_ALL)) {
            keep.add(r.activityInfo.packageName);
        }
        TvInputManager tv = (TvInputManager) context.getSystemService(Context.TV_INPUT_SERVICE);
        if (tv != null) {
            try {
                for (TvInputInfo input : tv.getTvInputList()) keep.add(input.getServiceInfo().packageName);
            } catch (RuntimeException ignored) {
                // no TV input service on this box
            }
        }
        return keep;
    }

    /** A package name, and nothing a shell would read as anything else. */
    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z0-9_.]+");

    /** The shell command that turns this app off or on. */
    static String command(String pkg, boolean on) {
        if (!PACKAGE.matcher(pkg).matches()) throw new IllegalArgumentException(pkg);
        return on ? "pm enable " + pkg : "pm disable-user --user 0 " + pkg;
    }

    /**
     * Remembers an app turned off here (or forgets one turned back on), once the TV says it took: what Turn all
     * back on undoes.
     */
    static void remember(Context context, String pkg, boolean on) {
        VibeSettings settings = new VibeSettings(context);
        Set<String> off = new HashSet<>(settings.appsOff());
        if (on) off.remove(pkg);
        else off.add(pkg);
        settings.setAppsOff(off);
    }

    /**
     * The apps turned off here before that are on now and may be turned off: from a backup restored on this
     * TV (the record comes with the settings; the apps don't change), or turned back on by an update. Not the
     * ones missing here (they may come later) or that this TV needs. By name.
     */
    static List<App> toTurnOffAgain(Context context) {
        PackageManager pm = context.getPackageManager();
        Set<String> keep = alsoKept(context);
        List<App> apps = new ArrayList<>();
        int flags = PackageManager.MATCH_DISABLED_COMPONENTS | PackageManager.MATCH_DISABLED_UNTIL_USED_COMPONENTS;
        for (String pkg : new VibeSettings(context).appsOff()) {
            ApplicationInfo ai;
            try {
                ai = pm.getApplicationInfo(pkg, flags);
            } catch (PackageManager.NameNotFoundException e) {
                continue;
            }
            if (!isOn(pm, pkg) || kept(ai, keep) || pkg.equals(context.getPackageName())) continue;
            String label = String.valueOf(ai.loadLabel(pm));
            apps.add(new App(pkg, label.isEmpty() ? pkg : label, false, true));
        }
        Collator byName = Collator.getInstance();
        byName.setStrength(Collator.PRIMARY);
        Collections.sort(apps, (a, b) -> byName.compare(a.label, b.label));
        return apps;
    }

    /** The apps turned off here and still off: what Turn all back on would turn on. */
    static List<String> turnedOff(Context context) {
        PackageManager pm = context.getPackageManager();
        List<String> off = new ArrayList<>();
        for (String pkg : new VibeSettings(context).appsOff()) {
            if (!isOn(pm, pkg)) off.add(pkg);
        }
        Collections.sort(off);
        return off;
    }
}
