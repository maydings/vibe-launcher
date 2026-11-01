package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Folders of apps on Home, as on tvOS: each has a name and its apps in order, and takes one place among the
 * tiles ("folder:<id>" in the tile order AppAdapter keeps). Saved as JSON beside that order. An app is in
 * one folder at most; a folder with no app left that's installed and shown goes (AppAdapter.reload).
 */
final class Folders {

    static final String KEY_PREFIX = "folder:";
    private static final String KEY = "folders";

    static final class Folder {
        final String id;
        String name;
        final List<String> apps = new ArrayList<>();

        Folder(String id, String name) {
            this.id = id;
            this.name = name;
        }
    }

    private final SharedPreferences prefs;
    private final Map<String, Folder> folders = new LinkedHashMap<>();

    Folders(SharedPreferences prefs) {
        this.prefs = prefs;
        reread();
    }

    /** From what's saved now: Home and an open folder each keep one, and either may have changed it. */
    void reread() {
        folders.clear();
        try {
            JSONArray all = new JSONArray(prefs.getString(KEY, "[]"));
            for (int i = 0; i < all.length(); i++) {
                JSONObject f = all.getJSONObject(i);
                Folder folder = new Folder(f.getString("id"), f.optString("name"));
                JSONArray apps = f.optJSONArray("apps");
                for (int j = 0; apps != null && j < apps.length(); j++) folder.apps.add(apps.getString(j));
                folders.put(folder.id, folder);
            }
        } catch (JSONException e) {
            folders.clear();   // unreadable: no folders rather than a crash on every start
        }
    }

    List<Folder> all() {
        return new ArrayList<>(folders.values());
    }

    Folder get(String id) {
        return folders.get(id);
    }

    /** The folder holding pkg, or null. */
    Folder of(String pkg) {
        for (Folder f : folders.values()) {
            if (f.apps.contains(pkg)) return f;
        }
        return null;
    }

    /** A new folder holding pkg (taken out of any other folder). */
    Folder create(String name, String pkg) {
        String id = Long.toString(System.currentTimeMillis(), 36);
        while (folders.containsKey(id)) id = id + "x";
        Folder f = new Folder(id, name);
        removeApp(pkg);
        f.apps.add(pkg);
        folders.put(id, f);
        save();
        return f;
    }

    /** Puts pkg at the end of the folder, out of any other one. */
    void add(String id, String pkg) {
        Folder f = folders.get(id);
        if (f == null) return;
        removeApp(pkg);
        f.apps.add(pkg);
        save();
    }

    /** Takes pkg out of its folder, if any. */
    void remove(String pkg) {
        if (removeApp(pkg)) save();
    }

    private boolean removeApp(String pkg) {
        boolean found = false;
        for (Folder f : folders.values()) found |= f.apps.remove(pkg);
        return found;
    }

    void rename(String id, String name) {
        Folder f = folders.get(id);
        if (f == null || name.trim().isEmpty()) return;
        f.name = name.trim();
        save();
    }

    void setApps(String id, List<String> apps) {
        Folder f = folders.get(id);
        if (f == null) return;
        f.apps.clear();
        f.apps.addAll(apps);
        save();
    }

    void delete(String id) {
        if (folders.remove(id) != null) save();
    }

    private void save() {
        JSONArray all = new JSONArray();
        try {
            for (Folder f : folders.values()) {
                all.put(new JSONObject().put("id", f.id).put("name", f.name).put("apps", new JSONArray(f.apps)));
            }
        } catch (JSONException e) {
            return;
        }
        prefs.edit().putString(KEY, all.toString()).apply();
    }

    /** A first name for a folder made from this app, from the kind of app it says it is, as tvOS does. */
    static String defaultName(Context context, String pkg) {
        int category = ApplicationInfo.CATEGORY_UNDEFINED;
        try {
            category = context.getPackageManager().getApplicationInfo(pkg, 0).category;
        } catch (PackageManager.NameNotFoundException ignored) {
        }
        switch (category) {
            case ApplicationInfo.CATEGORY_VIDEO: return context.getString(R.string.folder_video);
            case ApplicationInfo.CATEGORY_AUDIO: return context.getString(R.string.folder_music);
            case ApplicationInfo.CATEGORY_GAME: return context.getString(R.string.folder_games);
            default: return context.getString(R.string.folder);
        }
    }
}
