package com.maydings.vibelauncher;

import android.content.ContentResolver;
import android.content.Context;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.Shader;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Backdrop stills from the cards apps publish for the TV home screen (their channels and Watch Next
 * rows in the system TV provider), read with READ_TV_LISTINGS. A card's film or show is shown with a
 * clean full-size backdrop and the title's logo instead of the app's own art: from TMDB with a key,
 * from Stremio's free metadata without one. Otherwise only wide artwork is used, and low-res art
 * (Netflix's 853x480) becomes a 2x2 wall of four cards. YouTube publishes no cards, so its tile borrows
 * SmartTube's (the user's own feed), or without SmartTube shows popular videos via the YouTube Data API
 * key. Plex, with a Plex token, shows art from the user's own server, and HDHomeRun the shows on the
 * user's tuner now (HdHomeRun), and a streaming app with no cards what's popular on it (StreamingCatalogs).
 * Apps with none keep the colour glow.
 *
 * Each app has a pool of up to {@link #POOL_SIZE} stills, refreshed in the background when it is six
 * hours old: each new still found replaces the oldest, one at a time, so there is never a second set
 * on disk. They are stored already cut to the screen and darkened, so showing one is a quick decode of
 * a small file with no network: each visit to Home picks one at random.
 */
final class Backdrops {

    interface Callback {
        /**
         * The still for the app (null for none). For a film or show found by its title, also that title
         * and its logo (null when it has none, and then the title is shown as text).
         */
        void onBackdrop(String pkg, Bitmap bitmap, Bitmap logo, String title, String link);
    }

    interface FirstStillListener {
        /** The app had no stills and now has its first (main thread): Home shows it if that app is focused. */
        void onFirstStill(String pkg);
    }

    /** A still to make: one image or a wall of four, and for a known title its logo. */
    private static final class Choice {
        final List<File> files;
        final File logo;
        final String title, link;

        Choice(List<File> files, File logo, String title, String link) {
            this.files = files;
            this.logo = logo;
            this.title = title;
            this.link = link;
        }
    }

    /** A finished still in an app's pool: screen-sized and darkened, with the title's logo if any. */
    static final class Entry {
        final File still, logo;
        final String title;
        /**
         * What opens the still's title: the card's own link (an intent URI, as the stock launcher opens
         * it), or a YouTube video's address. Null for a wall of four, and for Plex and HDHomeRun.
         */
        final String link;
        final long made;   // when it went into the pool: the oldest is replaced first

        Entry(File still, File logo, String title, String link, long made) {
            this.still = still;
            this.logo = logo;
            this.title = title;
            this.link = link;
            this.made = made;
        }
    }

    /**
     * An app's pool: when its last round of replacing began, the keys and permission it was made with,
     * and its stills, oldest first. Changed only under the Backdrops lock.
     */
    private static final class Pool {
        long built;
        String fingerprint;
        final List<Entry> entries;

        Pool(long built, String fingerprint, List<Entry> entries) {
            this.built = built;
            this.fingerprint = fingerprint;
            this.entries = entries;
        }
    }

    static final String PERMISSION = "android.permission.READ_TV_LISTINGS";

    private static final Uri PREVIEW = Uri.parse("content://android.media.tv/preview_program");
    private static final Uri WATCH_NEXT = Uri.parse("content://android.media.tv/watch_next_program");
    private static final String[] COLUMNS = {"package_name", "intent_uri", "title", "poster_art_uri", "thumbnail_uri"};
    private static final int FIRST_IMAGE = 3;   // COLUMNS from here on are image URIs
    /** Watch Next rows also say whether you're watching it, and when you last did. */
    private static final String[] WATCH_NEXT_COLUMNS = {"package_name", "intent_uri", "title", "poster_art_uri",
            "thumbnail_uri", "watch_next_type", "last_engagement_time_utc_millis", "browsable"};
    /** Watch Next types for something started: part-way through (CONTINUE), or the next episode (NEXT). */
    private static final int CONTINUE = 0, NEXT = 1;
    private static final String YOUTUBE = "com.google.android.youtube.tv";
    private static final String PLEX = "com.plexapp.android";
    private static final String HDHOMERUN = "com.silicondust.view";
    private static final int GUIDE_WIDTH = 480;   // the HDHomeRun guide's images: only ever in a wall
    /** SmartTube builds (stable, beta, the old names): they publish the signed-in user's YouTube feed. */
    private static final String[] SMARTTUBE = SmartTube.PACKAGES;
    private static final int MIN_WIDTH = 800;   // Netflix publishes 853x480
    /**
     * An app's own card art, used only when it has nothing sharper (in a wall of four, else on its own): VLC
     * publishes 512x288 frames of what you're watching, and at 800 its cards showed nothing but its glow.
     */
    private static final int CARD_MIN_WIDTH = 480;
    private static final float MIN_ASPECT = 1.45f, MAX_ASPECT = 2.4f;
    private static final int SHARP_WIDTH = 1280;  // narrower art goes into a 2x2 wall
    private static final int WALL = 4;
    private static final int WALL_GAP = 6;        // px between the wall's cards
    private static final int POOL_SIZE = 12;
    private static final int POOL_TRIES = 60;     // lookups and downloads for a whole pool (5 a still)
    private static final int GUIDE_IMAGES = POOL_SIZE * WALL;  // most fetched to fill the pool with walls
    static final long POOL_MS = 6L * 60 * 60 * 1000;
    /**
     * How stills are stored: lossless, since a second lossy save (JPEG) showed in the darkened gradients.
     * Part of the pool's fingerprint, so changing it rebuilds every pool.
     */
    private static final String STILL_FORMAT = "png";
    /**
     * What a pool's index holds, also part of the fingerprint: pools from before refresh once to get it. 2
     * added each still's link; 3, Plex's names, logos and links.
     */
    private static final int POOL_VERSION = 3;
    private static final long EMPTY_RETRY_MS = 2L * 60 * 1000;   // an empty pool (offline?) is retried sooner
    private static final long RETRY_MS = 10L * 60 * 1000;        // after a round that found nothing
    private static final int MAX_IMAGE_BYTES = 6 * 1024 * 1024;
    private static final long CACHE_MS = 2L * 24 * 60 * 60 * 1000;   // downloads, reused by the next rebuilds

    private final Context context;
    private final VibeSettings settings;
    private final ContentResolver resolver;
    private final Tmdb tmdb;
    private final Stremio stremio;
    private final YouTubePopular youtubePopular;
    private final StreamingCatalogs streamingCatalogs;
    private final Plex plex;
    private final HdHomeRun hdHomeRun;
    private final File root, poolRoot;
    /** Showing a still: the one on screen goes to the front, ahead of anything else waiting. */
    private final LinkedBlockingDeque<Runnable> queue = new LinkedBlockingDeque<>();
    private final ThreadPoolExecutor worker = new ThreadPoolExecutor(1, 1, 0, TimeUnit.MILLISECONDS, queue);
    /**
     * Filling the pools: every app's on this one thread, at low priority so it never slows down what's on
     * screen. The pools being built take turns a card at a time (see {@link #pump}).
     */
    private final ExecutorService builder = Executors.newSingleThreadExecutor(r -> new Thread(() -> {
        // Android's background priority, not only Java's lowest: that also puts the thread in the background
        // group, off the cores the screen is drawn on. Java's alone left it competing with them: rebuilding
        // the pools at full tilt for a minute and more, it slowed Home down on the 940E.
        android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND);
        r.run();
    }, "BackdropPools"));
    private final ConcurrentLinkedQueue<Build> incoming = new ConcurrentLinkedQueue<>();
    private final List<Build> active = new ArrayList<>();   // pool thread only
    /** Bumped by reload: builds from before it are dropped, and put nothing into the new pools. */
    private final AtomicInteger generation = new AtomicInteger();
    private int turn;                                       // pool thread only
    /** Home's, while one is on screen; null in between (it's rebuilt for a new grid or language). */
    private volatile FirstStillListener firstStill;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final Map<String, Pool> pools = new HashMap<>();
    private final Map<String, Entry> shown = new HashMap<>();   // this visit's still per app
    private final Map<String, Entry> before = new HashMap<>();  // the last visit's, not repeated next
    private final Set<String> building = Collections.synchronizedSet(new HashSet<>());
    private final Map<String, Long> attempted = Collections.synchronizedMap(new HashMap<>());   // rounds that found nothing
    private final Set<String> unsuitable = Collections.synchronizedSet(new HashSet<>());
    private final Random random = new Random();
    private final int targetWidth, targetHeight;

    private static Backdrops instance;

    /**
     * The one for the whole app. Filling the pools is the app's job, not the screen's: Home is rebuilt for a
     * new grid or language, and each Home used to bring its own, so the old one, still replacing stills,
     * deleted the one the new Home had just picked (900F, on changing tiles per row). Created on first use
     * and kept: its threads wait idle on their queues between builds.
     */
    static synchronized Backdrops get(Context context) {
        if (instance == null) instance = new Backdrops(context.getApplicationContext());
        return instance;
    }

    /** Home's handlers: an app's first still, and what you're watching changing after its carousel opened. */
    void attach(FirstStillListener firstStill, Runnable watchingChanged) {
        this.firstStill = firstStill;
        this.watchingChanged = watchingChanged;
    }

    /**
     * Home going: its handlers go, but only if they're still the ones attached. A rebuilt Home can start
     * before the old one is destroyed, and the old one mustn't take the new one's away.
     */
    void detach(FirstStillListener firstStill, Runnable watchingChanged) {
        if (this.firstStill == firstStill) this.firstStill = null;
        if (this.watchingChanged == watchingChanged) this.watchingChanged = null;
    }

    private Backdrops(Context context) {
        this.context = context;
        settings = new VibeSettings(context);
        resolver = context.getContentResolver();
        tmdb = new Tmdb(context);
        stremio = new Stremio(context);
        youtubePopular = new YouTubePopular(context);
        streamingCatalogs = new StreamingCatalogs(context);
        root = new File(context.getCacheDir(), "cards");
        poolRoot = new File(context.getCacheDir(), "pools");
        targetWidth = context.getResources().getDisplayMetrics().widthPixels;
        targetHeight = context.getResources().getDisplayMetrics().heightPixels;
        plex = new Plex(context, targetWidth, targetHeight);
        hdHomeRun = new HdHomeRun(context);
        worker.prestartCoreThread();   // waiting on the queue, so jobs can go straight to its front
        builder.execute(this::prune);
    }

    /**
     * Empties every pool and the downloads, for building them all again from scratch (Settings → Reload
     * backdrops): the next refresh of each app finds it with none. Builds under way are dropped.
     */
    void reload() {
        synchronized (this) {
            generation.incrementAndGet();
            pools.clear();
            shown.clear();
            before.clear();
            deleteTree(poolRoot);
            deleteTree(root);
        }
        building.clear();
        attempted.clear();
        unsuitable.clear();
    }

    /** Pick a fresh still per app next time each is shown (called when Home comes back). */
    synchronized void reshuffle() {
        before.putAll(shown);
        shown.clear();
        // Stills replaced since the last visit can go now: nothing picks them any more. And the old
        // downloads: the launcher can run for weeks, so starting up isn't often enough.
        builder.execute(() -> {
            sweep();
            prune();
        });
    }

    /**
     * Rebuilds the app's pool in the background if it is due: six hours old, the keys changed, or it has
     * none yet (just moved to the top row, first run, or offline until now).
     */
    void refresh(String pkg) {
        if (!due(pkg) || !building.add(pkg)) return;
        incoming.offer(new Build(pkg));
        builder.execute(this::pump);   // ends at once when another pump already took it
    }

    /** How long until the first of these apps' pools is due; 0 when one already is. */
    long untilDue(List<String> pkgs) {
        long next = POOL_MS;
        long now = System.currentTimeMillis();
        for (String pkg : pkgs) {
            if (building.contains(pkg)) continue;   // rebuilt from now on
            next = Math.min(next, dueAt(pkg) - now);
        }
        return Math.max(0, next);
    }

    /**
     * Delivers this visit's still for the app on the main thread, or null when its pool has none yet.
     * Only ever reads the pool: filling it is the pool threads' job, so nothing here waits on the network.
     */
    void load(String pkg, Callback callback) {
        queue.offerFirst(() -> {
            Bitmap bitmap = null, logo = null;
            String title = null, link = null;
            try {
                Entry e = choose(pkg);
                if (e != null) {
                    bitmap = BitmapFactory.decodeFile(e.still.getPath());
                    logo = e.logo != null ? decodeLogo(e.logo) : null;
                    title = e.title;
                    link = e.link;
                }
                // None yet: the glow, until the pool's first still is in (see FirstStillListener).
            } catch (RuntimeException | OutOfMemoryError e) {
                // A bad image or cache entry, or no memory left on a small TV: the colour glow instead
                // of taking the launcher down (an error here would crash the whole app).
                bitmap = null;
                logo = null;
                title = null;
                link = null;
            }
            Bitmap b = bitmap, l = logo;
            String t = title, k = link;
            main.post(() -> callback.onBackdrop(pkg, b, l, t, k));
            refresh(pkg);
        });   // ahead of anything else: this is the one on screen
    }

    /**
     * The app's next (+1) or previous (-1) still in its pool, going round, for looking through them; it
     * becomes this visit's. Delivered on the main thread as load's; nothing when there's no other.
     */
    void step(String pkg, int direction, Callback callback) {
        queue.offerFirst(() -> {
            Entry e = next(pkg, direction);
            if (e == null) return;
            Bitmap bitmap, logo;
            try {
                bitmap = BitmapFactory.decodeFile(e.still.getPath());
                logo = e.logo != null ? decodeLogo(e.logo) : null;
            } catch (RuntimeException | OutOfMemoryError x) {
                return;   // a bad file or no memory: stay on the one showing
            }
            if (bitmap == null) return;
            Bitmap l = logo;
            main.post(() -> callback.onBackdrop(pkg, bitmap, l, e.title, e.link));
        });
    }

    private synchronized Entry next(String pkg, int direction) {
        Pool p = pool(pkg);
        if (p == null || p.entries.size() < 2) return null;
        // The carousel's order when it was opened for this app (what you're watching first), else the pool's.
        // A refresh since may have replaced a still: then the pool's own order, with no gone still in it.
        List<Entry> order = pkg.equals(carouselPkg) && carousel.size() == p.entries.size()
                && p.entries.containsAll(carousel) ? carousel : p.entries;
        int n = order.size();
        int at = order.indexOf(shown.get(pkg));
        if (at < 0) at = direction > 0 ? -1 : 0;   // none shown, or replaced since: start at an end
        Entry e = order.get(((at + direction) % n + n) % n);
        shown.put(pkg, e);
        return e;
    }

    /** Something you're watching, from Watch Next: when you last did, and how far in you are. */
    static final class Watching {
        final long engaged, durationMs, positionMs;

        Watching(long engaged, long durationMs, long positionMs) {
            this.engaged = engaged;
            this.durationMs = durationMs;
            this.positionMs = positionMs;
        }

        /** The time left, or 0 when the app didn't say how long it is or how far in you are. */
        long remainingMs() {
            return durationMs > 0 && positionMs > 0 ? Math.max(0, durationMs - positionMs) : 0;
        }
    }

    private List<Entry> carousel = Collections.emptyList();
    private String carouselPkg;
    private volatile Map<String, Watching> watching = Collections.emptyMap();

    /**
     * What you're watching now, by each Watch Next row's link (the link a pool entry keeps): read fresh, as
     * the pool is up to six hours old and you may have watched more since. Worker thread.
     */
    private Map<String, Watching> readWatching() {
        Map<String, Watching> found = new HashMap<>();
        String[] columns = {"intent_uri", "watch_next_type", "last_engagement_time_utc_millis", "duration_millis",
                "last_playback_position_millis", "browsable"};
        try (Cursor c = resolver.query(WATCH_NEXT, columns, null, null, null)) {
            while (c != null && c.moveToNext()) {
                String link = c.getString(0);
                if (link == null || !isStarted(c, 1)) continue;
                if (!c.isNull(5) && c.getInt(5) == 0) continue;   // removed from Continue watching
                found.put(link, new Watching(c.isNull(2) ? 0 : c.getLong(2), c.isNull(3) ? 0 : c.getLong(3),
                        c.isNull(4) ? 0 : c.getLong(4)));
            }
        } catch (RuntimeException e) {
            // No permission or no provider: nothing known to be watched.
        }
        return found;
    }

    /** The time left on what this link opens, as of the last openCarousel; 0 when not known. */
    long remainingMs(String link) {
        Watching w = link == null ? null : watching.get(link);
        return w == null ? 0 : w.remainingMs();
    }

    /**
     * Opening the picture alone for this app: reads what you're watching, puts it first in the carousel (the
     * last watched first), then the rest of the pool. With jump, and something watched in the pool, delivers
     * the first of them to show (unless it is showing already); otherwise delivers a null bitmap, just to say
     * the watching info is in. With switched (another app picked in the picture alone), always delivers the
     * picture to show: the first watched, else this visit's still, else null for the app's glow.
     */
    void openCarousel(String pkg, boolean jump, boolean switched, Callback callback) {
        queue.offerFirst(() -> {
            Entry first = orderCarousel(pkg, jump, switched);
            Bitmap bitmap = null, logo = null;
            if (first != null) {
                try {
                    bitmap = BitmapFactory.decodeFile(first.still.getPath());
                    logo = first.logo != null ? decodeLogo(first.logo) : null;
                } catch (RuntimeException | OutOfMemoryError x) {
                    bitmap = null;
                }
            }
            Bitmap b = bitmap, l = logo;
            Entry e = first;
            main.post(() -> callback.onBackdrop(pkg, b, l, e != null ? e.title : null, e != null ? e.link : null));
            if (pkg.equals(PLEX)) {
                // Plex's progress is the server's: asked again in the background, and the order and time left
                // follow once it's in (the picture showing stays).
                plex.refreshOnDeck(() -> queue.offerFirst(() -> {
                    orderCarousel(pkg, false, false);
                    Runnable listener = watchingChanged;   // read once: Home may go meanwhile
                    if (listener != null) main.post(listener);
                }));
            }
        });
    }

    /** Runs on the main thread when what you're watching changed after the carousel opened (Plex); see attach. */
    private volatile Runnable watchingChanged;

    /**
     * Reads what you're watching (Watch Next, and for Plex its server's On Deck as last fetched) and orders
     * the app's carousel by it; returns the still to show, per openCarousel, or null. Worker thread.
     */
    private Entry orderCarousel(String pkg, boolean jump, boolean switched) {
        Map<String, Watching> now = readWatching();
        if (pkg.equals(PLEX)) {
            // The Plex app shares nothing with Android TV: where you stopped comes from your server.
            for (Plex.Item item : plex.onDeck()) {
                if (item.link == null) continue;
                now.put(item.link, new Watching(item.viewedMs, item.durationMs, item.offsetMs));
            }
        }
        watching = now;
        Entry first;
        synchronized (this) {
            Pool p = pool(pkg);
            List<Entry> order = new ArrayList<>(), rest = new ArrayList<>();
            if (p != null) {
                for (Entry e : p.entries) (e.link != null && now.containsKey(e.link) ? order : rest).add(e);
            }
            order.sort((a, b) -> Long.compare(now.get(b.link).engaged, now.get(a.link).engaged));
            first = order.isEmpty() ? null : order.get(0);
            order.addAll(rest);
            carousel = order;
            carouselPkg = pkg;
            if (switched) {
                if (first == null) first = choose(pkg);   // this visit's still, if its file is still there
            } else if (!jump || first == shown.get(pkg)) {
                first = null;
            }
            if (first != null) shown.put(pkg, first);
        }
        return first;
    }

    interface SlideCallback {
        /** On the main thread: a still for the slideshow, from this app's pool. */
        void onSlide(String pkg, Entry entry, Bitmap bitmap, Bitmap logo);
    }

    /**
     * A still picked at random from all these apps' pools (any but the one showing), for the slideshow
     * Home runs when left alone. Nothing when there's no other.
     */
    void randomSlide(List<String> pkgs, Entry showing, SlideCallback callback) {
        queue.offer(() -> {
            String[] owner = new String[1];
            Entry e = pickSlide(pkgs, showing, owner);
            if (e == null) return;
            Bitmap bitmap, logo;
            try {
                bitmap = BitmapFactory.decodeFile(e.still.getPath());
                logo = e.logo != null ? decodeLogo(e.logo) : null;
            } catch (RuntimeException | OutOfMemoryError x) {
                return;   // a bad file or no memory: the next one in ten seconds
            }
            if (bitmap == null) return;
            Bitmap l = logo;
            main.post(() -> callback.onSlide(owner[0], e, bitmap, l));
        });
    }

    private synchronized Entry pickSlide(List<String> pkgs, Entry showing, String[] owner) {
        List<Entry> all = new ArrayList<>();
        List<String> owners = new ArrayList<>();
        for (String pkg : pkgs) {
            Pool p = pool(pkg);
            if (p == null) continue;
            for (Entry e : p.entries) {
                if (e == showing) continue;
                all.add(e);
                owners.add(pkg);
            }
        }
        if (all.isEmpty()) return null;
        int i = random.nextInt(all.size());
        owner[0] = owners.get(i);
        return all.get(i);
    }

    /** Makes this the app's still for this visit: the slideshow stopped on it. */
    synchronized void keep(String pkg, Entry e) {
        shown.put(pkg, e);
    }

    /** How many stills the app's pool has now. */
    synchronized int stillCount(String pkg) {
        Pool p = pool(pkg);
        return p == null ? 0 : p.entries.size();
    }

    /** This visit's still from the app's pool: kept for the visit, and not the one shown last time. */
    private synchronized Entry choose(String pkg) {
        Entry e = shown.get(pkg);
        if (e != null && e.still.exists()) return e;
        Pool p = pool(pkg);
        if (p == null || p.entries.isEmpty()) return null;
        List<Entry> options = new ArrayList<>(p.entries);
        Entry last = before.get(pkg);
        if (options.size() > 1 && last != null) {
            for (int i = 0; i < options.size(); i++) {
                if (options.get(i).still.equals(last.still)) {
                    options.remove(i);
                    break;
                }
            }
        }
        e = options.get(random.nextInt(options.size()));
        shown.put(pkg, e);
        return e;
    }

    // ---- pools ----------------------------------------------------------------------------------------

    /**
     * pools/<app>/ holds the app's stills and index.txt, listing them. (1.5.2 and before kept a folder per
     * build and current.txt naming the one in use: still read, and cleared away as its stills are replaced.)
     */
    private File poolDir(String pkg) {
        return new File(poolRoot, sha1(pkg));
    }

    /** The keys and the permission a pool was built with: changing any of them rebuilds it. */
    private String fingerprint() {
        boolean cards = context.checkSelfPermission(PERMISSION) == PackageManager.PERMISSION_GRANTED;
        return sha1(settings.tmdbKey() + "|" + settings.youtubeKey() + "|" + settings.plexToken() + "|" + cards
                + "|" + STILL_FORMAT + "|" + POOL_VERSION);
    }

    /** When the app's pool should next be refreshed (in the past when it's due now). */
    private synchronized long dueAt(String pkg) {
        Pool p = pool(pkg);
        long at = p == null || !p.fingerprint.equals(fingerprint()) ? 0
                : p.built + (p.entries.isEmpty() ? EMPTY_RETRY_MS : POOL_MS);
        Long tried = attempted.get(pkg);
        return tried != null ? Math.max(at, tried + RETRY_MS) : at;
    }

    private boolean due(String pkg) {
        return System.currentTimeMillis() >= dueAt(pkg);
    }

    /** The app's pool in use, read from disk the first time; null before one was built. */
    private synchronized Pool pool(String pkg) {
        if (pools.containsKey(pkg)) return pools.get(pkg);
        Pool p = null;
        try {
            p = readPool(poolDir(pkg));
        } catch (IOException | RuntimeException e) {
            // None yet, or unreadable: built afresh.
        }
        pools.put(pkg, p);
        return p;
    }

    /** Reads an app's pool from its folder. Its index lists file names relative to the folder. */
    private static Pool readPool(File dir) throws IOException {
        File index = new File(dir, "index.txt");
        String base = "";
        if (!index.exists()) {   // 1.5.2 and before: the build in use, in its own folder
            base = readText(new File(dir, "current.txt")).trim() + "/";
            index = new File(dir, base + "index.txt");
        }
        String[] lines = readText(index).split("\n");
        String[] head = lines[0].split("\t", -1);
        long built = Long.parseLong(head[0]);
        List<Entry> entries = new ArrayList<>();
        for (int i = 1; i < lines.length; i++) {
            String[] f = lines[i].split("\t", -1);
            if (f.length < 3 || f[0].isEmpty()) continue;
            entries.add(new Entry(new File(dir, base + f[0]), f[1].isEmpty() ? null : new File(dir, base + f[1]),
                    f[2].isEmpty() ? null : f[2], f.length > 4 && !f[4].isEmpty() ? f[4] : null,
                    f.length > 3 ? Long.parseLong(f[3]) : built));
        }
        return new Pool(built, head[1], entries);
    }

    private static String oneLine(String s) {
        return s == null ? "" : s.replace('\t', ' ').replace('\n', ' ').replace('\r', ' ');
    }

    /**
     * Saves the pool's index: written beside it and renamed over it, so a reader never sees half of one.
     * Under the Backdrops lock.
     */
    private static void writeIndex(File dir, Pool p) throws IOException {
        StringBuilder index = new StringBuilder().append(p.built).append('\t').append(p.fingerprint).append('\n');
        int skip = dir.getPath().length() + 1;
        for (Entry e : p.entries) {
            index.append(e.still.getPath().substring(skip)).append('\t')
                    .append(e.logo == null ? "" : e.logo.getPath().substring(skip)).append('\t')
                    .append(oneLine(e.title)).append('\t').append(e.made).append('\t')
                    .append(oneLine(e.link)).append('\n');
        }
        File tmp = new File(dir, "index.tmp");
        try (FileOutputStream out = new FileOutputStream(tmp)) {
            out.write(index.toString().getBytes(StandardCharsets.UTF_8));
        }
        if (!tmp.renameTo(new File(dir, "index.txt"))) throw new IOException("rename");
        new File(dir, "current.txt").delete();   // 1.5.2's pointer: the index above is the pool now
    }

    private static String readText(File f) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(f)) {
            return new String(Http.readAll(in, 64 * 1024), StandardCharsets.UTF_8);
        }
    }

    /** A round of refreshing an app's pool: its cards gone through one at a time. */
    private final class Build {
        final String pkg;
        final int generation = Backdrops.this.generation.get();
        long built;
        String fingerprint;
        Collector collector;
        int taken;   // stills this round put into the pool

        Build(String pkg) {
            this.pkg = pkg;
        }

        /** One card (the first turn reads the cards). False when the pool has all it will get. */
        boolean step() {
            if (collector == null) {
                built = System.currentTimeMillis();
                fingerprint = fingerprint();
                collector = new Collector(pkg, POOL_SIZE, POOL_TRIES);
                return true;
            }
            boolean more = collector.step();
            for (Choice c : collector.take(!more)) if (put(this, c)) taken++;
            return more;
        }
    }

    private boolean hasStill(String pkg) {
        Pool p = pool(pkg);
        return p != null && !p.entries.isEmpty();
    }

    /**
     * Refreshes the pools waiting, breadth first: they take turns a card at a time, so no app waits
     * behind another's whole pool. Apps with no still at all go first, until each has one (or has run out
     * of cards): showing something for every app beats more choice for some. Runs on the pool thread
     * until none are left.
     */
    private void pump() {
        while (true) {
            for (Build b; (b = incoming.poll()) != null; ) active.add(b);
            for (int i = active.size() - 1; i >= 0; i--) {
                if (active.get(i).generation != generation.get()) active.remove(i);   // from before a reload
            }
            if (active.isEmpty()) return;
            List<Build> first = new ArrayList<>();
            for (Build b : active) if (!hasStill(b.pkg)) first.add(b);
            List<Build> turns = first.isEmpty() ? active : first;
            Build b = turns.get(turn++ % turns.size());
            boolean more;
            try {
                more = b.step();
            } catch (RuntimeException | OutOfMemoryError e) {
                more = false;
                b.collector = null;   // what's in the pool stays; the next refresh tries again
            }
            if (more) continue;
            active.remove(b);
            try {
                if (b.collector != null) finish(b);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // What's in the pool stays; the next refresh tries again.
            } finally {
                if (b.generation == generation.get()) building.remove(b.pkg);   // else a newer build's
            }
        }
    }

    /**
     * Puts a new still into the app's pool: added while it has fewer than POOL_SIZE, else in place of its
     * oldest from before this round. The new still is written in full before it joins the pool, and the
     * one it replaces leaves the pool in the same step, so a pick never sees either half-way. The replaced
     * still's files are deleted, unless it's the one this visit shows: that goes on the next visit to
     * Home (see {@link #sweep}). Pool thread. False when it wasn't put in.
     */
    private boolean put(Build b, Choice c) {
        if (b.generation != generation.get()) return false;   // reloaded since
        String pkg = b.pkg;
        File dir = poolDir(pkg);
        dir.mkdirs();
        Entry e = renderStill(c, dir);
        if (e == null) return false;
        Entry gone = null;
        boolean first;
        synchronized (this) {
            if (b.generation != generation.get()) {   // reloaded while it rendered
                deleteStill(e);
                return false;
            }
            Pool p = pool(pkg);
            if (p == null) {
                p = new Pool(0, b.fingerprint, new ArrayList<>());   // built 0: the round in progress sets it
                pools.put(pkg, p);
            }
            first = p.entries.isEmpty();
            if (p.entries.size() >= POOL_SIZE) {
                for (Entry old : p.entries) {
                    if (old.made < b.built) {
                        gone = old;
                        break;
                    }
                }
                if (gone == null) {   // all new this round already
                    deleteStill(e);
                    return false;
                }
                p.entries.remove(gone);
            }
            p.entries.add(e);
            try {
                writeIndex(dir, p);
            } catch (IOException x) {
                p.entries.remove(e);
                if (gone != null) p.entries.add(0, gone);
                deleteStill(e);
                return false;
            }
            if (gone != null && gone != shown.get(pkg)) deleteStill(gone);
        }
        if (first) main.post(() -> {
            FirstStillListener listener = firstStill;
            if (listener != null) listener.onFirstStill(pkg);
        });
        return true;
    }

    /**
     * Ends a round. Nothing found leaves the pool as it is and tries again sooner; an app with no stills
     * at all gets an empty pool, retried after a couple of minutes. With new keys, the stills from before
     * the round are made with the old ones and go, once the round found at least one.
     */
    private void finish(Build b) throws IOException {
        if (b.generation != generation.get()) return;   // reloaded since
        String pkg = b.pkg;
        File dir = poolDir(pkg);
        synchronized (this) {
            Pool p = pool(pkg);
            if (b.taken == 0) {
                if (p != null && !p.entries.isEmpty()) {
                    attempted.put(pkg, b.built);   // keep what's there, try again later
                    return;
                }
                p = new Pool(b.built, b.fingerprint, new ArrayList<>());
                pools.put(pkg, p);
                dir.mkdirs();
                writeIndex(dir, p);
                return;
            }
            if (!p.fingerprint.equals(b.fingerprint)) {
                Entry on = shown.get(pkg);
                for (int i = p.entries.size() - 1; i >= 0; i--) {
                    Entry old = p.entries.get(i);
                    if (old.made >= b.built) continue;
                    p.entries.remove(i);
                    if (old != on) deleteStill(old);
                }
            }
            p.built = b.built;
            p.fingerprint = b.fingerprint;
            writeIndex(dir, p);
            attempted.remove(pkg);
        }
    }

    /** Renders a choice into new files in the app's folder: its still, and the title's logo if any. Null if it fails. */
    private Entry renderStill(Choice c, File dir) {
        long made = System.currentTimeMillis();
        String name = String.valueOf(made);
        for (int n = 1; new File(dir, name + ".png").exists(); n++) name = made + "-" + n;
        File still = new File(dir, name + ".png"), logo = null;
        try {
            Bitmap b = render(c);
            if (b == null) return null;
            b.setHasAlpha(false);   // opaque: stored as RGB, a quarter smaller than RGBA
            try (FileOutputStream out = new FileOutputStream(still)) {
                b.compress(Bitmap.CompressFormat.PNG, 100, out);
            } finally {
                b.recycle();
            }
            if (c.logo != null) {
                logo = new File(dir, name + ".logo");
                copy(c.logo, logo);
            }
            return new Entry(still, logo, c.title, c.link, made);
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            still.delete();
            if (logo != null) logo.delete();
            return null;
        }
    }

    private static void deleteStill(Entry e) {
        e.still.delete();
        if (e.logo != null) e.logo.delete();
    }

    /**
     * Deletes the files no pool lists: stills replaced while on screen, and 1.5.2's folders per build once
     * their stills are all replaced. Only pools in the current form (index.txt): an old one still in use
     * is left as it is. Pool thread.
     */
    private void sweep() {
        File[] apps = poolRoot.listFiles();
        if (apps == null) return;
        for (File app : apps) {
            if (!new File(app, "index.txt").exists()) continue;
            Set<String> used = new HashSet<>();
            used.add(new File(app, "index.txt").getPath());
            try {
                for (Entry e : readPool(app).entries) {
                    used.add(e.still.getPath());
                    if (e.logo != null) used.add(e.logo.getPath());
                }
            } catch (IOException | RuntimeException e) {
                continue;
            }
            sweep(app, used);
        }
    }

    private static void sweep(File dir, Set<String> used) {
        File[] files = dir.listFiles();
        if (files == null) return;
        for (File f : files) {
            if (f.isDirectory()) {
                sweep(f, used);
                f.delete();   // only goes once empty
            } else if (!used.contains(f.getPath())) {
                f.delete();
            }
        }
    }

    private static void deleteTree(File f) {
        File[] inside = f.listFiles();
        if (inside != null) for (File c : inside) deleteTree(c);
        f.delete();
    }

    private static void copy(File from, File to) throws IOException {
        try (java.io.FileInputStream in = new java.io.FileInputStream(from);
             FileOutputStream out = new FileOutputStream(to)) {
            out.write(Http.readAll(in, MAX_IMAGE_BYTES));
        }
    }

    /** The finished still for a choice: cut to the screen and darkened. Null if it can't be decoded. */
    private Bitmap render(Choice c) {
        Bitmap src = c.files.size() == 1 ? decode(c.files.get(0)) : wall(c.files);
        if (src == null) return null;
        Bitmap out = src;
        if (src.getWidth() != targetWidth || src.getHeight() != targetHeight) {
            out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
            new Canvas(out).drawBitmap(src, centreCrop(src.getWidth(), src.getHeight(), targetWidth, targetHeight),
                    new Rect(0, 0, targetWidth, targetHeight), new Paint(Paint.FILTER_BITMAP_FLAG));
            src.recycle();
        }
        darken(out);
        return out;
    }

    /**
     * One card from the provider: its title, whether its art carries a sticker, and its images. Images
     * narrower than minWidth aren't used.
     */
    private static final class Card {
        final String title;
        final boolean badged;
        final List<String> images = new ArrayList<>();
        int minWidth = CARD_MIN_WIDTH;
        String link;   // what opens the card's title (see Entry.link)
        /**
         * The art comes with the title's name and logo from the app's own source (Plex): used as is, not
         * looked up by name (which can find another film of the same name). The logo may be null.
         */
        boolean ownArt;
        String logo;

        Card(String title, boolean badged) {
            this.title = title;
            this.badged = badged;
        }
    }

    /**
     * Up to max stills to make, in the order found: clean backdrops for the cards' titles and the
     * cards' own sharp, unbadged art. Only when there are none, walls of four low-res cards (which may
     * be badged: the sticker doesn't change the card's size), or else one low-res card on its own.
     * HDHomeRun is the exception: what's on often has few titles to look up (news, talk shows), so its
     * sharp stills are topped up to max with walls of the guide's small images, which aren't counted
     * as tries. Gone through a card at a time (step), so pools can take turns.
     */
    private final class Collector {
        final String pkg;
        final int max, maxTries;
        final List<Card> cards;
        final TitleArt titleArt;
        final List<Choice> sharp = new ArrayList<>(), walls = new ArrayList<>();
        final List<File> wall = new ArrayList<>();
        File plain;   // first unbadged low-res card, the fallback when there aren't enough for a wall
        String plainLink;
        final Set<String> used = new HashSet<>();
        int tries, next, images;
        int sharpTaken, wallsTaken;

        Collector(String pkg, int max, int maxTries) {
            this.pkg = pkg;
            this.max = max;
            this.maxTries = maxTries;
            cards = cards(pkg);
            // Films and shows by title: TMDB with a key, Stremio without. Not for video sites' video names.
            titleArt = isVideoSite(pkg) ? null : tmdb.enabled() ? tmdb : stremio;
        }

        /** The next card. False when done. */
        boolean step() {
            if (next < cards.size() && sharp.size() < max && tries < maxTries) {
                card(cards.get(next++));
                return true;
            }
            if (!pkg.equals(HDHOMERUN)) return false;
            // The rest of the shows, by their guide images alone, until the walls fill the pool.
            while (next < cards.size() && sharp.size() + walls.size() < max && images < GUIDE_IMAGES) {
                String url = cards.get(next++).images.get(0);
                if (unsuitable.contains(url) || !used.add(url)) continue;
                images++;
                File file = fetch(url, GUIDE_WIDTH);
                if (file != null) addToWall(file);
                return true;
            }
            return false;
        }

        private void card(Card card) {
            if (titleArt != null && card.title != null && !card.ownArt) {
                tries++;
                TitleArt.Art art = titleArt.lookup(card.title);
                if (art != null && used.add(art.backdrop) && !unsuitable.contains(art.backdrop)) {
                    File file = fetch(art.backdrop, true);
                    if (file != null) {
                        File logo = art.logo != null && !unsuitable.contains(art.logo) ? fetch(art.logo, false) : null;
                        sharp.add(new Choice(Collections.singletonList(file), logo, card.title, card.link));
                        return;
                    }
                }
            }
            for (String url : card.images) {
                if (unsuitable.contains(url) || !used.add(url)) continue;
                if (tries++ >= maxTries) break;
                File file = fetch(url, card.minWidth);
                if (file == null) continue;
                if (width(file) >= SHARP_WIDTH) {
                    // A card with its own logo (Plex) keeps its name and logo with its own art.
                    File logo = card.logo != null && !unsuitable.contains(card.logo) ? fetch(card.logo, false) : null;
                    if (!card.badged) {
                        sharp.add(new Choice(Collections.singletonList(file), logo, card.ownArt ? card.title : null,
                                card.link));
                    }
                } else {
                    if (plain == null && !card.badged) {
                        plain = file;
                        plainLink = card.link;
                    }
                    addToWall(file);
                }
                break;   // one image per card
            }
        }

        private void addToWall(File file) {
            wall.add(file);
            if (wall.size() == WALL) {
                walls.add(new Choice(new ArrayList<>(wall), null, null, null));
                wall.clear();
            }
        }

        /**
         * The stills found since the last call, to go into the pool: sharp ones as they're found (and
         * HDHomeRun's walls too, as its sharp stills are topped up with them). For the others, walls or a
         * lone low-res card only once done, and only when no sharp one turned up. Never more than max.
         */
        List<Choice> take(boolean done) {
            List<Choice> out = new ArrayList<>();
            while (sharpTaken < sharp.size() && given() < max) out.add(sharp.get(sharpTaken++));
            // HDHomeRun: never one small guide image stretched over the screen, so no lone card.
            boolean wallsNow = pkg.equals(HDHOMERUN) || (done && sharpTaken == 0);
            while (wallsNow && wallsTaken < walls.size() && given() < max) out.add(walls.get(wallsTaken++));
            if (done && !pkg.equals(HDHOMERUN) && given() == 0 && plain != null) {
                out.add(new Choice(Collections.singletonList(plain), null, null, plainLink));
                plain = null;
            }
            return out;
        }

        private int given() {
            return sharpTaken + wallsTaken;
        }
    }

    /**
     * The app's home screen cards, unbadged ones first, each group shuffled. Empty without the
     * permission or any cards.
     */
    private List<Card> cards(String pkg) {
        if (pkg.equals(HDHOMERUN)) {
            // What's on the user's tuner now: each title looked up for a sharp backdrop, else the guide's image.
            List<Card> cards = new ArrayList<>();
            for (HdHomeRun.Show show : hdHomeRun.onNow()) {
                Card card = new Card(show.title, false);
                card.images.add(show.image);
                card.minWidth = GUIDE_WIDTH;
                cards.add(card);
            }
            Collections.shuffle(cards, random);
            return cards;
        }
        if (pkg.equals(PLEX)) {
            // The server's own art, name and logo; Plex's old cards point at a provider the new app no longer
            // has. On Deck (what you're watching) first, the last watched first; then recently added, shuffled.
            List<Card> deck = new ArrayList<>(), added = new ArrayList<>();
            Set<String> seenArt = new HashSet<>();
            for (Plex.Item item : plex.items()) {
                if (!seenArt.add(item.art)) continue;   // a show's episodes share its art
                Card card = new Card(item.title, false);
                card.images.add(item.art);
                card.link = item.link;
                card.logo = item.logo;
                card.ownArt = true;
                (item.onDeck ? deck : added).add(card);
            }
            if (!deck.isEmpty() || !added.isEmpty()) {
                Collections.shuffle(added, random);
                deck.addAll(added);
                return deck;
            }
        }
        Set<String> sources = new HashSet<>(Collections.singletonList(pkg));
        if (pkg.equals(YOUTUBE)) Collections.addAll(sources, SMARTTUBE);
        List<Card> clean = new ArrayList<>(), badged = new ArrayList<>();
        // What you've started watching comes first, the last watched first, so it's in the pool.
        List<Card> started = new ArrayList<>();
        Map<Card, Long> engaged = new HashMap<>();
        Set<String> seen = new HashSet<>();
        for (Uri table : new Uri[]{WATCH_NEXT, PREVIEW}) {
            boolean watchNext = table == WATCH_NEXT;
            // Filtering by package in the query isn't allowed without system permissions; read all.
            try (Cursor c = resolver.query(table, watchNext ? WATCH_NEXT_COLUMNS : COLUMNS, null, null, null)) {
                while (c != null && c.moveToNext()) {
                    if (!sources.contains(c.getString(0))) continue;
                    Card card = new Card(c.getString(2), badged(c.getString(1)));
                    card.link = c.getString(1);
                    for (int i = FIRST_IMAGE; i < COLUMNS.length; i++) {   // the image columns, in both tables
                        String url = c.getString(i);
                        if (url == null || url.isEmpty() || !seen.add(url)) continue;
                        String sharper = youtubeMaxRes(url);
                        if (sharper != null && seen.add(sharper)) card.images.add(sharper);
                        card.images.add(url);
                    }
                    if (card.images.isEmpty()) continue;
                    // Removed from Continue watching (browsable 0): an ordinary card, not one you're watching.
                    if (watchNext && isStarted(c, 5) && (c.isNull(7) || c.getInt(7) != 0)) {
                        started.add(card);
                        engaged.put(card, c.isNull(6) ? 0L : c.getLong(6));
                    } else {
                        (card.badged ? badged : clean).add(card);
                    }
                }
            } catch (RuntimeException e) {
                // No permission, or the provider is missing (not a TV): no stills.
            }
        }
        if (pkg.equals(YOUTUBE) && clean.isEmpty()) {
            // No SmartTube cards: popular videos instead (with the YouTube key), else films' trailers.
            for (String id : youtubePopular.videoIds()) {
                Card card = new Card(null, false);
                card.link = "https://www.youtube.com/watch?v=" + id;
                card.images.add("https://i.ytimg.com/vi/" + id + "/maxresdefault.jpg");
                card.images.add("https://i.ytimg.com/vi/" + id + "/hq720.jpg");
                clean.add(card);
            }
        }
        if (clean.isEmpty() && badged.isEmpty() && started.isEmpty() && StreamingCatalogs.covers(pkg)) {
            // A streaming app with no cards (Netflix before anyone signs in): what's popular on it, with each
            // title's own name and logo. Opened by the app's tile, as there's no link into the app for them.
            for (StreamingCatalogs.Title t : streamingCatalogs.titles(pkg)) {
                Card card = new Card(t.name, false);
                card.images.add(t.backdrop);
                card.logo = t.logo;
                card.ownArt = true;
                clean.add(card);
            }
        }
        Collections.shuffle(clean, random);
        Collections.shuffle(badged, random);
        clean.addAll(badged);
        Collections.sort(started, (a, b) -> Long.compare(engaged.get(b), engaged.get(a)));
        started.addAll(clean);
        return started;
    }

    private static boolean isStarted(Cursor c, int typeColumn) {
        if (c.isNull(typeColumn)) return false;
        int type = c.getInt(typeColumn);
        return type == CONTINUE || type == NEXT;
    }

    /** Video sites: their card titles are video names, not films or shows to look up on TMDB. */
    private static boolean isVideoSite(String pkg) {
        if (pkg.startsWith(YOUTUBE)) return true;   // also YouTube Kids (com.google.android.youtube.tvkids)
        for (String s : SMARTTUBE) if (s.equals(pkg)) return true;
        return false;
    }

    /**
     * YouTube thumbnails come in several sizes under one video ID; the largest, maxresdefault, is
     * 1280x720 when the uploader provided one (a 404 otherwise, and the card's own size is used).
     */
    private static String youtubeMaxRes(String url) {
        if (!url.startsWith("https://i.ytimg.com/vi/")) return null;
        int end = url.indexOf('/', "https://i.ytimg.com/vi/".length());
        return end < 0 ? null : url.substring(0, end) + "/maxresdefault.jpg";
    }

    /**
     * Netflix bakes stickers such as "Recently Added" and "Top 10" into the artwork of some cards, and
     * names them in the card's link (badges=B:recently.added). Those don't work as a full-screen still.
     */
    private static boolean badged(String intentUri) {
        return intentUri != null && (intentUri.contains("badges%3D") || intentUri.contains("badges="));
    }

    /**
     * The cached file for this image, downloading it first. Null if it can't be had, or for a backdrop
     * if it isn't wide and big enough.
     */
    private File fetch(String url, boolean backdrop) {
        return fetch(url, backdrop ? MIN_WIDTH : 0);
    }

    /** As above, for a backdrop at least minWidth wide; 0 for any decodable image (a logo). */
    private File fetch(String url, int minWidth) {
        boolean backdrop = minWidth > 0;
        File file = new File(root, sha1(url) + ".img");
        if (file.exists()) {
            file.setLastModified(System.currentTimeMillis());
            return file;
        }
        byte[] data = read(url);
        if (data == null || !(backdrop ? suitable(data, minWidth) : decodable(data))) {
            unsuitable.add(url);
            return null;
        }
        root.mkdirs();
        File tmp = null;
        try {
            tmp = File.createTempFile("download", ".tmp", root);   // unique: both threads may fetch one image
            try (FileOutputStream out = new FileOutputStream(tmp)) {
                out.write(data);
            }
        } catch (IOException e) {
            if (tmp != null) tmp.delete();
            return null;
        }
        return tmp.renameTo(file) || file.exists() ? file : null;
    }

    /** A file name for a URL: a 32-bit hash could give two images the same name and show the wrong one. */
    private static String sha1(String s) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-1").digest(s.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder();
            for (byte b : d) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            return Integer.toHexString(s.hashCode());
        }
    }

    /** The image's bytes, or null when it can't be had (offline, gone, too big). */
    private byte[] read(String url) {
        try {
            if (url.startsWith("http://") || url.startsWith("https://")) return Http.get(url, MAX_IMAGE_BYTES);
            // content:// and android.resource:// art served by the app itself.
            try (InputStream in = resolver.openInputStream(Uri.parse(url))) {
                return in == null ? null : Http.readAll(in, MAX_IMAGE_BYTES);
            }
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean decodable(byte[] data) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        return o.outWidth > 0 && o.outHeight > 0;
    }

    private static boolean suitable(byte[] data, int minWidth) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeByteArray(data, 0, data.length, o);
        if (o.outWidth < minWidth || o.outHeight <= 0) return false;
        float aspect = o.outWidth / (float) o.outHeight;
        return aspect >= MIN_ASPECT && aspect <= MAX_ASPECT;
    }

    /**
     * Decoded no larger than the screen, in full colour: 16-bit colour (half the memory) turned dark,
     * smooth areas into visible bands.
     */
    private Bitmap decode(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= targetWidth) o.inSampleSize *= 2;
        o.inMutable = true;   // darkened in place
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    /**
     * Darkens a still behind the clock and the dock for legibility, with a gradient that leaves the
     * middle of the picture nearly untouched. Baked into the image once, here on the worker thread,
     * instead of a full-screen layer the GPU would paint on every frame.
     */
    private static void darken(Bitmap bitmap) {
        Canvas canvas = new Canvas(bitmap);
        Paint scrim = new Paint();
        scrim.setShader(new LinearGradient(0, 0, 0, bitmap.getHeight(),
                new int[]{0xB3000000, 0x40000000, 0xF2000000}, new float[]{0f, 0.38f, 1f}, Shader.TileMode.CLAMP));
        canvas.drawRect(0, 0, bitmap.getWidth(), bitmap.getHeight(), scrim);
    }

    /** A title logo, kept transparent and no wider than 800 px (it shows at about a third of the screen). */
    static Bitmap decodeLogo(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= 800) o.inSampleSize *= 2;
        return BitmapFactory.decodeFile(file.getPath(), o);
    }

    private static int width(File file) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        return o.outWidth;
    }

    /** Four cards in a 2x2 grid filling the screen, each centre-cropped into its cell. */
    private Bitmap wall(List<File> files) {
        Bitmap out = Bitmap.createBitmap(targetWidth, targetHeight, Bitmap.Config.ARGB_8888);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(Color.BLACK);
        Paint paint = new Paint(Paint.FILTER_BITMAP_FLAG);
        int cellW = (targetWidth - WALL_GAP) / 2, cellH = (targetHeight - WALL_GAP) / 2;
        for (int i = 0; i < files.size(); i++) {
            Bitmap card = BitmapFactory.decodeFile(files.get(i).getPath());
            if (card == null) continue;
            int left = (i % 2) * (cellW + WALL_GAP), top = (i / 2) * (cellH + WALL_GAP);
            canvas.drawBitmap(card, centreCrop(card.getWidth(), card.getHeight(), cellW, cellH),
                    new Rect(left, top, left + cellW, top + cellH), paint);
            card.recycle();
        }
        return out;
    }

    /** The part of a w x h image with the cell's aspect ratio, centred. */
    private static Rect centreCrop(int w, int h, int cellW, int cellH) {
        if (w * cellH > h * cellW) {
            int cw = h * cellW / cellH;
            return new Rect((w - cw) / 2, 0, (w + cw) / 2, h);
        }
        int ch = w * cellH / cellW;
        return new Rect(0, (h - ch) / 2, w, (h + ch) / 2);
    }

    /**
     * Drops downloads not used for two days: the pools keep their own finished copies, so no picture is
     * lost. A still is never dropped for its age, only once a newer one replaced it, so there is always a
     * picture to show. The exception: the pool of an app that can't be a tile any more,
     * because it was uninstalled, disabled or hidden. Brought back, it has no pool and gets a fresh one.
     */
    private void prune() {
        long now = System.currentTimeMillis();
        File[] files = root.listFiles();
        if (files != null) for (File f : files) if (now - f.lastModified() > CACHE_MS) f.delete();
        File[] dirs = poolRoot.listFiles();
        if (dirs == null) return;
        Set<String> apps = AppAdapter.tilePackages(context);
        if (apps.isEmpty()) return;   // the app list unreadable (early in boot?): never take that as "none"
        Set<String> keep = new HashSet<>();
        for (String pkg : apps) keep.add(sha1(pkg));   // pools are named by hash
        boolean dropped = false;
        for (File d : dirs) {
            if (keep.contains(d.getName())) continue;
            deleteTree(d);
            dropped = true;
        }
        // Forget the dropped pools too, or a cached one would still be served: read from disk again,
        // an app that comes back starts with none.
        if (dropped) {
            synchronized (this) {
                pools.clear();
            }
        }
    }
}
