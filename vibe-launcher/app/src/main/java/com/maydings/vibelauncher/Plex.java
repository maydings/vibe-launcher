package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * Backdrops for the Plex tile from the user's own server: the fan art of what's on Continue Watching
 * (On Deck, with where you stopped) and recently added, with each title's name, logo and a link the Plex
 * app opens it with. The new Plex app publishes no home screen cards. Uses the Plex token from Vibe
 * Launcher settings to find the server through plex.tv (its secure local address first), and has the
 * server scale the art (stored at up to 4K) to the screen.
 */
final class Plex {

    private static final long SERVER_MS = 24L * 60 * 60 * 1000;
    private static final long ART_MS = 30L * 60 * 1000;
    private static final long ON_DECK_MS = 60L * 1000;   // where you stopped: asked again a minute later

    /** A title on the server: its art and logo (URLs, token included), name, Plex app link, and progress. */
    static final class Item {
        final String art, logo, title, link;
        final boolean onDeck;
        final long viewedMs, durationMs, offsetMs;

        Item(String art, String logo, String title, String link, boolean onDeck, long viewedMs, long durationMs,
             long offsetMs) {
            this.art = art;
            this.logo = logo;
            this.title = title;
            this.link = link;
            this.onDeck = onDeck;
            this.viewedMs = viewedMs;
            this.durationMs = durationMs;
            this.offsetMs = offsetMs;
        }
    }

    private final VibeSettings settings;
    private final SharedPreferences cache;
    private final int width, height;
    private List<Item> art = Collections.emptyList();
    private long artTime;
    private String artToken = "";
    /** What's On Deck, as last fetched (by items() or refreshOnDeck): read without waiting. */
    private volatile List<Item> onDeck = Collections.emptyList();
    private volatile long onDeckTime;
    private final Object deckLock = new Object();
    /** Asks the server for On Deck away from the threads that show and build pictures. */
    private final ExecutorService refresher = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);   // see Backdrops.builder
        r.run();
    }, "PlexOnDeck"));

    Plex(Context context, int width, int height) {
        settings = new VibeSettings(context);
        cache = context.getSharedPreferences("plex", Context.MODE_PRIVATE);
        this.width = width;
        this.height = height;
        if (cache.getString("clientId", null) == null) {
            cache.edit().putString("clientId", UUID.randomUUID().toString()).apply();
        }
    }

    /**
     * The titles for backdrops: On Deck first (the last watched first), then recently added; empty without
     * a token or server. Worker thread.
     */
    synchronized List<Item> items() {
        String token = settings.plexToken();
        if (token.isEmpty()) return Collections.emptyList();
        long now = System.currentTimeMillis();
        if (token.equals(artToken) && now - artTime < ART_MS && !art.isEmpty()) return art;
        for (int attempt = 0; attempt < 2; attempt++) {
            String[] server = server(token, attempt > 0);
            if (server == null) break;
            try {
                List<Item> found = fetch(server[0], server[1], "/library/onDeck", true);
                onDeck = new ArrayList<>(found);
                onDeckTime = now;
                found.addAll(fetch(server[0], server[1], "/library/recentlyAdded", false));
                art = found;
                artTime = now;
                artToken = token;
                return art;
            } catch (IOException | JSONException | RuntimeException e) {
                // The cached address may be stale (new IP, server moved): look it up again once.
            }
        }
        return art;   // the last good list, if any
    }

    /** What's On Deck as last fetched, at once: the screen never waits on the server. */
    List<Item> onDeck() {
        return settings.plexToken().isEmpty() ? Collections.emptyList() : onDeck;
    }

    /**
     * Asks the server for On Deck again when the list is over a minute old, in the background; done runs (on
     * that thread) when a new list is in. Nothing happens without a token, or when it's fresh.
     */
    void refreshOnDeck(Runnable done) {
        String token = settings.plexToken();
        if (token.isEmpty() || System.currentTimeMillis() - onDeckTime < ON_DECK_MS) return;
        refresher.execute(() -> {
            synchronized (deckLock) {
                long now = System.currentTimeMillis();
                if (now - onDeckTime < ON_DECK_MS) return;   // another request just did it
                String[] server = server(token, false);
                if (server == null) return;
                try {
                    onDeck = fetch(server[0], server[1], "/library/onDeck", true);
                    onDeckTime = now;
                } catch (IOException | JSONException | RuntimeException e) {
                    onDeckTime = now;   // offline or moved: the last list for another minute; items() looks again
                    return;
                }
            }
            done.run();
        });
    }

    /** {base URI, server access token} for the first owned (else any) online server, cached for a day. */
    private String[] server(String token, boolean refresh) {
        long now = System.currentTimeMillis();
        if (!refresh && token.equals(cache.getString("forToken", "")) && now - cache.getLong("time", 0) < SERVER_MS) {
            String uri = cache.getString("uri", null), access = cache.getString("access", null);
            if (uri != null && access != null) return new String[]{uri, access};
        }
        try {
            JSONArray resources = new JSONArray(get("https://plex.tv/api/v2/resources?includeHttps=1&includeRelay=0", token));
            JSONObject best = null;
            for (int i = 0; i < resources.length(); i++) {
                JSONObject r = resources.getJSONObject(i);
                if (!r.optString("provides").contains("server") || r.optJSONArray("connections") == null) continue;
                if (best == null || (r.optBoolean("owned") && !best.optBoolean("owned"))) best = r;
            }
            if (best == null) return null;
            JSONArray connections = best.getJSONArray("connections");
            String uri = null;
            for (int pass = 0; pass < 2 && uri == null; pass++) {   // local first, then remote
                for (int i = 0; i < connections.length(); i++) {
                    JSONObject c = connections.getJSONObject(i);
                    if (c.optBoolean("relay") || c.optBoolean("local") != (pass == 0)) continue;
                    uri = c.getString("uri");
                    break;
                }
            }
            if (uri == null) return null;
            String access = best.optString("accessToken", token);
            cache.edit().putString("uri", uri).putString("access", access).putString("forToken", token)
                    .putLong("time", now).apply();
            return new String[]{uri, access};
        } catch (IOException | JSONException | RuntimeException e) {
            return null;
        }
    }

    /**
     * The titles in this server list. An episode stands for its show (its art, logo and name); its link opens
     * that episode. The Plex app's links: plex://movie/<slug>, plex://show/<slug>/season/<n>/episode/<n>.
     */
    private List<Item> fetch(String base, String token, String path, boolean deck) throws IOException, JSONException {
        List<Item> items = new ArrayList<>();
        JSONArray found = new JSONObject(get(base + path, token)).getJSONObject("MediaContainer")
                .optJSONArray("Metadata");
        for (int i = 0; found != null && i < found.length(); i++) {
            JSONObject m = found.getJSONObject(i);
            boolean episode = "episode".equals(m.optString("type"));
            String a = episode ? m.optString("grandparentArt", m.optString("art")) : m.optString("art");
            if (a.isEmpty()) continue;
            String title = episode ? m.optString("grandparentTitle") : m.optString("title");
            String link = null;
            String slug = episode ? m.optString("grandparentSlug") : m.optString("slug");
            if (!slug.isEmpty() && "movie".equals(m.optString("type"))) {
                link = "plex://movie/" + Uri.encode(slug);
            } else if (!slug.isEmpty() && episode && m.has("parentIndex") && m.has("index")) {
                link = "plex://show/" + Uri.encode(slug) + "/season/" + m.optInt("parentIndex")
                        + "/episode/" + m.optInt("index");
            }
            items.add(new Item(image(base, token, a, width, height, true), logo(base, token, m, episode),
                    title.isEmpty() ? null : title, link, deck, m.optLong("lastViewedAt") * 1000,
                    m.optLong("duration"), m.optLong("viewOffset")));
        }
        return items;
    }

    /** The title's clear logo (newer servers list one; for an episode, its show's), or null. */
    private String logo(String base, String token, JSONObject m, boolean episode) {
        JSONArray images = m.optJSONArray("Image");
        for (int i = 0; !episode && images != null && i < images.length(); i++) {
            JSONObject image = images.optJSONObject(i);
            if (image != null && "clearLogo".equals(image.optString("type")) && !image.optString("url").isEmpty()) {
                return image(base, token, image.optString("url"), width / 2, height / 4, false);
            }
        }
        // An episode's own Image list is its own: its show's logo sits at a fixed place on the server.
        String show = m.optString("grandparentRatingKey");
        return episode && !show.isEmpty()
                ? image(base, token, "/library/metadata/" + show + "/clearLogo", width / 2, height / 4, false) : null;
    }

    /** One of the server's images, scaled by the server: to cover w by h (fill), else to fit within it. */
    private static String image(String base, String token, String path, int w, int h, boolean fill) {
        return base + "/photo/:/transcode?width=" + w + "&height=" + h + "&minSize=" + (fill ? 1 : 0)
                + "&upscale=0"
                + "&url=" + Uri.encode(path) + "&X-Plex-Token=" + Uri.encode(token);
    }

    private String get(String url, String token) throws IOException {
        Map<String, String> headers = new HashMap<>();
        headers.put("Accept", "application/json");
        headers.put("X-Plex-Token", token);
        headers.put("X-Plex-Product", "Vibe Launcher");
        headers.put("X-Plex-Client-Identifier", cache.getString("clientId", "vibe-launcher"));
        return Http.getText(url, headers);
    }
}
