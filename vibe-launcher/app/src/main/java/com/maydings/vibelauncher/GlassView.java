package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.util.AttributeSet;
import android.view.View;

/**
 * A translucent glass slab: soft sheen fill, bright top specular, crisp rim and a soft outer glow.
 * The slab is exactly the focused tile's (scaled) shape, with the same corner radius, so the rim
 * follows the tile edge evenly; the view is 6dp larger on each side to leave room for the glow.
 * One instance floats over the grid and is moved with spring animations between tiles. Its sheen, rim
 * and glow take the highlight colour from Settings (white unless chosen); the top specular stays white.
 * Round a tile that's being moved the rim is white and thicker, on the same line.
 */
public class GlassView extends View {

    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint specular = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint rim = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint glow = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final Path clip = new Path();
    private final float density;
    private float radius;
    /** The highlight colour, RGB: white unless chosen (Settings > Appearance > Highlight colour). */
    private int tint = 0xFFFFFF;
    private boolean moving;

    public GlassView(Context context, AttributeSet attrs) {
        super(context, attrs);
        density = getResources().getDisplayMetrics().density;
        radius = 20f * density;
        rim.setStyle(Paint.Style.STROKE);
        rim.setStrokeWidth(1.5f * density);
        colours();
        // Drawn once into a cached layer and only moved and faded after that: hardware shadow-blur on shapes
        // requires API 28+, so API 26-27 uses a cached software layer instead (still rasterized only on resize).
        setLayerType(android.os.Build.VERSION.SDK_INT >= 28 ? LAYER_TYPE_HARDWARE : LAYER_TYPE_SOFTWARE, null);
    }

    /** The highlight colour (RGB; the alpha is ignored). */
    void setTint(int rgb) {
        rgb &= 0xFFFFFF;
        if (rgb == tint) return;
        tint = rgb;
        colours();
        if (getHeight() > 0) sheen(getHeight());
        invalidate();
    }

    /** The highlight colour at this alpha (0..255). */
    private int tinted(int alpha) {
        return alpha << 24 | tint;
    }

    /** Whether the tile it's round is being moved: the rim white and thicker, where the coloured one is. */
    void setMoving(boolean moving) {
        if (moving == this.moving) return;
        this.moving = moving;
        colours();
        invalidate();
    }

    private void colours() {
        rim.setColor(moving ? 0xFFFFFFFF : tinted(0xCC));
        rim.setStrokeWidth((moving ? 3f : 1.5f) * density);
        // One soft shadow-blur glow instead of stacked strokes, which banded. Hardware-drawn on
        // Android 9+, unlike BlurMaskFilter (which needs a software layer).
        glow.setColor(tinted(0xFF));
        glow.setShadowLayer(5f * density, 0f, 0f, tinted(0x55));
    }

    private void sheen(int h) {
        fill.setShader(new LinearGradient(0, 0, 0, h, tinted(0x3D), tinted(0x12), Shader.TileMode.CLAMP));
    }

    /** Corner radius of the slab: the focused tile's radius at its focused scale. */
    void setRadius(float px) {
        if (px == radius) return;
        radius = px;
        invalidate();
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        super.onSizeChanged(w, h, oldw, oldh);
        sheen(h);
        specular.setShader(new LinearGradient(0, 0, 0, h * 0.45f, 0x66FFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float inset = 6f * density;   // room for the glow
        if (getWidth() <= 2 * inset || getHeight() <= 2 * inset) return;
        rect.set(inset, inset, getWidth() - inset, getHeight() - inset);

        clip.reset();
        clip.addRoundRect(rect, radius, radius, Path.Direction.CW);

        // Glow: only the blur outside the slab shows; the solid shape itself is clipped away.
        canvas.save();
        canvas.clipOutPath(clip);
        canvas.drawRoundRect(rect, radius, radius, glow);
        canvas.restore();

        canvas.drawRoundRect(rect, radius, radius, fill);

        canvas.save();
        canvas.clipPath(clip);
        canvas.drawRect(rect.left, rect.top, rect.right, rect.top + rect.height() * 0.45f, specular);
        canvas.restore();

        // Rim: centred on the edge, so its smooth stroke covers the glow's inner edge there, which clipOutPath
        // leaves hard and stepped (Android doesn't smooth clips): kept inside the edge, the steps showed at the
        // corners.
        canvas.drawRoundRect(rect, radius, radius, rim);
    }
}
