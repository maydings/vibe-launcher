package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Bitmap;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

/**
 * What the user changed on an app's tile (tile menu > Edit): its name, its icon instead of its banner, or a
 * picture of their own. The choices are kept with the tile order (the "tiles" preferences, as JSON by package);
 * the pictures in files/tiles/<package>.png, not the cache, which Android may empty and which couldn't fetch
 * them back.
 */
final class TileCustom {

    /** The size pictures are kept at: a tile's 16:9, sharp at 1080p even zoomed in focus. */
    static final int PICTURE_W = 640, PICTURE_H = 360;
    private static final String PREFS = "tiles";
    private static final String KEY = "custom";

    private final SharedPreferences prefs;
    private final File dir;
    private JSONObject all;

    TileCustom(Context context) {
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dir = new File(context.getFilesDir(), "tiles");
        try {
            all = new JSONObject(prefs.getString(KEY, "{}"));
        } catch (JSONException e) {
            all = new JSONObject();   // unreadable: every tile as its app made it
        }
    }

    private JSONObject of(String pkg) {
        JSONObject o = all.optJSONObject(pkg);
        return o != null ? o : new JSONObject();
    }

    /** The name given to the app's tile, or null for the app's own. */
    String name(String pkg) {
        String n = of(pkg).optString("name", "");
        return n.isEmpty() ? null : n;
    }

    /** The app's icon on its tile, not its banner. */
    boolean useIcon(String pkg) {
        return of(pkg).optBoolean("icon", false);
    }

    /** The picture chosen for the app's tile, or null. */
    File picture(String pkg) {
        File f = new File(dir, pkg + ".png");
        return of(pkg).optBoolean("picture", false) && f.exists() ? f : null;
    }

    /** Anything changed on this app's tile (for Reset). */
    boolean changed(String pkg) {
        return all.has(pkg);
    }

    void setName(String pkg, String name) {
        put(pkg, "name", name == null || name.trim().isEmpty() ? null : name.trim());
    }

    void setUseIcon(String pkg, boolean icon) {
        put(pkg, "icon", icon ? Boolean.TRUE : null);
    }

    /** Keeps the picture, scaled to PICTURE_W x PICTURE_H, as the app's tile. */
    boolean setPicture(String pkg, Bitmap picture) {
        if (!dir.isDirectory() && !dir.mkdirs()) return false;
        Bitmap sized = picture.getWidth() == PICTURE_W && picture.getHeight() == PICTURE_H ? picture
                : Bitmap.createScaledBitmap(picture, PICTURE_W, PICTURE_H, true);
        File f = new File(dir, pkg + ".png");
        try (FileOutputStream out = new FileOutputStream(f)) {
            if (!sized.compress(Bitmap.CompressFormat.PNG, 100, out)) return false;
        } catch (IOException e) {
            f.delete();
            return false;
        }
        put(pkg, "icon", null);   // a picture of its own replaces either
        put(pkg, "picture", Boolean.TRUE);
        return true;
    }

    /** The tile as the app made it: its own name and banner. */
    void reset(String pkg) {
        new File(dir, pkg + ".png").delete();
        all.remove(pkg);
        save();
    }

    private void put(String pkg, String field, Object value) {
        try {
            JSONObject o = of(pkg);
            if (value == null) o.remove(field);
            else o.put(field, value);
            if (o.length() == 0) all.remove(pkg);
            else all.put(pkg, o);
        } catch (JSONException e) {
            return;
        }
        save();
    }

    private void save() {
        prefs.edit().putString(KEY, all.toString()).apply();
    }
}
