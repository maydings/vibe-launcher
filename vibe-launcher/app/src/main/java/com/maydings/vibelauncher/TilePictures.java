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
 * Tile pictures (Settings > Pictures and videos > Tile pictures): pictures of the person's own for tiles, each kept
 * once, at a tile's 16:9 (PICTURE_W x PICTURE_H), in files/tiles/pic_&lt;id&gt;.png; which tile shows which is
 * TileCustom's ("pic"). The list is in the "tiles" preferences, so a backup carries it with the files (Backup).
 * Added only from the phone (PhoneLink's tile page, cropped there), the one place pictures come in.
 */
final class TilePictures {

    static final int PICTURE_W = 640, PICTURE_H = 360;
    private static final String PREFS = "tiles";
    private static final String KEY = "pictures";
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
    synchronized List<String> list() {
        List<String> out = new ArrayList<>();
        for (String id : ids()) if (file(id).exists()) out.add(id);
        return out;
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

    /** Keeps the picture, scaled to the tile's size: its id, or null when it couldn't be saved. */
    synchronized String add(Bitmap picture) {
        if (!dir.isDirectory() && !dir.mkdirs()) return null;
        String id = newId();
        Bitmap sized = picture.getWidth() == PICTURE_W && picture.getHeight() == PICTURE_H ? picture
                : Bitmap.createScaledBitmap(picture, PICTURE_W, PICTURE_H, true);
        File f = file(id);
        try (FileOutputStream out = new FileOutputStream(f)) {
            if (!sized.compress(Bitmap.CompressFormat.PNG, 100, out)) throw new IOException("not written");
        } catch (IOException e) {
            //noinspection ResultOfMethodCallIgnored
            f.delete();
            return null;
        }
        List<String> ids = ids();
        ids.add(id);
        save(ids);
        return id;
    }

    /** An existing file taken in as it is (TileCustom's older per-app pictures): its id, or null. */
    synchronized String adopt(File picture) {
        if (!picture.isFile()) return null;
        String id = newId();
        if (!picture.renameTo(file(id))) return null;
        List<String> ids = ids();
        ids.add(id);
        save(ids);
        return id;
    }

    /** Takes it out, file and all, and off every tile that showed it (those show their own again). */
    synchronized void delete(String id) {
        List<String> ids = ids();
        ids.remove(id);
        save(ids);
        //noinspection ResultOfMethodCallIgnored
        file(id).delete();
        new TileCustom(context).forgetPicture(id);
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
            return android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
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
