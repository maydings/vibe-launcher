package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.provider.Settings;
import android.widget.Toast;

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
        // What it does, then what to do in Settings: the user reads it here, before the list opens.
        String text = activity.getString(R.string.home_key_disclosure) + "\n\n"
                + activity.getString(R.string.home_key_disclosure_more, activity.getString(R.string.power_on_home),
                        activity.getString(R.string.restart)) + "\n\n"
                + activity.getString(R.string.home_key_steps, activity.getString(R.string.home_key_name));
        MainActivity.showNotice(activity, R.string.home_key_disclosure_title, text, R.string.home_key_agree,
                () -> openSettings(activity), null);
    }

    /** The TV's accessibility settings, where the user turns the Home button on (or off). */
    static void openSettings(Activity activity) {
        try {
            activity.startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS));
            Toast.makeText(activity, activity.getString(R.string.home_key_turn_on,
                    activity.getString(R.string.home_key_name)), Toast.LENGTH_LONG).show();
        } catch (ActivityNotFoundException | SecurityException e) {
            Toast.makeText(activity, R.string.home_not_changeable, Toast.LENGTH_LONG).show();
        }
    }
}
