package com.maydings.vibelauncher;

import android.content.Context;
import android.os.Build;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigInteger;
import java.net.ConnectException;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.RSAKeyGenParameterSpec;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLSocket;

/**
 * The TV's own ADB, as a computer would use it, from the TV itself: Vibe Launcher connects to 127.0.0.1:5555
 * (open while network debugging, or Sony's "ADB debugging", is on) and runs shell commands, such as turning an
 * app off (TvApps). The first time, the TV asks whether to allow it, as it asks for a computer.
 * <p>
 * Only the little of the protocol that takes: connect, sign the TV's token (or offer our public key, which
 * makes the TV ask), open one shell, read what it prints until it closes. The key is made once in the Android
 * Keystore and never leaves it; the TV keeps the public half in its list of allowed keys. A connection per
 * call: after the first, signing the token is a few milliseconds, and nothing stays open between calls.
 * <p>
 * Port 5555 first; when that's closed on Android 11 and up, Wireless debugging, once paired (AdbPairing): its port
 * (found by AdbMdns, remembered while it answers), where adbd asks for TLS (STLS) right after CNXN and lets in a
 * certificate whose key it was given by pairing (AdbTlsKey), with no question on the TV. After that the same
 * messages run inside TLS.
 * <p>
 * The adb build only (Settings > Apps isn't in the store build): Play wouldn't take an app that drives ADB.
 */
final class Adb {

    /** What a call came to. */
    static final int OK = 0, OFF = 1, NOT_ALLOWED = 2, NO_KEY = 3, FAILED = 4;
    /** Wireless debugging turned our certificate down: not paired, or forgotten since (Revoke on the TV). */
    static final int NOT_PAIRED = 5;

    static final class Result {
        final int status;
        final String output;

        Result(int status, String output) {
            this.status = status;
            this.output = output;
        }
    }

    private static final String HOST = "127.0.0.1";
    private static final int PORT = 5555;
    /** How long the TV has to answer, and how long someone has to answer the TV's question. */
    private static final int ANSWER_MS = 8000, ASK_MS = 15000;

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257, STLS = 0x534c5453;
    private static final int STLS_VERSION = 0x01000000;
    private static final int AUTH_TOKEN = 1, AUTH_SIGNATURE = 2, AUTH_RSAPUBLICKEY = 3;
    private static final int VERSION = 0x01000000;
    private static final int MAX_DATA = 256 * 1024;
    private static final String KEY_ALIAS = "adb";
    /**
     * adbd checks the signature as OpenSSL's RSA_sign(NID_sha1) of the token: PKCS #1 padding round the token
     * as if it were a SHA-1 hash, behind SHA-1's DigestInfo. The Keystore signs raw (NONEwithRSA), so it's added here.
     */
    private static final byte[] SHA1_DIGEST_INFO = {0x30, 0x21, 0x30, 0x09, 0x06, 0x05, 0x2b, 0x0e, 0x03, 0x02,
            0x1a, 0x05, 0x00, 0x04, 0x14};

    /** For Wireless debugging (its settings, key and port): set by whoever uses Adb first. */
    private static volatile Context app;
    /** Wireless debugging's port as last found; 0 not yet. */
    private static volatile int wirelessPort;
    /** Whether the last connection that got in was through Wireless debugging. */
    private static volatile boolean viaWireless;
    /** Wireless debugging's switch on, but the TV announced no port the last time it was looked for. */
    private static volatile boolean wirelessSilent;

    private Adb() {
    }


    static void use(Context context) {
        app = context.getApplicationContext();
    }

    /**
     * Vibe Launcher has got in over this TV's ADB at least once, either way: port 5555 (opened by USB debugging, or
     * by a player's own Network debugging switch, the Shield's) or Wireless debugging. Kept in a file of its own, outside backups: it's this TV's. Offering the accessibility Home
     * button, Settings warns that ADB is then most likely just turned off.
     */
    static boolean everWorked(Context context) {
        return state(context).getBoolean("everWorked", false);
    }

    private static android.content.SharedPreferences state(Context context) {
        return context.getApplicationContext().getSharedPreferences("adb_state", Context.MODE_PRIVATE);
    }

    /** A connection got in: remembered once. */
    private static void worked() {
        Context c = app;
        if (c != null && !everWorked(c)) state(c).edit().putBoolean("everWorked", true).apply();
    }

    /** Whether the last call that got in went through Wireless debugging (what to turn off when done). */
    static boolean usedWireless() {
        return viaWireless;
    }

    /**
     * Wireless debugging switched on but not running: no port announced when last looked for (the onn stops it with
     * USB debugging and keeps showing On); turning it off and on again starts it.
     */
    static boolean wirelessNotRunning(Context context) {
        return wirelessSilent && wirelessOn(context);
    }

    /** Android 11 and up: whether Wireless debugging is on (adb_wifi_enabled, a Global setting). */
    static boolean wirelessOn(Context context) {
        return wirelessSetting(context) == 1;
    }

    /**
     * adb_wifi_enabled: 1 on, 0 off, -1 not known (before Android 11, or a TV that won't let apps read it: Android 12
     * lets apps read only the hidden settings marked readable).
     */
    static int wirelessSetting(Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return -1;
        try {
            return android.provider.Settings.Global.getInt(context.getContentResolver(), "adb_wifi_enabled", -1);
        } catch (SecurityException e) {
            android.util.Log.i("VibeAdb", "adb_wifi_enabled not readable", e);
            return -1;
        }
    }

    /**
     * Connects and says whether the TV lets us in, without asking it unless ask (which shows the TV's question
     * and waits for it). Off the main thread.
     */
    static Result check(boolean ask) {
        return check(ask, 0);
    }

    /** As check, giving up on the TV's question at deadline (System.currentTimeMillis()); 0 for ASK_MS. */
    static Result check(boolean ask, long deadline) {
        try (Link link = Link.open(ask, deadline)) {
            return new Result(link.status, "");
        } catch (IOException e) {
            return new Result(FAILED, "");
        }
    }

    /** Runs one shell command and returns what it printed; ask as for check. Off the main thread. */
    static Result shell(String command, boolean ask) {
        try (Link link = Link.open(ask, 0)) {
            if (link.status != OK) return new Result(link.status, "");
            return new Result(OK, link.shell(command));
        } catch (IOException e) {
            return new Result(FAILED, "");
        }
    }

    /**
     * Through Wireless debugging only, port 5555 or not: whether the TV still takes our pairing (it forgets it on
     * Forget, Revoke, or a reset), so Settings shows Paired only while it's true. OFF when there's nothing to try.
     * Off the main thread.
     */
    static Result checkWireless() {
        try (Link link = Link.openWireless()) {
            return new Result(link == null ? OFF : link.status, "");
        } catch (IOException e) {
            return new Result(FAILED, "");
        }
    }

    /** One connection to the TV's adbd. */
    private static final class Link implements AutoCloseable {
        private Socket socket;
        private DataInputStream in;
        private OutputStream out;
        int status;
        private int maxData = 4096;

        /** Connected, or (socket null) nothing listening. */
        private Link(Socket socket) throws IOException {
            this.socket = socket;
            in = socket != null ? new DataInputStream(socket.getInputStream()) : null;
            out = socket != null ? socket.getOutputStream() : null;
        }

        static Link open(boolean ask, long deadline) throws IOException {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(HOST, PORT), 2000);
            } catch (ConnectException | SocketTimeoutException e) {
                s.close();
                Link wireless = openWireless();
                if (wireless != null) return wireless;
                Link off = new Link(null);
                off.status = OFF;   // nothing listening: debugging is off
                return off;
            }
            s.setTcpNoDelay(true);
            s.setSoTimeout(ANSWER_MS);
            Link link = new Link(s);
            link.status = link.connect(ask, deadline);
            if (link.status == OK) {
                viaWireless = false;
                worked();
            }
            return link;
        }

        /**
         * Through Wireless debugging, when it's on and Vibe Launcher was paired: at the port last found, else at
         * the one the TV announces now. Null when there's none to try.
         */
        private static Link openWireless() throws IOException {
            Context c = app;
            if (c != null) android.util.Log.i("VibeAdb", "wireless: setting=" + wirelessSetting(c) + " paired="
                    + AdbTlsKey.paired(c) + " port=" + wirelessPort);
            if (c == null || Build.VERSION.SDK_INT < Build.VERSION_CODES.R || wirelessSetting(c) == 0
                    || !AdbTlsKey.paired(c)) return null;
            int port = wirelessPort;
            if (port > 0) {
                Link link = openTls(c, port);
                if (link != null && link.status != FAILED) return link;
                if (link != null) link.close();   // something else has that port now: the TV's new one
            }
            port = AdbMdns.find(c, AdbMdns.CONNECT, 4000);
            android.util.Log.i("VibeAdb", "wireless: mDNS port=" + port);
            wirelessSilent = port <= 0 && wirelessOn(c);
            if (port <= 0) return null;
            wirelessPort = port;
            return openTls(c, port);
        }

        /** CNXN, the TV's STLS, ours back, then TLS 1.3 and its CNXN inside it. Null when nothing listens there. */
        private static Link openTls(Context c, int port) throws IOException {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(HOST, port), 2000);
            } catch (IOException e) {
                s.close();
                return null;
            }
            s.setTcpNoDelay(true);
            s.setSoTimeout(ANSWER_MS);
            Link link = new Link(s);
            try {
                link.send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
                Message m = link.read();
                if (m.command != STLS) {
                    link.status = FAILED;   // not adbd's Wireless debugging port after all
                    return link;
                }
                link.send(STLS, STLS_VERSION, 0, new byte[0]);
                AdbTlsKey key;
                try {
                    key = AdbTlsKey.get(c);
                } catch (GeneralSecurityException | RuntimeException e) {
                    link.status = NO_KEY;
                    return link;
                }
                SSLSocket tls = (SSLSocket) key.tls().getSocketFactory().createSocket(s, HOST, port, true);
                tls.setEnabledProtocols(new String[]{"TLSv1.3"});
                tls.setSoTimeout(ANSWER_MS);
                link.upgrade(tls);
                try {
                    tls.startHandshake();
                    while (true) {
                        Message cnxn = link.read();
                        if (cnxn.command == CNXN) {
                            link.maxData = Math.max(1024, Math.min(cnxn.arg1, MAX_DATA));
                            link.status = OK;
                            viaWireless = true;
                            worked();
                            return link;
                        }
                    }
                } catch (SSLException | java.io.EOFException e) {
                    android.util.Log.i("VibeAdb", "wireless: TLS turned down", e);
                    // Our certificate turned down (TLS 1.3 says so after the handshake): the TV doesn't know this
                    // key, or forgot it (Revoke). Paired anew from Settings > Apps.
                    AdbTlsKey.setPaired(c, false);
                    link.status = NOT_PAIRED;
                    return link;
                }
            } catch (GeneralSecurityException e) {
                link.status = FAILED;
                return link;
            } catch (IOException e) {
                android.util.Log.i("VibeAdb", "wireless: failed", e);
                link.close();
                throw e;
            }
        }

        /** The rest of the connection inside TLS. */
        private void upgrade(SSLSocket tls) throws IOException {
            socket = tls;
            in = new DataInputStream(tls.getInputStream());
            out = tls.getOutputStream();
        }

        /** The handshake: OK once the TV sends CNXN. */
        private int connect(boolean ask, long deadline) throws IOException {
            send(CNXN, VERSION, MAX_DATA, "host::\0".getBytes(StandardCharsets.UTF_8));
            boolean signed = false, offered = false;
            try {
                while (true) {
                    Message m = read();
                    if (m.command == CNXN) {
                        maxData = Math.max(1024, Math.min(m.arg1, MAX_DATA));
                        return OK;
                    }
                    if (m.command != AUTH || m.arg0 != AUTH_TOKEN) continue;
                    if (!signed) {   // a key it may already know
                        byte[] signature;
                        try {
                            signature = sign(m.data);
                        } catch (GeneralSecurityException e) {
                            return NO_KEY;
                        }
                        send(AUTH, AUTH_SIGNATURE, 0, signature);
                        signed = true;
                    } else if (ask && !offered) {   // it doesn't: offer it, and the TV asks
                        byte[] key;
                        try {
                            key = publicKey();
                        } catch (GeneralSecurityException e) {
                            return NO_KEY;
                        }
                        send(AUTH, AUTH_RSAPUBLICKEY, 0, key);
                        offered = true;
                        // Someone has to answer it: until the deadline, else ASK_MS; unanswered, Not connected.
                        int wait = deadline > 0 ? (int) Math.max(1000, deadline - System.currentTimeMillis()) : ASK_MS;
                        socket.setSoTimeout(wait);
                    } else {
                        return NOT_ALLOWED;
                    }
                }
            } catch (SocketTimeoutException e) {
                return NOT_ALLOWED;   // not answered
            } catch (IOException e) {
                // Closed on us: turned down on the TV, or (not asking) a key it doesn't know.
                return signed ? NOT_ALLOWED : FAILED;
            }
        }

        /** One shell command's output, until the TV closes it. */
        String shell(String command) throws IOException {
            int local = 1, remote = 0;
            send(OPEN, local, 0, ("shell:" + command + "\0").getBytes(StandardCharsets.UTF_8));
            ByteArrayOutputStream output = new ByteArrayOutputStream();
            socket.setSoTimeout(60000);   // a long list of commands (Turn all back on) takes a while
            while (true) {
                Message m = read();
                if (m.arg1 != local && m.command != CNXN) continue;
                if (m.command == OKAY) {
                    remote = m.arg0;
                } else if (m.command == WRTE) {
                    remote = m.arg0;
                    output.write(m.data);
                    send(OKAY, local, remote, new byte[0]);
                } else if (m.command == CLSE) {
                    if (remote != 0) send(CLSE, local, remote, new byte[0]);
                    return output.toString("UTF-8");
                }
            }
        }

        private static final class Message {
            int command, arg0, arg1;
            byte[] data;
        }

        private Message read() throws IOException {
            byte[] head = new byte[24];
            in.readFully(head);
            ByteBuffer b = ByteBuffer.wrap(head).order(ByteOrder.LITTLE_ENDIAN);
            Message m = new Message();
            m.command = b.getInt();
            m.arg0 = b.getInt();
            m.arg1 = b.getInt();
            int length = b.getInt();
            b.getInt();   // the data's checksum: TCP already has one
            if (b.getInt() != ~m.command || length < 0 || length > MAX_DATA) throw new IOException("bad message");
            m.data = new byte[length];
            in.readFully(m.data);
            return m;
        }

        private void send(int command, int arg0, int arg1, byte[] data) throws IOException {
            int sum = 0;
            for (byte x : data) sum += x & 0xFF;   // the older versions' check, still asked for by version 1.0
            ByteBuffer b = ByteBuffer.allocate(24 + data.length).order(ByteOrder.LITTLE_ENDIAN);
            b.putInt(command).putInt(arg0).putInt(arg1).putInt(data.length).putInt(sum).putInt(~command).put(data);
            out.write(b.array());
            out.flush();
        }

        @Override
        public void close() throws IOException {
            if (socket != null) socket.close();
        }
    }

    // ---- the key -----------------------------------------------------------------------------------

    /** Vibe Launcher's key, made the first time, in the Keystore. */
    private static KeyStore keys() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(KEY_ALIAS)) {
            KeyPairGenerator g = KeyPairGenerator.getInstance(KeyProperties.KEY_ALGORITHM_RSA, "AndroidKeyStore");
            g.initialize(new KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_SIGN)
                    .setAlgorithmParameterSpec(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
                    .setDigests(KeyProperties.DIGEST_NONE)
                    .setSignaturePaddings(KeyProperties.SIGNATURE_PADDING_RSA_PKCS1)
                    .build());
            g.generateKeyPair();
        }
        return ks;
    }

    private static byte[] sign(byte[] token) throws GeneralSecurityException {
        try {
            Signature s = Signature.getInstance("NONEwithRSA");
            s.initSign((PrivateKey) keys().getKey(KEY_ALIAS, null));
            s.update(SHA1_DIGEST_INFO);
            s.update(token);
            return s.sign();
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
    }

    /**
     * The public key as adbd keeps it (Android's RSAPublicKey struct, little-endian, base64): the modulus in
     * 32-bit words, -1/n[0] mod 2^32 and R^2 mod n for its Montgomery maths, and the exponent; then a name.
     */
    private static byte[] publicKey() throws GeneralSecurityException {
        RSAPublicKey pub;
        try {
            pub = (RSAPublicKey) keys().getCertificate(KEY_ALIAS).getPublicKey();
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        BigInteger n = pub.getModulus();
        int words = 2048 / 32;
        BigInteger r32 = BigInteger.ONE.shiftLeft(32);
        BigInteger n0inv = r32.subtract(n.mod(r32).modInverse(r32));
        BigInteger rr = BigInteger.ONE.shiftLeft(2 * 2048).mod(n);
        ByteBuffer b = ByteBuffer.allocate(4 + 4 + words * 4 * 2 + 4).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(words).putInt(n0inv.intValue());
        putWords(b, n, words);
        putWords(b, rr, words);
        b.putInt(pub.getPublicExponent().intValue());
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " Vibe Launcher\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }
}
