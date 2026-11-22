package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Random;

/**
 * Clean, full-resolution backdrops and title logos from TMDB for the titles apps put on their cards
 * (Netflix's own art is 853x480 with its logo and stickers baked in). Uses the key from Vibe Launcher
 * settings. Only exact title matches are used, and answers are remembered for a month so each title
 * costs two API calls at most. A title's details (Settings > Backdrops: its year, score, synopsis and genres,
 * shown over the backdrop) are apart from its pictures: asked for when the title is first shown, two calls,
 * and kept as long.
 */
final class Tmdb implements TitleArt {

    private static final String API = "https://api.themoviedb.org/3";
    private static final String BACKDROPS = "https://image.tmdb.org/t/p/original";
    private static final String LOGOS = "https://image.tmdb.org/t/p/w500";
    private static final int CHOICES = 4;   // best few textless backdrops per title, picked at random
    private static final long FOUND_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long MISSING_MS = 3L * 24 * 60 * 60 * 1000;   // retry unmatched titles sooner

    private final VibeSettings settings;
    private final SharedPreferences cache;
    private final Random random = new Random();

    Tmdb(Context context) {
        settings = new VibeSettings(context);
        cache = context.getSharedPreferences("tmdb_cache", Context.MODE_PRIVATE);
    }

    boolean enabled() {
        return !settings.tmdbKey().isEmpty();
    }

    @Override
    public Art lookup(String title) {
        String key = TitleArt.normalize(title);
        if (key.isEmpty() || !enabled()) return null;
        // Remembered as "time|backdrop paths|logo path"; entries from before logos are looked up again.
        String[] cached = cache.getString(key, "").split("\\|", -1);
        long now = System.currentTimeMillis();
        if (cached.length == 3 && !cached[1].equals("null")) {   // "null": kept so once, no picture's address
            long age = now - Long.parseLong(cached[0]);
            if (age < (cached[1].isEmpty() ? MISSING_MS : FOUND_MS)) return art(cached[1], cached[2]);
        }
        String[] found;
        try {
            found = find(title, key);
        } catch (IOException | JSONException | RuntimeException e) {
            return null;   // offline or a bad key: don't remember, try again next time
        }
        cache.edit().putString(key, now + "|" + found[0] + "|" + found[1]).apply();
        return art(found[0], found[1]);
    }

    private Art art(String paths, String logo) {
        if (paths.isEmpty()) return null;
        String[] list = paths.split(",");
        return new Art(BACKDROPS + list[random.nextInt(list.length)], logo.isEmpty() ? null : LOGOS + logo);
    }

    /** {comma-separated backdrop paths, logo path} for the best exact match; "" for none. */
    private String[] find(String title, String key) throws IOException, JSONException {
        JSONObject best = best(title, key, true);
        if (best == null) return new String[]{"", ""};
        // Which one its pictures are of: its details are that one's too (fetchInfo), not asked for by name again.
        cache.edit().putString(MATCHED + key, best.getString("media_type") + "/" + best.getLong("id")).apply();

        String lang = Locale.getDefault().getLanguage();
        JSONObject images = get("/" + best.getString("media_type") + "/" + best.getLong("id")
                + "/images?include_image_language=null,en," + lang);
        // Backdrops tagged with no language have no text on them: no title, no logo.
        List<String> paths = new ArrayList<>();
        JSONArray backdrops = images.optJSONArray("backdrops");
        for (int i = 0; backdrops != null && i < backdrops.length() && paths.size() < CHOICES; i++) {
            JSONObject b = backdrops.getJSONObject(i);   // TMDB lists them best-rated first
            if (b.optInt("width") >= 1280 && b.optString("iso_639_1", "null").equals("null")) {
                paths.add(b.getString("file_path"));
            }
        }
        if (paths.isEmpty()) paths.add(best.getString("backdrop_path"));
        return new String[]{String.join(",", paths), logo(images.optJSONArray("logos"), lang)};
    }

    /** The most popular film or show by exactly this name (with a backdrop, when one is needed), or null. */
    private JSONObject best(String title, String key, boolean pictured) throws IOException, JSONException {
        JSONArray results = get("/search/multi?include_adult=false&query=" + Uri.encode(title))
                .optJSONArray("results");
        JSONObject best = null;
        for (int i = 0; results != null && i < results.length(); i++) {
            JSONObject r = results.getJSONObject(i);
            String type = r.optString("media_type");
            if (!type.equals("movie") && !type.equals("tv")) continue;
            boolean same = key.equals(TitleArt.normalize(r.optString(type.equals("tv") ? "name" : "title")))
                    || key.equals(TitleArt.normalize(r.optString(type.equals("tv") ? "original_name" : "original_title")));
            if (!same || (pictured && text(r, "backdrop_path").isEmpty())) continue;
            if (best == null || r.optDouble("popularity") > best.optDouble("popularity")) best = r;
        }
        return best;
    }

    /** A film or show's details, as shown with its backdrop: what there is of them. */
    static final class Info {
        final String year, overview;
        final List<String> genres = new ArrayList<>();
        final double score;   // TMDB's users' (0 to 10), or 0 when too few have voted

        Info(String year, String overview, double score) {
            this.year = year;
            this.overview = overview;
            this.score = score;
        }
    }

    private static final String INFO = "i|";

    /**
     * The details kept for this title, or null: none kept, or nothing in them to show. Reads what's kept
     * only, so it can be asked on the main thread.
     */
    Info info(String title) {
        JSONObject o = kept(title);
        if (o == null) return null;
        // "null": kept so before what TMDB hadn't got was read as nothing.
        String year = o.optString("y"), overview = o.optString("o");
        Info info = new Info(year.equals("null") ? "" : year, overview.equals("null") ? "" : overview,
                o.optDouble("s", 0));
        JSONArray g = o.optJSONArray("g");
        for (int i = 0; g != null && i < g.length(); i++) info.genres.add(g.optString(i));
        boolean empty = info.overview.isEmpty() && info.genres.isEmpty() && info.score <= 0;
        return empty ? null : info;
    }

    /** Whether this title's details are still to ask for: none kept, kept too long ago, or in another language. */
    boolean infoWanted(String title) {
        if (!enabled() || TitleArt.normalize(title).isEmpty()) return false;
        JSONObject o = kept(title);
        if (o == null || !language().equals(o.optString("l"))) return true;
        return System.currentTimeMillis() - o.optLong("t") >= (o.has("o") ? FOUND_MS : MISSING_MS);
    }

    private JSONObject kept(String title) {
        String kept = cache.getString(INFO + TitleArt.normalize(title), null);
        if (kept == null) return null;
        try {
            return new JSONObject(kept);
        } catch (JSONException e) {
            return null;
        }
    }

    private static String language() {
        return Locale.getDefault().toLanguageTag();
    }

    private static final String KNOWN = "k|", MATCHED = "m|";

    /**
     * This title is known to be this film (or show) on TMDB: said by the app whose card it is (Plex's server
     * lists each title's id). Its details are then asked for by the id, not by its name; a title newly known
     * is asked for again, whatever was kept for its name.
     */
    /** Whether this title has been said to be a film or show on TMDB (known). */
    static boolean isKnown(Context context, String title) {
        String key = TitleArt.normalize(title);
        return !key.isEmpty()
                && context.getSharedPreferences("tmdb_cache", Context.MODE_PRIVATE).contains(KNOWN + key);
    }

    static void known(Context context, String title, boolean tv, long id) {
        String key = TitleArt.normalize(title), which = (tv ? "tv/" : "movie/") + id;
        if (key.isEmpty()) return;
        SharedPreferences cache = context.getSharedPreferences("tmdb_cache", Context.MODE_PRIVATE);
        if (which.equals(cache.getString(KNOWN + key, null))) return;
        cache.edit().putString(KNOWN + key, which).remove(INFO + key).apply();
    }

    /**
     * Asks for this title's details and keeps them (that there are none, too): its year, score, synopsis and
     * genres, in the TV's language; the synopsis in English where there's none in that language. The title
     * is the one an app said it is (known), else the one its backdrop here is of, else the most popular by
     * exactly this name. False when it
     * couldn't ask (offline, a bad key). Worker thread only.
     */
    boolean fetchInfo(String title) {
        String key = TitleArt.normalize(title);
        if (key.isEmpty() || !enabled()) return false;
        try {
            JSONObject o = new JSONObject();
            o.put("t", System.currentTimeMillis());
            o.put("l", language());
            String which = cache.getString(KNOWN + key, cache.getString(MATCHED + key, null));
            JSONObject best = null;
            if (which == null) {
                best = best(title, key, false);
                if (best != null) which = best.optString("media_type") + "/" + best.getLong("id");
            }
            if (which != null) {
                boolean tv = which.startsWith("tv/");
                JSONObject details = get("/" + which + "?language=" + Uri.encode(language()));
                String date = text(details, tv ? "first_air_date" : "release_date");
                String overview = text(details, "overview").trim();
                if (overview.isEmpty()) overview = text(best != null ? best : get("/" + which), "overview").trim();
                JSONArray names = new JSONArray(), genres = details.optJSONArray("genres");
                for (int i = 0; genres != null && i < genres.length() && names.length() < 3; i++) {
                    String name = text(genres.getJSONObject(i), "name");
                    if (!name.isEmpty()) names.put(name);
                }
                o.put("y", date.length() >= 4 ? date.substring(0, 4) : "");
                o.put("o", overview);
                o.put("s", details.optInt("vote_count") >= 20 ? details.optDouble("vote_average", 0) : 0);
                o.put("g", names);
            }
            cache.edit().putString(INFO + key, o.toString()).apply();
            return true;
        } catch (IOException | JSONException | RuntimeException e) {
            return false;   // asked again when it's next shown
        }
    }

    /** A value as text, "" where there's none: TMDB says null for what it hasn't got, which reads as "null". */
    static String text(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key);
    }

    /** The best-rated PNG logo in the TV's language, else English, else language-free; "" if none. */
    static String logo(JSONArray logos, String lang) throws JSONException {
        for (String want : new String[]{lang, "en", "null"}) {
            for (int i = 0; logos != null && i < logos.length(); i++) {
                JSONObject l = logos.getJSONObject(i);
                if (l.optString("iso_639_1", "null").equals(want) && l.optString("file_path").endsWith(".png")) {
                    return l.getString("file_path");
                }
            }
        }
        return "";
    }

    private JSONObject get(String path) throws IOException, JSONException {
        String key = settings.tmdbKey();
        // A v4 read access token (a long JWT) goes in a header; a v3 API key goes in the URL.
        boolean token = key.startsWith("eyJ");
        String url = API + path + (token ? "" : (path.contains("?") ? "&" : "?") + "api_key=" + Uri.encode(key));
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        if (token) headers.put("Authorization", "Bearer " + key);
        return new JSONObject(Http.getText(url, headers));
    }
}
