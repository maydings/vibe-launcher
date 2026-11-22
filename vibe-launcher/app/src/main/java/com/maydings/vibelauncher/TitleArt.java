package com.maydings.vibelauncher;

import java.util.Locale;

/**
 * Clean artwork for a film or show, found by the title an app put on its home screen card: a backdrop
 * without text, and the title's logo to show over it. TMDB when the user has a key, Stremio's free
 * metadata otherwise.
 */
interface TitleArt {

    /** A textless backdrop and, when there is one, the title's logo (transparent PNG); URLs. */
    final class Art {
        final String backdrop, logo;

        Art(String backdrop, String logo) {
            this.backdrop = backdrop;
            this.logo = logo;
        }
    }

    /** Art for an exact title match, or null. Network: worker thread only. */
    Art lookup(String title);

    /**
     * "The Office (U.S.)" and "the office u s" compare equal: lower case, letters and digits only. So do
     * "Fast & Furious" and "Fast and Furious": an ampersand is read as the word.
     */
    static String normalize(String s) {
        if (s == null) return "";
        return s.toLowerCase(Locale.ROOT).replace("&", " and ").replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}
