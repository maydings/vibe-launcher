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
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import javax.crypto.AEADBadTagException;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * Everything you've set up, in one JSON file: Vibe Launcher's settings (the API keys too) and the "tiles"
 * preferences (the order, hidden apps, folders, each tile's name, icon or picture), with the tile pictures
 * themselves (files/tiles) inside as base64. Caches (backdrops, TMDB, Plex's server) are left out: they
 * come back by themselves. So are what the TV itself decides (the channels sent to it, the questions Home
 * has asked once) and the apps turned off on it (TV_ONLY), which belong to that TV.
 * <p>
 * Format 2 encrypts the whole payload (including API keys) with AES-256-GCM using a key derived from a
 * user passphrase via PBKDF2-HMAC-SHA256. Format 1 (plaintext) is still accepted on restore for older backups.
 * <p>
 * Kept on the TV (Settings > General > Backup and restore) as Download/vibe-launcher-backup.json, or taken to
 * and from a phone (PhoneLink).
 */
final class Backup {

    static final String FILE_NAME = "vibe-launcher-backup.json";
    /** What this file says it is, and the layout's versions (1 plaintext, 2 encrypted). */
    private static final String MARK = "vibeLauncherBackup";
    private static final int FORMAT_PLAIN = 1;
    private static final int FORMAT_ENCRYPTED = 2;
    private static final String KDF_NAME = "pbkdf2-sha256";
    private static final int KDF_ITERATIONS = 100_000;
    private static final int MIN_ITERATIONS = 10_000;
    private static final int MAX_ITERATIONS = 1_000_000;
    private static final int SALT_BYTES = 16;
    private static final int IV_BYTES = 12;
    private static final int KEY_BITS = 256;
    private static final int MAX_BACKUP_BYTES = 32 * 1024 * 1024;
    /** The preferences kept: the launcher's settings, and the tiles' order, folders and changes. */
    private static final String[] PREFS = {"vibe_settings", "tiles"};
    /**
     * Settings that belong to this TV and stay out of a backup: the apps turned off here (VibeSettings.appsOff),
     * which differ from one TV model to the next. Restoring keeps this TV's own, and leaves out any an older
     * backup carries.
     */
    private static final Set<String> TV_ONLY = new HashSet<>(Collections.singletonList("appsOff"));
    private static final String PICTURES = "tiles";   // under files/, as TileCustom keeps them
    /** A picture's file name: an app's package and .png, nothing that could reach outside the folder. */
    private static final Pattern PICTURE_NAME = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9._]{0,200}\\.png");
    /** Safe font choice values: Outfit (""), "same", system family ("sys:..."), or a base-36 id. */
    private static final Pattern SAFE_FONT = Pattern.compile("^$|same|sys:[a-z0-9-]+|[a-z0-9]{1,32}");
    /** Larger than any picture TileCustom keeps (640 x 360), but no bigger: anything else isn't one of ours. */
    private static final int MAX_PICTURE_SIDE = 4096;

    /** Expected type tags for known preference keys so a malformed backup cannot cause ClassCastException crash-loops. */
    private static final Map<String, Map<String, String>> KNOWN_TYPES = buildKnownTypes();

    private static Map<String, Map<String, String>> buildKnownTypes() {
        Map<String, Map<String, String>> m = new HashMap<>();
        Map<String, String> vs = new HashMap<>();
        for (String k : new String[]{"tmdbKey", "youtubeKey", "plexToken", "pexelsKey", "pixabayKey",
                "openSkyId", "openSkySecret", "uiFont", "clockFont", "language", "weatherCity", "artOrder"}) {
            vs.put(k, "s");
        }
        for (String k : new String[]{"topBarMinimal", "showClock", "showDate", "hideInactiveInputs",
                "autoHideBar", "slideshowClock", "carets", "jpegStills", "powerOnHome", "adbOffWhenDone",
                "ignoreChannelKeys", "weatherOn", "titleInfo", "flightsOverColour"}) {
            vs.put(k, "b");
        }
        for (String k : new String[]{"wallpaperMode", "dockOpacity", "topBarOpacity", "columns", "dockApps",
                "slideshowFrom", "clockHours", "backAction", "glassTint", "weatherStyle", "weatherUnits",
                "flightsReach"}) {
            vs.put(k, "i");
        }
        vs.put("weatherLat", "f");
        vs.put("weatherLon", "f");
        vs.put("appsOff", "S");
        vs.put("artOff", "S");
        m.put("vibe_settings", vs);

        Map<String, String> tiles = new HashMap<>();
        for (String k : new String[]{"order", "folders", "custom", "pictures", "pictureNames"}) {
            tiles.put(k, "s");
        }
        tiles.put("hidden", "S");
        m.put("tiles", tiles);
        return m;
    }

    /** Outcome of reading/decrypting a backup. */
    static final int READ_OK = 0, READ_INVALID = 1, READ_WRONG_PASSPHRASE = 2;

    /** A backup read and checked through, ready to put in place (apply). */
    static final class Contents {
        final Map<String, JSONObject> prefs = new HashMap<>();
        final Map<String, byte[]> pictures = new HashMap<>();
        String language = "";   // the settings' language: Settings is made again if it's another one
    }

    static final class ReadResult {
        final int status;
        final Contents contents;

        private ReadResult(int status, Contents contents) {
            this.status = status;
            this.contents = contents;
        }
    }

    private Backup() {
    }

    // ---- making and reading ------------------------------------------------------------------------

    /** The whole backup as encrypted JSON (format 2), including API keys. Requires a non-empty passphrase. */
    static String make(Context context, String passphrase) throws JSONException, IOException, GeneralSecurityException {
        if (passphrase == null || passphrase.isEmpty()) throw new IllegalArgumentException("passphrase required");
        return encrypt(makePlain(context), passphrase);
    }

    /** The unencrypted backup payload (format 1 JSON), used inside the encrypted format 2 envelope. */
    private static String makePlain(Context context) throws JSONException, IOException {
        JSONObject root = new JSONObject();
        root.put(MARK, FORMAT_PLAIN);
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
                pictures.put(f.getName(), Base64.getEncoder().encodeToString(readAll(new FileInputStream(f), MAX_BACKUP_BYTES)));
            }
        }
        root.put("pictures", pictures);
        return root.toString();
    }

    /** Wraps a plaintext backup JSON payload in a format 2 AES-256-GCM envelope derived from passphrase. */
    static String encrypt(String plainJson, String passphrase) throws JSONException, GeneralSecurityException {
        if (plainJson == null || passphrase == null || passphrase.isEmpty()) {
            throw new IllegalArgumentException("passphrase required");
        }
        SecureRandom rng = new SecureRandom();
        byte[] salt = new byte[SALT_BYTES];
        byte[] iv = new byte[IV_BYTES];
        rng.nextBytes(salt);
        rng.nextBytes(iv);
        byte[] keyBytes = deriveKey(passphrase, salt, KDF_ITERATIONS);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
            byte[] ciphertext = cipher.doFinal(plainJson.getBytes(StandardCharsets.UTF_8));
            JSONObject env = new JSONObject();
            env.put(MARK, FORMAT_ENCRYPTED);
            env.put("version", BuildConfig.VERSION_NAME);
            env.put("made", System.currentTimeMillis());
            env.put("kdf", KDF_NAME);
            env.put("iter", KDF_ITERATIONS);
            env.put("salt", Base64.getEncoder().encodeToString(salt));
            env.put("iv", Base64.getEncoder().encodeToString(iv));
            env.put("data", Base64.getEncoder().encodeToString(ciphertext));
            return env.toString();
        } finally {
            Arrays.fill(keyBytes, (byte) 0);
        }
    }

    /** Whether this JSON text is an encrypted (format 2) Vibe Launcher backup. */
    static boolean isEncrypted(String json) {
        if (json == null) return false;
        try {
            JSONObject root = new JSONObject(json);
            return root.optInt(MARK, 0) == FORMAT_ENCRYPTED && root.has("data") && root.has("salt") && root.has("iv");
        } catch (JSONException e) {
            return false;
        }
    }

    /** Reads and validates a plaintext (format 1) or encrypted (format 2) backup with explicit error status. */
    static ReadResult readWithStatus(String json, String passphrase) {
        if (json == null) return new ReadResult(READ_INVALID, null);
        try {
            JSONObject root = new JSONObject(json);
            int format = root.optInt(MARK, 0);
            if (format == FORMAT_ENCRYPTED) {
                if (passphrase == null || passphrase.isEmpty()) {
                    return new ReadResult(READ_WRONG_PASSPHRASE, null);
                }
                if (!KDF_NAME.equals(root.optString("kdf"))) return new ReadResult(READ_INVALID, null);
                int iter = root.optInt("iter", 0);
                if (iter < MIN_ITERATIONS || iter > MAX_ITERATIONS) return new ReadResult(READ_INVALID, null);
                byte[] salt = Base64.getDecoder().decode(root.getString("salt"));
                byte[] iv = Base64.getDecoder().decode(root.getString("iv"));
                byte[] ciphertext = Base64.getDecoder().decode(root.getString("data"));
                if (salt.length < SALT_BYTES || iv.length != IV_BYTES || ciphertext.length < 16) {
                    return new ReadResult(READ_INVALID, null);
                }
                byte[] keyBytes = deriveKey(passphrase, salt, iter);
                try {
                    Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
                    cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keyBytes, "AES"), new GCMParameterSpec(128, iv));
                    String inner = new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
                    root = new JSONObject(inner);
                    format = root.optInt(MARK, 0);
                } catch (AEADBadTagException e) {
                    return new ReadResult(READ_WRONG_PASSPHRASE, null);
                } finally {
                    Arrays.fill(keyBytes, (byte) 0);
                }
            }
            if (format != FORMAT_PLAIN) return new ReadResult(READ_INVALID, null);
            Contents c = parsePlain(root);
            return c != null ? new ReadResult(READ_OK, c) : new ReadResult(READ_INVALID, null);
        } catch (JSONException | IllegalArgumentException | GeneralSecurityException e) {
            return new ReadResult(READ_INVALID, null);
        }
    }

    /** Convenience wrapper for tests and callers expecting Contents or null. */
    static Contents read(String json, String passphrase) {
        return readWithStatus(json, passphrase).contents;
    }

    private static Contents parsePlain(JSONObject root) throws JSONException {
        Contents c = new Contents();
        JSONObject prefs = root.getJSONObject("prefs");
        for (String name : PREFS) {
            JSONObject values = prefs.optJSONObject(name);
            if (values == null) values = new JSONObject();   // nothing kept there: none on the TV either
            Map<String, String> expected = KNOWN_TYPES.get(name);
            for (Iterator<String> keys = values.keys(); keys.hasNext(); ) {
                String key = keys.next();
                JSONArray typed = values.getJSONArray(key);
                if (typed.length() != 2 || typed.isNull(0) || typed.isNull(1)) return null;
                String tag = typed.getString(0);
                if (tag.length() != 1 || "sSilfb".indexOf(tag.charAt(0)) < 0) return null;
                if (expected != null && expected.containsKey(key) && !expected.get(key).equals(tag)) {
                    return null;
                }
                if (!validTypedValue(tag, typed)) return null;
                if ("vibe_settings".equals(name) && ("uiFont".equals(key) || "clockFont".equals(key))) {
                    String choice = typed.getString(1);
                    if (!SAFE_FONT.matcher(choice).matches()) return null;
                }
            }
            c.prefs.put(name, values);
        }
        JSONArray lang = c.prefs.get("vibe_settings").optJSONArray("language");
        if (lang != null && !lang.isNull(1)) c.language = lang.optString(1, "");
        JSONObject pictures = root.optJSONObject("pictures");
        if (pictures != null) {
            for (Iterator<String> names = pictures.keys(); names.hasNext(); ) {
                String name = names.next();
                if (!PICTURE_NAME.matcher(name).matches()) return null;
                String b64 = pictures.getString(name).replaceAll("\\s+", "");
                byte[] png = Base64.getDecoder().decode(b64);
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
    }

    private static boolean validTypedValue(String tag, JSONArray typed) {
        Object v = typed.opt(1);
        switch (tag) {
            case "s": return v instanceof String;
            case "b": return v instanceof Boolean;
            case "i":
            case "l":
            case "f": return v instanceof Number;
            case "S": {
                if (!(v instanceof JSONArray)) return false;
                JSONArray a = (JSONArray) v;
                for (int i = 0; i < a.length(); i++) {
                    if (a.isNull(i) || !(a.opt(i) instanceof String)) return false;
                }
                return true;
            }
            default: return false;
        }
    }

    private static byte[] deriveKey(String passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
        char[] chars = passphrase.toCharArray();
        PBEKeySpec spec = new PBEKeySpec(chars, salt, iterations, KEY_BITS);
        try {
            SecretKeyFactory skf = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256");
            return skf.generateSecret(spec).getEncoded();
        } finally {
            spec.clearPassword();
            Arrays.fill(chars, '\0');
        }
    }

    /**
     * Puts the backup in place of what's on the TV: each set of preferences emptied and filled from it,
     * and the tile pictures swapped for its own. Written at once (commit), so Home made again straight after
     * reads the new ones. Home and Settings still hold the old ones in memory: the caller makes them again.
     */
    @SuppressLint("ApplySharedPref")
    static boolean apply(Context context, Contents c) {
        File dir = new File(context.getFilesDir(), PICTURES);
        if (!c.pictures.isEmpty() && !dir.isDirectory() && !dir.mkdirs()) return false;
        // Stage new tile pictures to temp files first so a disk-full error doesn't wipe existing pictures.
        Map<File, File> staged = new HashMap<>();
        try {
            for (Map.Entry<String, byte[]> p : c.pictures.entrySet()) {
                File dest = new File(dir, p.getKey());
                File tmp = new File(dir, p.getKey() + ".restore.part");
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(p.getValue());
                }
                staged.put(tmp, dest);
            }
        } catch (IOException e) {
            for (File tmp : staged.keySet()) //noinspection ResultOfMethodCallIgnored
                tmp.delete();
            return false;
        }

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
                if (typed == null || typed.length() != 2 || typed.isNull(1)) continue;
                switch (typed.optString(0)) {
                    case "s": edit.putString(key, typed.optString(1)); break;
                    case "i": edit.putInt(key, typed.optInt(1)); break;
                    case "l": edit.putLong(key, typed.optLong(1)); break;
                    case "f": edit.putFloat(key, (float) typed.optDouble(1)); break;
                    case "b": edit.putBoolean(key, typed.optBoolean(1)); break;
                    case "S": {
                        Set<String> set = new HashSet<>();
                        JSONArray a = typed.optJSONArray(1);
                        for (int i = 0; a != null && i < a.length(); i++) {
                            if (!a.isNull(i)) set.add(a.optString(i));
                        }
                        edit.putStringSet(key, set);
                        break;
                    }
                    default: break;
                }
            }
            ok &= edit.commit();
        }
        File[] old = dir.listFiles();
        if (old != null) {
            for (File f : old) {
                if (!staged.containsKey(f)) //noinspection ResultOfMethodCallIgnored
                    f.delete();
            }
        }
        for (Map.Entry<File, File> entry : staged.entrySet()) {
            if (!entry.getKey().renameTo(entry.getValue())) {
                //noinspection ResultOfMethodCallIgnored
                entry.getKey().delete();
                ok = false;
            }
        }
        Wallpapers.tilePicturesChanged();   // the Library lists them: read again
        return ok;
    }

    // ---- the copy on the TV ------------------------------------------------------------------------

    /** Up to Android 10 the Download folder needs storage access, asked for the first time it's used. */
    static boolean needsPermission(Context context) {
        return Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q
                && context.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED;
    }

    /** Makes an encrypted backup and keeps it on the TV, over the last one; false when it couldn't be written. */
    static boolean saveToTv(Context context, String passphrase) {
        try {
            byte[] data = make(context, passphrase).getBytes(StandardCharsets.UTF_8);
            if (Build.VERSION.SDK_INT <= Build.VERSION_CODES.Q) {
                File dir = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS);
                if (!dir.isDirectory() && !dir.mkdirs()) return false;
                // Written beside it first, then put in its place: a write cut short leaves the last backup whole.
                File tmp = new File(dir, FILE_NAME + ".tmp"), file = new File(dir, FILE_NAME);
                try (OutputStream out = new FileOutputStream(tmp)) {
                    out.write(data);
                }
                if (!tmp.renameTo(file)) {
                    //noinspection ResultOfMethodCallIgnored
                    tmp.delete();
                    return false;
                }
                return true;
            }
            ContentResolver resolver = context.getContentResolver();
            // Written over the copy that's there, so the file keeps its one name: a new one beside it would be
            // named "vibe-launcher-backup (1).json" by Android.
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
        } catch (JSONException | IOException | GeneralSecurityException | RuntimeException e) {
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
            return in != null ? new String(readAll(in, MAX_BACKUP_BYTES), StandardCharsets.UTF_8) : null;
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
                // By its name, or as Android names it beside one of that name it can't write over (another
                // install's, or one copied in): "vibe-launcher-backup (1).json". Only this install's own are
                // seen here, the newest first.
                MediaStore.MediaColumns.DISPLAY_NAME + " LIKE ? AND " + MediaStore.MediaColumns.RELATIVE_PATH + "=?",
                new String[]{FILE_NAME.replace(".json", "") + "%.json", Environment.DIRECTORY_DOWNLOADS + "/"},
                MediaStore.MediaColumns.DATE_MODIFIED + " DESC");
    }

    private static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        try (InputStream from = in) {
            ByteArrayOutputStream out = new ByteArrayOutputStream();
            byte[] buf = new byte[16 * 1024];
            for (int n; (n = from.read(buf)) > 0; ) {
                if (out.size() + n > maxBytes) throw new IOException("backup too large");
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        }
    }
}
