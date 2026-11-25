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
    private static final int MAX_CACHE_ENTRIES = 600;

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
        String id = null;
        if (cached.length == 2) {
            try {
                long age = now - Long.parseLong(cached[0]);
                if (age >= 0 && age < (cached[1].isEmpty() ? MISSING_MS : FOUND_MS)) id = cached[1];
            } catch (NumberFormatException ignored) {
            }
        }
        if (id == null) {
            try {
                id = find(title, key);
            } catch (IOException | JSONException | RuntimeException e) {
                return null;   // offline or the service changed: try again next time
            }
            cache.edit().putString(key, now + "|" + id).apply();
            pruneIfNeeded(now);
        }
        // The logo address is the same for every title; a title without one answers 404 and gets none.
        return id.isEmpty() || !id.matches("tt\\d{5,10}") ? null : new Art(IMAGES + "background/large/" + id + "/img",
                IMAGES + "logo/large/" + id + "/img");
    }

    private void pruneIfNeeded(long now) {
        java.util.Map<String, ?> all = cache.getAll();
        if (all.size() <= MAX_CACHE_ENTRIES) return;
        SharedPreferences.Editor e = cache.edit();
        int removed = 0;
        for (java.util.Map.Entry<String, ?> entry : all.entrySet()) {
            if (!(entry.getValue() instanceof String)) continue;
            String[] parts = ((String) entry.getValue()).split("\\|", -1);
            if (parts.length != 2) {
                e.remove(entry.getKey());
                removed++;
                continue;
            }
            try {
                long age = now - Long.parseLong(parts[0]);
                if (age < 0 || age >= (parts[1].isEmpty() ? MISSING_MS : FOUND_MS)) {
                    e.remove(entry.getKey());
                    removed++;
                }
            } catch (NumberFormatException ignored) {
                e.remove(entry.getKey());
                removed++;
            }
        }
        if (all.size() - removed > MAX_CACHE_ENTRIES) {
            int drop = (all.size() - removed) - (MAX_CACHE_ENTRIES * 3 / 4);
            for (String k : all.keySet()) {
                if (drop <= 0) break;
                e.remove(k);
                drop--;
            }
        }
        e.apply();
    }

    /** IMDb id of the most popular exact match among films, then shows; "" for none. */
    private static String find(String title, String key) throws IOException, JSONException {
        for (String type : new String[]{"movie", "series"}) {
            String url = String.format(SEARCH, type, Uri.encode(title));
            JSONArray metas = new JSONObject(Http.getText(url, Collections.emptyMap())).optJSONArray("metas");
            for (int i = 0; metas != null && i < metas.length(); i++) {   // listed most popular first
                JSONObject m = metas.getJSONObject(i);
                String id = m.optString("id");
                if (id.matches("tt\\d{5,10}") && key.equals(TitleArt.normalize(m.optString("name")))
                        && !m.isNull("background") && !m.optString("background").isEmpty()) {
                    return id;
                }
            }
        }
        return "";
    }
}
