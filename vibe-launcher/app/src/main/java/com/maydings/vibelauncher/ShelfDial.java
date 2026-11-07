package com.maydings.vibelauncher;

import android.animation.ValueAnimator;
import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.drawable.GradientDrawable;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.SoundEffectConstants;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

import java.util.Collections;
import java.util.List;

/**
 * A dock folder's mini row (MainActivity's shelf) as a dial: the selected app always in the middle, under an outline
 * that never moves, and Left or Right turning the row under it by one app, like an odometer's digit rolling
 * sideways. Two apps show each side, smaller and fainter further out (one each side in a folder of three or four,
 * the other one on the right in a folder of two: an app never shows twice), and the row wraps round at its ends.
 * <p>
 * Seven places (three each side of the middle) hold tiles; a turn hands each tile to the next place, makes only the
 * one coming in afresh, and slides them all one place, by translation and scale on hardware layers only, as the
 * rows move elsewhere on Home (the 940E can't redraw tiles on every frame). Only the outline in the middle takes
 * focus, so the row's focus is one view, wherever the apps are.
 */
@SuppressLint("ViewConstructor")
final class ShelfDial extends FrameLayout {

    /** What the dial asks of Home. */
    interface Host {
        /** The app's banner (or icon) into this card, the tile's size. */
        void fill(FrameLayout card, AppAdapter.Entry app);

        /** The app in the middle, while the dial has focus: on focus and after each turn. */
        void centred(AppAdapter.Entry app);

        /** Up, Down and the rest, Left and Right aside: true when handled. */
        boolean key(int keyCode);

        /** The highlight color now (it can change in Settings while the dial exists). */
        int highlight();
    }

    private static final long TURN_MS = 240;
    private static final int SIDE = 3, PLACES = 2 * SIDE + 1;
    /** Size and opacity at each place out from the middle (0, 1, 2, and 3 coming in or going out). */
    private static final float[] SCALE = {1f, 0.82f, 0.68f, 0.58f};
    private static final float[] ALPHA = {1f, 0.55f, 0.28f, 0f};
    private static final float FOCUS_ZOOM = 1.12f;

    private final Host host;
    private final int tileW, tileH, stroke, gap, room;
    /** Each place's tile (its frame) and the card in it the picture goes in, from the far left. */
    private final FrameLayout[] frames = new FrameLayout[PLACES], cards = new FrameLayout[PLACES];
    /** The outline in the middle: what has focus, and what's clicked. */
    final View selector;
    private final GradientDrawable ring = new GradientDrawable();

    private List<AppAdapter.Entry> apps = Collections.emptyList();
    private int centre;
    /** Places the tiles are still to slide toward their own (a turn under way), -1 to 1. */
    private float offset;
    /** The places that show in this folder, at rest: no app twice. */
    private int lo, hi;
    /** The middle's zoom, 1 to FOCUS_ZOOM, with focus. */
    private float zoom = 1f;
    private ValueAnimator turning, zooming;

    ShelfDial(Context context, Host host, int tileW, float density) {
        super(context);
        this.host = host;
        this.tileW = tileW;
        this.tileH = AppAdapter.tileHeightPx(tileW);
        stroke = Math.round(3 * density);
        gap = Math.round(14 * density);
        room = Math.round(14 * density);   // above and below: the middle's zoom isn't cut off
        setClipChildren(false);
        setClipToPadding(false);
        float radius = AppAdapter.cornerRadius(tileW, density);
        for (int i = 0; i < PLACES; i++) {
            FrameLayout frame = new FrameLayout(context);
            frame.setPadding(stroke, stroke, stroke, stroke);
            frame.setClipChildren(false);
            FrameLayout card = new FrameLayout(context);
            card.setBackgroundResource(R.drawable.tile_bg);
            card.setOutlineProvider(new android.view.ViewOutlineProvider() {
                @Override
                public void getOutline(View view, android.graphics.Outline outline) {
                    outline.setRoundRect(0, 0, view.getWidth(), view.getHeight(), radius);
                }
            });
            card.setClipToOutline(true);
            frame.addView(card, new LayoutParams(tileW, tileH));
            frame.setLayerType(LAYER_TYPE_HARDWARE, null);   // slid and scaled, never redrawn while turning
            addView(frame, new LayoutParams(tileW + 2 * stroke, tileH + 2 * stroke, Gravity.CENTER));
            frames[i] = frame;
            cards[i] = card;
        }
        // The outline goes round the middle tile, in a frame a stroke wider, its inner edge on the tile's corner.
        ring.setCornerRadius(radius + stroke / 2f);
        ring.setColor(0);
        selector = new View(context);
        selector.setFocusable(true);
        selector.setClickable(true);
        selector.setDefaultFocusHighlightEnabled(false);
        addView(selector, new LayoutParams(tileW + 2 * stroke, tileH + 2 * stroke, Gravity.CENTER));
        selector.setOnFocusChangeListener((v, focused) -> {
            if (focused) ring.setStroke(stroke, host.highlight());
            selector.setBackground(focused ? ring : null);
            zoomTo(focused ? FOCUS_ZOOM : 1f, focused ? 220 : 160);
            if (focused && !apps.isEmpty()) host.centred(centreApp());
        });
        selector.setOnKeyListener((v, keyCode, event) -> {
            if (event.getAction() != KeyEvent.ACTION_DOWN) return false;
            if (keyCode == KeyEvent.KEYCODE_DPAD_LEFT || keyCode == KeyEvent.KEYCODE_DPAD_RIGHT) {
                turn(keyCode == KeyEvent.KEYCODE_DPAD_RIGHT ? 1 : -1);
                return true;   // along the row, never out of it
            }
            boolean ok = keyCode == KeyEvent.KEYCODE_DPAD_CENTER || keyCode == KeyEvent.KEYCODE_ENTER;
            if (ok && event.getRepeatCount() > 0) return true;   // OK held: the app opens once, not again per repeat
            return host.key(keyCode);
        });
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // Room for the middle and two places each side, whatever this folder shows: the middle stays put.
        int w = Math.round(2 * (along(2) + SCALE[2] * (tileW + 2 * stroke) / 2f));
        int h = tileH + 2 * room;
        super.onMeasure(MeasureSpec.makeMeasureSpec(w, MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec(h, MeasureSpec.EXACTLY));
    }

    /** From the dial's top to the tiles' top. */
    int tileTop() {
        return room;
    }

    int size() {
        return apps.size();
    }

    AppAdapter.Entry centreApp() {
        return apps.isEmpty() ? null : apps.get(centre);
    }

    /** This folder's apps, the one with this package in the middle (else the first). */
    void setApps(List<AppAdapter.Entry> list, String middle) {
        apps = list;
        int n = list.size();
        lo = n >= 5 ? -2 : n >= 3 ? -1 : 0;
        hi = n >= 5 ? 2 : n >= 2 ? 1 : 0;
        centre = 0;
        for (int i = 0; i < n; i++) if (list.get(i).pkg.equals(middle)) centre = i;
        if (turning != null) turning.cancel();
        offset = 0;
        for (int i = 0; i < PLACES; i++) bind(i);
        place();
    }

    /** The app with this package in the middle, at once (no turn). */
    void setCentre(String pkg) {
        for (int i = 0; i < apps.size(); i++) {
            if (apps.get(i).pkg.equals(pkg) && i != centre) {
                setApps(apps, pkg);
                return;
            }
        }
    }

    /** One app along: right brings the next one into the middle. Wraps round. */
    private void turn(int dir) {
        int n = apps.size();
        if (n < 2) return;
        centre = Math.floorMod(centre + dir, n);
        // Each tile to its neighbour's place: the one falling off the far end comes in at the other, made afresh.
        if (dir > 0) {
            FrameLayout f = frames[0], c = cards[0];
            System.arraycopy(frames, 1, frames, 0, PLACES - 1);
            System.arraycopy(cards, 1, cards, 0, PLACES - 1);
            frames[PLACES - 1] = f;
            cards[PLACES - 1] = c;
            bind(PLACES - 1);
        } else {
            FrameLayout f = frames[PLACES - 1], c = cards[PLACES - 1];
            System.arraycopy(frames, 0, frames, 1, PLACES - 1);
            System.arraycopy(cards, 0, cards, 1, PLACES - 1);
            frames[0] = f;
            cards[0] = c;
            bind(0);
        }
        // Still where they were: they slide the rest of the way. A press while turning carries on from there.
        float from = Math.max(-1f, Math.min(1f, offset + dir));
        if (turning != null) turning.cancel();
        offset = from;
        place();
        turning = ValueAnimator.ofFloat(from, 0f);
        turning.setDuration(TURN_MS);
        turning.setInterpolator(new DecelerateInterpolator(1.6f));
        turning.addUpdateListener(a -> {
            offset = (float) a.getAnimatedValue();
            place();
        });
        turning.start();
        playSoundEffect(dir > 0 ? SoundEffectConstants.NAVIGATION_RIGHT : SoundEffectConstants.NAVIGATION_LEFT);
        if (selector.isFocused()) host.centred(centreApp());
    }

    private void bind(int place) {
        FrameLayout card = cards[place];
        card.removeAllViews();
        int n = apps.size();
        if (n == 0) return;
        host.fill(card, apps.get(Math.floorMod(centre + place - SIDE, n)));
    }

    private void zoomTo(float to, long ms) {
        if (zooming != null) zooming.cancel();
        zooming = ValueAnimator.ofFloat(zoom, to);
        zooming.setDuration(ms);
        zooming.addUpdateListener(a -> {
            zoom = (float) a.getAnimatedValue();
            selector.setScaleX(zoom);
            selector.setScaleY(zoom);
            place();
        });
        zooming.start();
    }

    /** Every tile where it is now: along the row, its size and how faint, from how far it is from the middle. */
    private void place() {
        for (int i = 0; i < PLACES; i++) {
            float p = i - SIDE + offset, a = Math.abs(p);
            View f = frames[i];
            float s = at(SCALE, a) * (1 + (zoom - 1) * Math.max(0f, 1 - a));
            float alpha = at(ALPHA, a);
            // Past the places this folder shows (no app twice), faded out within half a place.
            if (p > hi) alpha *= Math.max(0f, 1 - (p - hi) * 2);
            else if (p < lo) alpha *= Math.max(0f, 1 - (lo - p) * 2);
            f.setTranslationX(Math.signum(p) * along(a));
            f.setScaleX(s);
            f.setScaleY(s);
            f.setAlpha(alpha);
            f.setVisibility(alpha <= 0.01f ? INVISIBLE : VISIBLE);
        }
    }

    /** How far from the middle a tile this many places out sits: the tiles between, at their sizes, and the gaps. */
    private float along(float a) {
        float x = 0, fw = tileW + 2 * stroke;
        for (int j = 0; j < SCALE.length - 1 && a > 0; j++) {
            float inner = j == 0 ? FOCUS_ZOOM : SCALE[j];   // room for the middle's zoom
            float step = (inner + SCALE[j + 1]) / 2f * fw + gap;
            x += step * Math.min(1f, a);
            a -= 1;
        }
        return x;
    }

    /** The value this far out, between the places' own. */
    private static float at(float[] v, float a) {
        int i = (int) Math.floor(a);
        if (i >= v.length - 1) return v[v.length - 1];
        float t = a - i;
        return v[i] + (v[i + 1] - v[i]) * t;
    }
}
