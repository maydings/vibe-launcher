package com.maydings.vibelauncher;

import android.content.Context;
import android.graphics.Typeface;
import android.util.TypedValue;

import java.util.Locale;

/**
 * The font for text, by role: regular or medium (headings), for the clock and weather or the rest. The chosen font
 * (Appearance > Font, Clock and weather font; Fonts) when it has the current language's letters, else the system
 * font, so no word comes out in two fonts; Outfit when none is chosen, by the font_* strings (a font resource, or a
 * system family by name where a language needs it, as values-vi/fonts.xml).
 */
final class UiFont {

    private UiFont() {
    }

    /** For the rest of the text: stringRes is R.string.font_regular or R.string.font_medium. */
    static Typeface get(Context context, int stringRes) {
        return pick(context, stringRes, false);
    }

    /** For the clock, the date and the weather. */
    static Typeface clock(Context context, boolean medium) {
        return pick(context, medium ? R.string.font_medium : R.string.font_regular, true);
    }

    private static Typeface pick(Context context, int stringRes, boolean clock) {
        VibeSettings settings = new VibeSettings(context);
        String choice = clock ? settings.clockFont() : settings.uiFont();
        boolean medium = stringRes == R.string.font_medium;
        if (!Fonts.OUTFIT.equals(choice)) {
            Typeface t = Fonts.typeface(context, choice, medium);
            if (t != null) {
                if (Fonts.covers(context, choice, sample(context))) return t;
                return Typeface.create(medium ? "sans-serif-medium" : "sans-serif", Typeface.NORMAL);
            }
        }
        return outfit(context, stringRes);
    }

    /** Outfit, or the system family a language's font_* string names. */
    private static Typeface outfit(Context context, int stringRes) {
        TypedValue v = new TypedValue();
        context.getResources().getValue(stringRes, v, true);
        if (v.type == TypedValue.TYPE_STRING && v.string != null && !v.string.toString().startsWith("res/")) {
            return Typeface.create(v.string.toString(), Typeface.NORMAL);
        }
        return context.getResources().getFont(v.resourceId);
    }

    private static String sampleOf, sample;

    /**
     * What a font must have to be used for the current language: the language's name in itself, digits, and
     * the letters of a few of Vibe Launcher's own longer texts in it. Its name alone hasn't all its letters
     * ("Türkçe" has no ğ, ş or ı), and a font without some would have those drawn in another.
     */
    static synchronized String sample(Context context) {
        String tag = new VibeSettings(context).language() + '/' + Lang.system().toLanguageTag();
        if (tag.equals(sampleOf)) return sample;
        StringBuilder letters = new StringBuilder(languageName(context)).append("0123456789");
        try {
            Context in = Lang.wrap(context);
            for (int text : new int[]{R.string.flights_short, R.string.title_info_short, R.string.wp_custom_short,
                    R.string.backup_short}) {
                String words = in.getString(text);
                for (int i = 0; i < words.length(); ) {
                    int c = words.codePointAt(i);
                    i += Character.charCount(c);
                    if (Character.isLetter(c) && letters.indexOf(new String(Character.toChars(c))) < 0) {
                        letters.appendCodePoint(c);
                    }
                }
            }
        } catch (RuntimeException e) {
            // its name and the digits, then
        }
        sampleOf = tag;
        sample = letters.toString();
        return sample;
    }

    /** The language Vibe Launcher shows now, by its own name ("Tiếng Việt", "العربية"). */
    static String languageName(Context context) {
        String tag = new VibeSettings(context).language();
        if (!tag.isEmpty()) return Lang.name(tag);
        Locale l = Lang.system();
        String name = l.getDisplayLanguage(l);
        return name.isEmpty() ? "English" : Character.toUpperCase(name.charAt(0)) + name.substring(1);
    }
}
