package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.Collections;

/**
 * Backdrops and title logos without a key, from the metadata Stremio's own apps use (Cinemeta for the
 * title search, metahub for the images). The fallback when no TMDB key is set: not an official public
 * API, so a failure just means the card's own art is used. Matches are remembered for a month.
 */
final class Stremio implements TitleArt {

    private static final String SEARCH = "https://v3-cinemeta.strem.io/catalog/%s/top/search=%s.json";
    private static final String IMAGES = "https://images.metahub.space/";
    private static final long FOUND_MS = 30L * 24 * 60 * 60 * 1000;
    private static final long MISSING_MS = 3L * 24 * 60 * 60 * 1000;

    private final SharedPreferences cache;

    Stremio(Context context) {
        cache = context.getSharedPreferences("stremio_cache", Context.MODE_PRIVATE);
    }

    /**
     * Art for a title known by its IMDb id (an app put it on its card: Nuvio does): metahub's backdrop and logo,
     * no search, no key. A title metahub has no backdrop for answers 404 when fetched, and the next source is tried.
     */
    static Art byImdb(String id) {
        return id == null || !id.matches("tt\\d{5,10}") ? null
                : new Art(IMAGES + "background/large/" + id + "/img", IMAGES + "logo/large/" + id + "/img");
    }

    @Override
    public Art lookup(String title) {
        String key = TitleArt.normalize(title);
        if (key.isEmpty()) return null;
        // Remembered as "time|IMDb id" ("" when there was no match).
        String[] cached = cache.getString(key, "").split("\\|", -1);
        long now = System.currentTimeMillis();
        String id;
        if (cached.length == 2 && now - Long.parseLong(cached[0]) < (cached[1].isEmpty() ? MISSING_MS : FOUND_MS)) {
            id = cached[1];
        } else {
            try {
                id = find(title, key);
            } catch (IOException | JSONException | RuntimeException e) {
                return null;   // offline or the service changed: try again next time
            }
            cache.edit().putString(key, now + "|" + id).apply();
        }
        // The logo address is the same for every title; a title without one answers 404 and gets none.
        return id.isEmpty() ? null : new Art(IMAGES + "background/large/" + id + "/img",
                IMAGES + "logo/large/" + id + "/img");
    }

    /** IMDb id of the most popular exact match among films, then shows; "" for none. */
    private static String find(String title, String key) throws IOException, JSONException {
        for (String type : new String[]{"movie", "series"}) {
            String url = String.format(SEARCH, type, Uri.encode(title));
            JSONArray metas = new JSONObject(Http.getText(url, Collections.emptyMap())).optJSONArray("metas");
            for (int i = 0; metas != null && i < metas.length(); i++) {   // listed most popular first
                JSONObject m = metas.getJSONObject(i);
                String id = m.optString("id");
                if (id.startsWith("tt") && key.equals(TitleArt.normalize(m.optString("name")))
                        && !m.optString("background").isEmpty()) {
                    return id;
                }
            }
        }
        return "";
    }
}
