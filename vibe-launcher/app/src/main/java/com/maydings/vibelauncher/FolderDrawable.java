package com.maydings.vibelauncher;

import android.graphics.Bitmap;
import android.graphics.BitmapShader;
import android.graphics.Canvas;
import android.graphics.ColorFilter;
import android.graphics.Matrix;
import android.graphics.Paint;
import android.graphics.PixelFormat;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.drawable.Drawable;

import java.util.ArrayList;
import java.util.List;

/**
 * A folder's tile, as on tvOS: its first nine apps small, three by three, each its banner (or its icon on a
 * dark plate), on the tile's own dark fill. Fills whatever bounds it's given (no size of its own).
 *
 * Each preview is made once per size, at the size it shows: drawn large, halved step by step (each step
 * averages four pixels, so detail shrinks smoothly instead of being skipped, as one bilinear jump from a
 * 320 px banner to a 50 px cell does), then drawn through a shader as an anti-aliased rounded rectangle
 * (clipPath corners are hard-edged).
 */
final class FolderDrawable extends Drawable {

    private static final int GRID = 3;
    private final List<Drawable> sources = new ArrayList<>();
    private final List<Boolean> isBanner = new ArrayList<>();
    private final List<Paint> cells = new ArrayList<>();
    private final List<RectF> places = new ArrayList<>();
    private float radius;
    private int alpha = 255;

    FolderDrawable(List<AppAdapter.Entry> apps) {
        for (AppAdapter.Entry e : apps) {
            if (sources.size() == GRID * GRID) break;
            Drawable d = e.banner != null ? e.banner : e.icon;
            if (d == null) continue;
            // A copy of its own: the tile's ImageView keeps the original at the tile's size.
            Drawable.ConstantState state = d.getConstantState();
            sources.add(state != null ? state.newDrawable().mutate() : d);
            isBanner.add(e.banner != null);
        }
    }

    @Override
    protected void onBoundsChange(Rect b) {
        cells.clear();
        places.clear();
        if (b.isEmpty()) return;
        // Cells keep the tiles' 16:9 and fill as much of the tile as that allows, centred.
        float gap = b.width() * 0.035f;
        float w = (b.width() - gap * (GRID + 1)) / GRID;
        float h = w * 9f / 16f;
        if (h * GRID + gap * (GRID + 1) > b.height()) {   // too tall: fit the height instead
            h = (b.height() - gap * (GRID + 1)) / GRID;
            w = h * 16f / 9f;
        }
        float left0 = b.left + (b.width() - (w * GRID + gap * (GRID - 1))) / 2f;
        float top0 = b.top + (b.height() - (h * GRID + gap * (GRID - 1))) / 2f;
        radius = h * 0.14f;
        int cw = Math.max(1, Math.round(w)), ch = Math.max(1, Math.round(h));
        for (int i = 0; i < sources.size(); i++) {
            float left = left0 + (i % GRID) * (w + gap), top = top0 + (i / GRID) * (h + gap);
            Bitmap small = render(sources.get(i), isBanner.get(i), cw, ch);
            Paint p = new Paint(Paint.ANTI_ALIAS_FLAG | Paint.FILTER_BITMAP_FLAG);
            BitmapShader shader = new BitmapShader(small, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP);
            Matrix m = new Matrix();
            m.setScale(w / cw, h / ch);   // the rounding to whole pixels, made up
            m.postTranslate(left, top);
            shader.setLocalMatrix(m);
            p.setShader(shader);
            p.setAlpha(alpha);
            cells.add(p);
            places.add(new RectF(left, top, left + w, top + h));
        }
    }

    /** One preview at w x h: a banner filling it, or an icon on a plate, shrunk in halves from 4x. */
    private static Bitmap render(Drawable d, boolean banner, int w, int h) {
        int scale = 4;
        Bitmap big = Bitmap.createBitmap(w * scale, h * scale, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(big);
        Rect saved = new Rect(d.getBounds());
        if (banner) {
            d.setBounds(0, 0, big.getWidth(), big.getHeight());
        } else {
            c.drawColor(0xFF2A2A2E);
            int s = Math.round(big.getHeight() * 0.7f);
            int x = (big.getWidth() - s) / 2, y = (big.getHeight() - s) / 2;
            d.setBounds(x, y, x + s, y + s);
        }
        d.draw(c);
        d.setBounds(saved);
        Bitmap out = big;
        while (scale > 1) {   // 4x to 2x to 1x: each pixel the average of the four it replaces
            scale /= 2;
            Bitmap half = Bitmap.createScaledBitmap(out, w * scale, h * scale, true);
            if (out != half) out.recycle();
            out = half;
        }
        return out;
    }

    @Override
    public void draw(Canvas canvas) {
        for (int i = 0; i < cells.size(); i++) canvas.drawRoundRect(places.get(i), radius, radius, cells.get(i));
    }

    @Override
    public void setAlpha(int alpha) {
        this.alpha = alpha;
        for (Paint p : cells) p.setAlpha(alpha);
        invalidateSelf();
    }

    @Override
    public void setColorFilter(ColorFilter filter) {
        for (Paint p : cells) p.setColorFilter(filter);
        invalidateSelf();
    }

    @Override
    public int getOpacity() {
        return PixelFormat.TRANSLUCENT;
    }
}
