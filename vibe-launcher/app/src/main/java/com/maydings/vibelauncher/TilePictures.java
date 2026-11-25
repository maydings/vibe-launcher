package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import org.json.JSONArray;
import org.json.JSONException;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Tile pictures: the Library's small pictures (Wallpapers lists them with the rest), framed for a tile on the
 * phone (PhoneLink's tile page), each kept once, at a tile's 16:9 (PICTURE_W x PICTURE_H), in
 * files/tiles/pic_&lt;id&gt;.png; which tile shows which is TileCustom's ("pic"). The list, and what each is
 * called, are in the "tiles" preferences, so a backup carries them with the files (Backup).
 */
final class TilePictures {

    static final int PICTURE_W = 640, PICTURE_H = 360;
    private static final String PREFS = "tiles";
    private static final String KEY = "pictures", NAMES = "pictureNames";
    private static final String PREFIX = "pic_";

    private final Context context;
    private final SharedPreferences prefs;
    private final File dir;

    TilePictures(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dir = new File(context.getFilesDir(), "tiles");
    }

    /** All of them, oldest first, those whose file is there. */
    List<String> list() {
        synchronized (TilePictures.class) {
            List<String> out = new ArrayList<>();
            for (String id : ids()) if (file(id).exists()) out.add(id);
            return out;
        }
    }

    private List<String> ids() {
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < a.length(); i++) out.add(a.getString(i));
        } catch (JSONException e) {
            // unreadable: none
        }
        return out;
    }

    private void save(List<String> ids) {
        prefs.edit().putString(KEY, new JSONArray(ids).toString()).apply();
    }

    File file(String id) {
        return new File(dir, PREFIX + id + ".png");
    }

    /** What it's called (typed on the phone), or "" for nothing. */
    String title(String id) {
        synchronized (TilePictures.class) {
            return names().optString(id, "");
        }
    }

    void setTitle(String id, String title) {
        synchronized (TilePictures.class) {
            org.json.JSONObject names = names();
            String called = title == null ? "" : title.replaceAll("\\s+", " ").trim();
            if (called.length() > Wallpapers.MAX_TITLE) called = called.substring(0, Wallpapers.MAX_TITLE).trim();
            try {
                if (called.isEmpty()) names.remove(id);
                else names.put(id, called);
            } catch (JSONException e) {
                return;
            }
            prefs.edit().putString(NAMES, names.toString()).apply();
            Wallpapers.tilePicturesChanged();
        }
    }

    private org.json.JSONObject names() {
        try {
            return new org.json.JSONObject(prefs.getString(NAMES, "{}"));
        } catch (JSONException e) {
            return new org.json.JSONObject();   // unreadable: none named
        }
    }

    /**
     * A picture for a tile, from its file: a tile picture as it is, or one of the Library's cut to the screen,
     * read small and brought to the tile's size. Null when it can't be read.
     */
    /** Tile pictures as last made, by their file and its time: the tiles are made again often, the files seldom. */
    private static final java.util.Map<String, Object[]> made = new java.util.HashMap<>();

    static Bitmap forTile(File file) {
        String path = file.getPath();
        long time = file.lastModified();
        synchronized (made) {
            Object[] kept = made.get(path);
            if (kept != null && (Long) kept[0] == time && !((Bitmap) kept[1]).isRecycled()) return (Bitmap) kept[1];
        }
        Bitmap picture = decodeForTile(file);
        synchronized (made) {
            if (made.size() >= 32) made.clear();   // more than any Home has: begun again
            if (picture != null) made.put(path, new Object[]{time, picture});
            else made.remove(path);
        }
        return picture;
    }

    private static Bitmap decodeForTile(File file) {
        android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
        o.inJustDecodeBounds = true;
        android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
        if (o.outWidth <= 0 || o.outHeight <= 0) return null;
        o.inJustDecodeBounds = false;
        o.inSampleSize = 1;
        long maxPixels = 4L * PICTURE_W * PICTURE_H;
        while ((o.outWidth / (o.inSampleSize * 2) >= PICTURE_W && o.outHeight / (o.inSampleSize * 2) >= PICTURE_H)
                || ((long) (o.outWidth / o.inSampleSize) * (o.outHeight / o.inSampleSize) > maxPixels)) {
            o.inSampleSize *= 2;
        }
        try {
            Bitmap read = android.graphics.BitmapFactory.decodeFile(file.getPath(), o);
            if (read == null || (read.getWidth() <= PICTURE_W && read.getHeight() <= PICTURE_H)) return read;
            // The middle of it at the tile's shape, as a backdrop is cut to the screen.
            float scale = Math.max(PICTURE_W / (float) read.getWidth(), PICTURE_H / (float) read.getHeight());
            int cw = Math.min(read.getWidth(), Math.round(PICTURE_W / scale));
            int ch = Math.min(read.getHeight(), Math.round(PICTURE_H / scale));
            Bitmap cut = Bitmap.createBitmap(read, (read.getWidth() - cw) / 2, (read.getHeight() - ch) / 2, cw, ch);
            Bitmap sized = Bitmap.createScaledBitmap(cut, PICTURE_W, PICTURE_H, true);
            if (cut != read && cut != sized) cut.recycle();
            if (sized != read) read.recycle();
            return sized;
        } catch (RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    /** Keeps the picture, scaled to the tile's size: its id, or null when it couldn't be saved. */
    String add(Bitmap picture) {
        synchronized (TilePictures.class) {
            if (!dir.isDirectory() && !dir.mkdirs()) return null;
            String id = newId();
            Bitmap sized = picture.getWidth() == PICTURE_W && picture.getHeight() == PICTURE_H ? picture
                    : Bitmap.createScaledBitmap(picture, PICTURE_W, PICTURE_H, true);
            File f = file(id);
            File tmp = new File(dir, f.getName() + ".part");
            try {
                try (FileOutputStream out = new FileOutputStream(tmp)) {
                    if (!sized.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("not written");
                }
                if (!tmp.renameTo(f)) throw new IOException("not moved");
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                tmp.delete();
                return null;
            } finally {
                if (sized != picture) sized.recycle();
            }
            List<String> ids = ids();
            ids.add(id);
            save(ids);
            Wallpapers.tilePicturesChanged();
            return id;
        }
    }

    /** An existing file taken in as it is (TileCustom's older per-app pictures): its id, or null. */
    String adopt(File picture) {
        synchronized (TilePictures.class) {
            if (!picture.isFile()) return null;
            String id = newId();
            if (!picture.renameTo(file(id))) return null;
            List<String> ids = ids();
            ids.add(id);
            save(ids);
            Wallpapers.tilePicturesChanged();
            return id;
        }
    }

    /** Takes it out, file and all, and off every tile that showed it (those show their own again). */
    void delete(String id) {
        synchronized (TilePictures.class) {
            List<String> ids = ids();
            ids.remove(id);
            save(ids);
            File f = file(id);
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            synchronized (made) {
                made.remove(f.getPath());
            }
            setTitle(id, null);
            new TileCustom(context).forgetPicture(id);
            Wallpapers.tilePicturesChanged();
        }
    }

    /** The phone's "data:image/...;base64,..." picture, or null when it isn't one (or is unreasonably large). */
    static Bitmap decode(String dataUrl) {
        if (dataUrl == null || !dataUrl.startsWith("data:image/")) return null;
        int comma = dataUrl.indexOf(',');
        if (comma < 0) return null;
        try {
            byte[] bytes = android.util.Base64.decode(dataUrl.substring(comma + 1), android.util.Base64.DEFAULT);
            android.graphics.BitmapFactory.Options o = new android.graphics.BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
            if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > 4096 || o.outHeight > 4096) return null;
            o.inJustDecodeBounds = false;
            o.inSampleSize = 1;
            while (o.outWidth / (o.inSampleSize * 2) >= PICTURE_W && o.outHeight / (o.inSampleSize * 2) >= PICTURE_H) {
                o.inSampleSize *= 2;
            }
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length, o);
        } catch (IllegalArgumentException | OutOfMemoryError e) {
            return null;
        }
    }

    private String newId() {
        String id = Long.toString(System.currentTimeMillis(), 36);
        List<String> ids = ids();
        while (ids.contains(id) || file(id).exists()) id = id + "x";
        return id;
    }
}
