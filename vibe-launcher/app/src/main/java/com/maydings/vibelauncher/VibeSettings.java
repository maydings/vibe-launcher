package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;

/** The launcher's own settings, entered under Settings > Vibe Launcher settings and kept on the device. */
final class VibeSettings {

    private static final String FILE = "vibe_settings";
    private static final String TMDB_KEY = "tmdbKey";
    private static final String YOUTUBE_KEY = "youtubeKey";
    private static final String PLEX_TOKEN = "plexToken";
    private static final String COLUMNS = "columns";
    private static final String LANGUAGE = "language";
    private static final String HIDE_INACTIVE_INPUTS = "hideInactiveInputs";
    private static final String AUTO_HIDE_BAR = "autoHideBar";
    private static final String SLIDESHOW_CLOCK = "slideshowClock";
    private static final String POWER_ON_HOME = "powerOnHome";
    private static final String DOCK_APPS = "dockApps";
    private static final String GLASS_TINT = "glassTint";
    private static final String CARETS = "carets";
    private static final String CLOCK_HOURS = "clockHours";
    private static final String BACK_ACTION = "backAction";

    private final SharedPreferences prefs;

    VibeSettings(Context context) {
        prefs = context.getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    /** TMDB API key (v3) or read access token, or "" when not set. */
    String tmdbKey() {
        return prefs.getString(TMDB_KEY, "");
    }

    /** YouTube Data API key, or "" when not set. */
    String youtubeKey() {
        return prefs.getString(YOUTUBE_KEY, "");
    }

    /** Plex account token (X-Plex-Token), or "" when not set. */
    String plexToken() {
        return prefs.getString(PLEX_TOKEN, "");
    }

    /** Tiles per row on Home (4, 5 or 6), or the given default when not chosen. */
    int columns(int fallback) {
        int c = prefs.getInt(COLUMNS, fallback);
        return c >= 4 && c <= 6 ? c : fallback;
    }

    void setColumns(int columns) {
        prefs.edit().putInt(COLUMNS, columns).apply();
    }

    /**
     * How many apps the dock holds, 1 up to a row's worth; a full row unless fewer were chosen (kept as 0, so
     * the dock stays full when tiles per row changes).
     */
    int dockApps(int columns) {
        int n = prefs.getInt(DOCK_APPS, 0);
        return n >= 1 && n < columns ? n : columns;
    }

    /** Fewer than a row's worth, or 0 for a full row. */
    void setDockApps(int n) {
        prefs.edit().putInt(DOCK_APPS, n).apply();
    }

    /** The chosen language's tag (see Lang), or "" to follow the TV's own. */
    String language() {
        return prefs.getString(LANGUAGE, "");
    }

    /** Saved at once (commit): Home is recreated in the new language straight after. */
    @SuppressLint("ApplySharedPref")
    void setLanguage(String tag) {
        prefs.edit().putString(LANGUAGE, tag).commit();
    }

    /** Whether the Inputs menu leaves out inputs with nothing connected. */
    boolean hideInactiveInputs() {
        return prefs.getBoolean(HIDE_INACTIVE_INPUTS, false);
    }

    void setHideInactiveInputs(boolean hide) {
        prefs.edit().putBoolean(HIDE_INACTIVE_INPUTS, hide).apply();
    }

    /**
     * Auto-hide (off by default): the top bar stays out of sight on the dock and with the picture alone, and
     * the rows stay down while the top bar has focus.
     */
    boolean autoHideBar() {
        return prefs.getBoolean(AUTO_HIDE_BAR, false);
    }

    void setAutoHideBar(boolean hide) {
        prefs.edit().putBoolean(AUTO_HIDE_BAR, hide).apply();
    }

    /** The top bar's time on the slideshow (on by default). */
    boolean slideshowClock() {
        return prefs.getBoolean(SLIDESHOW_CLOCK, true);
    }

    void setSlideshowClock(boolean show) {
        prefs.edit().putBoolean(SLIDESHOW_CLOCK, show).apply();
    }

    /** The small arrows on Home that show where the remote goes (on by default). */
    boolean carets() {
        return prefs.getBoolean(CARETS, true);
    }

    void setCarets(boolean show) {
        prefs.edit().putBoolean(CARETS, show).apply();
    }

    /** The clock in 12- or 24-hour time, or 0 for the TV's own setting (the default). */
    int clockHours() {
        return prefs.getInt(CLOCK_HOURS, 0);
    }

    void setClockHours(int hours) {
        prefs.edit().putInt(CLOCK_HOURS, hours).apply();
    }

    static final int BACK_NOTHING = 0, BACK_FIRST_APP = 1, BACK_POSTER = 2, BACK_SCREENSAVER = 3;

    /** What Back does on Home: nothing (the default), the first app, the poster view or the screensaver. */
    int backAction() {
        return prefs.getInt(BACK_ACTION, BACK_NOTHING);
    }

    void setBackAction(int action) {
        prefs.edit().putInt(BACK_ACTION, action).apply();
    }

    /** Home shortly after the screen turns on, over whatever the TV woke up on (off by default; PowerOnHome). */
    boolean powerOnHome() {
        return prefs.getBoolean(POWER_ON_HOME, false);
    }

    void setPowerOnHome(boolean on) {
        prefs.edit().putBoolean(POWER_ON_HOME, on).apply();
    }

    /** The glass highlight's colour, RGB (white by default; GlassView). */
    int glassTint() {
        return prefs.getInt(GLASS_TINT, 0xFFFFFF);
    }

    void setGlassTint(int rgb) {
        prefs.edit().putInt(GLASS_TINT, rgb & 0xFFFFFF).apply();
    }

    void save(String tmdbKey, String youtubeKey, String plexToken) {
        prefs.edit().putString(TMDB_KEY, tmdbKey.trim()).putString(YOUTUBE_KEY, youtubeKey.trim())
                .putString(PLEX_TOKEN, plexToken.trim()).apply();
    }
}
