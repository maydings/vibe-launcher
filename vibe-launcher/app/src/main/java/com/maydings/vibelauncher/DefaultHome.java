package com.maydings.vibelauncher;

import android.app.role.RoleManager;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;
import android.os.Build;
import android.provider.Settings;

/**
 * Making Vibe Launcher the default Home through the TV's own UI, with no computer. Android 10+ has a
 * system dialog for it. Android 9 has none (Sony's "Home app" setting is an empty stub), but while no
 * default is chosen, starting Home shows Android's chooser with "Always". Once a default is chosen there
 * is no way back from an app: only adb (build-and-install.sh) can change it then. Even as the default,
 * some TVs send the Home button to their stock launcher until it is disabled, which also needs adb.
 */
final class DefaultHome {

    private DefaultHome() {
    }

    private static Intent home() {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME);
    }

    /** Whether pressing Home opens Vibe Launcher. */
    static boolean isHome(Context context) {
        ResolveInfo ri = context.getPackageManager().resolveActivity(home(), PackageManager.MATCH_DEFAULT_ONLY);
        return ri != null && ri.activityInfo != null && context.getPackageName().equals(ri.activityInfo.packageName);
    }

    /**
     * Whether becoming the default Home (request) would bring the Home button here. Android picks a Home
     * app that declares a higher priority before it looks at the user's choice, and only system apps can:
     * Google TV's launcher does (2, on the onn 4K stick), so there the Home role is granted and changes
     * nothing. Then only the Home button service helps.
     */
    static boolean choiceWins(Context context) {
        int ours = 0, best = Integer.MIN_VALUE;
        for (ResolveInfo ri : context.getPackageManager().queryIntentActivities(home(), PackageManager.MATCH_DEFAULT_ONLY)) {
            if (ri.activityInfo == null) continue;
            if (context.getPackageName().equals(ri.activityInfo.packageName)) ours = ri.priority;
            else if (!ri.activityInfo.name.endsWith("FallbackHome")) best = Math.max(best, ri.priority);
        }
        return best <= ours;
    }

    /** The Home button (HomeKeyService) is on under Settings > Accessibility. */
    static boolean homeButtonOn(Context context) {
        String on = Settings.Secure.getString(context.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
        if (on == null) return false;
        String pkg = context.getPackageName();
        for (String service : on.split(":")) {
            if (service.equals(pkg + "/.HomeKeyService") || service.equals(pkg + "/" + pkg + ".HomeKeyService")) return true;
        }
        return false;
    }

    /** The system screen that makes Vibe Launcher the default Home, or null when this TV has none. */
    static Intent request(Context context) {
        if (Build.VERSION.SDK_INT >= 29) {
            RoleManager roles = context.getSystemService(RoleManager.class);
            if (roles != null && roles.isRoleAvailable(RoleManager.ROLE_HOME) && !roles.isRoleHeld(RoleManager.ROLE_HOME)) {
                return roles.createRequestRoleIntent(RoleManager.ROLE_HOME);
            }
            return null;
        }
        // No default yet: Home resolves to Android's chooser ("android"), which offers "Always".
        ResolveInfo ri = context.getPackageManager().resolveActivity(home(), PackageManager.MATCH_DEFAULT_ONLY);
        boolean chooser = ri != null && ri.activityInfo != null && "android".equals(ri.activityInfo.packageName);
        return chooser ? home().addFlags(Intent.FLAG_ACTIVITY_NEW_TASK) : null;
    }
}
