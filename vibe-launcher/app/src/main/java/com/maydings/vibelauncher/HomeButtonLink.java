package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.ActivityNotFoundException;
import android.content.Context;
import android.content.Intent;
import android.os.Handler;
import android.os.Looper;
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
        offer(activity, null, null);
    }

    /** How long Android gets to start the service once it's listed as on, before Accessibility settings open. */
    private static final long BIND_WAIT_MS = 2000;

    /**
     * As offer, with lead (why it's asked) above the disclosure, and done run once the service is on through
     * Agree here (a screen showing whether it's on refreshes; null for none). With WRITE_SECURE_SETTINGS (ADB gave
     * it once), Agree turns the service on itself (TvControl.turnOnHomeButton) and waits for Android to start it;
     * without, or if it doesn't start, Android's Accessibility settings open, where the window has said what to do.
     * Only where ADB has never worked does the window say that's why an accessibility service is used.
     */
    static void offer(Activity activity, CharSequence lead, Runnable done) {
        boolean write = TvControl.canWrite(activity);
        boolean direct = accessibilityDirect(activity);
        // What it does, then what Agree does or what to do in Settings: read here, before anything changes.
        String steps = write ? activity.getString(R.string.home_key_steps_direct)
                : activity.getString(direct ? R.string.home_key_steps : R.string.home_key_steps_settings,
                        activity.getString(R.string.home_key_name));
        boolean noAdb = !write && !Adb.everWorked(activity);
        String text = (lead != null ? lead + "\n\n" : "")
                + (noAdb ? activity.getString(R.string.home_key_disclosure_why) + "\n\n" : "")
                + activity.getString(R.string.home_key_disclosure) + "\n\n"
                + activity.getString(R.string.home_key_disclosure_more, activity.getString(R.string.power_on_home),
                        activity.getString(R.string.restart)) + "\n\n"
                + steps;
        Runnable toSettings = () -> {
            if (direct) openSettings(activity);
            else openAndroidSettings(activity);
        };
        MainActivity.showNotice(activity, R.string.home_key_disclosure_title, text, R.string.home_key_agree,
                () -> {
                    if (!write || !TvControl.turnOnHomeButton(activity)) {
                        toSettings.run();
                        return;
                    }
                    // Listed as on isn't running: some firmware won't start it. Bound, it's working; not, Accessibility
                    // settings show what Android made of it.
                    new Handler(Looper.getMainLooper()).postDelayed(() -> {
                        if (activity.isFinishing() || activity.isDestroyed()) return;
                        if (bound()) {
                            Toast.makeText(activity, activity.getString(R.string.home_key_on,
                                    activity.getString(R.string.home_key_name)), Toast.LENGTH_LONG).show();
                        } else {
                            toSettings.run();
                        }
                        if (done != null) done.run();
                    }, BIND_WAIT_MS);
                }, null);
    }

    /**
     * Home at power-on is on but has nothing reliable to do it: Vibe Launcher isn't Home and the Home button
     * service is off. Home's own process then does it alone (PowerOnHome), which is often gone after standby, and
     * from Android 10 isn't allowed to open itself from the background.
     */
    static boolean powerOnNeedsIt(Context context) {
        return new VibeSettings(context).powerOnHome() && !DefaultHome.isHome(context)
                && !DefaultHome.homeButtonOn(context);
    }

    /** Where the power-on offer is remembered as made: shared by Home's start and Settings > General. */
    private static final String PREFS = "homeButton";
    private static final String ASKED_POWER_ON = "askedPowerOn";

    /** Whether the service has been offered for Home at power-on (from Home or from Settings). */
    static boolean askedForPowerOn(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(ASKED_POWER_ON, false);
    }

    /**
     * The Home button service offered for Home at power-on: why first, and ADB recommended where it has worked.
     * Remembered as offered, so Home doesn't offer it again after a Cancel in Settings; done as in offer.
     */
    static void offerForPowerOn(Activity activity, Runnable done) {
        activity.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().putBoolean(ASKED_POWER_ON, true).apply();
        StringBuilder lead = new StringBuilder(activity.getString(R.string.power_on_needs_service,
                activity.getString(R.string.power_on_home), activity.getString(R.string.home_key_name)));
        if (Adb.everWorked(activity)) lead.append("\n\n").append(activity.getString(R.string.home_button_adb_note));
        offer(activity, lead, done);
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
