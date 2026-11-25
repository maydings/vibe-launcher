package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Executors;

/**
 * Airlines' logos for the flights' card (FlightBoard): by an airline's three-letter code from a collection on
 * GitHub, else by the two characters of its flight numbers from Aviasales. Each is downloaded once and kept in
 * the TV's cache, cut to what's drawn of it; a few are held in memory.
 */
final class AirlineLogos {

    @SuppressLint("StaticFieldLeak")   // the application's own context, which lives as long as this does
    private static AirlineLogos instance;

    static synchronized AirlineLogos get(Context context) {
        if (instance == null) instance = new AirlineLogos(context.getApplicationContext());
        return instance;
    }

    private final Context context;
    private final Handler main = new Handler(Looper.getMainLooper());

    private AirlineLogos(Context context) {
        this.context = context;
    }

    /** An airline's logo, cut to what's drawn of it. */
    static final class Logo {
        final Bitmap picture;

        Logo(Bitmap picture) {
            this.picture = picture;
        }
    }

    interface Listener {
        /** On the main thread; null when the airline has no logo to be had. */
        void onLogo(Logo logo);
    }

    /** By the airline's two-character code, where Aviasales has it: for those the collection below lacks. */
    private static final String LOGO_SHARP = "https://pics.avs.io/480/160/";
    /** By its three-letter code, from a collection on GitHub: the first asked. */
    private static final String LOGO_SMALL =
            "https://raw.githubusercontent.com/Jxck-S/airline-logos/main/radarbox_banners/";
    private static final long NO_LOGO_MS = 7L * 24 * 60 * 60 * 1000;

    private final java.util.concurrent.ExecutorService painter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Airline logos");
        t.setDaemon(true);
        return t;
    });
    // All three on the main thread only.
    private final Map<String, Logo> logos = new java.util.HashMap<>();
    /** What's been asked and answered since Home started: "UPS", and "UPS/5X" once the second code was known. */
    private final Set<String> logosAsked = new java.util.HashSet<>();
    private final Map<String, List<Listener>> logosWaited = new java.util.HashMap<>();
    private long logosQuietUntil;
    /** Those a server turned away for now (too many requests, say), and when each may be asked for again. */
    private final Map<String, Long> logosRefused = new java.util.HashMap<>();

    /** A server said no to this one for a reason other than having none. */
    private static final class Refused extends IOException {
    }

    /**
     * The airline's logo, from the TV's cache or downloaded once (kept there until Android clears it). Answered at
     * once with what's held, and again if a download brings one. The airline's own (three-letter) logo is tried
     * first, and the one under its two-character code only when there is none: one that's held is kept.
     */
    void logo(String icao, String iata, Listener to) {
        if (icao == null) {
            to.onLogo(null);
            return;
        }
        String asked = iata != null ? icao + '/' + iata : icao;
        Logo held = logos.get(icao);
        Long retryAt = logosRefused.get(asked);
        boolean refusedLately = retryAt != null && SystemClock.elapsedRealtime() < retryAt;
        if (logosAsked.contains(asked) || refusedLately || SystemClock.elapsedRealtime() < logosQuietUntil) {
            to.onLogo(held);
            return;
        }
        if (held != null) {
            to.onLogo(held);
            logosAsked.add(asked);
            return;
        }
        List<Listener> waiting = logosWaited.get(asked);
        if (waiting != null) {
            waiting.add(to);
            return;
        }
        waiting = new ArrayList<>();
        waiting.add(to);
        logosWaited.put(asked, waiting);
        painter.execute(() -> {
            Logo logo = null;
            boolean failed = false, turnedAway = false;
            try {
                logo = loadLogo(icao, iata);
            } catch (Refused e) {
                turnedAway = true;   // this one only, and for now: asked again in ten minutes
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                failed = true;   // offline, or the server's down: asked again in a minute
            }
            Logo got = logo;
            boolean again = failed, refused = turnedAway;
            main.post(() -> {
                if (again) logosQuietUntil = SystemClock.elapsedRealtime() + 60_000;
                else if (refused) logosRefused.put(asked, SystemClock.elapsedRealtime() + 10 * 60_000);
                else logosAsked.add(asked);
                if (got != null) {
                    // No more than 40 held: the rest go, and are read again from the TV's cache when next
                    // asked for (so what's been asked goes with them: else they'd be answered with none).
                    if (logos.size() >= 40) {
                        logos.clear();
                        logosAsked.clear();
                        logosAsked.add(asked);
                    }
                    logos.put(icao, got);
                }
                List<Listener> all = logosWaited.remove(asked);
                if (all != null) for (Listener l : all) l.onLogo(got != null ? got : logos.get(icao));
            });
        });
    }

    /**
     * By the airline's three letters first, which are its alone; the two characters of its flight numbers are
     * shared between airlines here and there (NetJets' are another's at Aviasales), so that one only where the
     * first has nothing.
     */
    private Logo loadLogo(String icao, String iata) throws IOException {
        File dir = new File(context.getCacheDir(), "airline-logos");
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache");
        File own = new File(dir, icao + ".png"), shared = new File(dir, icao + ".2.png");
        Logo logo = readLogo(own);
        boolean refused = false;
        if (logo == null) {
            try {
                logo = fetchLogo(LOGO_SMALL + icao + ".png", own, new File(dir, icao + ".no"));
            } catch (Refused e) {
                refused = true;   // the other source meanwhile
            }
        }
        if (logo == null) logo = readLogo(shared);
        if (logo == null && iata != null) {
            try {
                logo = fetchLogo(LOGO_SHARP + iata + "@2x.png", shared, new File(dir, icao + ".2.no"));
            } catch (Refused e) {
                refused = true;
            }
        }
        if (logo == null && refused) throw new Refused();
        return logo;
    }

    /**
     * Downloads one; where there's none (a 404) that's noted, and not asked again for a week. Refused for
     * another reason (too many requests, say), that's said (Refused) and nothing's noted: asked again later.
     */
    private static Logo fetchLogo(String url, File to, File none) throws IOException {
        long noneAge = System.currentTimeMillis() - none.lastModified();
        if (none.exists() && noneAge >= 0 && noneAge < NO_LOGO_MS) return null;
        byte[] bytes;
        try {
            bytes = Http.get(url, 1024 * 1024);
        } catch (IOException e) {
            String why = String.valueOf(e.getMessage());
            if (!why.startsWith("HTTP 4")) throw e;
            if (!why.equals("HTTP 404")) throw new Refused();
            bytes = null;
        }
        if (bytes != null) {
            File part = new File(to.getPath() + ".part");
            try (FileOutputStream out = new FileOutputStream(part)) {
                out.write(bytes);
            } catch (IOException e) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw e;
            }
            if (!part.renameTo(to)) {
                //noinspection ResultOfMethodCallIgnored
                part.delete();
                throw new IOException("not kept");
            }
            Logo logo = readLogo(to);
            if (logo != null) return logo;
        }
        try (FileOutputStream out = new FileOutputStream(none)) {   // none there, or not a picture
            out.write(0);
        }
        return null;
    }

    private static Logo readLogo(File file) {
        if (!file.isFile()) return null;
        BitmapFactory.Options bounds = new BitmapFactory.Options();
        bounds.inJustDecodeBounds = true;
        BitmapFactory.decodeFile(file.getPath(), bounds);
        if (bounds.outWidth < 8 || bounds.outHeight < 8
                || bounds.outWidth > 2048 || bounds.outHeight > 2048
                || (long) bounds.outWidth * bounds.outHeight > 2_000_000L) {
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
        Bitmap b = BitmapFactory.decodeFile(file.getPath());
        if (b == null || b.getWidth() < 8 || b.getHeight() < 8) {
            if (b != null) b.recycle();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
        // Cut to what's drawn: they come with clear space around them, each a different amount.
        int w = b.getWidth(), h = b.getHeight();
        int[] px = new int[w * h];
        b.getPixels(px, 0, w, 0, 0, w, h);
        int l = w, t = h, r = -1, bottom = -1;
        for (int y = 0; y < h; y++) {
            for (int x = 0; x < w; x++) {
                if ((px[y * w + x] >>> 24) < 32) continue;
                if (x < l) l = x;
                if (x > r) r = x;
                if (y < t) t = y;
                if (y > bottom) bottom = y;
            }
        }
        if (r < l || bottom < t) {
            b.recycle();
            //noinspection ResultOfMethodCallIgnored
            file.delete();
            return null;
        }
        if (l == 0 && t == 0 && r == w - 1 && bottom == h - 1) return new Logo(b);
        Bitmap cropped = Bitmap.createBitmap(b, l, t, r - l + 1, bottom - t + 1);
        b.recycle();
        return new Logo(cropped);
    }
}
