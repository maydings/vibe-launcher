package com.maydings.vibelauncher;

import android.Manifest;
import android.annotation.SuppressLint;
import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.graphics.BitmapFactory;
import android.net.Uri;
import android.os.Build;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Base64;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Everything you've set up, in one JSON file: Vibe Launcher's settings (the API keys too) and the "tiles"
 * preferences (the order, hidden apps, folders, each tile's name, icon or picture), with the tile pictures
 * themselves (files/tiles) inside as base64. Caches (backdrops, TMDB, Plex's server) are left out: they
 * come back by themselves. So are what the TV itself decides (the channels sent to it, the questions Home
 * has asked once) and the apps turned off on it (TV_ONLY), which belong to that TV.
 * <p>
 * Kept on the TV (Settings > General > Backup and restore) as Download/vibe-launcher-backup.json, or taken to
 * and from a phone (PhoneLink). Android TV has no file picker (the system's is a stub that only says it isn't
 * supported), so the TV's copy is one file in one place, replaced by each new backup.
 * <p>
 * Up to Android 10 it's an ordinary file, after asking for storage access. From Android 11 apps write to
 * Download through MediaStore with no permission, but can only read back what they wrote themselves: a copy
 * from before Vibe Launcher was reinstalled isn't found there (the phone way covers that).
 */
final class Backup {

    static final String FILE_NAME = "vibe-launcher-backup.json";
    /** What this file says it is, and the layout's version: a newer one may be something this can't read. */
    private static final String MARK = "vibeLauncherBackup";
    private static final int FORMAT = 1;
    /** The preferences kept: the launcher's settings, and the tiles' order, folders and changes. */
    private static final String[] PREFS = {"vibe_settings", "tiles"};
    /**
     * Settings that belong to this TV and stay out of a backup: the apps turned off here (VibeSettings.appsOff),
     * which differ from one TV model to the next. Restoring keeps this TV's own, and leaves out any an older
     * backup carries.
     */
    private static final Set<String> TV_ONLY = new HashSet<>(java.util.Collections.singletonList("appsOff"));
    private static final String PICTURES = "tiles";   // under files/, as TileCustom keeps them
    /** A picture's file name: an app's package and .png, nothing that could reach outside the folder. */
    private static final Pattern PICTURE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._]{0,200}\\.png");
    /** Larger than any picture TileCustom keeps (640 x 360), but no bigger: anything else isn't one of ours. */
    private static final int MAX_PICTURE_SIDE = 4096;

    /** A backup read and checked through, ready to put in place (apply). */
    static final class Contents {
        final Map<String, JSONObject> prefs = new HashMap<>();
        final Map<String, byte[]> pictures = new HashMap<>();
        String language = "";   // the settings' language: Settings is made again if it's another one
    }

    private Backup() {
    }

    // ---- making and reading ------------------------------------------------------------------------

    /** The whole backup as JSON text. */
    static String make(Context context) throws JSONException, IOException {
        JSONObject root = new JSONObject();
        root.put(MARK, FORMAT);
        root.put("version", BuildConfig.VERSION_NAME);
        root.put("made", System.currentTimeMillis());
        JSONObject prefs = new JSONObject();
        for (String name : PREFS) {
            JSONObject values = new JSONObject();
            // Each value with its type, as SharedPreferences gives it back (an int and a long look alike in JSON).
            for (Map.Entry<String, ?> e : context.getSharedPreferences(name, Context.MODE_PRIVATE).getAll().entrySet()) {
                Object v = e.getValue();
                JSONArray typed = new JSONArray();
                if (v instanceof String) typed.put("s").put(v);
                else if (v instanceof Integer) typed.put("i").put(v);
                else if (v instanceof Long) typed.put("l").put(v);
                else if (v instanceof Float) typed.put("f").put(((Float) v).doubleValue());
                else if (v instanceof Boolean) typed.put("b").put(v);
                else if (v instanceof Set) typed.put("S").put(new JSONArray((Set<?>) v));
                else continue;
                if (TV_ONLY.contains(e.getKey())) continue;
                values.put(e.getKey(), typed);
            }
            prefs.put(name, values);
        }
        root.put("prefs", prefs);
        JSONObject pictures = new JSONObject();
        File[] files = new File(context.getFilesDir(), PICTURES).listFiles();
        if (files != null) {
            for (File f : files) {
                if (!PICTURE_NAME.matcher(f.getName()).matches()) continue;
                pictures.put(f.getName(), Base64.encodeToString(readAll(new FileInputStream(f)), Base64.NO_WRAP));
            }
        }
        root.put("pictures", pictures);
        return root.toString();
    }

    /** The backup in this text, checked through; null when it isn't one this version can read. */
    static Contents read(String json) {
        if (json == null) return null;
        try {
            JSONObject root = new JSONObject(json);
            int format = root.optInt(MARK, 0);
            if (format < 1 || format > FORMAT) return null;
            Contents c = new Contents();
            JSONObject prefs = root.getJSONObject("prefs");
            for (String name : PREFS) {
                JSONObject values = prefs.optJSONObject(name);
                if (values == null) values = new JSONObject();   // nothing kept there: none on the TV either
                for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
                    JSONArray typed = values.getJSONArray(keys.next());
                    if (typed.length() != 2 || "sSilfb".indexOf(typed.getString(0)) < 0) return null;
                }
                c.prefs.put(name, values);
            }
            JSONArray lang = c.prefs.get("vibe_settings").optJSONArray("language");
            if (lang != null) c.language = lang.optString(1, "");
            JSONObject pictures = root.optJSONObject("pictures");
            if (pictures != null) {
                for (Iterator<String> names = pictures.keys(); names.hasNext(); ) {
                    String name = names.next();
                    if (!PICTURE_NAME.matcher(name).matches()) return null;
                    byte[] png = Base64.decode(pictures.getString(name), Base64.DEFAULT);
                    // Only pictures: the size read from its header, without making the bitmap.
                    BitmapFactory.Options o = new BitmapFactory.Options();
                    o.inJustDecodeBounds = true;
                    BitmapFactory.decodeByteArray(png, 0, png.length, o);
                    if (o.outWidth <= 0 || o.outHeight <= 0 || o.outWidth > MAX_PICTURE_SIDE
                            || o.outHeight > MAX_PICTURE_SIDE) return null;
                    c.pictures.put(name, png);
                }
            }
            return c;
        } catch (JSONException | IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Puts the backup in place of what's on the TV: each set of preferences emptied and filled from it,
     * and the tile pictures swapped for its own. Written at once (commit), so Home made again straight after
     * reads the new ones. Home and Settings still hold the old ones in memory: the caller makes them again.
     */
    @SuppressLint("ApplySharedPref")
    static boolean apply(Context context, Contents c) {
        boolean ok = true;
        for (Map.Entry<String, JSONObject> p : c.prefs.entrySet()) {
            SharedPreferences here = context.getSharedPreferences(p.getKey(), Context.MODE_PRIVATE);
            Map<String, ?> before = here.getAll();
            SharedPreferences.Editor edit = here.edit().clear();
            // This TV's own (TV_ONLY) stay as they were.
            for (String key : TV_ONLY) {
                Object v = before.get(key);
                if (v instanceof Set) {
                    Set<String> set = new HashSet<>();
                    for (Object o : (Set<?>) v) set.add(String.valueOf(o));
                    edit.putStringSet(key, set);
                }
            }
            JSONObject values = p.getValue();
            for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
                String key = keys.next();
                if (TV_ONLY.contains(key)) continue;   // an older backup's: not this TV's
                JSONArray typed = values.optJSONArray(key);
                Object v = typed.opt(1);
                switch (typed.optString(0)) {
                    case "s": edit.putString(key, String.valueOf(v)); break;
                    case "i": edit.putInt(key, typed.optInt(1)); break;
                    case "l": edit.putLong(key, typed.optLong(1)); break;
                    case "f": edit.putFloat(key, (float) typed.optDouble(1)); break;
                    case "b": edit.putBoolean(key, typed.optBoolean(1)); break;
                    case "S": {
                        Set<String> set = new HashSet<>();
                        JSONArray a = typed.optJSONArray(1);
                        for (int i = 0; a != null && i < a.length(); i++) set.add(a.optString(i));
                        edit.putStringSet(key, set);
                        break;
                    }
                    default: break;
                }
            }
            ok &= edit.commit();
        }
        File dir = new File(context.getFilesDir(), PICTURES);
        File[] old = dir.listFiles();
        if (old != null) for (File f : old) f.delete();
        if (!c.pictures.isEmpty() && !dir.isDirectory() && !dir.mkdirs()) return false;
        for (Map.Entry<String, byte[]> p : c.pictures.entrySet()) {
            try (OutputStream out = new FileOutputStream(new File(dir, p.getKey()))) {
                out.write(p.getValue());
            } catch (IOException e) {
                ok = false;
            }
        }
        return ok;
    }

    // ---- the copy on the TV ------------------------------------------------------------------------

    /** Up to Android 10 the Download folder needs storage access, asked for the first time it's used. */
    static boolean needsPermission(Context context) {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    /** Makes a backup and keeps it on the TV, over the last one; false when it couldn't be written. */
    static boolean saveToTv(Context context) {
        try {
            byte[] data = make(context).getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.isDirectory() && !dir.mkdirs()) return false;
                // Written beside it first, then put in its place: a write cut short leaves the last backup whole.
                File tmp = new File(dir, FILE_NAME + ".tmp"), file = new File(dir, FILE_NAME);
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                }
                if (!tmp.renameTo(file)) {
                    tmp.delete();
                    return false;
                }
                return true;
            }
            ContentResolver resolver = context.getContentResolver();
            Uri uri = ownCopy(context);
            if (uri == null) {
                ContentValues v = new ContentValues();
                v.put(MediaStore.MediaColumns.DISPLAY_NAME, FILE_NAME);
                v.put(MediaStore.MediaColumns.MIME_TYPE, "application/json");
                v.put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/");
                uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, v);
                if (uri == null) return false;
            }
            try (OutputStream out = resolver.openOutputStream(uri, "wt")) {   // "wt": the old one cut away
                if (out == null) return false;
                out.write(data);
            }
            return true;
        } catch (JSONException | IOException | RuntimeException e) {
            return false;
        }
    }

    /** When the TV's backup was made (milliseconds), or 0 when there's none (or it can't be looked at yet). */
    static long savedOnTv(Context context) {
        if (needsPermission(context)) return 0;
        if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
            File file = new File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME);
            return file.isFile() ? file.lastModified() : 0;
        }
        try (Cursor c = query(context, MediaStore.MediaColumns.DATE_MODIFIED)) {
            return c != null && c.moveToFirst() ? c.getLong(1) * 1000 : 0;   // seconds there
        } catch (RuntimeException e) {
            return 0;
        }
    }

    /** The TV's backup as text, or null when there's none or it can't be read. */
    static String loadFromTv(Context context) {
        try {
            InputStream in;
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                in = new FileInputStream(new File(
                        Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), FILE_NAME));
            } else {
                Uri uri = ownCopy(context);
                in = uri != null ? context.getContentResolver().openInputStream(uri) : null;
            }
            return in != null ? new String(readAll(in), StandardCharsets.UTF_8) : null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    /** Android 11 on: the copy this install wrote, newest first (only its own are listed). */
    @SuppressLint("NewApi")   // only called from Android 11 on
    private static Uri ownCopy(Context context) {
        try (Cursor c = query(context, MediaStore.MediaColumns.DATE_MODIFIED)) {
            if (c == null || !c.moveToFirst()) return null;
            return Uri.withAppendedPath(MediaStore.Downloads.EXTERNAL_CONTENT_URI, String.valueOf(c.getLong(0)));
        }
    }

    @SuppressLint("NewApi")   // only called from Android 11 on
    private static Cursor query(Context context, String column) {
        return context.getContentResolver().query(MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                new String[]{MediaStore.MediaColumns._ID, column},
                MediaStore.MediaColumns.DISPLAY_NAME + "=? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{FILE_NAME, Environment.DIRECTORY_DOWNLOADS + "/"},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
    }

    private static byte[] readAll(InputStream in) throws IOException {
        try (InputStream from = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = from.read(buf)) > 0; ) out.write(buf, 0, n);
            return out.toByteArray();
        }
    }
}
