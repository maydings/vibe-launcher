package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.provider.Settings;

/**
 * What the Home button service (HomeKeyService) lends the rest of the app while Android has it bound (never
 * set while it's off), and the way to turn it on: shared by Home's first start and gear menu, and Settings.
 */
final class HomeButtonLink {

    /** Opens the TV's own power menu, where it asks before restarting; null while the service isn't bound. */
    static volatile Runnable powerMenu;
    /** Turns the service off (Settings > General > Home button); null while it isn't bound. */
    static volatile Runnable turnOff;

    private HomeButtonLink() {
    }

    static boolean bound() {
        return powerMenu != null;
    }

    /**
     * Says what the Home button service sees, and that nothing leaves the TV, before the user is sent to turn
     * it on: app stores ask for this up front for any accessibility service that isn't an accessibility tool.
     */
    static void offer(Activity activity) {
        boolean direct = accessibilityDirect(activity);
        // What it does, then what to do in Settings: the user reads it here, before the list opens.
        String text = activity.getString(R.string.home_key_disclosure) + "\n\n"
                + activity.getString(R.string.home_key_disclosure_more, activity.getString(R.string.power_on_home),
                        activity.getString(R.string.restart)) + "\n\n"
                + activity.getString(direct ? R.string.home_key_steps : R.string.home_key_steps_settings,
                        activity.getString(R.string.home_key_name));
        MainActivity.showNotice(activity, R.string.home_key_disclosure_title, text, R.string.home_key_agree,
                () -> {
                    if (direct) openSettings(activity);
                    else openAndroidSettings(activity);
                }, null);
    }

    /**
     * Whether Android's Accessibility screen can be opened straight: on some TVs (Sony's Android 9) only a
     * placeholder answers (Android TV's frameworkpackagestubs), which closes at once, leaving nothing on screen.
     */
    static boolean accessibilityDirect(Context context) {
        return Screens.real(context, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }

    /** Android's Settings, where the window has said how to reach Accessibility. */
    private static void openAndroidSettings(Activity activity) {
        Screens.open(activity, SystemSettings.intent(activity));
    }

    /** The TV's accessibility settings, where the user turns the Home button on (or off). */
    static void openSettings(Activity activity) {
        // What to do there was said in the window before it (home_key_steps).
        Screens.open(activity, new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
    }
}
