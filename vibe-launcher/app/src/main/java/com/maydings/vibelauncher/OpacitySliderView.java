package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.view.KeyEvent;
import android.view.View;

/**
 * A vertical slider, as a TV's volume: Up and Down move it (5% a press, faster while held), OK is done. The
 * track fills from the bottom in white (a level, not a selection); the value stands beside the knob, as the language
 * writes a percentage. At either end, Up or Down goes on to whatever is above or below, as the colour wheel's
 * edge does. Left (Right in a right-to-left language) goes back, as a list row does.
 */
@android.annotation.SuppressLint("ViewConstructor")   // made in code only (SettingsScreen.opacity)
final class OpacitySliderView extends View {

    interface Listener {
        void changed(int percent);

        /** OK, or Left: the value is chosen, back to the list. */
        void done();
    }

    static final int STEP = 5;

    private final Paint track = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint fill = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knob = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint knobEdge = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint ring = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint text = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final RectF rect = new RectF();
    private final float density;
    private final Listener listener;
    private int value;

    OpacitySliderView(Context context, int percent, android.graphics.Typeface face, Listener listener) {
        super(context);
        this.listener = listener;
        density = getResources().getDisplayMetrics().density;
        value = Percent.clamp(percent);
        track.setColor(0x33FFFFFF);
        fill.setColor(0xB3FFFFFF);   // white, not the highlight colour: it's a level, not a selection
        knob.setColor(0xFFFFFFFF);
        knobEdge.setStyle(Paint.Style.STROKE);
        knobEdge.setStrokeWidth(2f * density);
        knobEdge.setColor(0x66000000);
        ring.setStyle(Paint.Style.STROKE);
        ring.setStrokeWidth(2f * density);
        ring.setColor(0xCCFFFFFF);
        text.setColor(0xFFFFFFFF);
        text.setTextSize(22f * getResources().getDisplayMetrics().scaledDensity);
        if (face != null) text.setTypeface(face);
        setFocusable(true);
    }

    private float trackTop() {
        return 18f * density;
    }

    private float trackBottom() {
        return getHeight() - 18f * density;
    }

    @Override
    protected void onDraw(Canvas canvas) {
        float cx = getWidth() / 2f, half = 6f * density;
        float top = trackTop(), bottom = trackBottom();
        float y = bottom - (bottom - top) * value / 100f;
        rect.set(cx - half, top, cx + half, bottom);
        canvas.drawRoundRect(rect, half, half, track);
        rect.set(cx - half, y, cx + half, bottom);
        canvas.drawRoundRect(rect, half, half, fill);
        if (isFocused()) {
            rect.set(cx - half - 7f * density, top - 7f * density, cx + half + 7f * density, bottom + 7f * density);
            canvas.drawRoundRect(rect, half + 7f * density, half + 7f * density, ring);
        }
        canvas.drawCircle(cx, y, 13f * density, knob);
        canvas.drawCircle(cx, y, 13f * density, knobEdge);   // apart from the white fill it sits on
        // The value beside the knob, on the side the language reads from.
        String label = Percent.format(value);
        boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
        float w = text.measureText(label), gap = 28f * density;
        float tx = rtl ? cx - gap - w : cx + gap;
        canvas.drawText(label, tx, y - (text.descent() + text.ascent()) / 2f, text);
    }

    @Override
    protected void onFocusChanged(boolean gainFocus, int direction, android.graphics.Rect previous) {
        super.onFocusChanged(gainFocus, direction, previous);
        invalidate();
    }

    @Override
    public boolean onKeyDown(int keyCode, KeyEvent event) {
        int d;
        switch (keyCode) {
            case KeyEvent.KEYCODE_DPAD_UP: d = 1; break;
            case KeyEvent.KEYCODE_DPAD_DOWN: d = -1; break;
            case KeyEvent.KEYCODE_DPAD_CENTER:
            case KeyEvent.KEYCODE_ENTER:
                listener.done();
                return true;
            case KeyEvent.KEYCODE_DPAD_LEFT:
            case KeyEvent.KEYCODE_DPAD_RIGHT:
                boolean rtl = getLayoutDirection() == LAYOUT_DIRECTION_RTL;
                if ((keyCode == KeyEvent.KEYCODE_DPAD_LEFT) != rtl) listener.done();
                return true;   // the other way: nothing there
            default: return super.onKeyDown(keyCode, event);
        }
        if ((d > 0 && value >= 100) || (d < 0 && value <= 0)) return false;   // at the end: on to the next thing
        int step = event.getRepeatCount() > 6 ? 2 * STEP : STEP;   // faster while held
        value = Percent.clamp((value / STEP + d * step / STEP) * STEP);
        invalidate();
        listener.changed(value);
        return true;
    }
}
