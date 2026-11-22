package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.Rect;
import android.graphics.RectF;
import android.graphics.Shader;
import android.view.View;

/**
 * The opacity pages' sample (SettingsScreen.opacity): over a picture (one of the System backdrop's own when there's
 * one, else a dusk gradient), either a small dock, its band as Home draws it (DockDecoration), or a top bar, its
 * dark tint as Home draws it (FrostView) with a clock and three round buttons, at the chosen opacity, so how much
 * shows through can be seen while the slider moves.
 */
@android.annotation.SuppressLint("ViewConstructor")   // made in code only (SettingsScreen.opacity)
final class DockSampleView extends View {

    private static final int TILES = 4;
    /** The tiles' colours: red, white, blue and yellow, as app banners mostly are. */
    private static final int[] TILE_COLOURS = {0xFFE50914, 0xFFF4F4F4, 0xFF1F6FEB, 0xFFF5C518};
    private final boolean topBar;
    private final Paint band = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint edge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint tile = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint button = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint clockText = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint sky = new Paint();
    private final Paint picturePaint = new Paint(Paint.FILTER_BITMAP_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final Rect src = new Rect(), dst = new Rect();
    private final float density;
    /** Decoded off the main thread; null (the dusk gradient) until then, or when there's none. */
    private volatile Bitmap picture;
    private volatile boolean attached;

    DockSampleView(Context context, int percent, boolean topBar) {
        super(context);
        this.topBar = topBar;
        density = getResources().getDisplayMetrics().density;
        band.setColor(topBar ? 0xFF000000 : DockDecoration.BAND);
        edge.setStyle(Paint.Style.STROKE);
        edge.setStrokeWidth(density);
        edge.setColor(DockDecoration.EDGE);
        tile.setColor(0xFFF4F4F4);
        button.setStyle(Paint.Style.STROKE);
        button.setStrokeWidth(1.5f * density);
        button.setColor(0x80FFFFFF);
        clockText.setColor(0xFFFFFFFF);
        clockText.setTextSize(20f * getResources().getDisplayMetrics().scaledDensity);
        clockText.setTypeface(UiFont.clock(context, false));
        setPercent(percent);
    }

    void setPercent(int percent) {
        band.setAlpha(Percent.alpha(percent));
        invalidate();
    }

    @Override
    protected void onAttachedToWindow() {
        super.onAttachedToWindow();
        attached = true;
        if (picture != null) return;
        // A picture of the person's own behind it, small: a wallpaper of the System backdrop's list, as Home shows them.
        Context app = getContext().getApplicationContext();
        new Thread(() -> {
            Bitmap found = null;
            Wallpapers walls = Wallpapers.get(app);
            for (Wallpapers.Item item : walls.listOf(Wallpapers.ROTATION)) {
                if (item.video) continue;
                BitmapFactory.Options o = new BitmapFactory.Options();
                o.inSampleSize = 4;
                found = BitmapFactory.decodeFile(walls.file(item).getPath(), o);
                if (found != null) break;
            }
            Bitmap decoded = found;
            if (decoded == null) return;
            post(() -> {
                if (!attached || picture != null) {
                    decoded.recycle();   // gone meanwhile
                    return;
                }
                picture = decoded;
                invalidate();
            });
        }, "dock-sample").start();
    }

    @Override
    protected void onDetachedFromWindow() {
        super.onDetachedFromWindow();
        attached = false;
        Bitmap gone = picture;
        picture = null;
        if (gone != null) gone.recycle();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        sky.setShader(new LinearGradient(0, 0, w, h, new int[]{0xFF2B3A67, 0xFFB5577A, 0xFFF3A65C}, null,
                Shader.TileMode.CLAMP));
        float r = 12f * density;
        clip.reset();
        clip.addRoundRect(new RectF(0, 0, w, h), r, r, Path.Direction.CW);
    }

    @Override
    protected void onDraw(Canvas canvas) {
        int w = getWidth(), h = getHeight();
        canvas.save();
        canvas.clipPath(clip);   // the sample's rounded corners
        if (picture != null) {
            // Centre-cropped to the sample, as Home fills the screen.
            float scale = Math.max(w / (float) picture.getWidth(), h / (float) picture.getHeight());
            int cw = Math.round(w / scale), ch = Math.round(h / scale);
            int cx = (picture.getWidth() - cw) / 2, cy = (picture.getHeight() - ch) / 2;
            src.set(cx, cy, cx + cw, cy + ch);
            dst.set(0, 0, w, h);
            canvas.drawBitmap(picture, src, dst, picturePaint);
        } else {
            canvas.drawRect(0, 0, w, h, sky);
        }
        if (topBar) drawTopBar(canvas, w);
        else drawDock(canvas, w, h);
        canvas.restore();
    }

    /** A strip across the top, the clock at the left and three round buttons at the right, as the full bar. */
    private void drawTopBar(Canvas canvas, int w) {
        float barH = 46f * density, pad = 16f * density;
        canvas.drawRect(0, 0, w, barH, band);
        canvas.drawRect(0, barH - density, w, barH, edge);
        float base = barH / 2f - (clockText.descent() + clockText.ascent()) / 2f;
        canvas.drawText("12:30", pad, base, clockText);
        float r = 12f * density;
        for (int i = 0; i < 3; i++) {
            float cx = w - pad - r - i * (2 * r + 10f * density);
            canvas.drawCircle(cx, barH / 2f, r, button);
        }
    }

    /** Four tiles in a band across the middle, in Home's proportions. */
    private void drawDock(Canvas canvas, int w, int h) {
        float pad = 8f * density, gap = 10f * density;
        float tileW = (w * 0.62f - (TILES - 1) * gap) / TILES, tileH = tileW * 9f / 16f;
        float total = TILES * tileW + (TILES - 1) * gap;
        float left = (w - total) / 2f, top = (h - tileH) / 2f;
        rect.set(left - pad, top - pad, left + total + pad, top + tileH + pad);
        float bandR = 10f * density;
        canvas.drawRoundRect(rect, bandR, bandR, band);
        canvas.drawRoundRect(rect, bandR, bandR, edge);
        for (int i = 0; i < TILES; i++) {
            float l = left + i * (tileW + gap);
            rect.set(l, top, l + tileW, top + tileH);
            tile.setColor(TILE_COLOURS[i % TILE_COLOURS.length]);
            canvas.drawRoundRect(rect, 5f * density, 5f * density, tile);
        }
    }
}
