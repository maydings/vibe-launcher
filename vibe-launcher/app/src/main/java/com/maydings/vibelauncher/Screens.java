package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ResolveInfo;

/**
 * Android's own screens (Developer options, About, Wi-Fi, Accessibility, App info), opened only where the TV really
 * has them. Android TV answers some it doesn't have with a placeholder (frameworkpackagestubs: Accessibility on
 * Sony's Android 9) that shows "You don't have an app that lets you do this" for a moment and closes, leaving the
 * person with nothing to act on. Such a screen counts as missing, and missing ones say so in a window instead.
 */
final class Screens {

    static final String PLACEHOLDER = "com.google.android.tv.frameworkpackagestubs";

    private Screens() {
    }

    /** Whether something other than the placeholder answers it. */
    static boolean real(Context context, Intent intent) {
        for (ResolveInfo ri : context.getPackageManager().queryIntentActivities(intent,
                android.content.pm.PackageManager.MATCH_DEFAULT_ONLY)) {
            if (ri.activityInfo != null && !PLACEHOLDER.equals(ri.activityInfo.packageName)) return true;
        }
        return false;
    }

    /** Opens it; where the TV has no such screen (or only the placeholder), a window says so. Whether it opened. */
    static boolean open(Activity activity, Intent intent) {
        if (activity == null) return false;
        if (real(activity, intent)) {
            try {
                activity.startActivity(intent);
                return true;
            } catch (RuntimeException ignored) {
                // said below
            }
        }
        MainActivity.showError(activity, R.string.screen_missing);
        return false;
    }
}
