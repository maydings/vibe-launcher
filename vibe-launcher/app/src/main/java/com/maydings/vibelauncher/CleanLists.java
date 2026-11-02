package com.maydings.vibelauncher;

import android.content.Context;
import android.os.Build;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.regex.Pattern;

/**
 * Clean Your TV's lists (github.com/Gathin23/cleanyourtv, MIT): for each TV someone has tested, what to turn off,
 * one package a line with why after a #, under "# section" headings, in examples/&lt;tv&gt;/debloat.txt, and what
 * TV it is in examples/&lt;tv&gt;/tv.json ("tv", "os", "brand", "notes", all free text). Read from GitHub as they
 * are now, when Settings > Apps > Clean-up lists is opened, so new lists and fixes come without an update.
 * <p>
 * Which list fits this TV can't be looked up: tv.json names it in words, not by an id Android has. So the lists of
 * its brand are ranked by how many of this TV's own names (its model, device, product and chip, from Build) their
 * tv.json mentions, best first, and the person picks. The rest of the brands are listed after, in case.
 * <p>
 * What the lists say is only ever a suggestion: SettingsScreen shows the plan, leaves out what isn't installed and
 * what TvApps keeps, starts the maker's "by choice" sections unticked, and turns off only what's left ticked once
 * asked; Undo is our own record (TvApps.remember), whatever the list says later.
 * <p>
 * One GitHub API call for the folder list (there's a limit of 60 an hour without an account, per address), the rest
 * plain files from raw.githubusercontent.com. The last answers are kept in the cache, for when GitHub can't be
 * reached. Worker threads only.
 */
final class CleanLists {

    private static final String REPO = "Gathin23/cleanyourtv";
    private static final String INDEX = "https://api.github.com/repos/" + REPO + "/contents/examples";
    private static final String RAW = "https://raw.githubusercontent.com/" + REPO + "/main/examples/";

    /** One TV's list, as tv.json describes it, and how well it fits this TV. */
    static final class Tv {
        final String folder;
        final String name, os, notes;
        final boolean sameBrand;
        final int score;

        Tv(String folder, String name, String os, String notes, boolean sameBrand, int score) {
            this.folder = folder;
            this.name = name;
            this.os = os;
            this.notes = notes;
            this.sameBrand = sameBrand;
            this.score = score;
        }
    }

    /** One line of a list: the package, why (after its #), its section, and whether the maker kept it by choice. */
    static final class Item {
        final String pkg, reason, section;
        final boolean optional;

        Item(String pkg, String reason, String section, boolean optional) {
            this.pkg = pkg;
            this.reason = reason;
            this.section = section;
            this.optional = optional;
        }
    }

    private static final Pattern PACKAGE = Pattern.compile("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z0-9_]+)+");
    /** Section headings for what the list's maker turned off because they don't use it, not because it's clutter. */
    private static final Pattern BY_CHOICE = Pattern.compile("(?i)by choice|optional|if you don.t use|owner.s choice");
    private static final Map<String, String> HEADERS = Collections.singletonMap("User-Agent", "Vibe-Launcher");

    private CleanLists() {
    }

    /**
     * Every list, this TV's brand first and the best matches at the top, each with its tv.json; from the cache when
     * GitHub can't be reached. Only the brand's tv.json files are fetched (a few dozen at most): the others are
     * named from their folder.
     */
    static List<Tv> lists(Context context) throws IOException {
        List<String> folders = folders(context);
        String brand = slug(Build.BRAND), maker = slug(Build.MANUFACTURER);
        List<String> mine = new ArrayList<>();
        for (String f : folders) if (f.startsWith(brand + "-") || f.startsWith(maker + "-")) mine.add(f);
        Map<String, JSONObject> about = tvJson(context, mine);
        List<Tv> out = new ArrayList<>();
        for (String f : folders) {
            JSONObject j = about.get(f);
            boolean same = mine.contains(f);
            String name = j != null ? j.optString("tv", f) : f;
            out.add(new Tv(f, name, j != null ? j.optString("os", "") : "", j != null ? j.optString("notes", "") : "",
                    same, same && j != null ? score(j) : 0));
        }
        Collections.sort(out, (a, b) -> a.sameBrand != b.sameBrand ? (a.sameBrand ? -1 : 1)
                : a.score != b.score ? b.score - a.score : a.folder.compareTo(b.folder));
        return out;
    }

    /** A list's lines, in its order. */
    static List<Item> items(Context context, String folder) throws IOException {
        String text = cached(context, "list-" + folder + ".txt", RAW + folder + "/debloat.txt");
        List<Item> items = new ArrayList<>();
        String section = "";
        for (String line : text.split("\n")) {
            String s = line.trim();
            if (s.startsWith("#")) {
                // A heading: a comment line of its own after a blank one or another heading, not one commented out
                // package (those are the maker's "kept, safe to turn off" lines: left alone here).
                String h = s.replaceFirst("^#+\\s*", "");
                if (!h.isEmpty() && !PACKAGE.matcher(h.split("\\s+")[0]).matches()) section = h;
                continue;
            }
            int hash = s.indexOf('#');
            String pkg = (hash >= 0 ? s.substring(0, hash) : s).trim();
            if (pkg.isEmpty() || !PACKAGE.matcher(pkg).matches()) continue;
            String reason = hash >= 0 ? s.substring(hash + 1).trim() : "";
            items.add(new Item(pkg, reason, section, BY_CHOICE.matcher(section).find()));
        }
        return items;
    }

    /** The lists' folders, from GitHub's API, or the last answer kept. */
    private static List<String> folders(Context context) throws IOException {
        String json = cached(context, "index.json", INDEX);
        List<String> out = new ArrayList<>();
        try {
            JSONArray a = new JSONArray(json);
            for (int i = 0; i < a.length(); i++) {
                JSONObject o = a.getJSONObject(i);
                if ("dir".equals(o.optString("type"))) out.add(o.getString("name"));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        if (out.isEmpty()) throw new IOException("no lists");
        return out;
    }

    /** These folders' tv.json, a few at a time; any that won't come are left out. */
    private static Map<String, JSONObject> tvJson(Context context, List<String> folders) {
        Map<String, JSONObject> out = new HashMap<>();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            Map<String, Future<String>> got = new HashMap<>();
            for (String f : folders) {
                got.put(f, pool.submit(() -> cached(context, "tv-" + f + ".json", RAW + f + "/tv.json")));
            }
            for (Map.Entry<String, Future<String>> e : got.entrySet()) {
                try {
                    out.put(e.getKey(), new JSONObject(e.getValue().get()));
                } catch (Exception ignored) {
                    // that one is named from its folder
                }
            }
        } finally {
            pool.shutdownNow();
        }
        return out;
    }

    /**
     * How many of this TV's names its tv.json mentions, weighted: the model (BRAVIA 4K GB) and the product
     * (BRAVIA_ATV2_UC) say the most, the device (BRAVIA_ATV2) a family, the chip (mt5891) and Android's version
     * a little.
     */
    private static int score(JSONObject j) {
        String text = (j.optString("tv") + " " + j.optString("notes") + " " + j.optString("os")).toLowerCase(Locale.ROOT);
        int s = 0;
        s += mentions(text, Build.MODEL, 10);
        s += mentions(text, Build.PRODUCT, 8);
        s += mentions(text, Build.DEVICE, 6);
        s += mentions(text, Build.BOARD, 2);
        s += mentions(text, Build.HARDWARE, 2);
        if (text.contains("android tv " + Build.VERSION.RELEASE) || text.contains("google tv " + Build.VERSION.RELEASE)
                || text.contains("android " + Build.VERSION.RELEASE)) s += 3;
        return s;
    }

    private static int mentions(String text, String name, int weight) {
        if (name == null || name.length() < 3 || name.equalsIgnoreCase("unknown")) return 0;
        return text.contains(name.toLowerCase(Locale.ROOT)) ? weight : 0;
    }

    /** "Sony" as the lists' folders begin: lower case, words joined by "-". */
    private static String slug(String s) {
        return s == null ? "" : s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-");
    }

    /** The file now, kept for next time; the kept one when it can't be fetched. */
    private static String cached(Context context, String name, String url) throws IOException {
        File dir = new File(context.getCacheDir(), "cleanlists");
        File f = new File(dir, name.replaceAll("[^A-Za-z0-9._-]", "_"));
        try {
            String text = Http.getText(url, HEADERS);
            if (dir.isDirectory() || dir.mkdirs()) {
                try (OutputStream out = new FileOutputStream(f)) {
                    out.write(text.getBytes(StandardCharsets.UTF_8));
                }
            }
            return text;
        } catch (IOException e) {
            if (!f.isFile()) throw e;
            return new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
        }
    }
}
