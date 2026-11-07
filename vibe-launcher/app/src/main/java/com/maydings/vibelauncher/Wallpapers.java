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
 * Custom wallpapers (Settings > Backdrops > Custom wallpapers): pictures and videos of the person's own, from the
 * phone (PhoneLink), Wallhaven or Pexels (WallpaperBrowserActivity), shown on Home instead of an app's glow, or
 * instead of every backdrop (VibeSettings.wallpaperMode). Kept in files/wallpapers, not the cache, which Android
 * may empty. A picture is stored already cut to the screen and darkened as backdrops are, so showing one is a
 * quick decode; a video as it came, darkened on screen by a layer over it. Which one shows is picked at random on
 * each visit to Home, never the same twice running when there's a choice.
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

    static final String PHONE = "phone", WALLHAVEN = "wallhaven", PEXELS = "pexels";
    /** The biggest video taken (from the phone or Pexels): about a few minutes of 1080p. */
    static final long MAX_VIDEO = 500L * 1024 * 1024;
    /** Free space kept on the TV beyond a new video, so apps aren't left short. */
    private static final long KEEP_FREE = 300L * 1024 * 1024;

    private static Wallpapers instance;

    private final File dir;
    private final SharedPreferences prefs;
    private final Random random = new Random();
    /** This visit's wallpaper (pick); the last one shown, so the next visit picks another. */
    private String current, last;

    static synchronized Wallpapers get(Context context) {
        if (instance == null) instance = new Wallpapers(context.getApplicationContext());
        return instance;
    }

    private Wallpapers(Context context) {
        dir = new File(context.getFilesDir(), "wallpapers");
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        prefs = context.getSharedPreferences("wallpapers", Context.MODE_PRIVATE);
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

    /** Whether one from this source with this name (a Wallhaven or Pexels id) is in already. */
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
        if (gone.id.equals(current)) current = null;
    }

    /** This visit's wallpaper, picked the first time it's asked for after reshuffle; null when there are none. */
    synchronized Item current() {
        List<Item> all = list();
        if (all.isEmpty()) return null;
        for (Item item : all) if (item.id.equals(current)) return item;
        return pick(all);
    }

    /** Another one than now (the slideshow's next), or the same when it's the only one. */
    synchronized Item next() {
        List<Item> all = list();
        if (all.isEmpty()) return null;
        last = current;
        return pick(all);
    }

    /** Home came back: the next one asked for is picked afresh. */
    synchronized void reshuffle() {
        if (current != null) last = current;
        current = null;
    }

    private Item pick(List<Item> all) {
        List<Item> choice = new ArrayList<>(all);
        if (choice.size() > 1) {
            for (int i = choice.size() - 1; i >= 0; i--) if (choice.get(i).id.equals(last)) choice.remove(i);
        }
        Item item = choice.get(random.nextInt(choice.size()));
        current = item.id;
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
