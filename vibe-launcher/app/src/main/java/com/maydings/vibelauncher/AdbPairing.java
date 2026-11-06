package com.maydings.vibelauncher;

import android.content.Context;
import android.net.ssl.SSLSockets;
import android.os.Build;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Arrays;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import javax.net.ssl.SSLSocket;

/**
 * Pairing with Wireless debugging's code (Android 11 and up), as `adb pair` does it (Android's
 * packages/modules/adb, pairing_connection and pairing_auth):
 * <ol>
 * <li>TLS 1.3 to the pairing port, each end showing a certificate, neither checked;</li>
 * <li>SPAKE2 (Spake2) with the code followed by 64 bytes exported from that TLS session ("adb-label\0"), so the
 * code only works on this connection: one message each way, "adb pair client" and "adb pair server";</li>
 * <li>its key through HKDF-SHA256 ("adb pairing_auth aes-128-gcm key") to an AES-128-GCM key, a counter as the
 * nonce, one each way;</li>
 * <li>our public key (AdbTlsKey), in an 8192-byte PeerInfo, sent encrypted; the TV's (its GUID) back. A wrong
 * code shows as the TV closing the connection, or as its answer not decrypting.</li>
 * </ol>
 * Packets are a version (1), a type (0 the SPAKE2 message, 1 PeerInfo) and the length, big-endian, then the data.
 * Worker thread.
 */
final class AdbPairing {

    static final int OK = 0, WRONG_CODE = 1, UNREACHABLE = 2, FAILED = 3, NO_KEY = 4;

    private static final byte VERSION = 1, SPAKE2_MSG = 0, PEER_INFO = 1;
    private static final int PEER_INFO_SIZE = 8192, MAX_PAYLOAD = PEER_INFO_SIZE * 2;
    private static final byte ADB_RSA_PUB_KEY = 0;
    private static final byte[] CLIENT = "adb pair client\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] SERVER = "adb pair server\0".getBytes(StandardCharsets.UTF_8);
    private static final byte[] HKDF_INFO = "adb pairing_auth aes-128-gcm key".getBytes(StandardCharsets.UTF_8);

    private AdbPairing() {
    }

    /** Pairs with the TV's pairing window on this port (127.0.0.1) using its six-digit code. */
    static int pair(Context context, String host, int port, String code) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return FAILED;
        AdbTlsKey key;
        try {
            key = AdbTlsKey.get(context);
        } catch (GeneralSecurityException | RuntimeException e) {   // the Keystore throws ProviderException too
            android.util.Log.i("VibeAdb", "pairing: no key", e);
            return NO_KEY;
        }
        Socket plain = new Socket();
        try {
            plain.connect(new InetSocketAddress(host, port), 3000);
        } catch (IOException e) {
            closeQuietly(plain);
            return UNREACHABLE;
        }
        boolean exchanged = false;
        try (SSLSocket tls = (SSLSocket) key.tls().getSocketFactory().createSocket(plain, host, port, true)) {
            tls.setSoTimeout(15000);
            tls.setEnabledProtocols(new String[]{"TLSv1.3"});
            tls.startHandshake();
            byte[] exported = SSLSockets.exportKeyingMaterial(tls, "adb-label\0", null, 64);
            if (exported == null) return FAILED;
            byte[] digits = code.getBytes(StandardCharsets.UTF_8);
            byte[] password = Arrays.copyOf(digits, digits.length + exported.length);
            System.arraycopy(exported, 0, password, digits.length, exported.length);

            DataInputStream in = new DataInputStream(tls.getInputStream());
            OutputStream out = tls.getOutputStream();
            Spake2 spake = new Spake2(Spake2.ALICE, CLIENT, SERVER);
            write(out, SPAKE2_MSG, spake.generateMessage(password));
            byte[] theirs = read(in, SPAKE2_MSG);
            byte[] secret = spake.processMessage(theirs);
            spake.destroy();
            if (secret == null) return WRONG_CODE;
            exchanged = true;
            Gcm gcm = new Gcm(hkdf(secret, 16));

            byte[] info = new byte[PEER_INFO_SIZE];
            info[0] = ADB_RSA_PUB_KEY;
            byte[] pub = key.adbPublicKey();
            System.arraycopy(pub, 0, info, 1, Math.min(pub.length, PEER_INFO_SIZE - 1));
            write(out, PEER_INFO, gcm.seal(info));
            byte[] answer;
            try {
                answer = gcm.open(read(in, PEER_INFO));
            } catch (GeneralSecurityException e) {
                return WRONG_CODE;   // didn't decrypt: not the same key, so not the same code
            }
            if (answer.length != PEER_INFO_SIZE) return FAILED;
            AdbTlsKey.setPaired(context, true);
            return OK;
        } catch (EOFException e) {
            // Closed on us after the codes were compared: the TV's side didn't decrypt ours, a wrong code.
            return exchanged ? WRONG_CODE : FAILED;
        } catch (IOException | GeneralSecurityException | RuntimeException e) {
            android.util.Log.i("VibeAdb", "pairing: " + (exchanged ? "after" : "before") + " the code exchange", e);
            return exchanged ? WRONG_CODE : FAILED;
        }
    }

    private static void write(OutputStream out, byte type, byte[] payload) throws IOException {
        ByteBuffer b = ByteBuffer.allocate(6 + payload.length).order(ByteOrder.BIG_ENDIAN);
        b.put(VERSION).put(type).putInt(payload.length).put(payload);
        out.write(b.array());
        out.flush();
    }

    private static byte[] read(DataInputStream in, byte type) throws IOException {
        byte version = in.readByte(), got = in.readByte();
        int length = in.readInt();
        if (version != VERSION || got != type || length <= 0 || length > MAX_PAYLOAD) throw new IOException("bad packet");
        byte[] data = new byte[length];
        in.readFully(data);
        return data;
    }

    /** HKDF-SHA256 with no salt (32 zero bytes) and HKDF_INFO; length up to 32. */
    static byte[] hkdf(byte[] ikm, int length) throws GeneralSecurityException {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(new byte[32], "HmacSHA256"));
        byte[] prk = mac.doFinal(ikm);
        mac.init(new SecretKeySpec(prk, "HmacSHA256"));
        mac.update(HKDF_INFO);
        mac.update((byte) 1);
        return Arrays.copyOf(mac.doFinal(), length);
    }

    /** AES-128-GCM with a 12-byte nonce: a counter (little-endian, eight bytes) per direction, then zeros. */
    private static final class Gcm {
        private final SecretKeySpec key;
        private long sent, received;

        Gcm(byte[] key) {
            this.key = new SecretKeySpec(key, "AES");
        }

        byte[] seal(byte[] plain) throws GeneralSecurityException {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, nonce(sent++)));
            return c.doFinal(plain);
        }

        byte[] open(byte[] sealed) throws GeneralSecurityException {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, nonce(received++)));
            return c.doFinal(sealed);
        }

        private static byte[] nonce(long n) {
            return ByteBuffer.allocate(12).order(ByteOrder.LITTLE_ENDIAN).putLong(n).array();
        }
    }

    private static void closeQuietly(Socket s) {
        try {
            s.close();
        } catch (IOException ignored) {
        }
    }
}
