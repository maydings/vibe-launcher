package com.maydings.vibelauncher;

import android.content.Context;
import android.hardware.display.DisplayManager;
import android.os.Build;
import android.view.Display;
import android.view.View;
import android.view.ViewGroup;
import android.widget.TextClock;

/**
 * Keeps the TextClocks under a view ticking on their own (from upstream cleanyourtv's launcher). Before Android 12
 * a TextClock without seconds only moves on ACTION_TIME_TICK, and some firmware stops sending it for good after a
 * sleep (a TCL Android 9 TV, once the power button has been used), which froze every clock until a reboot: the top
 * bar's and the slideshow's here. From Android 12 a TextClock times itself, and this does nothing.
 * <p>
 * Otherwise it redraws them at each minute change of the wall clock, and as soon as the display comes back on: the
 * wait to the next minute runs on uptime, which stands still while the TV sleeps. A display listener rather than
 * ACTION_SCREEN_ON, as that same firmware leaves the app on screen out of that broadcast. Each wait is worked out
 * again from the wall clock, so a clock set meanwhile lines up again within the minute.
 */
final class ClockRefresh implements Runnable, DisplayManager.DisplayListener {

    /** Past the minute change, so the redraw can't land just before it and paint the old minute again. */
    private static final long MARGIN_MS = 250L;

    private final View root;
    private final DisplayManager displays;
    /** The display's last state seen; it's on when start() runs. */
    private boolean on = true;

    /** root holds the TextClocks. */
    ClockRefresh(View root) {
        this.root = root;
        displays = (DisplayManager) root.getContext().getSystemService(Context.DISPLAY_SERVICE);
    }

    /** Redraws at the next minute change, then every minute and when the display comes on (Home on screen). */
    void start() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) return;
        on = true;
        displays.registerDisplayListener(this, null);   // twice is the same as once
        schedule();
    }

    /** Safe when never started. */
    void stop() {
        root.removeCallbacks(this);
        displays.unregisterDisplayListener(this);
    }

    @Override
    public void run() {
        redraw(root);
        schedule();
    }

    /** Redraws when our display goes from off to on, not on its other changes. */
    @Override
    public void onDisplayChanged(int displayId) {
        Display d = root.getDisplay();
        if (d == null || d.getDisplayId() != displayId) return;
        boolean nowOn = d.getState() == Display.STATE_ON;
        if (nowOn && !on) run();
        on = nowOn;
    }

    @Override
    public void onDisplayAdded(int displayId) {
    }

    @Override
    public void onDisplayRemoved(int displayId) {
    }

    private void schedule() {
        root.removeCallbacks(this);
        root.postDelayed(this, 60_000L - System.currentTimeMillis() % 60_000L + MARGIN_MS);
    }

    /**
     * Setting a clock's 12-hour pattern again picks its format afresh and calls its onTimeChanged (AOSP 8, 9 and
     * 11), so the same pattern changes nothing but the time shown.
     */
    private static void redraw(View v) {
        if (v instanceof TextClock) {
            TextClock c = (TextClock) v;
            c.setFormat12Hour(c.getFormat12Hour());
        } else if (v instanceof ViewGroup) {
            ViewGroup g = (ViewGroup) v;
            for (int i = 0; i < g.getChildCount(); i++) redraw(g.getChildAt(i));
        }
    }
}
