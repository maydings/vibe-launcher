package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * What the user changed on a tile (tile menu > Edit), by its key (an app's package, or "folder:<id>"): its name,
 * its icon instead of its banner, or one of the tile pictures (TilePictures) by id. The choices are kept with the
 * tile order (the "tiles" preferences, as JSON by key).
 * <p>
 * A picture kept for one app alone (files/tiles/&lt;package&gt;.png with "picture": true, as a backup may still
 * bring) is taken into the tile pictures when read, and the tile keeps showing it.
 */
final class TileCustom {

    private static final String PREFS = "tiles";
    private static final String KEY = "custom";

    private final Context context;
    private final SharedPreferences prefs;
    private final File dir;
    private JSONObject all;

    TileCustom(Context context) {
        this.context = context.getApplicationContext();
        prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
        dir = new File(context.getFilesDir(), "tiles");
        try {
            all = new JSONObject(prefs.getString(KEY, "{}"));
        } catch (JSONException e) {
            all = new JSONObject();   // unreadable: every tile as its app made it
        }
        adoptOwnPictures();
    }

    /** Pictures kept as <package>.png, one per app: into the tile pictures, each tile still showing its own. */
    private void adoptOwnPictures() {
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) {
            String key = it.next();
            JSONObject o = all.optJSONObject(key);
            if (o != null && o.has("picture")) keys.add(key);
        }
        if (keys.isEmpty()) return;
        TilePictures pictures = new TilePictures(context);
        for (String key : keys) {
            JSONObject o = all.optJSONObject(key);
            boolean had = o.optBoolean("picture", false);
            o.remove("picture");
            String id = had ? pictures.adopt(new File(dir, key + ".png")) : null;
            try {
                if (id != null) o.put("pic", id);
                if (o.length() == 0) all.remove(key);
            } catch (JSONException ignored) {
            }
        }
        save();
    }

    private JSONObject of(String key) {
        JSONObject o = all.optJSONObject(key);
        return o != null ? o : new JSONObject();
    }

    /** The name given to the tile, or null for its own. */
    String name(String key) {
        String n = of(key).optString("name", "");
        return n.isEmpty() ? null : n;
    }

    /** The app's icon on its tile, not its banner. */
    boolean useIcon(String key) {
        return of(key).optBoolean("icon", false);
    }

    /** The tile picture chosen for the tile, by id, or null. */
    String pictureId(String key) {
        String id = of(key).optString("pic", "");
        return id.isEmpty() ? null : id;
    }

    /** The file of the tile picture chosen for the tile, or null. */
    File picture(String key) {
        String id = pictureId(key);
        if (id == null) return null;
        File f = new TilePictures(context).file(id);
        return f.exists() ? f : null;
    }

    /** Anything changed on this tile (for Reset). */
    boolean changed(String key) {
        return all.has(key);
    }

    void setName(String key, String name) {
        put(key, "name", name == null || name.trim().isEmpty() ? null : name.trim());
    }

    void setUseIcon(String key, boolean icon) {
        put(key, "icon", icon ? Boolean.TRUE : null);
    }

    /** One of the tile pictures (by id) as the tile's; null for none (its own banner, or a folder's preview). */
    void setPicture(String key, String id) {
        if (id != null) put(key, "icon", null);   // a picture of its own replaces either
        put(key, "pic", id);
    }

    /** A tile picture deleted: off every tile that showed it. */
    void forgetPicture(String id) {
        boolean changed = false;
        List<String> keys = new ArrayList<>();
        for (Iterator<String> it = all.keys(); it.hasNext(); ) keys.add(it.next());
        for (String key : keys) {
            JSONObject o = all.optJSONObject(key);
            if (o == null || !id.equals(o.optString("pic"))) continue;
            o.remove("pic");
            if (o.length() == 0) all.remove(key);
            changed = true;
        }
        if (changed) save();
    }

    /** The tile as it was made: its own name and banner (the tile picture stays among the tile pictures). */
    void reset(String key) {
        all.remove(key);
        save();
    }

    private void put(String key, String field, Object value) {
        try {
            JSONObject o = of(key);
            if (value == null) o.remove(field);
            else o.put(field, value);
            if (o.length() == 0) all.remove(key);
            else all.put(key, o);
        } catch (JSONException e) {
            return;
        }
        save();
    }

    private void save() {
        prefs.edit().putString(KEY, all.toString()).apply();
    }
}
