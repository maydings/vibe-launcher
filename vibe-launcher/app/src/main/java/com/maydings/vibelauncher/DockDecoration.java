package com.maydings.vibelauncher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/**
 * Draws the lighter band behind the first (dock) row and adds a gap below it. A dock of fewer tiles than a
 * row is centred: its first and last tiles span the empty space beside them (AppAdapter.spanSize), inset
 * here, and the band fits round the tiles.
 */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final AppAdapter adapter;
    private final int columns;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** The band's margin above and below the tiles (Home places the app's name and the Play pill from it). */
    static final float PAD_DP = 10f;

    DockDecoration(AppAdapter adapter, int columns, float density) {
        this.adapter = adapter;
        this.columns = columns;
        this.density = density;
        band.setColor(0xFF141416);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(0x1FFFFFFF);
    }

    @Override
    public void getItemOffsets(@NonNull Rect outRect, @NonNull View view, @NonNull RecyclerView parent,
                               @NonNull RecyclerView.State state) {
        int pos = parent.getChildAdapterPosition(view);
        if (!adapter.inDock(pos)) return;
        outRect.top = (int) (6 * density);
        outRect.bottom = (int) (12 * density);
        // The empty space beside a shorter dock: the first and last tiles' spans include it, and it's taken
        // off them here, so each tile keeps one column's width.
        float cell = (parent.getWidth() - parent.getPaddingLeft() - parent.getPaddingRight()) / (float) columns;
        int inset = Math.round(adapter.dockInset() * cell);
        // Before the first tile and after the last: left and right, or the other way round right to left.
        boolean rtl = parent.getLayoutDirection() == View.LAYOUT_DIRECTION_RTL;
        if (pos == 0) {
            if (rtl) outRect.right = inset;
            else outRect.left = inset;
        }
        if (pos == adapter.dockCount() - 1) {
            if (rtl) outRect.left = inset;
            else outRect.right = inset;
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        int tileW = 0, tileH = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (!adapter.inDock(pos)) continue;
            // The holder already has the card: no view search for every dock tile on every frame.
            RecyclerView.ViewHolder holder = parent.getChildViewHolder(child);
            if (!(holder instanceof AppAdapter.Holder)) continue;
            View card = ((AppAdapter.Holder) holder).card;
            float l = child.getLeft() + card.getLeft();
            float t = child.getTop() + card.getTop() + child.getTranslationY();
            float r = l + card.getWidth();
            float b = t + card.getHeight();
            tileW = card.getWidth();
            tileH = card.getHeight();
            if (!any) {
                rect.set(l, t, r, b);
                any = true;
            } else {
                rect.union(l, t, r, b);
            }
        }
        if (!any) return;
        // Close round the tiles; wider at the sides by what a focused tile grows more across than up and down
        // (tiles are wider than tall), so a focused tile has the same room on every side.
        float pad = PAD_DP * density;
        float padX = pad + (AppAdapter.FOCUS_SCALE - 1f) / 2f * (tileW - tileH);
        rect.inset(-padX, -pad);
        // Concentric with the focused tile's corners (on the dock, one always is): grown by FOCUS_SCALE, it's
        // half its growth closer to the band on every side (padX makes the sides match the top and bottom),
        // and its corners are rounder by as much.
        float scale = AppAdapter.FOCUS_SCALE;
        float gap = pad - (scale - 1f) / 2f * tileH;
        float radius = AppAdapter.cornerRadius(tileW, density) * scale + gap;
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
