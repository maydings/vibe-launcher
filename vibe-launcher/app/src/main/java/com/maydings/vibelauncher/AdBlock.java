package com.maydings.vibelauncher;

import android.content.Context;
import android.content.SharedPreferences;
import android.net.ConnectivityManager;
import android.net.LinkProperties;
import android.net.Network;
import android.os.Build;
import android.provider.Settings;

import java.net.InetAddress;

/**
 * Ad blocking for the whole device (Settings > Advanced > Ad blocking): Android's own Private DNS (Android 9 and
 * newer) pointed at a DNS service that filters ads and trackers, so every app's lookups go through it: one of three,
 * or one of the person's own, each with a name, added from the phone (PhoneLink). Written with
 * WRITE_SECURE_SETTINGS, which Settings > Advanced has ADB grant (TvControl). What the device had before is kept
 * and put back when it's turned off.
 *
 * Private DNS with a hostname is strict: when the service can't be reached, no name resolves at all. So each choice
 * is checked once made (check) and the device's own setting put back when it doesn't work.
 */
final class AdBlock {

    /** The services offered: their names (brands, not translated) and DNS-over-TLS hostnames. */
    static final String[] NAMES = {"AdGuard", "AdGuard Family", "Mullvad"};
    static final String[] HOSTS = {"dns.adguard-dns.com", "family.adguard-dns.com", "adblock.dns.mullvad.net"};

    private static final String MODE = "private_dns_mode", SPECIFIER = "private_dns_specifier";
    private static final String MODE_HOSTNAME = "hostname";
    private static final String PREFS = "adblock", KEY_CUSTOM = "customs", KEY_SAVED = "saved",
            KEY_BEFORE_MODE = "beforeMode", KEY_BEFORE_HOST = "beforeHost";
    /** How long a choice has to start working before it's undone. */
    private static final long CHECK_MS = 10_000;

    private AdBlock() {
    }

    /** Whether this device can have it: Android 9 or newer. */
    static boolean supported() {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.P;
    }

    /** The hostname Private DNS uses now, or null when it isn't set to one (off, or Android's automatic mode). */
    static String active(Context context) {
        android.content.ContentResolver cr = context.getContentResolver();
        if (!MODE_HOSTNAME.equals(Settings.Global.getString(cr, MODE))) return null;
        String host = Settings.Global.getString(cr, SPECIFIER);
        return host == null || host.isEmpty() ? null : host;
    }

    /** The name for a hostname: one of NAMES or of the person's own, else the hostname itself. */
    static String nameOf(Context context, String host) {
        for (int i = 0; i < HOSTS.length; i++) if (HOSTS[i].equalsIgnoreCase(host)) return NAMES[i];
        for (String[] c : customs(context)) if (c[1].equalsIgnoreCase(host)) return c[0];
        return host;
    }

    /** The person's own services, in the order added: each {name, hostname}. */
    static java.util.List<String[]> customs(Context context) {
        java.util.List<String[]> all = new java.util.ArrayList<>();
        for (String line : prefs(context).getString(KEY_CUSTOM, "").split("\n")) {
            int tab = line.indexOf('\t');
            if (tab > 0) all.add(new String[]{line.substring(0, tab), line.substring(tab + 1)});
        }
        return all;
    }

    /** One of the person's own added, or renamed when its hostname is there already. Name blank: the hostname. */
    static void addCustom(Context context, String name, String host) {
        String label = name == null ? "" : name.replaceAll("[\\t\\n\\r]+", " ").trim();
        if (label.isEmpty()) label = host;
        java.util.List<String[]> all = customs(context);
        boolean found = false;
        for (String[] c : all) {
            if (c[1].equalsIgnoreCase(host)) {
                c[0] = label;
                found = true;
            }
        }
        if (!found) all.add(new String[]{label, host});
        saveCustoms(context, all);
    }

    /** One of the person's own taken off the list (turned off first when it's in use: SettingsScreen). */
    static void removeCustom(Context context, String host) {
        java.util.List<String[]> all = customs(context);
        all.removeIf(c -> c[1].equalsIgnoreCase(host));
        saveCustoms(context, all);
    }

    private static void saveCustoms(Context context, java.util.List<String[]> all) {
        StringBuilder b = new StringBuilder();
        for (String[] c : all) b.append(c[0]).append('\t').append(c[1]).append('\n');
        prefs(context).edit().putString(KEY_CUSTOM, b.toString()).apply();
    }

    /**
     * A hostname as the phone sent it, made plain ("https://abc.dns.nextdns.io/" or "tls://…" as copied from a
     * provider's page): lower case, no scheme, path or port; "" when what's left isn't a hostname.
     */
    static String clean(String typed) {
        String host = typed.trim().toLowerCase(java.util.Locale.ROOT);
        int scheme = host.indexOf("://");
        if (scheme >= 0) host = host.substring(scheme + 3);
        int end = host.length();
        for (char c : new char[]{'/', ':', '?', '#'}) {
            int at = host.indexOf(c);
            if (at >= 0) end = Math.min(end, at);
        }
        host = host.substring(0, end);
        while (host.endsWith(".")) host = host.substring(0, host.length() - 1);
        return host.matches("([a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?\\.)+[a-z0-9-]{2,63}") ? host : "";
    }

    /**
     * Private DNS set to a hostname. The device's own setting is kept the first time, for off(). False without the
     * permission.
     */
    @android.annotation.SuppressLint("ApplySharedPref")   // the device's own setting on disk before it's replaced
    static boolean use(Context context, String host) {
        if (!TvControl.canWrite(context)) return false;
        android.content.ContentResolver cr = context.getContentResolver();
        SharedPreferences p = prefs(context);
        try {
            if (!p.getBoolean(KEY_SAVED, false)) {
                p.edit().putBoolean(KEY_SAVED, true)
                        .putString(KEY_BEFORE_MODE, Settings.Global.getString(cr, MODE))
                        .putString(KEY_BEFORE_HOST, Settings.Global.getString(cr, SPECIFIER)).commit();
            }
            return Settings.Global.putString(cr, SPECIFIER, host) & Settings.Global.putString(cr, MODE, MODE_HOSTNAME);
        } catch (SecurityException e) {
            return false;
        }
    }

    /**
     * Private DNS as the device had it before ad blocking (Android's automatic mode when it had nothing set). A
     * hostname the device has of its own, which ad blocking never replaced, is left alone.
     */
    static boolean off(Context context) {
        if (!TvControl.canWrite(context)) return false;
        android.content.ContentResolver cr = context.getContentResolver();
        SharedPreferences p = prefs(context);
        try {
            boolean saved = p.getBoolean(KEY_SAVED, false);
            if (!saved) {
                // Nothing kept: ad blocking never changed it. A hostname of the device's own stays as it is.
                String now = active(context);
                if (now == null || !isOurs(context, now)) return true;
            }
            String mode = saved ? p.getString(KEY_BEFORE_MODE, null) : null;
            String host = saved ? p.getString(KEY_BEFORE_HOST, null) : null;
            // Its own hostname only: one of ours left from before would keep the ads blocked.
            if (MODE_HOSTNAME.equals(mode) && (host == null || isOurs(context, host))) mode = null;
            boolean ok = Settings.Global.putString(cr, MODE, mode) & Settings.Global.putString(cr, SPECIFIER, host);
            if (ok) p.edit().remove(KEY_SAVED).remove(KEY_BEFORE_MODE).remove(KEY_BEFORE_HOST).apply();
            return ok;
        } catch (SecurityException e) {
            return false;
        }
    }

    private static boolean isOurs(Context context, String host) {
        for (String h : HOSTS) if (h.equalsIgnoreCase(host)) return true;
        for (String[] c : customs(context)) if (c[1].equalsIgnoreCase(host)) return true;
        return false;
    }

    /**
     * Whether the network now uses this hostname for DNS and a name resolves through it, waiting up to CHECK_MS for
     * Android to connect to it. Off the main thread.
     */
    static boolean check(Context context, String host) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) return false;
        ConnectivityManager cm = context.getApplicationContext().getSystemService(ConnectivityManager.class);
        long until = System.currentTimeMillis() + CHECK_MS;
        while (System.currentTimeMillis() < until) {
            Network net = cm.getActiveNetwork();
            LinkProperties lp = net != null ? cm.getLinkProperties(net) : null;
            if (lp != null && lp.isPrivateDnsActive() && host.equalsIgnoreCase(lp.getPrivateDnsServerName())) {
                try {
                    // A name looked up through the network, which now asks only the chosen service.
                    InetAddress[] found = net.getAllByName("github.com");
                    if (found.length > 0) return true;
                } catch (java.io.IOException | RuntimeException ignored) {
                    // not answering yet
                }
            }
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }
}
