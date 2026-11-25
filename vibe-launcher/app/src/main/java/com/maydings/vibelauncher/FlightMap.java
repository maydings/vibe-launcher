package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.ColorMatrixColorFilter;
import android.graphics.DashPathEffect;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.os.SystemClock;
import android.view.View;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * The flights' ground (FlightBoard): a map of the place, dark, with "here" a little left of the middle (what's
 * said of the flight is at the other end), a ring at the distance set, and every aircraft heard of drawn where
 * it is by now. OpenSky says where each was every half minute at best: in between, each is moved along its
 * heading at its speed, a few times a second, and eased over to where the next answer puts it.
 *
 * The map is OpenStreetMap's own tiles for this one view, downloaded once and kept in the cache (asked again
 * only when Android has cleared it, or the place or the distance changes), drawn dark into one picture the size
 * of the screen. Without them (offline) the aircraft move over the plain ground.
 */
final class FlightMap extends View {

    private static final String TILES = "https://tile.openstreetmap.org/%d/%d/%d.png";
    private static final int TILE = 256;
    private static final long FRAME_MS = 100, EASE_MS = 2_000;
    /** Where "here" is on the screen, and how far up it the distance set reaches: clear of what's said at the end. */
    private static final float HERE_X = 0.36f, HERE_Y = 0.47f, RING = 0.43f;
    /**
     * A light map turned dark: its lightness upside down, its colours kept (water stays blue, parks green), and
     * dimmed, so it sits back behind the aircraft and what's said.
     */
    private static final float DIM = 0.8f;
    private static final float[] DARK = {
            0.574f * DIM, -1.430f * DIM, -0.144f * DIM, 0, 255 * DIM,
            -0.426f * DIM, -0.430f * DIM, -0.144f * DIM, 0, 255 * DIM,
            -0.426f * DIM, -1.430f * DIM, 0.856f * DIM, 0, 255 * DIM,
            0, 0, 0, 1, 0};

    private static final ExecutorService painter = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Flights map");
        t.setDaemon(true);
        return t;
    });

    private final float density;
    private final Paint picture = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG), fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint name = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Path plane = new Path(), helicopter = new Path(), light = new Path(), turned = new Path();
    private final android.graphics.Matrix turn = new android.graphics.Matrix();

    private double lat = Double.NaN, lon, reachKm;
    /** The view now: the zoom its tiles are of, where "here" is in that zoom's pixels, and screen pixels to each. */
    private int zoom;
    private double hereX, hereY, scale;
    private Bitmap map;
    /** What the picture is of, or is being made of ("lat,lon,km,w,h"), and when a failed one may be tried again. */
    private String mapOf, making;
    private long againAt;

    private List<Flights.Spot> spots = Collections.emptyList();
    private String chosen;
    /** By transponder address: where each was last drawn, and how far that is from where it's now said to be. */
    private final Map<String, float[]> drawnAt = new HashMap<>();
    private final Map<String, Eased> easing = new HashMap<>();

    private static final class Eased {
        long heardAt, from;
        float dx, dy;
    }

    FlightMap(Context context) {
        super(context);
        density = getResources().getDisplayMetrics().density;
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(density);
        ring.setColor(0x40FFFFFF);
        ring.setPathEffect(new DashPathEffect(new float[]{6 * density, 6 * density}, 0));
        name.setTextSize(11 * density);
        name.setTypeface(UiFont.get(context, R.string.font_regular));
        // An airliner from above, nose up, in a square of 2 by 2.
        plane.moveTo(0f, -1f);
        // Down one side: the nose rounding into a slim body, a swept wing that narrows to its tip, a small swept
        // tailplane, the tail's point.
        float[] half = {0.07f, -0.88f, 0.1f, -0.7f, 0.1f, -0.22f, 0.98f, 0.38f, 1f, 0.48f, 0.1f, 0.18f,
                0.085f, 0.66f, 0.4f, 0.9f, 0.41f, 0.98f, 0.06f, 0.86f, 0f, 1f};
        for (int i = 0; i < half.length; i += 2) plane.lineTo(half[i], half[i + 1]);
        for (int i = half.length - 4; i >= 0; i -= 2) plane.lineTo(-half[i], half[i + 1]);
        plane.close();
        // A helicopter: its cabin, the boom behind it with the tail rotor across its end, and the blades over all.
        helicopter.addOval(new RectF(-0.26f, -0.8f, 0.26f, 0.15f), Path.Direction.CW);
        add(helicopter, -0.06f, 0.1f, 0.06f, 1f, 0f);
        add(helicopter, -0.22f, 0.86f, 0.22f, 0.98f, 0f);
        add(helicopter, -0.07f, -1.3f, 0.07f, 0.7f, 45f);
        add(helicopter, -0.07f, -1.3f, 0.07f, 0.7f, -45f);
        // A light aircraft, high-winged: a slim body, one straight wing well forward, a small tailplane.
        Path body = new Path();
        body.moveTo(0f, -1f);
        float[] side = {0.11f, -0.82f, 0.11f, 0.1f, 0.05f, 1f};
        for (int i = 0; i < side.length; i += 2) body.lineTo(side[i], side[i + 1]);
        for (int i = side.length - 2; i >= 0; i -= 2) body.lineTo(-side[i], side[i + 1]);
        body.close();
        light.addPath(body);
        add(light, -1f, -0.52f, 1f, -0.2f, 0f);
        add(light, -0.36f, 0.76f, 0.36f, 0.96f, 0f);
    }

    /** One more bar in a shape, turned about its own middle: joined to what's there, not cut out of it. */
    private static void add(Path to, float l, float t, float r, float b, float degrees) {
        Path bar = new Path();
        bar.addRect(l, t, r, b, Path.Direction.CW);
        if (degrees != 0f) {
            android.graphics.Matrix m = new android.graphics.Matrix();
            m.setRotate(degrees, (l + r) / 2, (t + b) / 2);
            bar.transform(m);
        }
        to.op(bar, Path.Op.UNION);
    }

    /** Where the map is of, and how far out the ring goes. */
    void setPlace(double lat, double lon, double reachKm) {
        if (lat == this.lat && lon == this.lon && reachKm == this.reachKm) {
            make();   // a map that couldn't be had: again, in its time
            return;
        }
        this.lat = lat;
        this.lon = lon;
        this.reachKm = reachKm;
        look();
        forget();
        make();
    }

    /**
     * The view has changed (the place, the distance, the size): the map that's up is of the old one, and the
     * aircraft and the ring are drawn by the new. It goes, the plain ground showing until the new map is made;
     * and a wait that was for the old view's map isn't kept for this one's.
     */
    Runnable onDrawn;
    private final android.os.Handler mainHandler = new android.os.Handler(android.os.Looper.getMainLooper());
    private final Runnable nextFrame = this::invalidate;

    private void forget() {
        map = null;
        mapOf = null;
        againAt = 0;
        drawnAt.clear();
        easing.clear();
        invalidate();
    }

    void setSpots(List<Flights.Spot> spots) {
        this.spots = spots;
        drawnAt.keySet().retainAll(hexes(spots));
        easing.keySet().retainAll(drawnAt.keySet());
        if (isShown()) invalidate();
    }

    private static java.util.Set<String> hexes(List<Flights.Spot> spots) {
        java.util.Set<String> out = new java.util.HashSet<>();
        for (Flights.Spot s : spots) out.add(s.hex);
        return out;
    }

    /** The one what's said is about: drawn bigger, in a ring. */
    void setChosen(String hex) {
        chosen = hex;
    }

    /** Whether the map itself is up (its makers are then named beside it). */
    boolean drawn() {
        return map != null;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldW, int oldH) {
        look();
        if (w != oldW || h != oldH) forget();
        make();
    }

    /** Works out the view: the zoom whose tiles come nearest the screen's pixels (0.75 to 1.5 of them). */
    private void look() {
        if (Double.isNaN(lat) || getHeight() == 0 || reachKm <= 0) return;
        double metres = reachKm * 1000 / (RING * getHeight());   // to a screen pixel
        double atZero = 156543.03392 * Math.cos(Math.toRadians(lat));   // to a tile's pixel, at zoom 0
        zoom = Math.max(3, Math.min(18, (int) Math.ceil(Math.log(atZero / (1.5 * metres)) / Math.log(2))));
        scale = atZero / Math.pow(2, zoom) / metres;
        hereX = worldX(lon, zoom);
        hereY = worldY(lat, zoom);
    }

    private static double worldX(double lon, int zoom) {
        return (lon + 180) / 360 * TILE * Math.pow(2, zoom);
    }

    private static double worldY(double lat, int zoom) {
        double r = Math.toRadians(Math.max(-85, Math.min(85, lat)));
        return (1 - Math.log(Math.tan(r) + 1 / Math.cos(r)) / Math.PI) / 2 * TILE * Math.pow(2, zoom);
    }

    private float screenX(double lon) {
        double worldW = TILE * Math.pow(2, zoom);
        double dx = worldX(lon, zoom) - hereX;
        if (dx > worldW / 2) dx -= worldW;
        else if (dx < -worldW / 2) dx += worldW;
        return (float) (HERE_X * getWidth() + dx * scale);
    }

    private float screenY(double lat) {
        return (float) (HERE_Y * getHeight() + (worldY(lat, zoom) - hereY) * scale);
    }

    /** Makes the map's picture for the view as it is, unless that's the one up or on its way. */
    private void make() {
        int w = getWidth(), h = getHeight();
        if (Double.isNaN(lat) || w == 0 || h == 0 || scale <= 0) return;
        String of = String.format(Locale.ROOT, "%.4f,%.4f,%.1f,%d,%d", lat, lon, reachKm, w, h);
        if (of.equals(mapOf) || of.equals(making) || SystemClock.elapsedRealtime() < againAt) return;
        making = of;
        int z = zoom;
        double left = hereX - HERE_X * w / scale, top = hereY - HERE_Y * h / scale, s = scale;
        File dir = new File(getContext().getApplicationContext().getCacheDir(), "map");
        java.lang.ref.WeakReference<FlightMap> ref = new java.lang.ref.WeakReference<>(this);
        painter.execute(() -> {
            Bitmap made = null;
            try {
                made = paint(dir, z, left, top, s, w, h);
            } catch (IOException | RuntimeException | OutOfMemoryError e) {
                // Offline, or OpenStreetMap isn't answering: the plain ground meanwhile
            }
            Bitmap got = made;
            mainHandler.post(() -> {
                FlightMap view = ref.get();
                if (view == null || !view.isAttachedToWindow() || !of.equals(view.making)) {
                    if (got != null) got.recycle();
                    return;
                }
                view.making = null;
                if (got == null) {
                    view.againAt = SystemClock.elapsedRealtime() + 5 * 60_000;
                    return;
                }
                view.map = got;
                view.mapOf = of;
                view.invalidate();
                if (view.onDrawn != null) view.onDrawn.run();
            });
        });
    }

    /** Every tile the screen shows of, from the cache or downloaded into it, drawn dark into one picture. */
    private static Bitmap paint(File dir, int z, double left, double top, double scale, int w, int h)
            throws IOException {
        if (!dir.isDirectory() && !dir.mkdirs()) throw new IOException("no cache");
        Bitmap out = Bitmap.createBitmap(w, h, Bitmap.Config.RGB_565);
        Canvas canvas = new Canvas(out);
        canvas.drawColor(0xFF0A111C);
        Paint dark = new Paint(Paint.FILTER_BITMAP_FLAG | Paint.DITHER_FLAG);
        dark.setColorFilter(new ColorMatrixColorFilter(DARK));
        int across = 1 << z;
        RectF to = new RectF();
        for (int ty = (int) Math.floor(top / TILE); ty * (double) TILE < top + h / scale; ty++) {
            if (ty < 0 || ty >= across) continue;
            for (int tx = (int) Math.floor(left / TILE); tx * (double) TILE < left + w / scale; tx++) {
                int x = ((tx % across) + across) % across;   // round the world's back
                File file = new File(dir, z + "_" + x + "_" + ty + ".png");
                Bitmap tile = file.isFile() ? BitmapFactory.decodeFile(file.getPath()) : null;
                if (tile == null) {
                    byte[] bytes = Http.get(String.format(Locale.ROOT, TILES, z, x, ty), 512 * 1024);
                    tile = BitmapFactory.decodeByteArray(bytes, 0, bytes.length);
                    if (tile == null) throw new IOException("not a picture");
                    File part = new File(file.getPath() + ".part");
                    try (FileOutputStream f = new FileOutputStream(part)) {
                        f.write(bytes);
                    } catch (IOException e) {
                        //noinspection ResultOfMethodCallIgnored
                        part.delete();
                        throw e;
                    }
                    if (!part.renameTo(file)) part.delete();
                }
                // A hair over its place, so no line shows between it and the next.
                to.set((float) ((tx * (double) TILE - left) * scale), (float) ((ty * (double) TILE - top) * scale),
                        (float) (((tx + 1) * (double) TILE - left) * scale) + 0.5f,
                        (float) (((ty + 1) * (double) TILE - top) * scale) + 0.5f);
                canvas.drawBitmap(tile, null, to, dark);
                tile.recycle();
            }
        }
        return out;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        if (map != null) canvas.drawBitmap(map, 0, 0, picture);
        if (Double.isNaN(lat) || scale <= 0) return;
        float cx = HERE_X * w, cy = HERE_Y * h;
        canvas.drawCircle(cx, cy, RING * h, ring);
        fill.setColor(0x33FFFFFF);
        canvas.drawCircle(cx, cy, 9 * density, fill);
        fill.setColor(0xFFFFFFFF);
        canvas.drawCircle(cx, cy, 3.5f * density, fill);

        long now = SystemClock.elapsedRealtime();
        for (Flights.Spot s : spots) {
            double la = s.lat, lo = s.lon;
            boolean moving = !Double.isNaN(s.speed) && !Double.isNaN(s.track);
            if (moving) {
                double m = s.speed * (now - s.at) / 1000.0, t = Math.toRadians(s.track);
                la += m * Math.cos(t) / 110_570;
                lo = Flights.wrapLon(lo + m * Math.sin(t) / (111_320 * Math.max(0.2, Math.cos(Math.toRadians(la)))));
            }
            float x = screenX(lo), y = screenY(la);
            // Heard of again somewhere else than it was drawn (it turned, or sped up): eased over, not jumped.
            Eased e = easing.get(s.hex);
            float[] was = drawnAt.get(s.hex);
            if (e == null) easing.put(s.hex, e = new Eased());
            if (e.heardAt != s.at) {
                if (e.heardAt != 0 && was != null) {
                    e.dx = was[0] - x;
                    e.dy = was[1] - y;
                    e.from = now;
                }
                e.heardAt = s.at;
            }
            float left = e.from == 0 ? 0f : Math.max(0f, 1f - (now - e.from) / (float) EASE_MS);
            left = left * left * (3 - 2 * left);
            x += e.dx * left;
            y += e.dy * left;
            if (was == null) drawnAt.put(s.hex, was = new float[2]);
            was[0] = x;
            was[1] = y;
            if (x < -40 * density || y < -40 * density || x > w + 40 * density || y > h + 40 * density) continue;

            boolean one = s.hex.equals(chosen);
            // Beyond the ring it's not one of those taking their turn: faint, and unnamed.
            boolean within = one || Math.hypot(x - cx, y - cy) <= RING * h;
            float size = (one ? 11 : 7.5f) * density;
            if (s.kind == Flights.Spot.LIGHT) size *= 0.75f;   // a small aircraft, drawn as one
            else if (s.kind == Flights.Spot.HELICOPTER) size *= 0.85f;
            if (one) {
                fill.setColor(0x26FFFFFF);
                canvas.drawCircle(x, y, 22 * density, fill);
            }
            fill.setColor(one ? 0xFFFFFFFF : within ? 0xCCFFFFFF : 0x4DFFFFFF);
            if (moving) {
                turn.setScale(size, size);
                turn.postRotate((float) s.track);
                turn.postTranslate(x, y);
                (s.kind == Flights.Spot.HELICOPTER ? helicopter : s.kind == Flights.Spot.LIGHT ? light : plane)
                        .transform(turn, turned);
                canvas.drawPath(turned, fill);
            } else {
                canvas.drawCircle(x, y, size * 0.45f, fill);
            }
            if (within && !s.callsign.isEmpty()) {
                name.setColor(one ? 0xE6FFFFFF : 0x99FFFFFF);
                canvas.drawText(s.callsign, x + size + 5 * density, y + 4 * density, name);
            }
        }
        removeCallbacks(nextFrame);
        if (isShown() && !spots.isEmpty()) postDelayed(nextFrame, FRAME_MS);   // single deduplicated loop
    }

    @Override
    protected void onDetachedFromWindow() {
        removeCallbacks(nextFrame);
        super.onDetachedFromWindow();
    }

    @Override
    protected void onWindowVisibilityChanged(int visibility) {
        super.onWindowVisibilityChanged(visibility);
        removeCallbacks(nextFrame);
        if (visibility == VISIBLE) invalidate();   // Home's back in front
    }

    @Override
    protected void onVisibilityChanged(View changed, int visibility) {
        super.onVisibilityChanged(changed, visibility);
        removeCallbacks(nextFrame);
        if (visibility == VISIBLE) invalidate();   // the frames stopped while it was out of sight
    }
}
