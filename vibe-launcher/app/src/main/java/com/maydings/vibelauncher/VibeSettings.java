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
    private static final String GLASS_TINT = "glassTint";

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
