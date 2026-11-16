package com.maydings.vibelauncher;

import android.app.Activity;
import android.content.Context;
import android.util.AttributeSet;
import android.view.InflateException;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.TextView;

/**
 * Layouts name the font by string (android:fontFamily="@string/font_regular", or the TopButton style), which is read
 * once at inflation and can't follow a font chosen in Settings. This sees those views as they're made and gives them
 * UiFont's choice instead: the clock and weather font for the clock row and the slideshow's clock, the font for the
 * rest. Installed on each Activity's inflater before its first layout (popups and dialogs use the same inflater).
 */
final class FontInflater implements LayoutInflater.Factory2 {

    private static final String ANDROID = "http://schemas.android.com/apk/res/android";
    /** The views that take the clock and weather font. */
    private static final int[] CLOCK = {R.id.clock, R.id.ampm, R.id.date, R.id.weather, R.id.slideTime, R.id.slideAmPm};

    private FontInflater() {
    }

    static void install(Activity activity) {
        install(activity.getLayoutInflater());
    }

    static void install(LayoutInflater inflater) {
        if (inflater.getFactory2() == null && inflater.getFactory() == null) inflater.setFactory2(new FontInflater());
    }

    @Override
    public View onCreateView(View parent, String name, Context context, AttributeSet attrs) {
        int font = attrs.getAttributeResourceValue(ANDROID, "fontFamily", 0);
        boolean styled = attrs.getStyleAttribute() == R.style.TopButton;
        if (font != R.string.font_regular && font != R.string.font_medium && !styled) return null;
        View view;
        try {
            LayoutInflater inflater = LayoutInflater.from(context);
            if (name.indexOf('.') >= 0) {
                view = inflater.createView(name, null, attrs);
            } else {
                try {
                    view = inflater.createView(name, "android.widget.", attrs);
                } catch (ClassNotFoundException e) {
                    view = inflater.createView(name, "android.view.", attrs);
                }
            }
        } catch (ClassNotFoundException | InflateException e) {
            return null;   // made as usual, in the layout's own font
        }
        if (view instanceof TextView) {
            boolean medium = font == R.string.font_medium || (styled && font == 0);
            int id = attrs.getAttributeResourceValue(ANDROID, "id", 0);
            boolean clock = false;
            for (int c : CLOCK) clock |= c == id;
            ((TextView) view).setTypeface(clock ? UiFont.clock(context, medium)
                    : UiFont.get(context, medium ? R.string.font_medium : R.string.font_regular));
        }
        return view;
    }

    @Override
    public View onCreateView(String name, Context context, AttributeSet attrs) {
        return onCreateView(null, name, context, attrs);
    }
}
