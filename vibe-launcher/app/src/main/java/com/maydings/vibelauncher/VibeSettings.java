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
    private static final String PEXELS_KEY = "pexelsKey";
    private static final String TOP_BAR_MINIMAL = "topBarMinimal", SHOW_CLOCK = "showClock", SHOW_DATE = "showDate";
    private static final String WALLPAPER_MODE = "wallpaperMode";
    /** Custom wallpapers (Backdrops > Custom wallpapers): not shown, shown where an app has no picture, or always. */
    static final int WALLPAPER_OFF = 0, WALLPAPER_NO_PICTURE = 1, WALLPAPER_ALWAYS = 2;
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
    private static final String JPEG_STILLS = "jpegStills";
    private static final String APPS_OFF = "appsOff";
    private static final String ADB_OFF_WHEN_DONE = "adbOffWhenDone";
    private static final String IGNORE_CHANNEL_KEYS = "ignoreChannelKeys";
    private static final String ART_ORDER = "artOrder";
    private static final String WEATHER_ON = "weatherOn", WEATHER_STYLE = "weatherStyle", WEATHER_UNITS = "weatherUnits";
    private static final String WEATHER_CITY = "weatherCity", WEATHER_LAT = "weatherLat", WEATHER_LON = "weatherLon";
    /** What the weather line shows: temperature and sky, plus today's high and low, or the temperature only. */
    static final int WEATHER_SKY = 0, WEATHER_HIGH_LOW = 1, WEATHER_TEMP = 2;
    /** Degrees: by the TV's region, Celsius, Fahrenheit. */
    static final int UNITS_AUTO = 0, UNITS_C = 1, UNITS_F = 2;
    private static final String ART_OFF = "artOff";

    /**
     * Where a film or show card's backdrop and logo come from, tried in the person's order (Backdrops): TMDB by
     * title (with a key), the title's IMDb id on the card (Nuvio's) straight to metahub, Stremio's free data by
     * title, and the card's own picture.
     */
    static final String ART_TMDB = "tmdb", ART_ID = "id", ART_STREMIO = "stremio", ART_OWN = "own";
    static final java.util.List<String> ART_DEFAULT = java.util.Arrays.asList(ART_TMDB, ART_ID, ART_STREMIO, ART_OWN);

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

    /** The top bar as small icons, the time and the weather over the background, with no bar (off by default). */
    boolean topBarMinimal() {
        return prefs.getBoolean(TOP_BAR_MINIMAL, false);
    }

    void setTopBarMinimal(boolean on) {
        prefs.edit().putBoolean(TOP_BAR_MINIMAL, on).apply();
    }

    /** The time in the top bar (on by default). */
    boolean showClock() {
        return prefs.getBoolean(SHOW_CLOCK, true);
    }

    void setShowClock(boolean on) {
        prefs.edit().putBoolean(SHOW_CLOCK, on).apply();
    }

    /** The date in the full top bar (on by default; the minimal one never has it). */
    boolean showDate() {
        return prefs.getBoolean(SHOW_DATE, true);
    }

    void setShowDate(boolean on) {
        prefs.edit().putBoolean(SHOW_DATE, on).apply();
    }

    /** Pexels API key (videos to pick as wallpapers), or "" when not set. */
    String pexelsKey() {
        return prefs.getString(PEXELS_KEY, "");
    }

    /** When custom wallpapers show: WALLPAPER_OFF (the default), WALLPAPER_NO_PICTURE or WALLPAPER_ALWAYS. */
    int wallpaperMode() {
        int m = prefs.getInt(WALLPAPER_MODE, WALLPAPER_OFF);
        return m >= WALLPAPER_OFF && m <= WALLPAPER_ALWAYS ? m : WALLPAPER_OFF;
    }

    void setWallpaperMode(int mode) {
        prefs.edit().putInt(WALLPAPER_MODE, mode).apply();
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

    /**
     * Backdrops saved as JPEG (smaller, quicker to open) instead of lossless PNG, the default. Read by the
     * pool thread as it saves each picture.
     */
    boolean jpegStills() {
        return prefs.getBoolean(JPEG_STILLS, false);
    }

    void setJpegStills(boolean jpeg) {
        prefs.edit().putBoolean(JPEG_STILLS, jpeg).apply();
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

    /** The apps turned off in Settings > Apps (TvApps), by package: what Turn all back on turns on. */
    java.util.Set<String> appsOff() {
        return prefs.getStringSet(APPS_OFF, java.util.Collections.emptySet());
    }

    void setAppsOff(java.util.Set<String> pkgs) {
        prefs.edit().putStringSet(APPS_OFF, new java.util.HashSet<>(pkgs)).apply();
    }

    /**
     * Debugging turned off when Settings closes, once Settings > Advanced has used it (TvControl). On by default, so
     * the TV's ADB is only open while it's being used; off for people who also reach the TV from a computer.
     */
    boolean adbOffWhenDone() {
        return prefs.getBoolean(ADB_OFF_WHEN_DONE, true);
    }

    void setAdbOffWhenDone(boolean off) {
        prefs.edit().putBoolean(ADB_OFF_WHEN_DONE, off).apply();
    }

    /** Channel Up and Down do nothing on Home, instead of opening the TV's own TV app (off by default). */
    boolean ignoreChannelKeys() {
        return prefs.getBoolean(IGNORE_CHANNEL_KEYS, false);
    }

    void setIgnoreChannelKeys(boolean on) {
        prefs.edit().putBoolean(IGNORE_CHANNEL_KEYS, on).apply();
    }

    /** The weather under the date on Home (on by default). */
    boolean weatherOn() {
        return prefs.getBoolean(WEATHER_ON, true);
    }

    void setWeatherOn(boolean on) {
        prefs.edit().putBoolean(WEATHER_ON, on).apply();
    }

    int weatherStyle() {
        return prefs.getInt(WEATHER_STYLE, WEATHER_SKY);
    }

    void setWeatherStyle(int style) {
        prefs.edit().putInt(WEATHER_STYLE, style).apply();
    }

    int weatherUnits() {
        return prefs.getInt(WEATHER_UNITS, UNITS_AUTO);
    }

    void setWeatherUnits(int units) {
        prefs.edit().putInt(WEATHER_UNITS, units).apply();
    }

    /** Fahrenheit, as picked or (Automatic) where the TV is set to a country that uses it. */
    boolean weatherFahrenheit() {
        int u = weatherUnits();
        return u == UNITS_F || (u == UNITS_AUTO && Weather.fahrenheitHere());
    }

    /** The city picked for the weather, or null for Automatic (the TV's own, from its internet address). */
    Weather.Place weatherPlace() {
        String name = prefs.getString(WEATHER_CITY, "");
        if (name.isEmpty()) return null;
        return new Weather.Place(name, prefs.getFloat(WEATHER_LAT, 0), prefs.getFloat(WEATHER_LON, 0));
    }

    /** A city for the weather, or null for Automatic. */
    void setWeatherPlace(Weather.Place place) {
        SharedPreferences.Editor e = prefs.edit();
        if (place == null) e.remove(WEATHER_CITY).remove(WEATHER_LAT).remove(WEATHER_LON);
        else e.putString(WEATHER_CITY, place.name).putFloat(WEATHER_LAT, (float) place.lat)
                .putFloat(WEATHER_LON, (float) place.lon);
        e.apply();
    }

    /** The artwork sources in the person's order, all of them (on or off); any unknown left out, any new added. */
    java.util.List<String> artOrder() {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (String s : prefs.getString(ART_ORDER, "").split(",")) {
            if (ART_DEFAULT.contains(s) && !out.contains(s)) out.add(s);
        }
        for (String s : ART_DEFAULT) if (!out.contains(s)) out.add(s);
        return out;
    }

    void setArtOrder(java.util.List<String> order) {
        prefs.edit().putString(ART_ORDER, android.text.TextUtils.join(",", order)).apply();
    }

    boolean artOn(String source) {
        return !prefs.getStringSet(ART_OFF, java.util.Collections.emptySet()).contains(source);
    }

    void setArtOn(String source, boolean on) {
        java.util.Set<String> off = new java.util.HashSet<>(prefs.getStringSet(ART_OFF, java.util.Collections.emptySet()));
        if (on) off.remove(source);
        else off.add(source);
        prefs.edit().putStringSet(ART_OFF, off).apply();
    }

    void save(String tmdbKey, String youtubeKey, String plexToken, String pexelsKey) {
        prefs.edit().putString(TMDB_KEY, tmdbKey.trim()).putString(YOUTUBE_KEY, youtubeKey.trim())
                .putString(PLEX_TOKEN, plexToken.trim()).putString(PEXELS_KEY, pexelsKey.trim()).apply();
    }
}
