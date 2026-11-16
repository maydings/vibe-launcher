package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.graphics.Typeface;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fonts (Settings > Library > Fonts): fonts of the person's own, from Google Fonts (listed by Fontsource,
 * FontBrowserActivity) or the phone (PhoneLink), each kept once in files/fonts as a regular file and, where the font
 * has one, a medium one for headings. Which font Vibe Launcher's text and its clock and weather use is a choice
 * (VibeSettings.uiFont, clockFont): Outfit (OUTFIT), one of the system's families (SYSTEM), or one of these by id.
 * <p>
 * A font that lacks the current language's letters isn't used for it (UiFont): the system font is, so a word never
 * comes out half in one font and half in another. Which characters a font has is read from the file (FontFile).
 */
final class Fonts {

    /** Vibe Launcher's own font, the default for both choices. */
    static final String OUTFIT = "";
    /** The system's families offered, by Android's names. */
    static final String[] SYSTEM = {"sys:sans-serif", "sys:serif", "sys:monospace", "sys:sans-serif-condensed"};
    /** The biggest font file taken: Chinese, Japanese and Korean fonts run to several megabytes a weight. */
    static final long MAX_FILE = 25L * 1024 * 1024;

    static final class Item {
        final String id, family, source;
        final boolean medium;

        Item(String id, String family, String source, boolean medium) {
            this.id = id;
            this.family = family;
            this.source = source;
            this.medium = medium;
        }
    }

    static final String GOOGLE = "google", PHONE = "phone";

    private static final Map<String, Typeface> typefaces = new HashMap<>();
    private static final Map<String, int[]> coverage = new HashMap<>();

    private final File dir;
    private final SharedPreferences prefs;

    Fonts(Context context) {
        dir = new File(context.getFilesDir(), "fonts");
        prefs = context.getSharedPreferences("fonts", Context.MODE_PRIVATE);
    }

    File dir() {
        //noinspection ResultOfMethodCallIgnored
        dir.mkdirs();
        return dir;
    }

    /** A new file's place while it arrives (a download or an upload), before add. */
    File incoming() {
        return new File(dir(), System.nanoTime() + ".part");
    }

    /** All of them, in the order they were added, those whose file is there. */
    synchronized List<Item> list() {
        List<Item> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(prefs.getString("items", "[]"));
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                Item item = new Item(o.getString("id"), o.optString("family"), o.optString("source"),
                        o.optBoolean("medium"));
                if (file(item.id, false).exists()) out.add(item);
            }
        } catch (JSONException e) {
            // unreadable: none
        }
        return out;
    }

    Item get(String id) {
        for (Item item : list()) if (item.id.equals(id)) return item;
        return null;
    }

    /** Whether a font of this family from this source is in already. */
    boolean has(String source, String family) {
        for (Item item : list()) if (item.source.equals(source) && item.family.equals(family)) return true;
        return false;
    }

    File file(String id, boolean medium) {
        return new File(dir, id + (medium ? "-m.ttf" : "-r.ttf"));
    }

    /**
     * Takes a font in: its regular file, and a medium one or null. Both are moved here; null (and both gone) when
     * the regular one isn't a font Android can use. Worker thread.
     */
    Item add(File regular, File medium, String family, String source) {
        String id = Long.toString(System.currentTimeMillis(), 36);
        File r = file(id, false), m = file(id, true);
        try {
            if (FontFile.ranges(regular) == null || !regular.renameTo(r) || load(r) == null) return null;
            boolean hasMedium = medium != null && FontFile.ranges(medium) != null && medium.renameTo(m) && load(m) != null;
            if (!hasMedium) //noinspection ResultOfMethodCallIgnored
                m.delete();
            synchronized (this) {
                JSONArray a = new JSONArray(prefs.getString("items", "[]"));
                a.put(new JSONObject().put("id", id).put("family", family).put("source", source).put("medium", hasMedium));
                prefs.edit().putString("items", a.toString()).apply();
            }
            return new Item(id, family, source, hasMedium);
        } catch (JSONException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            r.delete();
            //noinspection ResultOfMethodCallIgnored
            m.delete();
            return null;
        } finally {
            //noinspection ResultOfMethodCallIgnored
            regular.delete();
            if (medium != null) //noinspection ResultOfMethodCallIgnored
                medium.delete();
        }
    }

    /** Takes it out, files and all; a choice that was it goes back to Outfit. */
    synchronized void delete(Context context, String id) {
        try {
            JSONArray a = new JSONArray(prefs.getString("items", "[]")), kept = new JSONArray();
            for (int i = 0; i < a.length(); i++) {
                if (!a.getJSONObject(i).optString("id").equals(id)) kept.put(a.getJSONObject(i));
            }
            prefs.edit().putString("items", kept.toString()).apply();
        } catch (JSONException ignored) {
        }
        //noinspection ResultOfMethodCallIgnored
        file(id, false).delete();
        //noinspection ResultOfMethodCallIgnored
        file(id, true).delete();
        VibeSettings settings = new VibeSettings(context);
        if (id.equals(settings.uiFont())) settings.setUiFont(OUTFIT);
        if (id.equals(settings.clockFont())) settings.setClockFont(OUTFIT);
        changed();
    }

    // ---- choices -----------------------------------------------------------------------------------

    /** A choice's typeface (regular, or medium for headings); null for Outfit or a font no longer here. */
    static Typeface typeface(Context context, String choice, boolean medium) {
        if (choice == null || OUTFIT.equals(choice)) return null;
        String key = choice + (medium ? "|m" : "|r");
        synchronized (typefaces) {
            if (typefaces.containsKey(key)) return typefaces.get(key);
        }
        Typeface t;
        if (choice.startsWith("sys:")) {
            Typeface base = Typeface.create(choice.substring(4), Typeface.NORMAL);
            t = medium && Build.VERSION.SDK_INT >= 28 ? Typeface.create(base, 500, false) : base;
        } else {
            Fonts fonts = new Fonts(context);
            Item item = fonts.get(choice);
            if (item == null) {
                t = null;
            } else {
                t = load(fonts.file(choice, medium && item.medium));
                if (t != null && medium && !item.medium && Build.VERSION.SDK_INT >= 28) t = Typeface.create(t, 500, false);
            }
        }
        synchronized (typefaces) {
            typefaces.put(key, t);
        }
        return t;
    }

    private static Typeface load(File file) {
        try {
            return file.exists() ? Typeface.createFromFile(file) : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    /** Whether the choice has every letter of this text (the system's families always do, through fallback). */
    static boolean covers(Context context, String choice, String text) {
        if (choice == null || choice.startsWith("sys:") || OUTFIT.equals(choice)) {
            return true;
        }
        int[] ranges;
        synchronized (coverage) {
            ranges = coverage.get(choice);
        }
        if (ranges == null) {
            ranges = FontFile.ranges(new Fonts(context).file(choice, false));
            if (ranges == null) ranges = new int[0];
            synchronized (coverage) {
                coverage.put(choice, ranges);
            }
        }
        return FontFile.covers(ranges, text);
    }

    /** A choice's name to list: Outfit, the system family's, or the font's own. */
    static String name(Context context, String choice) {
        if (choice == null || OUTFIT.equals(choice)) return "Outfit";
        switch (choice) {
            case "sys:sans-serif": return context.getString(R.string.font_sans);
            case "sys:serif": return context.getString(R.string.font_serif);
            case "sys:monospace": return context.getString(R.string.font_mono);
            case "sys:sans-serif-condensed": return context.getString(R.string.font_condensed);
            default:
                Item item = new Fonts(context).get(choice);
                return item != null ? item.family : "Outfit";
        }
    }

    /** A font added, deleted or chosen: typefaces made again when next asked for. */
    static void changed() {
        synchronized (typefaces) {
            typefaces.clear();
        }
        synchronized (coverage) {
            coverage.clear();
        }
    }
}
