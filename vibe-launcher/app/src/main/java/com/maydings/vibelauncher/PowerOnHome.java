package com.maydings.vibelauncher;

import android.content.ActivityNotFoundException;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Handler;
import android.os.Looper;

/**
 * Settings > General > Home at power-on (off by default): for TVs that wake up on the input or app they
 * were left on (the Sonys go back to the last HDMI input), Home comes up shortly after the screen turns on.
 * The Home button service does it while it's on, and skips it if a key was pressed first;
 * otherwise Home's own process does, which can't see keys in other apps.
 */
final class PowerOnHome {

    /** On the Home intent: MainActivity stays as it was if it's already in front. */
    static final String EXTRA = "com.maydings.vibelauncher.POWER_ON";
    /** Long enough for the TV to put back what it was showing, which Home then covers. */
    static final long DELAY_MS = 800;

    private static boolean registered;

    private PowerOnHome() {
    }

    /** Listens for the screen turning on, once per process (MainActivity.onCreate). */
    static void register(Context context) {
        if (registered) return;
        registered = true;
        Context app = context.getApplicationContext();
        Handler handler = new Handler(Looper.getMainLooper());
        app.registerReceiver(new BroadcastReceiver() {
            @Override
            public void onReceive(Context c, Intent i) {
                // The Home button service does it itself, with its key check.
                if (HomeButtonLink.bound() || !new VibeSettings(app).powerOnHome()) return;
                handler.postDelayed(() -> open(app), DELAY_MS);
            }
        }, new IntentFilter(Intent.ACTION_SCREEN_ON));
    }

    static Intent intent(Context context) {
        return new Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME).setPackage(context.getPackageName())
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK | Intent.FLAG_ACTIVITY_RESET_TASK_IF_NEEDED)
                .putExtra(EXTRA, true);
    }

    static void open(Context context) {
        try {
            context.startActivity(intent(context));
        } catch (ActivityNotFoundException | SecurityException ignored) {
            // Android kept a background start from opening it: the TV stays where it woke up.
        }
    }
}
