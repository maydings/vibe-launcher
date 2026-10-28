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
        else screen.first().requestFocus();
    }

    @Override
    protected void onResume() {
        super.onResume();
        screen.showSmartTube();   // installed or signed in meanwhile
        screen.showHomeButton();   // turned on or off in Accessibility meanwhile
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
