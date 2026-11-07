package com.maydings.vibelauncher;

import android.net.Uri;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collections;
import java.util.Map;

/** Plain GET requests for the backdrop and wallpaper sources (artwork, TMDB, YouTube, Plex, HDHomeRun). Worker threads only. */
final class Http {

    private static final int MAX_TEXT_BYTES = 4 * 1024 * 1024;
    /** Who's asking, as Wikimedia asks of apps; sent to Wikimedia only, the rest get Android's own as before. */
    private static final String USER_AGENT = "VibeLauncher/" + BuildConfig.VERSION_NAME
            + " (https://github.com/maydings/vibe-launcher)";

    private Http() {
    }

    private static boolean wikimedia(String url) {
        String host = Uri.parse(url).getHost();
        return host != null && (host.equals("wikimedia.org") || host.endsWith(".wikimedia.org"));
    }

    /** The body of a 200 reply, at most maxBytes long; anything else is an IOException. */
    static byte[] get(String url, Map<String, String> headers, int maxBytes) throws IOException {
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (wikimedia(url)) c.setRequestProperty("User-Agent", USER_AGENT);
            for (Map.Entry<String, String> h : headers.entrySet()) c.setRequestProperty(h.getKey(), h.getValue());
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            try (InputStream in = c.getInputStream()) {
                return readAll(in, maxBytes);
            }
        } finally {
            c.disconnect();
        }
    }

    static byte[] get(String url, int maxBytes) throws IOException {
        return get(url, Collections.emptyMap(), maxBytes);
    }

    /** A text (JSON) reply as a string. */
    static String getText(String url, Map<String, String> headers) throws IOException {
        return new String(get(url, headers, MAX_TEXT_BYTES), StandardCharsets.UTF_8);
    }

    /**
     * A text reply from a device on the home network (the HDHomeRun tuner), which only speaks plain HTTP.
     * Android refuses plain HTTP for this app, which keeps it from the internet; this asks over a socket
     * instead, and only addresses on the home network (192.168.x.x and the like).
     */
    static String getLan(String ip, String path) throws IOException {
        // A plain IPv4 address only: a name would be looked up first, and could point anywhere.
        if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) throw new IOException("not an address: " + ip);
        InetAddress address = InetAddress.getByName(ip);   // a literal address: no lookup
        if (!address.isSiteLocalAddress()) throw new IOException("not on the home network: " + ip);
        try (Socket socket = new Socket()) {
            socket.connect(new InetSocketAddress(address, 80), 5000);
            socket.setSoTimeout(10000);
            OutputStream out = socket.getOutputStream();
            out.write(("GET " + path + " HTTP/1.0\r\nHost: " + ip + "\r\nConnection: close\r\n\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            out.flush();
            String reply = new String(readAll(socket.getInputStream(), MAX_TEXT_BYTES), StandardCharsets.UTF_8);
            int body = reply.indexOf("\r\n\r\n");
            if (!reply.startsWith("HTTP/1.") || !reply.startsWith(" 200", 8) || body < 0) {
                throw new IOException("bad reply from " + ip);
            }
            return reply.substring(body + 4);
        }
    }

    /**
     * Saves a 200 reply to a file, at most maxBytes, and returns its SHA-256 (lower-case hex). Written as it
     * arrives: an APK (SmartTube's is up to 35 MB) held in memory whole could run a small TV out of it.
     */
    static String download(String url, File to, int maxBytes) throws IOException {
        MessageDigest sha;
        try {
            sha = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException e) {
            throw new IOException(e);
        }
        HttpURLConnection c = (HttpURLConnection) new URL(url).openConnection();
        try {
            c.setConnectTimeout(10000);
            c.setReadTimeout(15000);
            if (wikimedia(url)) c.setRequestProperty("User-Agent", USER_AGENT);
            int code = c.getResponseCode();
            if (code != 200) throw new IOException("HTTP " + code);
            // Too big by its own say: not fetched at all (else it would run to the limit before failing).
            if (c.getContentLengthLong() > maxBytes) throw new IOException("over " + maxBytes + " bytes");
            try (InputStream in = c.getInputStream(); FileOutputStream out = new FileOutputStream(to)) {
                byte[] buf = new byte[65536];
                long total = 0;
                int n;
                while ((n = in.read(buf)) > 0) {
                    total += n;
                    if (total > maxBytes) throw new IOException("over " + maxBytes + " bytes");
                    sha.update(buf, 0, n);
                    out.write(buf, 0, n);
                }
            }
        } finally {
            c.disconnect();
        }
        StringBuilder hex = new StringBuilder();
        for (byte b : sha.digest()) hex.append(String.format("%02x", b));
        return hex.toString();
    }

    /** All of a stream, refusing more than maxBytes (a runaway download). */
    static byte[] readAll(InputStream in, int maxBytes) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[16384];
        int n;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            if (out.size() > maxBytes) throw new IOException("over " + maxBytes + " bytes");
        }
        return out.toByteArray();
    }
}
