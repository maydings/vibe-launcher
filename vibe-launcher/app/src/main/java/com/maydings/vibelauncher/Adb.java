package com.maydings.vibelauncher;

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
 * Plain network debugging only (port 5555). Android 11's Wireless debugging, with its pairing code and TLS on a
 * random port, isn't spoken here.
 * <p>
 * The adb build only (Settings > Apps isn't in the store build): Play wouldn't take an app that drives ADB.
 */
final class Adb {

    /** What a call came to. */
    static final int OK = 0, OFF = 1, NOT_ALLOWED = 2, NO_KEY = 3, FAILED = 4;

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
    private static final int ANSWER_MS = 8000, ASK_MS = 90000;

    private static final int CNXN = 0x4e584e43, AUTH = 0x48545541, OPEN = 0x4e45504f, OKAY = 0x59414b4f,
            CLSE = 0x45534c43, WRTE = 0x45545257;
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

    private Adb() {
    }

    /**
     * Connects and says whether the TV lets us in, without asking it unless ask (which shows the TV's question
     * and waits for it). Off the main thread.
     */
    static Result check(boolean ask) {
        try (Link link = Link.open(ask)) {
            return new Result(link.status, "");
        } catch (IOException e) {
            return new Result(FAILED, "");
        }
    }

    /** Runs one shell command and returns what it printed; ask as for check. Off the main thread. */
    static Result shell(String command, boolean ask) {
        try (Link link = Link.open(ask)) {
            if (link.status != OK) return new Result(link.status, "");
            return new Result(OK, link.shell(command));
        } catch (IOException e) {
            return new Result(FAILED, "");
        }
    }

    /** One connection to the TV's adbd. */
    private static final class Link implements AutoCloseable {
        private final Socket socket;
        private final DataInputStream in;
        private final OutputStream out;
        int status;
        private int maxData = 4096;

        /** Connected, or (socket null) nothing listening. */
        private Link(Socket socket) throws IOException {
            this.socket = socket;
            in = socket != null ? new DataInputStream(socket.getInputStream()) : null;
            out = socket != null ? socket.getOutputStream() : null;
        }

        static Link open(boolean ask) throws IOException {
            Socket s = new Socket();
            try {
                s.connect(new InetSocketAddress(HOST, PORT), 2000);
            } catch (ConnectException | SocketTimeoutException e) {
                s.close();
                Link off = new Link(null);
                off.status = OFF;   // nothing listening: debugging is off
                return off;
            }
            s.setTcpNoDelay(true);
            s.setSoTimeout(ANSWER_MS);
            Link link = new Link(s);
            link.status = link.connect(ask);
            return link;
        }

        /** The handshake: OK once the TV sends CNXN. */
        private int connect(boolean ask) throws IOException {
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
                        socket.setSoTimeout(ASK_MS);   // someone has to answer it
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
