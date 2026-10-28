package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SweepGradient;
import android.view.KeyEvent;
import android.view.View;

/**
 * A colour wheel for the remote: the colour around it, paler towards the middle (white at the centre), at
 * full brightness. The arrows move its dot; held, it speeds up. OK is done; Up or Down past the wheel's edge
 * also leaves it.
 */
@SuppressLint("ViewConstructor")   // made in code only (SettingsScreen), never from a layout
final class ColorWheelView extends View {

    interface Listener {
        void picked(int rgb);

        /** OK: the colour is chosen. */
        void done();
    }

    private final Paint wheel = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pale = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint dot = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final float density;
    private final Listener listener;
    /** The dot, as a point in the unit circle: its angle is the hue (clockwise from the right), its distance the saturation. */
    private float x, y;

    ColorWheelView(Context context, int rgb, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        dot.setStyle(Paint.Style.STROKE);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * density);
        ring.setColor(0xCCFFFFFF);
        setFocusable(true);
        set(rgb);
    }

    /** Moves the dot to this colour's place (its brightness ignored: the wheel is all at full brightness). */
    void set(int rgb) {
        float[] hsv = new float[3];
        Color.colorToHSV(0xFF000000 | rgb, hsv);
        double a = Math.toRadians(hsv[0]);
        x = (float) (Math.cos(a) * hsv[1]);
        y = (float) (Math.sin(a) * hsv[1]);
        invalidate();
    }

    private int colour() {
        float sat = (float) Math.min(1.0, Math.hypot(x, y));
        float hue = (float) ((Math.toDegrees(Math.atan2(y, x)) + 360) % 360);
        return Color.HSVToColor(new float[]{hue, sat, 1f}) & 0xFFFFFF;
    }

    @Override
    protected void onSizeChanged(int w, int h, int oldw, int oldh) {
        float cx = w / 2f, cy = h / 2f, r = radius();
        // SweepGradient goes clockwise from the right, as the hue does here.
        wheel.setShader(new SweepGradient(cx, cy, new int[]{0xFFFF0000, 0xFFFFFF00, 0xFF00FF00, 0xFF00FFFF,
                0xFF0000FF, 0xFFFF00FF, 0xFFFF0000}, null));
        pale.setShader(new RadialGradient(cx, cy, Math.max(1f, r), 0xFFFFFFFF, 0x00FFFFFF, Shader.TileMode.CLAMP));
    }

    /** Room left round the wheel for its focus ring and the dot at the edge. */
    private float radius() {
        return Math.min(getWidth(), getHeight()) / 2f - 10f * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, cy = getHeight() / 2f, r = radius();
        canvas.drawCircle(cx, cy, r, wheel);
        canvas.drawCircle(cx, cy, r, pale);
        if (isFocused()) canvas.drawCircle(cx, cy, r + 6f * density, ring);
        float dx = cx + x * r, dy = cy + y * r;
        // A white ring with a dark one round it: it shows on every colour, white included.
        dot.setStrokeWidth(4f * density);
        dot.setColor(0x99000000);
        canvas.drawCircle(dx, dy, 9f * density, dot);
        dot.setStrokeWidth(2.5f * density);
        dot.setColor(0xFFFFFFFF);
        canvas.drawCircle(dx, dy, 9f * density, dot);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        float dx = 0, dy = 0;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_LEFT: dx = -1; break;
            case KeyEvent.KEYCODE_DPAD_RIGHT: dx = 1; break;
            case KeyEvent.KEYCODE_DPAD_UP: dy = -1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: dy = 1; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                listener.done();
                return true;
            default: return super.onKeyDown(keyCode, event);
        }
        // Small steps for a press, faster while held.
        float step = 0.03f + Math.min(0.07f, event.getRepeatCount() * 0.01f);
        float nx = x + dx * step, ny = y + dy * step;
        double d = Math.hypot(nx, ny);
        if (d > 1.0) {
            // Up or Down from the edge leaves the wheel (to the rows above and below); else it slides along it.
            if (dy != 0 && Math.hypot(x, y) > 0.999) return false;
            nx /= (float) d;
            ny /= (float) d;
        }
        x = nx;
        y = ny;
        invalidate();
        listener.picked(colour());
        return true;
    }
}
