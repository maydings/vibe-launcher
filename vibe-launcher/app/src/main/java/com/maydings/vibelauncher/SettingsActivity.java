package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.Context;
import android.os.Bundle;

/**
 * Vibe Launcher settings on a screen of their own, so Home can be made again behind them (tiles per row, the
 * language) and stops drawing while they're up. The screen itself is made ahead (SettingsScreen.prepare):
 * this only puts it on, fading in, and hands it back when it goes. Home in its task takes it off as usual
 * when Home is pressed.
 */
public final class SettingsActivity extends Activity {

    /** Back after a language change: opens on General, where the language was picked. */
    static final String ON_GENERAL = "onGeneral";
    /** Open on Apps > Default launcher (the top bar's Make Vibe Launcher your Home, with ADB allowed). */
    static final String ON_DEFAULT_LAUNCHER = "onDefaultLauncher";

    private SettingsScreen screen;

    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(Lang.wrap(base));   // the language chosen in Settings, or the TV's own
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        screen = SettingsScreen.get(this);
        setContentView(screen.attach(this));
        if (getIntent().getBooleanExtra(ON_GENERAL, false)) screen.showGeneral();
        else if (getIntent().getBooleanExtra(ON_DEFAULT_LAUNCHER, false)) screen.showDefaultLauncher();
        else screen.first().requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        screen.showSmartTube();   // installed or signed in meanwhile
        screen.showWallpapers();   // some picked in a browsing grid meanwhile
    }

    /** Storage access for a backup on the TV, up to Android 10 (Backup.needsPermission). */
    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode != SettingsScreen.STORAGE_REQUEST) return;
        screen.onStoragePermission(results.length > 0 && results[0] == android.content.pm.PackageManager.PERMISSION_GRANTED);
    }

    @Override
    public void onBackPressed() {
        screen.back();
    }

    @Override
    public void finish() {
        super.finish();
        overridePendingTransition(0, R.anim.settings_out);
    }

    @Override
    protected void onDestroy() {
        screen.detach(this);
        super.onDestroy();
    }
}
