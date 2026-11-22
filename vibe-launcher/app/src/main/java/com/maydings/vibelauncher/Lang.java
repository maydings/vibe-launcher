package com.maydings.vibelauncher;

import android.content.Context;
import android.content.res.Configuration;
import android.content.res.Resources;

import java.util.Locale;

/**
 * The launcher's language: the TV's own (the default), or one chosen in Settings → General. Android 13
 * has per-app languages built in; the TVs this runs on are older, so each screen applies the choice to
 * its own resources (wrap) and to the default locale, which also drives the clock, the date and TMDB's
 * logo language.
 */
final class Lang {

    /** The languages offered, by number of speakers; each folder under res/values-* matches one. */
    static final String[] TAGS = {
            "en", "zh-CN", "zh-TW", "hi", "es", "fr", "ar", "bn", "pt-BR", "ru",
            "ur", "id", "de", "ja", "mr", "te", "tr", "ta", "vi", "ko",
            "it", "th", "gu", "fa", "pl", "uk", "kn", "ml", "pa", "my",
            "ro", "nl", "hu", "el", "cs", "sv", "iw", "ms", "tl", "sw",
            "am", "ne", "si", "km", "da", "fi", "nb", "sk", "bg", "hr"};

    /** Each language's name in itself, as its speakers would look for it. */
    static final String[] NAMES = {
            "English", "简体中文", "繁體中文", "हिन्दी", "Español", "Français", "العربية", "বাংলা",
            "Português (Brasil)", "Русский", "اردو", "Bahasa Indonesia", "Deutsch", "日本語", "मराठी", "తెలుగు",
            "Türkçe", "தமிழ்", "Tiếng Việt", "한국어", "Italiano", "ไทย", "ગુજરાતી", "فارسی", "Polski",
            "Українська", "ಕನ್ನಡ", "മലയാളം", "ਪੰਜਾਬੀ", "မြန်မာ", "Română", "Nederlands", "Magyar",
            "Ελληνικά", "Čeština", "Svenska", "עברית", "Bahasa Melayu", "Filipino", "Kiswahili", "አማርኛ",
            "नेपाली", "සිංහල", "ខ្មែរ", "Dansk", "Suomi", "Norsk bokmål", "Slovenčina", "Български",
            "Hrvatski"};

    private Lang() {
    }

    /** The TV's own language, whatever this process set as the default. */
    static Locale system() {
        return Resources.getSystem().getConfiguration().getLocales().get(0);
    }

    /**
     * The country the TV is set to: the default locale's, or, when that's a language picked here with no
     * country of its own ("en"), the TV's own.
     */
    static String country() {
        String country = Locale.getDefault().getCountry();
        return country.isEmpty() ? system().getCountry() : country;
    }

    /** A context in the chosen language (unchanged for the TV's own), with the default locale to match. */
    static Context wrap(Context base) {
        String tag = new VibeSettings(base).language();
        if (tag.isEmpty()) {
            Locale.setDefault(system());   // back from a chosen language without a restart
            return base;
        }
        Locale locale = Locale.forLanguageTag(tag);
        Locale.setDefault(locale);
        Configuration config = new Configuration(base.getResources().getConfiguration());
        config.setLocale(locale);
        return base.createConfigurationContext(config);
    }

    /** A language's own name for showing in Settings; "" for the TV's own. */
    static String name(String tag) {
        for (int i = 0; i < TAGS.length; i++) if (TAGS[i].equals(tag)) return NAMES[i];
        return "";
    }
}
