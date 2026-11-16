package com.maydings.vibelauncher;

/** A percentage setting (the opacities): kept within 0 to 100, as an alpha, and written as the language writes it. */
final class Percent {

    private Percent() {
    }

    static int clamp(int percent) {
        return Math.max(0, Math.min(100, percent));
    }

    /** 0 to 255. */
    static int alpha(int percent) {
        return Math.round(255 * clamp(percent) / 100f);
    }

    /** "65%", "%65", "65 %": the language's own way. */
    static String format(int percent) {
        return java.text.NumberFormat.getPercentInstance().format(clamp(percent) / 100.0);
    }
}
