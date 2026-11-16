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
 * Backdrop pictures and videos of the person's own (Settings > Library), from the phone (PhoneLink),
 * Wallhaven, Pexels, Pixabay, NASA or Wikimedia Commons (WallpaperBrowserActivity), each kept once, in
 * files/wallpapers, not the cache, which Android may empty. A picture is stored already cut to the screen and
 * darkened as backdrops are, so showing one is a quick decode; a video as it came, darkened on screen by a layer
 * over it.
 * <p>
 * Lists say where they show, by key: an app's package or "folder:<id>" (tile menu > Edit > Backdrops), which then
 * shows only from its list, whatever else is set; and ROTATION, Settings > Backdrops > Custom wallpapers' own,
 * shown instead of an app's glow or of every backdrop (VibeSettings.wallpaperMode). A new one is on no list until
 * it's ticked for one. Which one of a list shows is picked at random on each visit to Home, never the same twice
 * running when there's a choice; with one, it's always that one.
 */
final class Wallpapers {

    /** One wallpaper: its id (also its file's name), a video or not, where it came from, and a name to list. */
    static final class Item {
        final String id, source, name;
        final boolean video;

        Item(String id, boolean video, String source, String name) {
            this.id = id;
            this.video = video;
            this.source = source;
            this.name = name;
        }
    }

    static final String PHONE = "phone", WALLHAVEN = "wallhaven", PEXELS = "pexels", PIXABAY = "pixabay",
            NASA = "nasa", COMMONS = "commons";
    /** The biggest video taken (from the phone or a browsed source): about a few minutes of 1080p. */
    static final long MAX_VIDEO = 500L * 1024 * 1024;
    /** Free space kept on the TV beyond a new video, so apps aren't left short. */
    private static final long KEEP_FREE = 300L * 1024 * 1024;

    /** The list of Settings > Backdrops > Custom wallpapers (not an app's or a folder's). */
    static final String ROTATION = "*";

    private static Wallpapers instance;

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
        dir = new File(context.getFilesDir(), "wallpapers");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        prefs = context.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
        lists();   // made now, the first time, with every one there is before any new one comes
    }

    /** Where they're kept; a new file goes here (a .part while it arrives) before add. */
    File dir() {
        return dir;
    }

    /** list() as last read: asked for on nearly every key on Home, so read once; add and delete clear it. */
    private List<Item> listed;

    /** All of them, oldest first. */
    synchronized List<Item> list() {
        if (listed != null) return new ArrayList<>(listed);
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item item = new Item(o.getString("id"), o.optBoolean("video"), o.optString("source"), o.optString("name"));
                if (file(item).exists()) out.add(item);
            }
        } catch (JSONException e) {
            // unreadable: none
        }
        listed = out;
        return new ArrayList<>(out);
    }

    boolean isEmpty() {
        return list().isEmpty();
    }

    /** Whether one from this source with this name (its id there) is in already. */
    boolean has(String source, String name) {
        for (Item item : list()) if (item.source.equals(source) && item.name.equals(name)) return true;
        return false;
    }

    /** The file shown for it: the finished picture, or the video. */
    File file(Item item) {
        return new File(dir, item.id + (item.video ? ".mp4" : ".jpg"));
    }

    /** A new file's place while it arrives, for a download or an upload to write to, then add. */
    File incoming() {
        return new File(dir, System.currentTimeMillis() + ".part");
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
     * Adds a picture (any size: cut to this screen and darkened, then saved) or a video (moved in as it is). The
     * file it came in is gone afterwards. Null when it couldn't be used. Worker thread.
     */
    Item add(File arrived, boolean video, String source, String name, int screenW, int screenH) {
        String id = String.valueOf(System.currentTimeMillis());
        Item item = new Item(id, video, source, name);
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
        } catch (IOException | RuntimeException e) {
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
                a.put(new JSONObject().put("id", id).put("video", video).put("source", source).put("name", name));
                prefs.edit().putString("items", a.toString()).apply();
                listed = null;
            } catch (JSONException e) {
                return null;
            }
        }
        return item;
    }

    /** Takes it out, file and all. */
    synchronized void delete(Item gone) {
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

    /** The lists by key; the first time, Custom wallpapers' holds every one there is. */
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
        for (Item item : list()) all.put(item.id);
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
        for (Item item : list()) if (ids.contains(item.id)) out.add(item);
        return out;
    }

    /** Whether this list has any to show. */
    boolean hasList(String key) {
        return key != null && !listOf(key).isEmpty();
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
    }

    /** A folder gone: its list too. */
    synchronized void forgetList(String key) {
        lists().remove(key);
        current.remove(key);
        last.remove(key);
        saveLists();
    }

    /** Every list as it is now, to tell when one changed (Home, back from Settings or a grid). */
    synchronized String listsState() {
        return lists().toString();
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

    /** A picture cut to the screen (centre-crop) and darkened like a backdrop; null when it isn't one. */
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
        Backdrops.darken(out);
        return out;
    }

    /** "A picture from the phone", for the list: what it is and where it came from (the strings say it). */
    static List<Item> sorted(List<Item> items) {
        List<Item> out = new ArrayList<>(items);
        Collections.reverse(out);   // newest first
        return out;
    }
}
