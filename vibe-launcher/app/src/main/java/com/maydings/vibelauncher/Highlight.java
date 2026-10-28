package com.maydings.vibelauncher;

import android.content.Context;
import android.content.res.ColorStateList;
import android.graphics.Color;
import android.graphics.PorterDuff;
import android.view.View;
import android.widget.ImageView;
import android.widget.TextView;

/**
 * The highlight color (Settings > Appearance > Highlight color) on everything that shows focus besides the
 * glass: the top bar's pills, the menus' rows, the Play pill's fill, Settings' rows and the update window's
 * buttons. Their drawables are white where focused; tinted in the color there (SRC_IN keeps each one's own
 * transparency) and left white elsewhere. Text on the color turns dark or white, whichever reads.
 */
final class Highlight {

    private static final int WHITE = 0xFFFFFFFF;
    private static final int[][] FOCUSED_OR_NOT = {{android.R.attr.state_focused}, {}};

    private Highlight() {
    }

    /** The chosen color, opaque (white unless chosen). */
    static int color(Context context) {
        return 0xFF000000 | new VibeSettings(context).glassTint();
    }

    /** Text on the color: near black on light colors, white on dark ones. */
    static int on(int color) {
        return Color.luminance(color) > 0.4f ? 0xFF111111 : WHITE;
    }

    private static ColorStateList focused(int whenFocused, int otherwise) {
        return new ColorStateList(FOCUSED_OR_NOT, new int[]{whenFocused, otherwise});
    }

    private static int withAlpha(int color, int alpha) {
        return alpha << 24 | (color & 0xFFFFFF);
    }

    private static void fill(View v, int color) {
        v.setBackgroundTintList(focused(color, WHITE));
        v.setBackgroundTintMode(PorterDuff.Mode.SRC_IN);
    }

    /** A top bar pill (pill_bg, pill_text): its text and icon too. */
    static void pill(TextView pill, int color) {
        fill(pill, color);
        ColorStateList text = focused(on(color), WHITE);
        pill.setTextColor(text);
        pill.setCompoundDrawableTintList(text);
    }

    /** The Play pill's fill (pill_filled): the focused look, always. */
    static void filled(TextView fill, int color) {
        fill.setBackgroundTintList(ColorStateList.valueOf(color));
        fill.setBackgroundTintMode(PorterDuff.Mode.SRC_IN);
        fill.setTextColor(on(color));
        fill.setCompoundDrawableTintList(ColorStateList.valueOf(on(color)));
    }

    /** A menu row (item_input): icon, title and status. */
    static void menuRow(View row, int color) {
        fill(row, color);
        ColorStateList text = focused(on(color), WHITE);
        ((ImageView) row.findViewById(R.id.icon)).setImageTintList(text);
        ((TextView) row.findViewById(R.id.title)).setTextColor(text);
        ((TextView) row.findViewById(R.id.status)).setTextColor(focused(withAlpha(on(color), 0x99), 0x73FFFFFF));
    }

    /** A Settings row (item_setting): name, value and its >. */
    static void settingRow(View row, int color) {
        fill(row, color);
        int on = on(color);
        ((TextView) row.findViewById(R.id.label)).setTextColor(focused(on, 0xEBFFFFFF));
        ColorStateList value = focused(on == WHITE ? 0xB3FFFFFF : 0xFF6E6E73, 0x8CFFFFFF);
        ((TextView) row.findViewById(R.id.value)).setTextColor(value);
        ((ImageView) row.findViewById(R.id.chevron)).setImageTintList(value);
    }
}
