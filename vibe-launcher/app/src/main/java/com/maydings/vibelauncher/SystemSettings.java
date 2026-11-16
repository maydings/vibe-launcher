package com.maydings.vibelauncher;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.provider.Settings;

/** The TV's own Settings: the gear menu's Android settings row, and holding Home with the Home button service on. */
final class SystemSettings {

    /** The TV settings apps' main screens, tried in turn: Android TV's own, then TCL's (upstream). */
    private static final ComponentName[] TV_SETTINGS = {
            new ComponentName("com.android.tv.settings", "com.android.tv.settings.MainSettings"),
            new ComponentName("com.tcl.settings", "com.tcl.settings.MainActivity")};

    private SystemSettings() {
    }

    /**
     * The standard Settings action, else a TV settings app's main screen where one is installed: some
     * firmware ships it with no filter for the action (upstream: an HY300 Pro+ projector, Android 14; TCL), and
     * the row only said the TV hasn't one.
     */
    static Intent intent(Context context) {
        Intent standard = new Intent(Settings.ACTION_SETTINGS);
        PackageManager pm = context.getPackageManager();
        if (standard.resolveActivity(pm) != null) return standard;
        for (ComponentName c : TV_SETTINGS) {
            Intent direct = new Intent().setComponent(c);
            if (direct.resolveActivity(pm) != null) return direct;
        }
        return standard;
    }
}
