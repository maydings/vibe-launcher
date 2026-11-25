package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * What's popular on a streaming service, for its tile when the app publishes no cards (Netflix before
 * anyone signs in, on the onn 4K stick). From Streaming Catalogs, a community add-on for Stremio
 * (github.com/rleroi/Stremio-Streaming-Catalogs-Addon) on its author's free hosting: no key, but not an
 * official service, so it may go away; then the tile keeps its glow. Its default lists cover the
 * services below; each title comes with its IMDb id, whose backdrop and logo come from metahub, as for
 * Stremio. Each list is reused for a day.
 */
final class StreamingCatalogs {

    private static final String BASE = "https://7a82163c306e-stremio-netflix-catalog-addon.baby-beamup.club/catalog/";
    private static final String IMAGES = "https://images.metahub.space/";
    private static final long REFRESH_MS = 24L * 60 * 60 * 1000;
    private static final int MAX_TITLES = 40;

    /** The add-on's list for each app (Max under its old and new names). */
    private static final Map<String, String> SERVICES = new HashMap<>();

    static {
        SERVICES.put("com.netflix.ninja", "nfx");
        SERVICES.put("com.disney.disneyplus", "dnp");
        SERVICES.put("com.amazon.amazonvideo.livingroom", "amp");
        SERVICES.put("com.apple.atve.androidtv.appletv", "atp");
        SERVICES.put("com.wbd.stream", "hbm");
        SERVICES.put("com.hbo.hbonow", "hbm");
    }

    /** A title on the list: its name, backdrop and logo (a title without a logo answers 404 and gets none). */
    static final class Title {
        final String name, backdrop, logo;

        Title(String name, String id) {
            this.name = name;
            backdrop = IMAGES + "background/large/" + id + "/img";
            logo = IMAGES + "logo/large/" + id + "/img";
        }
    }

    private final SharedPreferences cache;

    StreamingCatalogs(Context context) {
        cache = context.getSharedPreferences("streaming_catalogs", Context.MODE_PRIVATE);
    }

    static boolean covers(String pkg) {
        return SERVICES.containsKey(pkg);
    }

    /** The app's service's popular films and shows, alternating; empty for other apps, or offline with none saved. Worker thread. */
    List<Title> titles(String pkg) {
        String service = SERVICES.get(pkg);
        if (service == null) return Collections.emptyList();
        long now = System.currentTimeMillis();
        // Saved as "id<TAB>name" lines.
        String saved = cache.getString(service, "");
        long age = now - cache.getLong(service + ".time", 0);
        if (age < 0 || age >= REFRESH_MS) {
            try {
                saved = fetch(service);
                cache.edit().putString(service, saved).putLong(service + ".time", now).apply();
            } catch (IOException | JSONException | RuntimeException e) {
                // Offline or gone: the last list, if there is one.
            }
        }
        List<Title> titles = new ArrayList<>();
        for (String line : saved.split("\n")) {
            String[] parts = line.split("\t", 2);
            if (parts.length == 2) titles.add(new Title(parts[1], parts[0]));
        }
        return titles;
    }

    private static String fetch(String service) throws IOException, JSONException {
        List<List<String>> lists = new ArrayList<>();
        for (String type : new String[]{"movie", "series"}) {
            JSONArray metas = new JSONObject(Http.getText(BASE + type + "/" + service + ".json", Collections.emptyMap()))
                    .optJSONArray("metas");
            List<String> lines = new ArrayList<>();
            for (int i = 0; metas != null && i < metas.length(); i++) {
                JSONObject m = metas.getJSONObject(i);
                String id = m.optString("id");
                String name = (m.isNull("name") ? "" : m.optString("name"))
                        .replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
                if (id.matches("tt\\d{5,10}") && !name.isEmpty()
                        && !m.isNull("background") && !m.optString("background").isEmpty()) {
                    lines.add(id + "\t" + name);
                }
            }
            lists.add(lines);
        }
        // Films and shows taking turns, most popular first.
        List<String> out = new ArrayList<>();
        for (int i = 0; out.size() < MAX_TITLES && (i < lists.get(0).size() || i < lists.get(1).size()); i++) {
            for (List<String> l : lists) if (i < l.size() && out.size() < MAX_TITLES) out.add(l.get(i));
        }
        if (out.isEmpty()) throw new IOException("empty lists");   // keep the last one
        return String.join("\n", out);
    }
}
