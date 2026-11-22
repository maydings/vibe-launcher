package com.maydings.vibelauncher;

import android.content.Context;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Virtual Radar Server's standing data (CC0, on GitHub), read for the flights (Flights): an airline's routes by
 * callsign, the airports by their four-letter codes, and the airlines by their three-letter ones. Each list is
 * downloaded when first needed, kept in the TV's cache and asked for again after a month. Worker thread only.
 */
final class VrsDatabase {

    private final Context context;
    /** Lists whose refresh failed, and when each may be asked for again. */
    private final Map<String, Long> retryAt = new ConcurrentHashMap<>();

    VrsDatabase(Context context) {
        this.context = context;
    }

    /** Its lists on GitHub (CC0): an airline's routes by callsign, and the airports by their four-letter codes. */
    private static final String VRS = "https://raw.githubusercontent.com/vradarserver/standing-data/main/";
    private static final long LIST_MS = 30L * 24 * 60 * 60 * 1000;
    /** Airports looked up since Home started: {code to show, city, latitude, longitude}; an empty array for none. */
    private final Map<String, String[]> airports = new ConcurrentHashMap<>();


    static double number(String s) {
        try {
            return Double.parseDouble(s);
        } catch (NumberFormatException e) {
            return Double.NaN;
        }
    }

    /** A list from the cache, downloaded when it's not there or a month old (an old one does if that fails). */
    private File list(String path, String name) throws IOException {
        File dir = new File(context.getCacheDir(), "routes");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache");
        File file = new File(dir, name);
        if (file.isFile() && System.currentTimeMillis() - file.lastModified() < LIST_MS) return file;
        // An old one that couldn't be had anew just now: the old one for ten minutes, not asked for with each look.
        Long again = retryAt.get(name);
        if (file.isFile() && again != null && System.currentTimeMillis() < again) return file;
        byte[] bytes;
        try {
            bytes = Http.get(VRS + path, 8 * 1024 * 1024);
        } catch (IOException e) {
            // No such list (an airline with no routes kept): an empty one. Refused for another reason (too
            // many requests, say), it's as if it hadn't answered.
            if ("HTTP 404".equals(e.getMessage())) bytes = new byte[0];
            else if (file.isFile()) {
                retryAt.put(name, System.currentTimeMillis() + 10 * 60_000);
                return file;
            }
            else throw e;
        }
        File part = new File(file.getPath() + ".part");
        try (FileOutputStream out = new FileOutputStream(part)) {
            out.write(bytes);
        }
        if (!part.renameTo(file)) throw new IOException("not kept");
        return file;
    }

    /** The line of a list that starts with this key and a comma, in its fields; null when there's none. */
    static List<String> line(File list, String key) throws IOException {
        String start = key + ',';
        try (java.io.BufferedReader in = new java.io.BufferedReader(new java.io.InputStreamReader(
                new java.io.FileInputStream(list), StandardCharsets.UTF_8))) {
            for (String line = in.readLine(); line != null; line = in.readLine()) {
                if (!line.isEmpty() && line.charAt(0) == '\uFEFF') line = line.substring(1);
                if (!line.startsWith(start)) continue;
                List<String> fields = new ArrayList<>();
                StringBuilder field = new StringBuilder();
                boolean quoted = false;
                for (int i = 0; i < line.length(); i++) {
                    char c = line.charAt(i);
                    if (c == '"') {
                        if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') field.append(line.charAt(++i));
                        else quoted = !quoted;
                    } else if (c == ',' && !quoted) {
                        fields.add(field.toString());
                        field.setLength(0);
                    } else {
                        field.append(c);
                    }
                }
                fields.add(field.toString());
                return fields;
            }
        }
        return null;
    }

    /** The airports a callsign flies between, in order ("KLAX", "KEWR"), by the airline's list; null when not in it. */
    String[] stops(String code, String callsign) throws IOException {
        List<String> fields = line(list("routes/schema-01/" + code.charAt(0) + '/' + code + "-all.csv", code + ".csv"),
                callsign);
        if (fields == null || fields.size() < 5) return null;
        String[] stops = fields.get(4).trim().split("-");
        for (String stop : stops) if (!stop.matches("[A-Z0-9]{3,4}")) return null;
        return stops.length >= 2 ? stops : null;
    }

    /** An airport by its four-letter code: {the code it's known by, its city, latitude, longitude}; empty for none. */
    String[] airport(String code) throws IOException {
        String[] known = airports.get(code);
        if (known != null) return known;
        String[] out = new String[0];
        if (code.length() >= 2) {
            String two = code.substring(0, 2);
            List<String> f = line(list("airports/schema-01/" + two.charAt(0) + '/' + two + ".csv", "airports-" + two + ".csv"),
                    code);
            if (f != null && f.size() >= 8) {
                String three = f.get(3).trim();
                out = new String[]{three.isEmpty() ? code : three, f.get(4).trim(), f.get(6).trim(), f.get(7).trim()};
                if (Double.isNaN(number(out[2])) || Double.isNaN(number(out[3]))) out[2] = out[3] = "";
            }
        }
        airports.put(code, out);
        return out;
    }

    /** An airline's line in the list of them, by its three-letter code, in its fields; null when it's not there. */
    List<String> airline(String code) throws IOException {
        return line(list("airlines/schema-01/airlines.csv", "airlines.csv"), code);
    }
}
