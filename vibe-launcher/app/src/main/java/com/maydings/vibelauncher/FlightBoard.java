package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.util.AttributeSet;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.TextView;

import java.util.Collections;
import java.util.List;
import java.util.Locale;

/**
 * The aircraft passing near here (Flights), one at a time, in place of the system backdrop: over the backdrop
 * and the glow, under the app's name, the rows and the top bar. Its ground is a map of the place with every
 * aircraft heard of moving over it (FlightMap), so nothing of what's under it shows. What it says of the one
 * whose turn it is sits at the end of the screen, the airline's logo on a white plate over it (logos are drawn
 * for white), its foot at one height whatever tile has the focus (setFoot).
 *
 * It shows while Home wants it (setWanted): where the flights are what shows behind the tile in focus, the map
 * at once and the card while an aircraft is in range; or the card alone, with no map, over a tile's colour. With several, each has the screen for a few seconds in
 * turn, nearest first.
 */
public final class FlightBoard extends FrameLayout {

    private static final long EACH_MS = 8_000, FADE_MS = 400;
    private static final int WIDTH_DP = 340;   // the board's, frame and all
    private static final String[] COMPASS = {"N", "NE", "E", "SE", "S", "SW", "W", "NW"};

    private final LinearLayout box;
    private final TextView flight, headline, cities, aircraft, numbers, count, credit;
    private final ImageView plate;
    private final FlightMap map;
    /** The airline whose logo is wanted on the plate. */
    private String logoOf;
    private List<Flights.Near> near = Collections.emptyList();
    private boolean wanted, imperial, paused;
    /** With its map under the card (the flights are the backdrop), or the card alone over what's there. */
    private boolean mapped = true;
    /** The one showing, by its transponder address: kept on the screen as the list changes under it. */
    private String showing;
    private float foot = -1, ceiling;
    /** Told when it starts or stops covering what's under it (Home holds a video backdrop still meanwhile). */
    Runnable onCovering;
    private boolean wasCovering;

    public FlightBoard(Context context, AttributeSet attrs) {
        super(context, attrs);
        setBackgroundColor(0xFF0A111C);   // until the map's there, and where it can't be had
        map = new FlightMap(context);
        addView(map, new LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.MATCH_PARENT));
        setAlpha(0f);
        setVisibility(GONE);
        box = new LinearLayout(context);
        box.setOrientation(LinearLayout.VERTICAL);
        box.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        // In a frame of its own, as a board on the wall is: dark inside, so it reads over any part of the map.
        GradientDrawable frame = new GradientDrawable();
        frame.setColor(0xEB070B12);
        frame.setCornerRadius(14 * getResources().getDisplayMetrics().density);
        frame.setStroke(Math.round(2 * getResources().getDisplayMetrics().density), 0x59FFFFFF);
        box.setBackground(frame);
        int pad = Math.round(18 * getResources().getDisplayMetrics().density);
        box.setPadding(pad + pad / 4, pad, pad + pad / 4, pad);
        float d = getResources().getDisplayMetrics().density;
        // The flight's number, and the airline's logo after it (its name instead, where there's no logo).
        LinearLayout top = new LinearLayout(context);
        top.setGravity(Gravity.END | Gravity.CENTER_VERTICAL);
        // As tall as the logo's plate, with one or without, so the board is.
        box.addView(top, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, (int) (48 * d)));
        flight = line(22, 0xD9FFFFFF, R.string.font_regular);
        box.removeView(flight);
        top.addView(flight, new LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f));
        plate = new ImageView(context);
        GradientDrawable white = new GradientDrawable();
        white.setColor(0xFFFFFFFF);
        white.setCornerRadius(8 * d);
        plate.setBackground(white);
        plate.setPadding((int) (12 * d), (int) (8 * d), (int) (12 * d), (int) (8 * d));
        plate.setAdjustViewBounds(true);
        plate.setMaxWidth((int) (170 * d));
        plate.setScaleType(ImageView.ScaleType.FIT_CENTER);
        plate.setVisibility(GONE);
        LinearLayout.LayoutParams plateAt = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.WRAP_CONTENT, (int) (48 * d));
        plateAt.setMarginStart((int) (14 * d));
        top.addView(plate, plateAt);
        headline = line(46, 0xFFFFFFFF, R.string.font_medium);
        headline.setLetterSpacing(-0.01f);
        cities = line(18, 0xB3FFFFFF, R.string.font_regular);
        aircraft = line(18, 0xB3FFFFFF, R.string.font_regular);
        numbers = line(18, 0xB3FFFFFF, R.string.font_regular);
        count = line(13, 0x73FFFFFF, R.string.font_regular);
        // The map's makers, as they ask: in the map's bottom corner.
        credit = line(9, 0x80FFFFFF, R.string.font_regular);
        box.removeView(credit);
        LayoutParams creditAt = new LayoutParams(LayoutParams.WRAP_CONTENT, LayoutParams.WRAP_CONTENT,
                Gravity.BOTTOM | Gravity.END);
        creditAt.setMarginEnd((int) (16 * d));
        creditAt.bottomMargin = (int) (8 * d);
        addView(credit, creditAt);
        ((LinearLayout.LayoutParams) headline.getLayoutParams()).topMargin = (int) (6 * d);
        ((LinearLayout.LayoutParams) aircraft.getLayoutParams()).topMargin = (int) (10 * d);
        ((LinearLayout.LayoutParams) count.getLayoutParams()).topMargin = (int) (8 * d);
        // One size, whatever it says: as wide as this, a line with nothing to say keeps its place (set), and
        // as tall as its lines with one more, for the one that's too long for the width (wrap).
        int tall = box.getPaddingTop() + box.getPaddingBottom() + (int) ((48 + 6 + 10 + 8 + 2) * d)
                + headline.getLineHeight() + 2 * cities.getLineHeight() + aircraft.getLineHeight()
                + numbers.getLineHeight() + count.getLineHeight();
        LayoutParams at = new LayoutParams((int) (WIDTH_DP * d), tall, Gravity.END | Gravity.CENTER_VERTICAL);
        at.setMarginEnd((int) (50 * d));
        addView(box, at);
        // Its foot stays where it was put.
        box.addOnLayoutChangeListener((v, l, t, r, b, ol, ot, or, ob) -> settle());
    }

    private TextView line(int sp, int color, int font) {
        TextView text = new TextView(getContext());
        text.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        text.setTextColor(color);
        text.setTypeface(UiFont.get(getContext(), font));
        text.setIncludeFontPadding(false);
        text.setSingleLine(true);
        text.setEllipsize(android.text.TextUtils.TruncateAt.END);
        text.setGravity(Gravity.END);
        box.addView(text, new LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT,
                LinearLayout.LayoutParams.WRAP_CONTENT));
        return text;
    }

    /** The airline's logo on the plate; none for an aircraft of no airline. */
    private void wear(Flights.Near n) {
        if (n.icao == null) {
            logoOf = null;
            plate.setVisibility(GONE);
            return;
        }
        if (!n.icao.equals(logoOf)) {   // another airline's: its own, or none, by the answer that follows
            plate.setVisibility(GONE);
            plate.setImageDrawable(null);
        }
        logoOf = n.icao;
        String icao = n.icao;
        AirlineLogos.get(getContext()).logo(icao, n.iata, logo -> {
            if (!icao.equals(logoOf)) return;   // the screen has gone to another since
            plate.setVisibility(logo != null ? VISIBLE : GONE);
            if (logo != null) plate.setImageBitmap(logo.picture);
            if (showingNow != null) say(showingNow);   // the airline's name gives way to its logo, or comes back
        });
    }

    /** Where its foot goes, down the screen: the same for every tile (Home says where). Not said: the middle. */
    void setFoot(float y) {
        foot = y;
        settle();
    }

    /** How far down the top bar comes: the frame starts under it, its foot lower for it if it must be. */
    void setCeiling(float y) {
        ceiling = y;
        settle();
    }

    private void settle() {
        if (foot > 0 && box.getHeight() > 0) box.setY(Math.max(ceiling, foot - box.getHeight()));
    }

    /** Whether Home would show it here now, were there an aircraft to show. */
    void setWanted(boolean wanted, boolean imperial, boolean mapped) {
        if (mapped != this.mapped) {
            this.mapped = mapped;
            map.setVisibility(mapped ? VISIBLE : GONE);
            setBackgroundColor(mapped ? 0xFF0A111C : 0);
        }
        this.wanted = wanted;
        this.imperial = imperial;
        apply();
    }

    /** The aircraft in range now, nearest first (Flights tells Home every few seconds). */
    void setFlights(List<Flights.Near> near, List<Flights.Spot> all, Weather.Place here, double reachKm) {
        this.near = near;
        if (here != null && mapped) map.setPlace(here.lat, here.lon, reachKm);
        map.setSpots(all);
        apply();
    }

    /** Where the map is of, and how far the flights are looked for: known before any aircraft is heard of. */
    void setHere(Weather.Place here, double reachKm) {
        map.setPlace(here.lat, here.lon, reachKm);
    }

    /** Whether it's covering what's under it (or on its way to): its map, and a card while an aircraft is in range. */
    boolean covering() {
        return wanted && mapped;
    }

    private void apply() {
        if (covering() != wasCovering) {
            wasCovering = covering();
            if (onCovering != null) onCovering.run();
        }
        if (!wanted) {
            removeCallbacks(next);
            showing = null;
            showingNow = null;
            if (getVisibility() != VISIBLE) return;
            animate().cancel();
            animate().alpha(0f).setDuration(FADE_MS).withEndAction(() -> setVisibility(GONE)).start();
            return;
        }
        Flights.Near one = null;
        for (Flights.Near n : near) if (n.hex.equals(showing)) one = n;
        box.setVisibility(near.isEmpty() ? INVISIBLE : VISIBLE);
        set(credit, mapped && map.drawn() ? "© OpenStreetMap contributors" : null);
        if (near.isEmpty()) {   // none in range: the map alone
            removeCallbacks(next);
            showing = null;
            showingNow = null;
            map.setChosen(null);
        } else if (one == null) {   // the first, or the one showing has gone out of range: the nearest
            one = near.get(0);
            showing = one.hex;
            removeCallbacks(next);
            if (!paused) postDelayed(next, EACH_MS);
        }
        if (one != null) say(one);
        if (getVisibility() != VISIBLE || getAlpha() < 1f) {
            setVisibility(VISIBLE);
            animate().cancel();
            animate().alpha(1f).setDuration(FADE_MS).start();
        }
    }

    /** Home is behind another app (or back): the aircraft don't take their turns for nobody. */
    void setPaused(boolean paused) {
        this.paused = paused;
        removeCallbacks(next);
        if (!paused && wanted && !near.isEmpty()) postDelayed(next, EACH_MS);
    }

    /** The next one's turn: what's said fades out, changes and comes back. Alone, it stays. */
    private final Runnable next = new Runnable() {
        @Override
        public void run() {
            removeCallbacks(this);
            if (!wanted || paused || near.isEmpty()) return;
            postDelayed(this, EACH_MS);
            if (near.size() < 2) return;
            int at = 0;
            for (int i = 0; i < near.size(); i++) if (near.get(i).hex.equals(showing)) at = i + 1;
            Flights.Near to = near.get(at % near.size());
            showing = to.hex;
            box.animate().cancel();
            box.animate().alpha(0f).setDuration(250).withEndAction(() -> {
                apply();   // by what's in range now
                box.animate().alpha(1f).setDuration(350).start();
            }).start();
        }
    };

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        removeCallbacks(next);
    }

    private Flights.Near showingNow;

    private void say(Flights.Near n) {
        if (n == null) return;
        boolean first = showingNow != n;
        showingNow = n;
        if (first) wear(n);   // which says it again, once the logo's known
        String name = !n.callsign.isEmpty() ? n.callsign : n.registration != null ? n.registration
                : n.hex.toUpperCase(Locale.ROOT);
        boolean route = n.from != null && n.to != null;
        // The flight as its airline numbers it ("5X 2182"), else as it calls itself on the radio.
        String number = n.flight != null ? spaced(n.flight) : route || n.airline != null ? n.callsign : null;
        boolean logo = plate.getVisibility() == VISIBLE;
        set(flight, logo ? number : join("  ", n.airline, number));
        headline.setText(route ? n.from + " → " + n.to : name);
        set(cities, route && n.fromCity != null && n.toCity != null ? n.fromCity + " → " + n.toCity : null);
        set(aircraft, join(" · ", n.type, n.registration != null && !n.registration.equals(name)
                ? n.registration : null));
        String up = Double.isNaN(n.climb) || Math.abs(n.climb) < 1.5 ? "" : n.climb > 0 ? " ↑" : " ↓";
        String high = Double.isNaN(n.altitude) ? null
                : (imperial ? whole(n.altitude * 3.28084, 100) + " ft" : whole(n.altitude, 50) + " m") + up;
        String fast = Double.isNaN(n.speed) ? null
                : imperial ? whole(n.speed * 2.236936, 1) + " mph" : whole(n.speed * 3.6, 1) + " km/h";
        double away = imperial ? n.km / 1.609344 : n.km;
        String where = (away < 10 ? String.format(Locale.getDefault(), "%.1f", away) : whole(away, 1))
                + (imperial ? " mi " : " km ") + COMPASS[(int) Math.round(n.bearing / 45) % 8];
        set(numbers, join(" · ", join(" · ", high, fast), where));
        wrap();
        int at = near.indexOf(n) + 1;
        set(count, near.size() > 1 ? at + " / " + near.size() : null);
        map.setChosen(n.hex);
    }

    /**
     * A second line for the first of these that's too long for the board: the cities, the aircraft, the
     * numbers. The board has room for one; another too long is cut short.
     */
    private void wrap() {
        float room = box.getLayoutParams().width - box.getPaddingLeft() - box.getPaddingRight();
        boolean given = false;
        for (TextView line : new TextView[]{cities, aircraft, numbers}) {
            boolean two = !given && line.getVisibility() == VISIBLE
                    && line.getPaint().measureText(line.getText().toString()) > room;
            given |= two;
            if (line.getMaxLines() != (two ? 2 : 1)) {
                line.setSingleLine(!two);
                line.setMaxLines(two ? 2 : 1);
                line.setEllipsize(android.text.TextUtils.TruncateAt.END);
            }
        }
    }

    /** A line's text; with none it's empty but keeps its place, so the board is one size. */
    private static void set(TextView view, String text) {
        view.setVisibility(text == null ? INVISIBLE : VISIBLE);
        if (text != null && !text.contentEquals(view.getText())) view.setText(text);
    }

    private static String join(String with, String a, String b) {
        return a == null ? b : b == null ? a : a + with + b;
    }

    /** "5X2182" as "5X 2182": the airline's two characters, then the number. */
    private static String spaced(String flight) {
        return flight.length() > 2 ? flight.substring(0, 2) + ' ' + flight.substring(2) : flight;
    }

    /** A number to the nearest step, with the language's thousands mark: 9,600. */
    private static String whole(double value, int step) {
        return String.format(Locale.getDefault(), "%,d", Math.round(value / step) * step);
    }
}
