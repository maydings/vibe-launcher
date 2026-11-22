package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Random;

/**
 * The Library (Settings > Library): the person's own pictures and videos, from the phone (PhoneLink), Wallhaven,
 * Pexels, Pixabay, NASA, Wikimedia Commons or Apple's aerials (WallpaperBrowserActivity), each kept once and
 * usable anywhere: as a tile's picture (TileCustom) or behind Home. Those cut to the screen, and the videos, are
 * in files/wallpapers, not the cache, which Android may empty; the tile pictures, framed small on the phone, are
 * TilePictures', and listed here with the rest (small). A picture is stored as it looks, cut to the screen, and
 * darkened as backdrops are when it shows as one (backdrop); a video as it came, darkened on screen by a layer
 * over it.
 * <p>
 * Lists say what shows where, by key, each naming Library items by id: an app's package or "folder:<id>" (tile
 * menu > Edit > Backdrops), the tile's own; ROTATION, Settings > Backdrops > System backdrop's own, shown for
 * an app with no picture or for a tile set to them; and SCREENSAVER, the slideshow's own when it's set to show it
 * (VibeSettings.slideshowFrom). What a tile shows is its choice (AUTO, or one thing alone). A new item is on no
 * list until it's ticked for one. Which one of a list shows is picked at random on each visit to Home, never the
 * same twice running when there's a choice; with one, it's always that one.
 */
final class Wallpapers {

    /**
     * One thing in the Library: its id (also its file's name), a video or not, where it came from (source) and
     * its id there (name), and what it's called (title: the source's name for it, or the one typed on the phone;
     * "" for none). small: a tile picture (TilePictures: 640 x 360), soft as a backdrop. dark: a picture saved
     * already darkened as a backdrop, dim on a tile.
     */
    static final class Item {
        final String id, source, name, title;
        final boolean video, small, dark;

        Item(String id, boolean video, String source, String name, String title, boolean small, boolean dark) {
            this.id = id;
            this.video = video;
            this.source = source;
            this.name = name;
            this.title = title == null ? "" : title;
            this.small = small;
            this.dark = dark;
        }
    }

    static final String PHONE = "phone", WALLHAVEN = "wallhaven", PEXELS = "pexels", PIXABAY = "pixabay",
            NASA = "nasa", COMMONS = "commons", APPLE = "apple";
    /** The source of a tile picture (small): from the phone, framed there for a tile. */
    static final String TILE = "tile";
    /**
     * What a tile can show behind Home, each a letter of its order (order): the app's own pictures (CONTENT), the
     * tile's own list of the Library's (OWN), the system backdrop (SYSTEM), the flights overhead (FLIGHTS) and
     * its colour (COLOUR). The system backdrop is itself one of two (systemOrder): its list of the Library's
     * (GALLERY), or the flights. In an order a capital is enabled, a small letter disabled.
     */
    static final char CONTENT = 'c', OWN = 'o', SYSTEM = 's', FLIGHTS = 'f', COLOUR = 'k', GALLERY = 'g';
    /** A tile's order left alone: flights disabled, its colour last. The system backdrop's: neither enabled. */
    static final String TILE_ORDER = "COSfK", SYSTEM_ORDER = "gf";
    /** What a device's own data may keep for a tile in place of an order: one thing alone (firstOrders). */
    private static final int AUTO = 0, ONLY_CONTENT = 1, ONLY_SYSTEM = 2, ONLY_COLOUR = 3, ONLY_OWN = 4;
    /** The longest a title is kept. */
    static final int MAX_TITLE = 60;
    /** The biggest video taken (from the phone or a browsed source): about a few minutes of 1080p. */
    static final long MAX_VIDEO = 500L * 1024 * 1024;
    /** Free space kept on the TV beyond a new video, so apps aren't left short. */
    private static final long KEEP_FREE = 300L * 1024 * 1024;

    /** The list of Settings > Backdrops > System backdrop (not an app's or a folder's). */
    static final String ROTATION = "*";
    /** The slideshow's own list (Settings > Appearance > Screensaver backdrops): no package, which has a dot. */
    static final String SCREENSAVER = "screensaver";

    @android.annotation.SuppressLint("StaticFieldLeak")   // holds the application's context, never an activity's
    private static Wallpapers instance;

    private final Context context;
    private final File dir;
    private final SharedPreferences prefs;
    private final Random random = new Random();
    /** Each list's wallpaper this visit (pick), and the last one it showed, so the next visit picks another. */
    private final java.util.Map<String, String> current = new java.util.HashMap<>(), last = new java.util.HashMap<>();
    /** The lists by key, as last read (lists()). */
    private JSONObject lists;

    static synchronized Wallpapers get(Context context) {
        if (instance == null) instance = new Wallpapers(context.getApplicationContext());
        return instance;
    }

    private Wallpapers(Context context) {
        this.context = context;
        dir = new File(context.getFilesDir(), "wallpapers");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        // What was still arriving when the launcher last stopped (a power cut, an update): never finished, and
        // in nobody's Library to be deleted from. Now, before anything new starts to arrive.
        File[] unfinished = dir.listFiles((d, name) -> name.endsWith(".part"));
        if (unfinished != null) for (File f : unfinished) //noinspection ResultOfMethodCallIgnored
            f.delete();
        prefs = context.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        lists();   // made now, the first time, with every one there is before any new one comes
        firstOrders();
    }

    /** How many times the tile pictures have changed: list() is read again when it has moved on. */
    private static final java.util.concurrent.atomic.AtomicInteger tileChanges =
            new java.util.concurrent.atomic.AtomicInteger();

    /**
     * The tile pictures changed (TilePictures): list() is read again. Takes no lock: it's called with the
     * Library's held (delete, setTitle) and from other threads (a phone's upload) alike.
     */
    static void tilePicturesChanged() {
        tileChanges.incrementAndGet();
    }

    /** Where they're kept; a new file goes here (a .part while it arrives) before add. */
    File dir() {
        return dir;
    }

    /** The items as last read: asked for on nearly every key on Home, so read once; add and delete clear it. */
    private List<Item> listed;
    /** tileChanges when listed was read. */
    private int listedAt;

    /** All of them, oldest first: a copy, the caller's own. */
    synchronized List<Item> list() {
        return new ArrayList<>(items());
    }

    /** All of them, oldest first, as kept here: read only, under the Library's lock. */
    private List<Item> items() {
        int now = tileChanges.get();
        if (listed != null && listedAt == now) return listed;
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                boolean video = o.optBoolean("video");
                Item item = new Item(o.getString("id"), video, o.optString("source"), o.optString("name"),
                        o.optString("title"), false, !video && !o.optBoolean("plain"));
                if (file(item).exists()) out.add(item);
            }
        } catch (JSONException e) {
            // unreadable: none
        }
        // The tile pictures are in the same Library: kept where a backup carries them, listed here with the rest.
        TilePictures tiles = new TilePictures(context);
        for (String id : tiles.list()) out.add(new Item(id, false, TILE, "", tiles.title(id), true, false));
        listed = out;
        listedAt = now;
        return out;
    }

    boolean isEmpty() {
        return list().isEmpty();
    }

    /** Whether one from this source with this name (its id there) is in already. */
    boolean has(String source, String name) {
        for (Item item : list()) if (item.source.equals(source) && item.name.equals(name)) return true;
        return false;
    }

    /** Its file: the picture (cut to the screen, or a tile's), or the video. */
    File file(Item item) {
        if (item.small) return new TilePictures(context).file(item.id);
        return new File(dir, item.id + (item.video ? ".mp4" : ".jpg"));
    }

    /** Where a video's thumbnail is kept, beside it. */
    private File thumbFile(Item item) {
        return new File(dir, item.id + ".thumb");
    }

    /** A video's thumbnail, when one is kept; else null. */
    Bitmap thumb(Item item) {
        File kept = thumbFile(item);
        return kept.length() > 0 ? BitmapFactory.decodeFile(kept.getPath()) : null;
    }

    /** Keeps a video's thumbnail (cell-sized), for the grids to show without reading the video. */
    void keepThumb(Item item, Bitmap thumb) {
        File kept = thumbFile(item);
        try (FileOutputStream out = new FileOutputStream(kept)) {
            thumb.compress(Bitmap.CompressFormat.JPEG, 85, out);
        } catch (IOException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            kept.delete();   // made again next time
        }
    }

    /** The one with this id, or null. */
    synchronized Item item(String id) {
        if (id == null) return null;
        for (Item item : items()) if (item.id.equals(id)) return item;
        return null;
    }

    /**
     * A picture as a backdrop: darkened here as backdrops are (Backdrops.darken), unless it was saved so. Shown
     * scaled to the screen by the view, so a tile picture comes out soft. Null when it can't be read. Worker thread.
     */
    Bitmap backdrop(Item item) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inMutable = !item.dark;
        try {
            Bitmap b = BitmapFactory.decodeFile(file(item).getPath(), o);
            if (b != null && !item.dark) Backdrops.darken(b);
            return b;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Free space where the wallpapers are kept. */
    long free() {
        return dir.getUsableSpace();
    }

    private static long lastStamp;

    /** The time now, for a new file's name: never the same twice, though two be made in one millisecond. */
    private static synchronized long stamp() {
        lastStamp = Math.max(System.currentTimeMillis(), lastStamp + 1);
        return lastStamp;
    }

    /** A new file's place while it arrives, for a download or an upload to write to, then add. */
    File incoming() {
        return new File(dir, stamp() + ".part");
    }

    /** Whether there's room for a video this big, with some space left over for the TV. */
    boolean roomFor(long bytes) {
        return bytes <= MAX_VIDEO && dir.getUsableSpace() > bytes + KEEP_FREE;
    }

    /** The biggest video that can come in now (its size not known before): space left over kept for the TV. */
    long videoRoom() {
        return Math.max(0, Math.min(MAX_VIDEO, dir.getUsableSpace() - KEEP_FREE));
    }

    /**
     * Adds a picture (any size: cut to this screen, then saved as it looks, to be darkened when it shows as a
     * backdrop) or a video (moved in as it is), with what it's called (title, "" for none). The file it came in is
     * gone afterwards. Null when it couldn't be used. Worker thread.
     */
    Item add(File arrived, boolean video, String source, String name, String title, int screenW, int screenH) {
        String id = String.valueOf(stamp());
        String called = title == null ? "" : title.replaceAll("\\s+", " ").trim();
        if (called.length() > MAX_TITLE) called = called.substring(0, MAX_TITLE).trim();
        Item item = new Item(id, video, source, name, called, false, false);
        File to = file(item);
        try {
            if (video) {
                if (!isVideo(arrived) || !arrived.renameTo(to)) return null;
            } else {
                Bitmap still = render(arrived, screenW, screenH);
                if (still == null) return null;
                try (FileOutputStream out = new FileOutputStream(to)) {
                    still.compress(Bitmap.CompressFormat.JPEG, 92, out);
                } finally {
                    still.recycle();
                }
            }
        } catch (IOException | RuntimeException | OutOfMemoryError e) {   // a picture too big to open, too
            //noinspection ResultOfMethodCallIgnored
            to.delete();
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            arrived.delete();
        }
        synchronized (this) {
            try {
                JSONArray a = new JSONArray(prefs.getString("items", "[]"));
                a.put(new JSONObject().put("id", id).put("video", video).put("source", source).put("name", name)
                        .put("title", called).put("plain", !video));
                prefs.edit().putString("items", a.toString()).apply();
                listed = null;
            } catch (JSONException e) {
                return null;
            }
        }
        return item;
    }

    /** What it's called from now on ("" for nothing: it's listed by where it came from). */
    synchronized void setTitle(Item item, String title) {
        if (item.small) {
            new TilePictures(context).setTitle(item.id, title);
            listed = null;
            return;
        }
        try {
            JSONArray a = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if (o.optString("id").equals(item.id)) o.put("title", title == null ? "" : title);
            }
            prefs.edit().putString("items", a.toString()).apply();
            listed = null;
        } catch (JSONException ignored) {
        }
    }

    /** Takes it out, file and all, off every list and every tile that showed it. */
    synchronized void delete(Item gone) {
        if (gone.small) {
            new TilePictures(context).delete(gone.id);   // its file, and off the tiles showing it
            listed = null;
            offLists(gone);
            return;
        }
        new TileCustom(context).forgetPicture(gone.id);
        try {
            JSONArray a = new JSONArray(prefs.getString("items", "[]")), kept = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                if (!a.getJSONObject(i).optString("id").equals(gone.id)) kept.put(a.getJSONObject(i));
            }
            prefs.edit().putString("items", kept.toString()).apply();
        } catch (JSONException ignored) {
        }
        listed = null;
        //noinspection ResultOfMethodCallIgnored
        file(gone).delete();
        //noinspection ResultOfMethodCallIgnored
        thumbFile(gone).delete();
        offLists(gone);
    }

    private void offLists(Item gone) {
        JSONObject all = lists();
        for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
            String key = it.next();
            if (gone.id.equals(current.get(key))) current.remove(key);
            JSONArray a = all.optJSONArray(key);
            try {
                if (a != null) all.put(key, without(a, gone.id));
            } catch (JSONException ignored) {
            }
        }
        saveLists();
    }

    // ---- lists ---------------------------------------------------------------------------------------

    /** The lists by key; the first time, the System backdrop's holds every one there is. */
    private synchronized JSONObject lists() {
        if (lists != null) return lists;
        String saved = prefs.getString("lists", null);
        try {
            if (saved != null) {
                lists = new JSONObject(saved);
                return lists;
            }
        } catch (JSONException e) {
            // unreadable: as the first time
        }
        lists = new JSONObject();
        JSONArray all = new JSONArray();
        for (Item item : list()) if (!item.small) all.put(item.id);
        try {
            lists.put(ROTATION, all);
        } catch (JSONException ignored) {
        }
        saveLists();
        return lists;
    }

    private void saveLists() {
        prefs.edit().putString("lists", lists().toString()).apply();
    }

    private static JSONArray without(JSONArray a, String id) {
        JSONArray kept = new JSONArray();
        for (int i = 0; i < a.length(); i++) if (!id.equals(a.optString(i))) kept.put(a.optString(i));
        return kept;
    }

    /** What's on this list, in the order they were added (only those still here). */
    synchronized List<Item> listOf(String key) {
        JSONArray a = lists().optJSONArray(key);
        List<Item> out = new ArrayList<>();
        if (a == null || a.length() == 0) return out;
        java.util.Set<String> ids = new java.util.HashSet<>();
        for (int i = 0; i < a.length(); i++) ids.add(a.optString(i));
        for (Item item : items()) if (ids.contains(item.id)) out.add(item);
        return out;
    }

    /** Whether this list has any to show: asked on every key on Home, so nothing is copied for it. */
    synchronized boolean hasList(String key) {
        JSONArray a = key == null ? null : lists().optJSONArray(key);
        if (a == null || a.length() == 0) return false;
        for (Item item : items()) {
            for (int i = 0; i < a.length(); i++) if (item.id.equals(a.optString(i))) return true;
        }
        return false;
    }

    synchronized boolean inList(String key, String id) {
        JSONArray a = lists().optJSONArray(key);
        if (a == null) return false;
        for (int i = 0; i < a.length(); i++) if (id.equals(a.optString(i))) return true;
        return false;
    }

    /** Ticks it on this list or takes it off. */
    synchronized void setInList(String key, String id, boolean in) {
        JSONArray a = lists().optJSONArray(key);
        if (a == null) a = new JSONArray();
        a = without(a, id);
        if (in) a.put(id);
        try {
            if (a.length() == 0 && !ROTATION.equals(key)) lists().remove(key);
            else lists().put(key, a);
        } catch (JSONException ignored) {
        }
        if (!in && id.equals(current.get(key))) current.remove(key);
        saveLists();
        // The first one ticked for a list enables it where it's used, if it was disabled.
        if (!in || a.length() != 1) return;
        if (ROTATION.equals(key)) setSystemOrder(enabled(systemOrder(), GALLERY, true));
        else if (!SCREENSAVER.equals(key)) setOrder(key, enabled(order(key), OWN, true));
    }

    /** A folder gone: its list too, and what it was set to show. */
    synchronized void forgetList(String key) {
        lists().remove(key);
        current.remove(key);
        last.remove(key);
        saveLists();
        setOrder(key, TILE_ORDER);
    }

    /** Every list and order as it is now, to tell when one changed (Home, back from Settings or a grid). */
    synchronized String listsState() {
        return lists().toString() + orders().toString() + systemOrder();
    }

    // ---- what each tile shows --------------------------------------------------------------------------

    /** The tiles' orders by key (those not left alone), as last read. */
    private JSONObject orders;

    private synchronized JSONObject orders() {
        if (orders != null) return orders;
        try {
            orders = new JSONObject(prefs.getString("orders", "{}"));
        } catch (JSONException e) {
            orders = new JSONObject();   // unreadable: every tile left alone
        }
        return orders;
    }

    /** An order with each of these letters once, in its own order first; any missing added as it is in these. */
    static String whole(String order, String all) {
        StringBuilder made = new StringBuilder();
        if (order != null) {
            for (char c : order.toCharArray()) {
                boolean known = all.toLowerCase(java.util.Locale.ROOT).indexOf(Character.toLowerCase(c)) >= 0;
                if (known && at(made.toString(), c) < 0) made.append(c);
            }
        }
        for (char c : all.toCharArray()) if (at(made.toString(), c) < 0) made.append(c);
        return made.toString();
    }

    /** Where this thing is in an order (enabled or not), or -1. */
    static int at(String order, char what) {
        for (int i = 0; i < order.length(); i++) {
            if (Character.toLowerCase(order.charAt(i)) == Character.toLowerCase(what)) return i;
        }
        return -1;
    }

    /** Whether this thing is enabled in an order. */
    static boolean on(String order, char what) {
        int i = at(order, what);
        return i >= 0 && Character.isUpperCase(order.charAt(i));
    }

    /** The order with this thing enabled or disabled; a tile's colour stays enabled. */
    static String enabled(String order, char what, boolean on) {
        int i = at(order, what);
        if (i < 0) return order;
        char c = on || Character.toLowerCase(what) == COLOUR ? Character.toUpperCase(what) : Character.toLowerCase(what);
        return order.substring(0, i) + c + order.substring(i + 1);
    }

    /** The order with the thing at this place moved one up (-1) or down. */
    static String moved(String order, int from, int dir) {
        int to = from + dir;
        if (from < 0 || to < 0 || from >= order.length() || to >= order.length()) return order;
        char[] c = order.toCharArray();
        char t = c[from];
        c[from] = c[to];
        c[to] = t;
        return new String(c);
    }

    /**
     * What this tile (an app's package, or a folder's key) shows, first to last (tile menu > Edit > Backdrops):
     * the first that's enabled and has something to show is what shows (pick). Left alone: TILE_ORDER.
     */
    synchronized String order(String key) {
        if (key == null) return TILE_ORDER;
        return enabled(whole(orders().optString(key, TILE_ORDER), TILE_ORDER), COLOUR, true);
    }

    /** Whether this tile's order is its own, not left alone. */
    synchronized boolean hasOrder(String key) {
        return key != null && orders().has(key);
    }

    synchronized void setOrder(String key, String order) {
        if (key == null) return;
        try {
            if (TILE_ORDER.equals(order)) orders().remove(key);
            else orders().put(key, order);
        } catch (JSONException ignored) {
        }
        prefs.edit().putString("orders", orders().toString()).apply();
    }

    /**
     * The system backdrop (Settings > Backdrops > System backdrop): its list of the Library's and the flights,
     * first to last, neither enabled until chosen; the first enabled with something to show is it (systemPick).
     */
    synchronized String systemOrder() {
        String kept = prefs.getString("systemOrder", null);
        // None kept: a list with something ticked for it is what it shows.
        if (kept == null) return hasList(ROTATION) ? enabled(SYSTEM_ORDER, GALLERY, true) : SYSTEM_ORDER;
        return whole(kept, SYSTEM_ORDER);
    }

    synchronized void setSystemOrder(String order) {
        prefs.edit().putString("systemOrder", order).apply();
    }

    /** What the system backdrop is now: GALLERY, FLIGHTS (when they can show: flights), or 0 for nothing. */
    synchronized char systemPick(boolean flights) {
        for (char c : systemOrder().toCharArray()) {
            if (c == Character.toUpperCase(GALLERY) && hasList(ROTATION)) return GALLERY;
            if (c == Character.toUpperCase(FLIGHTS) && flights) return FLIGHTS;
        }
        return 0;
    }

    /**
     * What this tile shows now: the first in its order that's enabled and has something. CONTENT (when its app
     * may have pictures: content), OWN, GALLERY (the system backdrop's list), FLIGHTS (its own, or as the system
     * backdrop; when they can show: flights) or COLOUR.
     */
    synchronized char pick(String key, boolean content, boolean flights) {
        for (char c : order(key).toCharArray()) {
            if (!Character.isUpperCase(c)) continue;
            c = Character.toLowerCase(c);
            if (c == CONTENT && content) return CONTENT;
            if (c == OWN && key != null && hasList(key)) return OWN;
            if (c == FLIGHTS && flights) return FLIGHTS;
            if (c == SYSTEM) {
                char system = systemPick(flights);
                if (system != 0) return system;
            }
            if (c == COLOUR) return COLOUR;
        }
        return COLOUR;
    }

    /** Whether the flights are enabled anywhere: as the system backdrop's, or in a tile's own order. */
    synchronized boolean flightsUsed() {
        if (on(systemOrder(), FLIGHTS)) return true;
        for (java.util.Iterator<String> it = orders().keys(); it.hasNext(); ) {
            if (on(orders().optString(it.next(), ""), FLIGHTS)) return true;
        }
        return false;
    }

    /** The order of a tile set to show the system backdrop before anything else. */
    static String systemFirst() {
        return "SCOfK";
    }

    /**
     * A Library with no orders kept yet gets them from what a device's own data may keep instead, to show what
     * it showed. One thing chosen for a tile: that alone, then its colour. Or lists and the System backdrop's
     * switch (VibeSettings.wallpaperShowKept): a tile with a list of its own shows that alone; a switch that's
     * off unticks the System backdrop's list, so none show; one set to always puts the system backdrop first
     * on the dock's tiles once Home knows them (pendingAlways).
     */
    private synchronized void firstOrders() {
        if (prefs.contains("orders")) return;
        JSONObject made = new JSONObject();
        JSONObject all = lists();
        android.content.SharedPreferences.Editor edit = prefs.edit();
        try {
            if (prefs.contains("choices")) {
                JSONObject chosen = new JSONObject(prefs.getString("choices", "{}"));
                for (java.util.Iterator<String> it = chosen.keys(); it.hasNext(); ) {
                    String key = it.next();
                    int c = chosen.optInt(key, AUTO);
                    if (c == ONLY_CONTENT) made.put(key, "CosfK");
                    else if (c == ONLY_SYSTEM && hasList(ROTATION)) made.put(key, "ScofK");
                    else if (c == ONLY_COLOUR) made.put(key, "Kcosf");
                    else if (c == ONLY_OWN && hasList(key)) made.put(key, "OcsfK");
                }
            } else {
                for (java.util.Iterator<String> it = all.keys(); it.hasNext(); ) {
                    String key = it.next();
                    JSONArray a = all.optJSONArray(key);
                    if (!ROTATION.equals(key) && !SCREENSAVER.equals(key) && a != null && a.length() > 0) {
                        made.put(key, "OcsfK");
                    }
                }
                int show = new VibeSettings(context).wallpaperShowKept();
                edit.putBoolean("always", show == VibeSettings.SHOW_KEPT_ALWAYS && hasList(ROTATION));
                if (show == VibeSettings.SHOW_KEPT_OFF) all.put(ROTATION, new JSONArray());
                edit.putString("lists", all.toString());
            }
        } catch (JSONException ignored) {
        }
        orders = made;
        edit.putString("orders", made.toString()).apply();
    }

    /** The System backdrop's switch said always: the dock's tiles left alone are to have it first (systemFirst). */
    boolean pendingAlways() {
        return prefs.getBoolean("always", false);
    }

    void donePendingAlways() {
        prefs.edit().remove("always").apply();
    }

    /** This visit's one from the list, picked the first time it's asked for after reshuffle; null when it's empty. */
    synchronized Item current(String key) {
        List<Item> all = listOf(key);
        if (all.isEmpty()) return null;
        for (Item item : all) if (item.id.equals(current.get(key))) return item;
        return pick(key, all);
    }

    /** Another one from the list than now (the slideshow's next), or the same when it's the only one. */
    synchronized Item next(String key) {
        List<Item> all = listOf(key);
        if (all.isEmpty()) return null;
        last.put(key, current.get(key));
        return pick(key, all);
    }

    /**
     * The one before or after this visit's (direction -1 or 1), in the order they were added, going round: Left
     * and Right on the picture alone. Null when there's no other.
     */
    synchronized Item step(String key, int direction) {
        List<Item> all = listOf(key);
        if (all.size() < 2) return null;
        int at = 0;
        for (int i = 0; i < all.size(); i++) if (all.get(i).id.equals(current.get(key))) at = i;
        Item item = all.get(((at + direction) % all.size() + all.size()) % all.size());
        last.put(key, current.get(key));
        current.put(key, item.id);
        return item;
    }

    /** How many there are, on no list or any. */
    int count() {
        return list().size();
    }

    /** Home came back: the next one asked for, from each list, is picked afresh. */
    synchronized void reshuffle() {
        last.putAll(current);
        current.clear();
    }

    private Item pick(String key, List<Item> all) {
        List<Item> choice = new ArrayList<>(all);
        String before = last.get(key);
        if (choice.size() > 1) {
            for (int i = choice.size() - 1; i >= 0; i--) if (choice.get(i).id.equals(before)) choice.remove(i);
        }
        Item item = choice.get(random.nextInt(choice.size()));
        current.put(key, item.id);
        return item;
    }

    /** Videos smaller than 720p (on their shorter side) aren't taken: a phone's in-page recording comes out tiny. */
    static final int MIN_VIDEO_SIDE = 720;

    /** A video's width and height as stored (before any rotation), or null when it can't be read. */
    static int[] videoSize(File file) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(file.getPath());
            int w = Integer.parseInt(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH));
            int h = Integer.parseInt(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT));
            return new int[]{w, h};
        } catch (RuntimeException e) {
            return null;
        } finally {
            try {
                r.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    /** Whether the TV reads a video track in it (not some other file sent as one). */
    private static boolean isVideo(File file) {
        android.media.MediaMetadataRetriever r = new android.media.MediaMetadataRetriever();
        try {
            r.setDataSource(file.getPath());
            return "yes".equals(r.extractMetadata(android.media.MediaMetadataRetriever.METADATA_KEY_HAS_VIDEO));
        } catch (RuntimeException e) {
            return false;
        } finally {
            try {
                r.release();
            } catch (IOException | RuntimeException ignored) {
            }
        }
    }

    /** A picture cut to the screen (centre-crop), as it looks; null when it isn't one. */
    private static Bitmap render(File file, int w, int h) {
        BitmapFactory.Options o = new BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        while (o.outWidth / (o.inSampleSize * 2) >= w && o.outHeight / (o.inSampleSize * 2) >= h) o.inSampleSize *= 2;
        Bitmap src = BitmapFactory.decodeFile(file.getPath(), o);
        if (src == null) return null;
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888);
        float scale = Math.max(w / (float) src.getWidth(), h / (float) src.getHeight());
        int cw = Math.round(w / scale), ch = Math.round(h / scale);
        int cx = (src.getWidth() - cw) / 2, cy = (src.getHeight() - ch) / 2;
        new Canvas(out).drawBitmap(src, new Rect(cx, cy, cx + cw, cy + ch), new Rect(0, 0, w, h),
                new Paint(Paint.FILTER_BITMAP_FLAG));
        src.recycle();
        return out;
    }

    /** "A picture from the phone", for the list: what it is and where it came from (the strings say it). */
    static List<Item> sorted(List<Item> items) {
        List<Item> out = new ArrayList<>(items);
        Collections.reverse(out);   // newest first
        return out;
    }
}
