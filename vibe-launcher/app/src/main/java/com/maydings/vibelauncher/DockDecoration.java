package com.maydings.vibelauncher;

import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Rect;
import android.graphics.RectF;
import android.view.View;

import androidx.annotation.NonNull;
import androidx.recyclerview.widget.RecyclerView;

/** Draws the lighter band behind the first (dock) row and adds a gap below it. */
public class DockDecoration extends RecyclerView.ItemDecoration {

    private final int dockSize;
    private final float density;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    /** The band's margin above and below the tiles (Home places the app's name and the Play pill from it). */
    static final float PAD_DP = 10f;

    DockDecoration(int dockSize, float density) {
        this.dockSize = dockSize;
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
        if (pos >= 0 && pos < dockSize) {
            outRect.top = (int) (6 * density);
            outRect.bottom = (int) (12 * density);
        }
    }

    @Override
    public void onDraw(@NonNull Canvas c, @NonNull RecyclerView parent, @NonNull RecyclerView.State state) {
        boolean any = false;
        int tileW = 0, tileH = 0;
        for (int i = 0; i < parent.getChildCount(); i++) {
            View child = parent.getChildAt(i);
            int pos = parent.getChildAdapterPosition(child);
            if (pos < 0 || pos >= dockSize) continue;
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
        // Concentric with the tiles' corners: their radius plus the band's padding, for any tiles per row.
        float radius = AppAdapter.cornerRadius(tileW, density) + pad;
        c.drawRoundRect(rect, radius, radius, band);
        c.drawRoundRect(rect, radius, radius, edge);
    }
}
