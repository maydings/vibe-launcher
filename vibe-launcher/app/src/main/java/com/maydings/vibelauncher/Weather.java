package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.Uri;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The weather under the date on Home: what it's like now and today's high and low, from Open-Meteo (free, no key;
 * its terms ask for "Weather data by Open-Meteo.com", said on Settings > Weather). Where: the city picked in
 * Settings (found with Open-Meteo's geocoding), or else worked out from the TV's internet address (ipapi.co,
 * ipwho.is when that fails), to the city only, and remembered for a day. The weather is reused for 20 minutes.
 * Network: worker thread only.
 */
final class Weather {

    private static final String FORECAST = "https://api.open-meteo.com/v1/forecast?latitude=%s&longitude=%s"
            + "&current=temperature_2m,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min"
            + "&timezone=auto&forecast_days=1&temperature_unit=%s";
    private static final String GEOCODE = "https://geocoding-api.open-meteo.com/v1/search?name=%s&count=8&language=%s";
    private static final String[] IP_LOCATION = {"https://ipapi.co/json/", "https://ipwho.is/"};
    private static final long WEATHER_MS = 20L * 60 * 1000, PLACE_MS = 24L * 60 * 60 * 1000;

    /** A place: its name to show, and where it is. */
    static final class Place {
        final String name;
        final double lat, lon;

        Place(String name, double lat, double lon) {
            this.name = name;
            this.lat = lat;
            this.lon = lon;
        }
    }

    /** The weather now: temperature, today's high and low (in the units asked for), WMO code, day or night. */
    static final class Now {
        final double temp, high, low;
        final int code;
        final boolean day;

        Now(double temp, double high, double low, int code, boolean day) {
            this.temp = temp;
            this.high = high;
            this.low = low;
            this.code = code;
            this.day = day;
        }
    }

    private Weather() {
    }

    private static SharedPreferences cache(Context c) {
        return c.getSharedPreferences("weather_cache", Context.MODE_PRIVATE);
    }

    /** Where the weather is for: the city picked in Settings, else the TV's own (its internet address). */
    static Place place(Context c, VibeSettings settings) {
        Place picked = settings.weatherPlace();
        return picked != null ? picked : autoPlace(c);
    }

    /** The city the TV's internet address is in, remembered for a day; null when it can't be found. */
    static Place autoPlace(Context c) {
        SharedPreferences p = cache(c);
        long now = System.currentTimeMillis();
        if (now - p.getLong("autoAt", 0) < PLACE_MS && p.contains("autoName")) {
            return new Place(p.getString("autoName", ""), p.getFloat("autoLat", 0), p.getFloat("autoLon", 0));
        }
        for (String url : IP_LOCATION) {
            try {
                JSONObject o = new JSONObject(Http.getText(url, Collections.emptyMap()));
                if (!o.has("latitude") || !o.has("longitude")) continue;
                Place place = new Place(o.optString("city", ""), o.getDouble("latitude"), o.getDouble("longitude"));
                p.edit().putLong("autoAt", now).putString("autoName", place.name)
                        .putFloat("autoLat", (float) place.lat).putFloat("autoLon", (float) place.lon).apply();
                return place;
            } catch (IOException | JSONException | RuntimeException ignored) {
                // the next one
            }
        }
        return null;
    }

    /** The automatic city as last found, without going online (for Settings to show); null when not known. */
    static String autoPlaceName(Context c) {
        String name = cache(c).getString("autoName", null);
        return name == null || name.isEmpty() ? null : name;
    }

    /** Cities by this name, most likely first: "Paris, Île-de-France, France". */
    static List<Place> search(String name) throws IOException {
        List<Place> out = new ArrayList<>();
        String url = String.format(Locale.ROOT, GEOCODE, Uri.encode(name.trim()), Locale.getDefault().getLanguage());
        try {
            JSONArray results = new JSONObject(Http.getText(url, Collections.emptyMap())).optJSONArray("results");
            for (int i = 0; results != null && i < results.length(); i++) {
                JSONObject r = results.getJSONObject(i);
                StringBuilder label = new StringBuilder(r.optString("name"));
                for (String part : new String[]{r.optString("admin1"), r.optString("country")}) {
                    if (!part.isEmpty() && label.indexOf(part) < 0) label.append(", ").append(part);
                }
                out.add(new Place(label.toString(), r.getDouble("latitude"), r.getDouble("longitude")));
            }
        } catch (JSONException e) {
            throw new IOException(e);
        }
        return out;
    }

    /** The weather at this place, reused for 20 minutes (and while offline); null when there's none. */
    static Now now(Context c, Place place, boolean fahrenheit) {
        SharedPreferences p = cache(c);
        String key = String.format(Locale.ROOT, "%.3f,%.3f,%s", place.lat, place.lon, fahrenheit ? "f" : "c");
        long at = p.getLong("nowAt", 0);
        boolean same = key.equals(p.getString("nowKey", ""));
        if (same && System.currentTimeMillis() - at < WEATHER_MS) return parse(p.getString("now", null));
        try {
            String url = String.format(Locale.ROOT, FORECAST, place.lat, place.lon, fahrenheit ? "fahrenheit" : "celsius");
            String json = Http.getText(url, Collections.emptyMap());
            Now now = parse(json);
            if (now != null) {
                p.edit().putString("now", json).putString("nowKey", key).putLong("nowAt", System.currentTimeMillis()).apply();
            }
            return now;
        } catch (IOException | RuntimeException e) {
            return same ? parse(p.getString("now", null)) : null;   // offline: the last one, if for here
        }
    }

    private static Now parse(String json) {
        if (json == null) return null;
        try {
            JSONObject o = new JSONObject(json), cur = o.getJSONObject("current"), daily = o.getJSONObject("daily");
            return new Now(cur.getDouble("temperature_2m"), daily.getJSONArray("temperature_2m_max").getDouble(0),
                    daily.getJSONArray("temperature_2m_min").getDouble(0), cur.getInt("weather_code"),
                    cur.optInt("is_day", 1) == 1);
        } catch (JSONException e) {
            return null;
        }
    }

    /** Fahrenheit where people use it (the US and a few others), Celsius elsewhere. */
    static boolean fahrenheitHere() {
        switch (Locale.getDefault().getCountry()) {
            case "US": case "PR": case "GU": case "VI": case "AS": case "MP": case "UM":
            case "LR": case "BS": case "BZ": case "KY": case "PW": case "FM": case "MH":
                return true;
            default:
                return false;
        }
    }

    /** The word for the sky, from the WMO weather code. */
    static int text(int code) {
        if (code == 0) return R.string.weather_clear;
        if (code == 1) return R.string.weather_mainly_clear;
        if (code == 2) return R.string.weather_partly_cloudy;
        if (code == 3) return R.string.weather_cloudy;
        if (code == 45 || code == 48) return R.string.weather_fog;
        if (code >= 51 && code <= 57) return R.string.weather_drizzle;
        if (code >= 61 && code <= 67) return R.string.weather_rain;
        if (code >= 71 && code <= 77) return R.string.weather_snow;
        if (code >= 80 && code <= 82) return R.string.weather_showers;
        if (code == 85 || code == 86) return R.string.weather_snow;
        if (code >= 95) return R.string.weather_thunder;
        return R.string.weather_cloudy;
    }

    /** Its icon: the sun or the moon when clear, else the cloud with what's falling from it. */
    static int icon(int code, boolean day) {
        if (code <= 1) return day ? R.drawable.ic_wx_sun : R.drawable.ic_wx_moon;
        if (code == 2) return day ? R.drawable.ic_wx_partly : R.drawable.ic_wx_cloud;
        if (code == 45 || code == 48) return R.drawable.ic_wx_fog;
        if ((code >= 51 && code <= 67) || (code >= 80 && code <= 82)) return R.drawable.ic_wx_rain;
        if ((code >= 71 && code <= 77) || code == 85 || code == 86) return R.drawable.ic_wx_snow;
        if (code >= 95) return R.drawable.ic_wx_thunder;
        return R.drawable.ic_wx_cloud;
    }

    /** A temperature as shown: rounded, with the degree sign only (the unit is the person's choice). */
    static String degrees(double t) {
        return Math.round(t) + "°";
    }
}
