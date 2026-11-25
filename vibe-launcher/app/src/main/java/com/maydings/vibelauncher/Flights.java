package com.maydings.vibelauncher;

import android.content.Context;
import android.net.Uri;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * The aircraft passing near the place the weather is for (Settings > Backdrops > Flights overhead), for
 * FlightBoard to show.
 *
 * Where they are comes from the OpenSky Network: every 30 seconds with an OpenSky API client (Settings > API
 * keys), every 4 minutes without one, which is what its daily allowance lets a TV left on all day ask (4,000
 * and 400 requests; one request is one credit while the area asked for is under 25 square degrees). Between two
 * answers each aircraft is moved along its heading at its speed, so without a client a wider area is asked for:
 * what's overhead in four minutes is up to 90 km away now.
 *
 * OpenSky says where an aircraft is, its callsign and its transponder address, nothing else. What it is (type,
 * registration) and where it's going (airline, origin, destination) come from adsbdb.com, free and with no key,
 * by the address and by the callsign. The airline's name and the two characters of its flight numbers are
 * Virtual Radar Server's, by the callsign's first three letters, as is a route adsbdb lacks. Each answer is kept in a file (flights.json) so the same aircraft or flight number isn't
 * asked about again: an aircraft for six months, a route for one (airlines move flight numbers around with the
 * season), not known for a month or a week. A route whose two airports the aircraft is nowhere between is
 * wrong for this flight: it's hidden and asked for again the next day.
 *
 * Runs only while Home is in front (start, stop). The listener is told on the main thread.
 */
final class Flights {

    private static final String STATES = "https://opensky-network.org/api/states/all"
            + "?lamin=%.4f&lomin=%.4f&lamax=%.4f&lomax=%.4f&extended=1";   // with each one's category
    private static final String TOKEN =
            "https://auth.opensky-network.org/auth/realms/opensky-network/protocol/openid-connect/token";
    private static final String ADSBDB = "https://api.adsbdb.com/v0/";

    private static final long WITH_CLIENT_MS = 30_000, WITHOUT_MS = 240_000;
    /** How far beyond the distance shown is asked for: what an airliner covers before the next answer. */
    private static final double WITH_CLIENT_KM = 20, WITHOUT_KM = 90;
    /** An aircraft is moved along for this long past the time between answers, then dropped as not heard. */
    private static final long STALE_MS = 90_000;
    /**
     * How long OpenSky is left between two asks, as it is now (with a client or without, slower when it's
     * said to wait or the day's credits run low): an aircraft is stale once it's unheard for this and more.
     */
    private volatile long askEvery = WITHOUT_MS;
    /** OpenSky's price of one credit holds up to this many square degrees. */
    private static final double MAX_AREA = 24;
    /** Credits left under this: asked half as often, so the day's allowance lasts. */
    private static final int LOW_CREDITS = 60;
    private static final long TICK_MS = 5_000;
    /** How many of the nearest are handed on, and looked up. */
    static final int SHOWN = 40;

    private static final long DAY = 24L * 60 * 60 * 1000;
    private static final long AIRCRAFT_MS = 180 * DAY, AIRCRAFT_MISS_MS = 30 * DAY;
    private static final long ROUTE_MS = 30 * DAY, ROUTE_MISS_MS = 7 * DAY, ROUTE_WRONG_MS = DAY;
    private static final long AIRLINE_MS = 180 * DAY, AIRLINE_MISS_MS = 30 * DAY;
    /** Kept of each kind at most: the ones that run out first go. */
    private static final int MAX_KEPT = 5000;
    private static final String FILE = "flights.json";

    /** An aircraft near here, as it is now (moved along since it was last heard). */
    static final class Near {
        final String hex, callsign;
        final double km, bearing;   // from here: how far, and which way (degrees from north)
        final double lat, lon;      // where it is by now
        /** Metres, metres a second (over the ground, and up); NaN when not known. */
        final double altitude, speed, climb;
        /** From adsbdb, each null until known (or when it isn't): "Boeing 767 34AF/W", "N359UP". */
        final String type, registration;
        /** "United Parcel Service", "5X2182"; the two airports' codes and cities. */
        final String airline, flight, from, fromCity, to, toCity;
        /** The airline's codes, for its logo: "UPS" (the callsign's start) and "5X" (the flight number's); null when not known. */
        final String icao, iata;

        Near(Heard h, double km, double bearing, double lat, double lon, JSONObject aircraft, JSONObject route,
             JSONObject airline) {
            this.lat = lat;
            this.lon = lon;
            hex = h.hex;
            callsign = h.callsign;
            this.km = km;
            this.bearing = bearing;
            altitude = h.altitude;
            speed = h.speed;
            climb = h.climb;
            type = plain(text(aircraft, "y"));
            registration = text(aircraft, "r");
            String name = text(airline, "n");   // the list's, by the callsign; else as adsbdb has it with the route
            this.airline = name != null ? name : text(route, "a");
            icao = airlineOf(h.callsign);
            // The flight as the airline numbers it: its own two characters and the number, else as adsbdb says.
            String numbered = text(route, "f"), two = text(airline, "i");
            flight = two != null && icao != null ? two + h.callsign.substring(3) : numbered;
            from = text(route, "oc");
            fromCity = text(route, "on");
            to = text(route, "dc");
            toCity = text(route, "dn");
            iata = flight != null && flight.matches("[A-Z0-9]{2}\\d.*") ? flight.substring(0, 2) : null;
        }

        /** The maker as it's spoken of, not as it's registered: "The Boeing Company 777" is a Boeing 777. */
        private static String plain(String type) {
            if (type == null) return null;
            String out = type.replaceFirst("(?i)^The\\s+", "").replaceAll(
                    "(?i)\\s+(Company|Co\\.?|Corporation|Corp\\.?|Incorporated|Inc\\.?|Limited|Ltd\\.?|LLC|GmbH|S\\.?A\\.?S\\.?)(?=\\s|$)", "");
            return out.trim().isEmpty() ? type : out.trim();
        }

        static String text(JSONObject o, String key) {
            if (o == null) return null;
            String s = o.optString(key, "");
            return s.isEmpty() || s.equals("null") ? null : s;   // "null": kept so before nothing-said was read as none
        }
    }

    interface Listener {
        /** The nearest aircraft within the distance set, nearest first; empty when there are none. */
        void onFlights(List<Near> near, List<Spot> all);
    }

    /** An aircraft for the map: where it was at a moment (SystemClock.elapsedRealtime) and how it was moving. */
    static final class Spot {
        final String hex, callsign;
        final double lat, lon;
        /** Metres a second and degrees from north; NaN when not known (it then stays where it is). */
        final double speed, track;
        final long at;
        /** What to draw it as: an airliner, a helicopter, or a light aircraft (straight wings, a propeller). */
        final int kind;

        static final int AIRLINER = 0, HELICOPTER = 1, LIGHT = 2;
        private static final java.util.regex.Pattern ROTOR = java.util.regex.Pattern.compile(
                "(?i)helicopter|rotorcraft|robinson|sikorsky|eurocopter|aerospatiale|agusta|leonardo|\\bbell\\b"
                        + "|mcdonnell douglas heli|\\bmd heli|enstrom|schweizer|guimbal|kaman|kamov|\\bmil\\b");
        private static final java.util.regex.Pattern PROP = java.util.regex.Pattern.compile(
                "(?i)cessna(?!.*citation)|piper|cirrus|mooney|diamond|beech(?!.*(jet|premier|hawker))|grumman american"
                        + "|\\baviat\\b|\\bvans?\\b|van's|maule|champion|socata|tecnam|\\brobin\\b|glasair|lancair|cubcrafters"
                        + "|cub crafters|pilatus pc-?(6|12)|de havilland.*(beaver|otter)|aeronca|taylorcraft|luscombe");

        /**
         * By what it says it is, where it says; else by its type, where that's known (adsbdb's, for those that
         * came near). Neither known, it's drawn as an airliner.
         */
        private static int kind(Heard h, String type) {
            if (h.category == 8) return HELICOPTER;
            if (type != null) return ROTOR.matcher(type).find() ? HELICOPTER : PROP.matcher(type).find() ? LIGHT : AIRLINER;
            if (h.category == 2 || h.category == 9 || h.category == 12) return LIGHT;
            return AIRLINER;   // not known which: no guessing
        }

        Spot(Heard h, String type) {
            kind = kind(h, type);
            hex = h.hex;
            callsign = h.callsign;
            lat = h.lat;
            lon = h.lon;
            speed = h.speed;
            track = h.track;
            at = h.at;
        }
    }

    /** Where "here" is (the weather's place); null until the first answer. */
    Weather.Place place() {
        return here;
    }

    /** An aircraft as OpenSky last said: where, and how it was moving. */
    static final class Heard {
        final String hex, callsign;
        final double lat, lon, altitude, speed, track, climb;
        final long at;   // SystemClock.elapsedRealtime() of that position
        final int category;   // what it says it is (ADS-B's emitter category); 0 or 1 mostly: it doesn't say

        Heard(String hex, String callsign, double lat, double lon, double altitude, double speed, double track,
              double climb, long at, int category) {
            this.category = category;
            this.hex = hex;
            this.callsign = callsign;
            this.lat = lat;
            this.lon = lon;
            this.altitude = altitude;
            this.speed = speed;
            this.track = track;
            this.climb = climb;
            this.at = at;
        }
    }

    private static Flights instance;

    static synchronized Flights get(Context context) {
        if (instance == null) instance = new Flights(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final VibeSettings settings;
    private final VrsDatabase vrs;
    private final Handler main = new Handler(Looper.getMainLooper());
    private final ScheduledExecutorService worker = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Flights");
        t.setDaemon(true);
        return t;
    });
    /** Separate executor for adsbdb/VRS enrichment so slow lookups never starve OpenSky polling. */
    private final java.util.concurrent.ExecutorService enricher = Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "FlightsLookup");
        t.setDaemon(true);
        return t;
    });

    /** What's kept from adsbdb, by transponder address, callsign and airline code; each with "e", when it runs out. */
    private final Map<String, JSONObject> aircraft = new ConcurrentHashMap<>(), routes = new ConcurrentHashMap<>(),
            airlines = new ConcurrentHashMap<>();
    private final Set<String> asking = ConcurrentHashMap.newKeySet();
    private boolean loaded, saveDue;   // worker thread only
    private ScheduledFuture<?> saveTask;   // worker thread only

    private volatile List<Heard> heard = Collections.emptyList();
    private volatile Weather.Place here;
    private volatile long nextAskAt;   // elapsedRealtime: not asked again before, across stops and starts

    private Listener listener;   // main thread only, with the three under it
    private boolean running;
    private int chain;
    private ScheduledFuture<?> poll;
    /** adsbdb didn't answer (down, or asked too much): left alone until then. */
    private volatile long adsbdbQuietUntil;

    /** The OpenSky client's token, the client it's for and when it runs out; worker thread only. */
    private String token, tokenFor = "";
    private long tokenUntil, clientRefusedAt;

    private Flights(Context context) {
        this.context = context;
        settings = new VibeSettings(context);
        vrs = new VrsDatabase(context);
    }

    // ---- on and off --------------------------------------------------------------------------------------

    /** Home in front with the flights on: asks now if it's time, then goes on asking; the listener hears every 5 s. */
    void start(Listener l) {
        listener = l;
        if (running) return;
        running = true;
        int mine = ++chain;
        long wait = Math.max(0, nextAskAt - SystemClock.elapsedRealtime());
        poll = worker.schedule(() -> ask(mine), wait, TimeUnit.MILLISECONDS);
        main.removeCallbacks(tick);
        main.post(tick);
    }

    void stop() {
        listener = null;
        if (!running) return;
        running = false;
        if (poll != null) poll.cancel(false);
        main.removeCallbacks(tick);
    }

    /** How many aircraft and routes are kept (Settings shows it beside Clear). */
    int kept() {
        return aircraft.size() + routes.size() + airlines.size();
    }

    /** Whether what's kept has been read from its file yet (Settings asks for it with load). */
    void load(Runnable then) {
        worker.execute(() -> {
            read();
            main.post(then);
        });
    }

    /** Everything kept from adsbdb goes: each aircraft and route is asked about again as it comes by. */
    void clear(Runnable then) {
        worker.execute(() -> {
            read();
            aircraft.clear();
            routes.clear();
            airlines.clear();
            //noinspection ResultOfMethodCallIgnored
            new File(context.getFilesDir(), FILE).delete();
            if (saveTask != null) {
                saveTask.cancel(false);
                saveTask = null;
            }
            saveDue = false;
            main.post(then);
        });
    }

    // ---- where they are: OpenSky ---------------------------------------------------------------------

    /**
     * One answer from OpenSky, then the next one due. Each start has its own run of these (chain): one still
     * waiting for an answer when Home went and came back doesn't go on beside the new one, and the new one
     * doesn't ask before it's time.
     */
    private void ask(int mine) {
        long early = nextAskAt - SystemClock.elapsedRealtime();
        long wait = early > 1000 ? early : answer();
        if (early <= 1000) {
            nextAskAt = SystemClock.elapsedRealtime() + wait;
            askEvery = wait;
        }
        main.post(() -> {
            if (running && mine == chain) poll = worker.schedule(() -> ask(mine), wait, TimeUnit.MILLISECONDS);
        });
    }

    /** Asks OpenSky what's in the air around here; how long to wait before asking again. */
    private long answer() {
        long wait = WITHOUT_MS;
        try {
            read();
            String id = settings.openSkyId(), secret = settings.openSkySecret();
            boolean client = !id.isEmpty() && !secret.isEmpty();
            String bearer = client ? token(id, secret) : null;
            wait = bearer != null ? WITH_CLIENT_MS : WITHOUT_MS;
            Weather.Place place = Weather.place(context, settings);
            if (place == null) return wait;   // not known where: again next time
            here = place;
            double reach = settings.flightsKm() + (bearer != null ? WITH_CLIENT_KM : WITHOUT_KM);
            Reply reply = request(box(place, reach), bearer, null);
            if (reply.code == 401 && bearer != null) {   // the token ran out early: a new one, once
                token = null;
                bearer = token(id, secret);
                wait = bearer != null ? WITH_CLIENT_MS : WITHOUT_MS;
                reach = settings.flightsKm() + (bearer != null ? WITH_CLIENT_KM : WITHOUT_KM);
                reply = request(box(place, reach), bearer, null);
            }
            // The day's allowance is spent: when it says to come back.
            if (reply.code == 429) return Math.max(wait, Math.min(reply.retryAfter, 3600) * 1000L);
            if (reply.code != 200) return wait;
            heard = parse(reply.body);
            if (reply.remaining >= 0 && reply.remaining < LOW_CREDITS) wait *= 2;
        } catch (IOException | JSONException | RuntimeException e) {
            // offline, or an answer that isn't one: what was heard is moved along until it's stale
        }
        return wait;
    }

    /** The area asked for: this far each way from here, narrowed where that would cost a second credit. */
    static String box(Weather.Place place, double km) {
        double dLat = km / 110.57;
        double dLon = km / (111.32 * Math.max(0.2, Math.cos(Math.toRadians(place.lat))));
        if (4 * dLat * dLon > MAX_AREA) dLon = MAX_AREA / (4 * dLat);
        return String.format(Locale.ROOT, STATES, Math.max(-90, place.lat - dLat), Math.max(-180, place.lon - dLon),
                Math.min(90, place.lat + dLat), Math.min(180, place.lon + dLon));
    }

    /** The aircraft in an answer that are in the air and say where they are. */
    static List<Heard> parse(String body) throws JSONException {
        JSONObject root = new JSONObject(body);
        long time = root.optLong("time", 0);
        JSONArray states = root.optJSONArray("states");
        List<Heard> out = new ArrayList<>();
        long now = SystemClock.elapsedRealtime();
        for (int i = 0; states != null && i < states.length(); i++) {
            JSONArray s = states.optJSONArray(i);
            if (s == null || s.length() < 12 || s.optBoolean(8, false)) continue;   // on the ground
            double lon = s.optDouble(5, Double.NaN), lat = s.optDouble(6, Double.NaN);
            if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
            long age = Math.max(0, time - s.optLong(3, time));   // seconds, by OpenSky's clock, not the TV's
            if (age > 60) continue;
            // Its height by satellite, else by air pressure (which reads below sea level on a high-pressure day,
            // near the ground). Neither is ever under the ground: at walking pace there it has landed (some don't
            // say so), and is left out; else it's low, and shown as that.
            double altitude = s.optDouble(13, Double.NaN);
            if (Double.isNaN(altitude)) altitude = s.optDouble(7, Double.NaN);
            if (altitude <= 0) {
                if (s.optDouble(9, Double.NaN) < 40) continue;
                altitude = 0;
            }
            String callsign = s.isNull(1) ? "" : s.optString(1, "").trim().toUpperCase(Locale.ROOT);
            out.add(new Heard(s.optString(0, "").toLowerCase(Locale.ROOT), callsign, lat, lon, altitude,
                    s.optDouble(9, Double.NaN), s.optDouble(10, Double.NaN), s.optDouble(11, Double.NaN),
                    now - age * 1000, s.optInt(17, 0)));
        }
        return out;
    }

    /** A token for this OpenSky client, kept until shortly before it runs out; null to go without (refused, or offline). */
    private String token(String id, String secret) {
        String client = id + '\n' + secret;
        long now = SystemClock.elapsedRealtime();
        if (!client.equals(tokenFor)) {   // another client typed in: the old one's token and refusal don't count
            tokenFor = client;
            token = null;
            clientRefusedAt = 0;
        }
        if (token != null && now < tokenUntil) return token;
        token = null;
        if (clientRefusedAt != 0 && now - clientRefusedAt < 30 * 60_000L) return null;
        try {
            Reply reply = request(TOKEN, null, "grant_type=client_credentials&client_id=" + Uri.encode(id)
                    + "&client_secret=" + Uri.encode(secret));
            if (reply.code != 200) {
                if (reply.code == 400 || reply.code == 401) clientRefusedAt = now;   // not a client OpenSky knows
                return null;
            }
            JSONObject o = new JSONObject(reply.body);
            token = o.optString("access_token", "");
            if (token.isEmpty()) token = null;
            tokenUntil = now + Math.max(60, o.optLong("expires_in", 1800) - 60) * 1000;
            return token;
        } catch (IOException | JSONException e) {
            return null;
        }
    }

    // ---- where they are now ----------------------------------------------------------------------------

    private final Runnable tick = new Runnable() {
        @Override
        public void run() {
            if (!running) return;
            main.postDelayed(this, TICK_MS);
            if (listener != null) {
                List<Near> near = near();
                listener.onFlights(near, all());
            }
        }
    };

    /** Every aircraft heard of and not gone stale, as last heard: the map moves each along itself. */
    private List<Spot> all() {
        List<Spot> out = new ArrayList<>();
        long now = SystemClock.elapsedRealtime();
        long stale = Math.min(askEvery, 600_000) + STALE_MS;
        for (Heard h : heard) {
            if (now - h.at > stale) continue;
            JSONObject known = fresh(aircraft, h.hex);
            out.add(new Spot(h, known != null ? Near.text(known, "y") : null));
        }
        return out;
    }

    /** The nearest within the distance set, each moved along since it was heard; their details asked for. */
    private List<Near> near() {
        Weather.Place place = here;
        List<Near> out = new ArrayList<>();
        if (place == null) return out;
        double reach = settings.flightsKm();
        long now = SystemClock.elapsedRealtime();
        long stale = Math.min(askEvery, 600_000) + STALE_MS;
        for (Heard h : heard) {
            long since = now - h.at;
            if (since > stale) continue;
            double lat = h.lat, lon = h.lon;
            if (!Double.isNaN(h.speed) && !Double.isNaN(h.track)) {
                double m = h.speed * since / 1000.0, t = Math.toRadians(h.track);
                lat += m * Math.cos(t) / 110_570;
                lon = wrapLon(lon + m * Math.sin(t) / (111_320 * Math.max(0.2, Math.cos(Math.toRadians(lat)))));
            }
            double km = km(place.lat, place.lon, lat, lon);
            if (km > reach) continue;
            JSONObject route = fresh(routes, h.callsign);
            if (route != null && !route.optBoolean("m") && !between(route, lat, lon)) {
                wrongRoute(h.callsign, route.optBoolean("v"));
                route = null;
            }
            String code = airlineOf(h.callsign);
            out.add(new Near(h, km, bearing(place.lat, place.lon, lat, lon), lat, lon, fresh(aircraft, h.hex), route,
                    code != null ? fresh(airlines, code) : null));
        }
        Collections.sort(out, (a, b) -> Double.compare(a.km, b.km));
        if (out.size() > SHOWN) out = new ArrayList<>(out.subList(0, SHOWN));
        for (Near n : out) lookUp(n.hex, n.callsign, n.lat, n.lon);
        return out;
    }

    static double wrapLon(double lon) {
        return ((lon + 180.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    static double deltaLon(double lon1, double lon2) {
        return ((lon2 - lon1 + 540.0) % 360.0 + 360.0) % 360.0 - 180.0;
    }

    static double km(double lat1, double lon1, double lat2, double lon2) {
        double x = deltaLon(lon1, lon2) * 111.32 * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = (lat2 - lat1) * 110.57;
        return Math.hypot(x, y);
    }

    static double bearing(double lat1, double lon1, double lat2, double lon2) {
        double x = deltaLon(lon1, lon2) * Math.cos(Math.toRadians((lat1 + lat2) / 2));
        double y = lat2 - lat1;
        return (Math.toDegrees(Math.atan2(x, y)) + 360) % 360;
    }

    /** Along a great circle, properly: routes cross oceans, where the flat sums above are far out. */
    static double far(double lat1, double lon1, double lat2, double lon2) {
        double a = Math.toRadians(lat1), b = Math.toRadians(lat2), d = Math.toRadians(deltaLon(lon1, lon2));
        double h = Math.pow(Math.sin((b - a) / 2), 2) + Math.cos(a) * Math.cos(b) * Math.pow(Math.sin(d / 2), 2);
        return 2 * 6371 * Math.asin(Math.min(1, Math.sqrt(h)));
    }

    /** Whether an aircraft here can be flying this route: not far off the way between its two airports. */
    static boolean between(JSONObject route, double lat, double lon) {
        if (!route.has("ola") || !route.has("dla")) return true;   // not known where they are: taken as said
        double ola = route.optDouble("ola"), olo = route.optDouble("olo");
        double dla = route.optDouble("dla"), dlo = route.optDouble("dlo");
        if (!Double.isFinite(ola) || !Double.isFinite(olo) || !Double.isFinite(dla) || !Double.isFinite(dlo)) {
            return true;
        }
        double direct = far(ola, olo, dla, dlo);
        return far(ola, olo, lat, lon) + far(lat, lon, dla, dlo) <= direct * 1.3 + 150;
    }

    /** The airline's code a callsign starts with ("UAL" of UAL510); null for a registration and the like. */
    static String airlineOf(String callsign) {
        if (callsign.length() < 4) return null;
        for (int i = 0; i < 3; i++) {
            char c = callsign.charAt(i);
            if (c < 'A' || c > 'Z') return null;
        }
        return Character.isDigit(callsign.charAt(3)) ? callsign.substring(0, 3) : null;
    }

    // ---- what they are: adsbdb, and what's kept of it ---------------------------------------------------

    /** What's kept under this key and hasn't run out; null to ask (again). */
    private static JSONObject fresh(Map<String, JSONObject> kept, String key) {
        if (key == null || key.isEmpty()) return null;
        JSONObject o = kept.get(key);
        return o != null && o.optLong("e") > System.currentTimeMillis() ? o : null;
    }

    /**
     * Asks for what isn't kept about this aircraft and its flight, one request after the other: adsbdb, and for a
     * route it doesn't know, or has wrong (the aircraft is nowhere near it), Virtual Radar Server's list.
     */
    private void lookUp(String hex, String callsign, double lat, double lon) {
        String code = airlineOf(callsign);
        JSONObject kept = code != null ? fresh(routes, callsign) : null;
        boolean plane = !hex.isEmpty() && fresh(aircraft, hex) == null;
        boolean route = code != null && kept == null;   // a registration flies no route
        boolean second = kept != null && kept.optBoolean("m") && !kept.optBoolean("t");
        boolean whose = code != null && fresh(airlines, code) == null;
        if (!plane && !route && !second && !whose) return;
        if (SystemClock.elapsedRealtime() < adsbdbQuietUntil || !asking.add(hex + '/' + callsign)) return;
        enricher.execute(() -> {
            try {
                if (SystemClock.elapsedRealtime() < adsbdbQuietUntil) return;
                if (plane && fresh(aircraft, hex) == null) keepAircraft(hex);
                if (route && fresh(routes, callsign) == null) keepRoute(callsign);
                if (route || second) {
                    JSONObject r = fresh(routes, callsign);
                    if (r != null && r.optBoolean("m") && !r.optBoolean("t")) {
                        keepSecondRoute(callsign, code, lat, lon, r.optLong("e"));
                    }
                }
                if (code != null && fresh(airlines, code) == null) keepAirline(code);
            } catch (IOException | RuntimeException e) {
                // Offline or a server down: nothing kept, and asked again in a minute if it's still near.
                adsbdbQuietUntil = SystemClock.elapsedRealtime() + 60_000;
            } finally {
                asking.remove(hex + '/' + callsign);
            }
        });
    }

    // ---- the second opinion on a route: Virtual Radar Server's standing data ---------------------------

    /**
     * The route the list has for this callsign, kept in adsbdb's place ("v": from the list); of a flight with
     * stops, the leg the aircraft is nearest to being on. Not in the list: the miss stays, marked as tried ("t").
     */
    private void keepSecondRoute(String callsign, String code, double lat, double lon, long until) throws IOException {
        String[] stops = vrs.stops(code, callsign);
        JSONObject keep = new JSONObject();
        try {
            if (stops == null) {
                keep.put("m", true).put("t", true).put("e", until);
            } else {
                String[] from = null, to = null;
                String fromCode = stops[0], toCode = stops[1];
                double least = Double.MAX_VALUE;
                for (int i = 0; i + 1 < stops.length; i++) {
                    String[] a = vrs.airport(stops[i]), b = vrs.airport(stops[i + 1]);
                    double off = a.length == 0 || b.length == 0 || a[2].isEmpty() || b[2].isEmpty() ? Double.MAX_VALUE / 2
                            : far(VrsDatabase.number(a[2]), VrsDatabase.number(a[3]), lat, lon) + far(lat, lon, VrsDatabase.number(b[2]), VrsDatabase.number(b[3]))
                            - far(VrsDatabase.number(a[2]), VrsDatabase.number(a[3]), VrsDatabase.number(b[2]), VrsDatabase.number(b[3]));
                    if (off < least) {
                        least = off;
                        from = a;
                        to = b;
                        fromCode = stops[i];
                        toCode = stops[i + 1];
                    }
                }
                boolean known = from != null && from.length > 0 && to.length > 0;
                keep.put("v", true).put("a", "").put("f", "")
                        .put("oc", known ? from[0] : fromCode).put("on", known ? from[1] : "")
                        .put("dc", known ? to[0] : toCode).put("dn", known ? to[1] : "")
                        .put("e", System.currentTimeMillis() + ROUTE_MS);
                if (known && !from[2].isEmpty() && !to[2].isEmpty()) {
                    keep.put("ola", VrsDatabase.number(from[2])).put("olo", VrsDatabase.number(from[3]))
                            .put("dla", VrsDatabase.number(to[2])).put("dlo", VrsDatabase.number(to[3]));
                }
            }
        } catch (JSONException e) {
            return;
        }
        put(routes, callsign, keep);
    }

    private void keepAircraft(String hex) throws IOException {
        Reply reply = request(ADSBDB + "aircraft/" + Uri.encode(hex), null, null);
        JSONObject a = reply.code == 200 ? object(reply.body, "aircraft") : null;
        if (a == null && reply.code != 404 && reply.code != 200) throw new IOException("adsbdb " + reply.code);
        JSONObject keep = new JSONObject();
        try {
            if (a == null) {
                keep.put("m", true).put("e", System.currentTimeMillis() + AIRCRAFT_MISS_MS);
            } else {
                String maker = said(a, "manufacturer").trim(), type = said(a, "type").trim();
                keep.put("y", (maker + ' ' + type).trim()).put("r", said(a, "registration").trim())
                        .put("e", System.currentTimeMillis() + AIRCRAFT_MS);
            }
        } catch (JSONException e) {
            return;
        }
        put(aircraft, hex, keep);
    }

    private void keepRoute(String callsign) throws IOException {
        Reply reply = request(ADSBDB + "callsign/" + Uri.encode(callsign), null, null);
        JSONObject r = reply.code == 200 ? object(reply.body, "flightroute") : null;
        if (r == null && reply.code != 404 && reply.code != 200) throw new IOException("adsbdb " + reply.code);
        JSONObject keep = new JSONObject();
        try {
            JSONObject from = r != null ? r.optJSONObject("origin") : null;
            JSONObject to = r != null ? r.optJSONObject("destination") : null;
            if (from == null || to == null) {
                keep.put("m", true).put("e", System.currentTimeMillis() + ROUTE_MISS_MS);
            } else {
                JSONObject airline = r.optJSONObject("airline");
                keep.put("a", airline != null ? said(airline, "name") : "")
                        .put("f", said(r, "callsign_iata"))
                        .put("oc", airportCode(from)).put("on", said(from, "municipality"))
                        .put("dc", airportCode(to)).put("dn", said(to, "municipality"))
                        .put("e", System.currentTimeMillis() + ROUTE_MS);
                double ola = from.optDouble("latitude", Double.NaN);
                double olo = from.optDouble("longitude", Double.NaN);
                double dla = to.optDouble("latitude", Double.NaN);
                double dlo = to.optDouble("longitude", Double.NaN);
                if (Double.isFinite(ola) && Double.isFinite(olo) && Double.isFinite(dla) && Double.isFinite(dlo)) {
                    keep.put("ola", ola).put("olo", olo).put("dla", dla).put("dlo", dlo);
                }
            }
        } catch (JSONException e) {
            return;
        }
        put(routes, callsign, keep);
    }

    /** An airport as people know it ("EWR"), else by its four letters. */
    private static String airportCode(JSONObject airport) {
        String iata = said(airport, "iata_code").trim();
        return iata.isEmpty() ? said(airport, "icao_code").trim() : iata;
    }

    /**
     * The airline by its three-letter code, from Virtual Radar Server's list of them: its name and the two
     * characters its flight numbers start with ("s": from this list; adsbdb has some of those out of date).
     */
    private void keepAirline(String code) throws IOException {
        List<String> f = vrs.airline(code);
        JSONObject keep = new JSONObject();
        try {
            String name = f != null && f.size() >= 4 ? f.get(1).trim() : "";
            if (name.isEmpty()) keep.put("m", true).put("s", "v").put("e", System.currentTimeMillis() + AIRLINE_MISS_MS);
            else keep.put("n", name).put("i", f.get(3).trim()).put("s", "v")
                    .put("e", System.currentTimeMillis() + AIRLINE_MS);
        } catch (JSONException e) {
            return;
        }
        put(airlines, code, keep);
    }

    /** adsbdb's {"response": {name: {...}}}, or null when that's not what came. */
    /** What a server said for this, "" where it said nothing (null, which otherwise reads as the word). */
    static String said(JSONObject o, String key) {
        return o.isNull(key) ? "" : o.optString(key, "");
    }

    private static JSONObject object(String body, String name) {
        try {
            JSONObject response = new JSONObject(body).optJSONObject("response");
            return response != null ? response.optJSONObject(name) : null;
        } catch (JSONException e) {
            return null;
        }
    }

    /** This route isn't the one being flown: hidden as not known, and asked for again in a day. */
    private void wrongRoute(String callsign, boolean second) {
        try {
            put(routes, callsign, new JSONObject().put("m", true).put("t", second)   // adsbdb's: the list is asked next
                    .put("e", System.currentTimeMillis() + ROUTE_WRONG_MS));
        } catch (JSONException ignored) {
            // fixed keys
        }
    }

    private void put(Map<String, JSONObject> kept, String key, JSONObject value) {
        kept.put(key, value);
        worker.execute(() -> {
            if (saveDue) return;
            saveDue = true;
            saveTask = worker.schedule(this::write, 30, TimeUnit.SECONDS);
        });
    }

    /** What's kept, read once from its file; what has run out is left behind. Worker thread. */
    private void read() {
        if (loaded) return;
        loaded = true;
        File file = new File(context.getFilesDir(), FILE);
        if (!file.isFile()) return;
        try {
            JSONObject root = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
            fill(aircraft, root.optJSONObject("aircraft"));
            fill(routes, root.optJSONObject("routes"));
            fill(airlines, root.optJSONObject("airlines"));
            // Only what the list said: asked again otherwise.
            for (Iterator<JSONObject> i = airlines.values().iterator(); i.hasNext(); ) if (!i.next().has("s")) i.remove();
        } catch (IOException | JSONException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();   // not one of ours any more: started again
        }
    }

    private static void fill(Map<String, JSONObject> kept, JSONObject from) {
        if (from == null) return;
        long now = System.currentTimeMillis();
        for (Iterator<String> keys = from.keys(); keys.hasNext(); ) {
            String key = keys.next();
            JSONObject o = from.optJSONObject(key);
            if (o != null && o.optLong("e") > now) kept.putIfAbsent(key, o);
        }
    }

    /** What's kept, written beside its file and then put in its place. Worker thread. */
    private void write() {
        saveDue = false;
        File file = new File(context.getFilesDir(), FILE), part = new File(context.getFilesDir(), FILE + ".part");
        try {
            JSONObject root = new JSONObject();
            root.put("aircraft", pruned(aircraft)).put("routes", pruned(routes)).put("airlines", pruned(airlines));
            try (OutputStream out = new FileOutputStream(part)) {
                out.write(root.toString().getBytes(StandardCharsets.UTF_8));
            }
            if (!part.renameTo(file)) throw new IOException("rename");
        } catch (IOException | JSONException | RuntimeException e) {
            //noinspection ResultOfMethodCallIgnored
            part.delete();   // kept in memory all the same; written the next time something's added
        }
    }

    /** What hasn't run out, and of that the MAX_KEPT lasting longest; the rest is let go. */
    private static JSONObject pruned(Map<String, JSONObject> kept) throws JSONException {
        long now = System.currentTimeMillis();
        List<Map.Entry<String, JSONObject>> all = new ArrayList<>(kept.entrySet());
        Collections.sort(all, (a, b) -> Long.compare(b.getValue().optLong("e"), a.getValue().optLong("e")));
        JSONObject out = new JSONObject();
        for (int i = 0; i < all.size(); i++) {
            Map.Entry<String, JSONObject> e = all.get(i);
            if (i < MAX_KEPT && e.getValue().optLong("e") > now) out.put(e.getKey(), e.getValue());
            else kept.remove(e.getKey(), e.getValue());
        }
        return out;
    }

    // ---- asking --------------------------------------------------------------------------------------

    private static final class Reply {
        int code;
        String body = "";
        /** OpenSky's credits left (-1 when not said), and after how many seconds to come back once they're spent. */
        int remaining = -1, retryAfter = 600;
    }

    /** A GET (or, with form, a POST of it) whose answer is read whatever its code, at most 4 MB of it. */
    private static Reply request(String url, String bearer, String form) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10_000);
            c.setReadTimeout(20_000);
            c.setRequestProperty("Accept", "application/json");
            if (bearer != null) c.setRequestProperty("Authorization", "Bearer " + bearer);
            if (form != null) {
                c.setDoOutput(true);
                c.setRequestProperty("Content-Type", "application/x-www-form-urlencoded");
                try (OutputStream out = c.getOutputStream()) {
                    out.write(form.getBytes(StandardCharsets.UTF_8));
                }
            }
            Reply reply = new Reply();
            reply.code = c.getResponseCode();
            reply.remaining = number(c.getHeaderField("X-Rate-Limit-Remaining"), -1);
            reply.retryAfter = number(c.getHeaderField("X-Rate-Limit-Retry-After-Seconds"), 600);
            try (InputStream in = reply.code < 400 ? c.getInputStream() : c.getErrorStream()) {
                if (in != null) reply.body = new String(Http.readAll(in, 4 * 1024 * 1024), StandardCharsets.UTF_8);
            }
            return reply;
        } finally {
            c.disconnect();
        }
    }

    private static int number(String header, int otherwise) {
        if (header == null) return otherwise;
        try {
            return (int) Double.parseDouble(header.trim());
        } catch (NumberFormatException e) {
            return otherwise;
        }
    }
}
