package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * Popular YouTube videos, for the YouTube backdrop when SmartTube (the user's own feed) has no cards.
 * With the YouTube Data API key from Vibe Launcher settings, the most popular in the TV's country (one
 * request costs 1 unit of the free 10,000 a day). Without one, the trailers of popular films from
 * Stremio's free catalog (Cinemeta), which lists each film's trailers on YouTube. Either list is reused
 * for a few hours.
 */
final class YouTubePopular {

    private static final long REFRESH_MS = 4L * 60 * 60 * 1000;
    private static final String TOP_FILMS = "https://v3-cinemeta.strem.io/catalog/movie/top.json";
    private static final int MAX_TRAILERS = 30;

    private final VibeSettings settings;
    private final SharedPreferences cache;

    YouTubePopular(Context context) {
        settings = new VibeSettings(context);
        cache = context.getSharedPreferences("youtube_popular", Context.MODE_PRIVATE);
    }

    /** Video IDs, most popular first; empty when offline with nothing saved. Network: worker thread only. */
    List<String> videoIds() {
        String key = settings.youtubeKey();
        // The trailers are the same everywhere; "" stands for their list in the saved region.
        String region = key.isEmpty() ? "" : region();
        long now = System.currentTimeMillis();
        String ids = cache.getString("ids", "");
        long age = now - cache.getLong("time", 0);
        boolean fresh = age >= 0 && age < REFRESH_MS
                && region.equals(cache.getString("region", "")) && key.equals(cache.getString("key", ""));
        if (!fresh) {
            try {
                ids = key.isEmpty() ? trailers() : fetchWithFallback(key, region);
                cache.edit().putString("ids", ids).putLong("time", now).putString("region", region)
                        .putString("key", key).apply();
            } catch (IOException | JSONException | RuntimeException e) {
                // Offline or a bad key: use the last list if there is one.
            }
        }
        return ids.isEmpty() ? Collections.emptyList() : new ArrayList<>(Arrays.asList(ids.split(",")));
    }

    private static String fetchWithFallback(String key, String region) throws IOException, JSONException {
        try {
            return fetch(key, region);
        } catch (IOException | JSONException e) {
            if ("US".equals(region)) throw e;
            return fetch(key, "US");
        }
    }

    private static String fetch(String key, String region) throws IOException, JSONException {
        String url = "https://www.googleapis.com/youtube/v3/videos?part=id&chart=mostPopular&maxResults=30"
                + "&regionCode=" + region + "&key=" + Uri.encode(key);
        JSONArray items = new JSONObject(Http.getText(url, Collections.emptyMap())).optJSONArray("items");
        List<String> ids = new ArrayList<>();
        for (int i = 0; items != null && i < items.length(); i++) {
            String id = items.getJSONObject(i).optString("id");
            if (id.matches("[A-Za-z0-9_-]{11}")) ids.add(id);
        }
        if (ids.isEmpty()) throw new IOException("empty popular list");
        return String.join(",", ids);
    }

    /** A trailer of each popular film (its first; often there are a few), most popular first. */
    private static String trailers() throws IOException, JSONException {
        JSONArray metas = new JSONObject(Http.getText(TOP_FILMS, Collections.emptyMap())).optJSONArray("metas");
        List<String> ids = new ArrayList<>();
        for (int i = 0; metas != null && i < metas.length() && ids.size() < MAX_TRAILERS; i++) {
            JSONArray trailers = metas.getJSONObject(i).optJSONArray("trailerStreams");
            String id = trailers == null || trailers.length() == 0 ? "" : trailers.getJSONObject(0).optString("ytId");
            if (id.matches("[A-Za-z0-9_-]{11}")) ids.add(id);
        }
        if (ids.isEmpty()) throw new IOException("no trailers");   // keep the last list
        return String.join(",", ids);
    }

    /** The TV's country (e.g. "US"), which YouTube's chart is per. */
    private static String region() {
        String country = Lang.country();
        return country.length() == 2 ? country.toUpperCase(Locale.ROOT) : "US";
    }
}
