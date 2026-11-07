package com.maydings.vibelauncher;

import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.content.pm.ResolveInfo;

/**
 * Whether Vibe Launcher is the TV's Home. It becomes Home through the TV's own ADB (Settings > Advanced >
 * Default launcher, TvControl.makeHomeCommand): the TV's own launchers off, Vibe Launcher set as Home. Android's
 * own ways (its Home dialog, the chooser) don't reach the Home button on most TVs, whose launcher outranks them.
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
}
