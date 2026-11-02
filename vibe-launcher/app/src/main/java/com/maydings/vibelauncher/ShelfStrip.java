package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.FrameLayout;

/**
 * The window a dock folder's mini row (MainActivity's shelf) sits in: as wide as the row while it fits, else
 * the screen's width, with the row sliding sideways to keep the focused app in view.
 * <p>
 * Not a HorizontalScrollView: that jumped straight to the new place when focus moved (it only scrolls smoothly
 * for keys it handles itself, and the row's tiles handle Left and Right), and each step of a scroll redrew
 * every tile, which the 940E couldn't keep up with. Here the row moves by its translation on a hardware layer,
 * so a slide is only the layer being drawn somewhere else, as the rows and the top bar move elsewhere on Home.
 */
@SuppressLint("ViewConstructor")
final class ShelfStrip extends FrameLayout {

    private static final long SLIDE_MS = 240;

    private final View row;
    /**
     * Kept clear past the focused tile, toward the end that has more: part of the next app shows at the edge, as
     * there's more that way, and the focused tile's zoom and outline aren't cut off by the edge.
     */
    private final int edge;
    /**
     * How far along the row the window starts, or will once a slide under way ends: two quick presses aim from
     * the first's end, so the second doesn't come out short. Only this moves the row, so it stays true.
     */
    private float aim;
    /** A tile focused before the row was laid out (just made): brought into view once it has its place. */
    private View pending;

    ShelfStrip(Context context, View row, int edge) {
        super(context);
        this.row = row;
        this.edge = edge;
        addView(row);
    }

    @Override
    protected void onMeasure(int widthSpec, int heightSpec) {
        // The row as wide as all its tiles (not squeezed into the screen), and this only as wide as there's room.
        row.measure(MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED), heightSpec);
        int w = row.getMeasuredWidth();
        if (MeasureSpec.getMode(widthSpec) != MeasureSpec.UNSPECIFIED) w = Math.min(w, MeasureSpec.getSize(widthSpec));
        setMeasuredDimension(w, row.getMeasuredHeight());
    }

    @Override
    protected void onLayout(boolean changed, int l, int t, int r, int b) {
        row.layout(0, 0, row.getMeasuredWidth(), row.getMeasuredHeight());
        View tile = pending;
        pending = null;
        if (tile != null && tile.getParent() == row) jump(target(tile));   // straight there: the row only just appeared
        // A row that now fits (or got shorter) can't stay slid past its end.
        else if (clamp(aim) != aim) jump(clamp(aim));
    }

    /** Back to the row's start, at once: another folder's row. */
    void reset() {
        pending = null;
        jump(0f);
    }

    private void jump(float at) {
        row.animate().cancel();
        aim = at;
        row.setTranslationX(-at);
    }

    /**
     * Slides the row so this tile (one of its children) is in view, with the edge's room past it, unless it
     * already is. Before the row is laid out (just made, focused straight away) the tile has no place yet: it
     * goes there at once in onLayout instead, as the row only just appeared. (Not a post(): that can run before
     * the layout, with the tile still unplaced, and the focused app stayed off the screen.)
     */
    void follow(View tile) {
        // Not row.isLayoutRequested(): the focus ring (setForeground) asks for a layout on every move, and every
        // move would then jump instead of sliding.
        if (row.getWidth() == 0 || tile.getWidth() == 0) {
            pending = tile;
            requestLayout();
            return;
        }
        pending = null;
        float at = target(tile);
        if (at == aim) return;   // already there, or already heading there (a press while it slides)
        aim = at;
        row.animate().translationX(-at).setDuration(SLIDE_MS).setInterpolator(new DecelerateInterpolator(1.6f))
                .withLayer().start();
    }

    /** Where along the row the window should start for this tile to show, from where it is or is going. */
    private float target(View tile) {
        float at = aim;
        float left = tile.getLeft() - edge, right = tile.getRight() + edge;
        if (left < at) at = left;
        else if (right > at + getWidth()) at = right - getWidth();
        return clamp(at);
    }

    private float clamp(float at) {
        return Math.max(0f, Math.min(at, Math.max(0, row.getMeasuredWidth() - getWidth())));
    }
}
