package com.maydings.vibelauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** The TV's own Settings: the gear menu's Android settings row, and holding Home in the store build. */
final class SystemSettings {

    private static final ComponentName AOSP_TV_SETTINGS =
            new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings");

    private SystemSettings() {
    }

    /**
     * The standard Settings action, else the TV settings app's main screen where it's installed: some
     * firmware ships it with no filter for the action (upstream: an HY300 Pro+ projector, Android 14), and
     * the row only said the TV hasn't one.
     */
    static Intent intent(Context context) {
        Intent standard = new Intent(Settings.ACTION_SETTINGS);
        PackageManager pm = context.getPackageManager();
        if (standard.resolveActivity(pm) != null) return standard;
        try {
            pm.getPackageInfo(AOSP_TV_SETTINGS.getPackageName(), 0);
            return new Intent().setComponent(AOSP_TV_SETTINGS);
        } catch (PackageManager.NameNotFoundException e) {
            return standard;
        }
    }
}
