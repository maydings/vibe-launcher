package com.maydings.vibelauncher;

import android.annotation.SuppressLint;
import android.content.Context;
import android.content.SharedPreferences;
import android.security.keystore.KeyGenParameterSpec;
import android.security.keystore.KeyProperties;
import android.util.Base64;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.KeyStore;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPublicKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAKeyGenParameterSpec;

import javax.crypto.Cipher;
import javax.crypto.KeyGenerator;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509ExtendedTrustManager;

/**
 * Vibe Launcher's key for Wireless debugging (Android 11 and up): what AdbPairing hands the TV with the pairing code,
 * and what Adb's TLS connection then shows it, as a certificate, instead of signing a token.
 * <p>
 * A second key, not the one port 5555 uses (Adb's, in the Keystore): TLS 1.3 signs with RSA-PSS, through the TLS
 * library's own RSA, which a Keystore key can't be used by everywhere. So this one is an ordinary RSA key, kept in
 * Vibe Launcher's own storage encrypted with an AES key that lives in the Keystore and never leaves it. If that
 * can't be read back (a restore onto another TV), a new key is made, and the TV has to be paired again.
 * <p>
 * The certificate is self-signed and minimal: adbd only looks at the public key in it, against the keys it was
 * given by pairing.
 */
final class AdbTlsKey {

    private static final String PREFS = "adb_tls";
    private static final String WRAP_ALIAS = "adb-tls-wrap";
    private static final String NAME = "Vibe Launcher";

    private static AdbTlsKey cached;

    final PrivateKey privateKey;
    final X509Certificate certificate;

    private AdbTlsKey(PrivateKey privateKey, X509Certificate certificate) {
        this.privateKey = privateKey;
        this.certificate = certificate;
    }

    /** The key, made the first time. Worker thread (making an RSA key takes a second or two on a TV). */
    static synchronized AdbTlsKey get(Context context) throws GeneralSecurityException {
        if (cached != null) return cached;
        SharedPreferences prefs = prefs(context);
        String key = prefs.getString("key", null), cert = prefs.getString("cert", null);
        if (key != null && cert != null) {
            try {
                byte[] pkcs8 = unwrap(Base64.decode(key, Base64.NO_WRAP));
                PrivateKey priv = KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8));
                X509Certificate c = (X509Certificate) CertificateFactory.getInstance("X.509")
                        .generateCertificate(new ByteArrayInputStream(Base64.decode(cert, Base64.NO_WRAP)));
                return cached = new AdbTlsKey(priv, c);
            } catch (GeneralSecurityException | IllegalArgumentException | IOException e) {
                // the Keystore lost its AES key (a restore, a reset Keystore): a new key, paired anew
            }
        }
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(new RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4), new SecureRandom());
        KeyPair pair = g.generateKeyPair();
        X509Certificate c = selfSigned(pair);
        byte[] wrapped;
        try {
            wrapped = wrap(pair.getPrivate().getEncoded());
        } catch (IOException e) {
            throw new GeneralSecurityException(e);
        }
        prefs.edit()
                .putString("key", Base64.encodeToString(wrapped, Base64.NO_WRAP))
                .putString("cert", Base64.encodeToString(c.getEncoded(), Base64.NO_WRAP))
                .putBoolean("paired", false)
                .apply();
        return cached = new AdbTlsKey(pair.getPrivate(), c);
    }

    static SharedPreferences prefs(Context context) {
        return context.getApplicationContext().getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    /** Whether a TV has taken this key by pairing (it may have forgotten it since: Revoke on the TV). */
    static boolean paired(Context context) {
        return prefs(context).getBoolean("paired", false);
    }

    static void setPaired(Context context, boolean paired) {
        prefs(context).edit().putBoolean("paired", paired).apply();
    }

    /** The public key as adbd keeps it, then " Vibe Launcher" (what the TV lists it as) and a 0 byte. */
    byte[] adbPublicKey() {
        return adbPublicKey((RSAPublicKey) certificate.getPublicKey());
    }

    /**
     * Android's RSAPublicKey struct (little-endian, base64): the modulus in 32-bit words, -1/n[0] mod 2^32 and
     * R^2 mod n for its Montgomery maths, and the exponent; then a name.
     */
    static byte[] adbPublicKey(RSAPublicKey pub) {
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
        String text = Base64.encodeToString(b.array(), Base64.NO_WRAP) + " " + NAME + "\0";
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private static void putWords(ByteBuffer b, BigInteger v, int words) {
        BigInteger mask = BigInteger.ONE.shiftLeft(32).subtract(BigInteger.ONE);
        for (int i = 0; i < words; i++) b.putInt(v.shiftRight(32 * i).and(mask).intValue());
    }

    // ---- TLS ---------------------------------------------------------------------------------------

    /**
     * TLS 1.3 showing this key's certificate, and taking whatever certificate the TV shows: adbd's is made up on
     * the spot, and who's at the other end is settled otherwise (the pairing code; 127.0.0.1 after it).
     */
    SSLContext tls() throws GeneralSecurityException {
        SSLContext c = SSLContext.getInstance("TLSv1.3");
        c.init(new KeyManager[]{new OurKey()}, new TrustManager[]{new AnyServer()}, new SecureRandom());
        return c;
    }

    private final class OurKey extends X509ExtendedKeyManager {
        private static final String ALIAS = "adb";

        @Override
        public String[] getClientAliases(String keyType, Principal[] issuers) {
            return new String[]{ALIAS};
        }

        // adbd lists made-up issuers (its keys' fingerprints): ours is among them once paired, whatever they say.
        @Override
        public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
            return ALIAS;
        }

        @Override
        public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
            return ALIAS;
        }

        @Override
        public String[] getServerAliases(String keyType, Principal[] issuers) {
            return null;
        }

        @Override
        public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
            return null;
        }

        @Override
        public X509Certificate[] getCertificateChain(String alias) {
            return new X509Certificate[]{certificate};
        }

        @Override
        public PrivateKey getPrivateKey(String alias) {
            return privateKey;
        }
    }

    @SuppressLint({"CustomX509TrustManager", "TrustAllX509TrustManager"})
    private static final class AnyServer extends X509ExtendedTrustManager {
        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) {
        }

        @Override
        public void checkClientTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public void checkServerTrusted(X509Certificate[] chain, String authType) {
        }

        @Override
        public X509Certificate[] getAcceptedIssuers() {
            return new X509Certificate[0];
        }
    }

    // ---- the private key, kept encrypted -----------------------------------------------------------

    private static SecretKey wrapKey() throws GeneralSecurityException, IOException {
        KeyStore ks = KeyStore.getInstance("AndroidKeyStore");
        ks.load(null);
        if (!ks.containsAlias(WRAP_ALIAS)) {
            KeyGenerator g = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore");
            g.init(new KeyGenParameterSpec.Builder(WRAP_ALIAS, KeyProperties.PURPOSE_ENCRYPT | KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setKeySize(256)
                    .build());
            return g.generateKey();
        }
        return (SecretKey) ks.getKey(WRAP_ALIAS, null);
    }

    /** The IV (12 bytes) then the encrypted bytes. */
    private static byte[] wrap(byte[] plain) throws GeneralSecurityException, IOException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.ENCRYPT_MODE, wrapKey());
        byte[] iv = c.getIV(), body = c.doFinal(plain);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(iv);
        out.write(body);
        return out.toByteArray();
    }

    private static byte[] unwrap(byte[] wrapped) throws GeneralSecurityException, IOException {
        Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
        c.init(Cipher.DECRYPT_MODE, wrapKey(), new GCMParameterSpec(128, wrapped, 0, 12));
        return c.doFinal(wrapped, 12, wrapped.length - 12);
    }

    // ---- a minimal self-signed certificate ---------------------------------------------------------

    /** X.509 v3, CN=Vibe Launcher, 2020 to 2049, signed SHA256withRSA by its own key. */
    static X509Certificate selfSigned(KeyPair pair) throws GeneralSecurityException {
        byte[] sha256WithRsa = seq(oid(new byte[]{0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B}),
                new byte[]{0x05, 0x00});
        byte[] name = seq(tlv(0x31, seq(oid(new byte[]{0x55, 0x04, 0x03}), tlv(0x0C, NAME.getBytes(StandardCharsets.UTF_8)))));
        byte[] validity = seq(tlv(0x17, "200101000000Z".getBytes(StandardCharsets.US_ASCII)),
                tlv(0x17, "491231235959Z".getBytes(StandardCharsets.US_ASCII)));
        byte[] serial = new byte[8];
        new SecureRandom().nextBytes(serial);
        serial[0] &= 0x7F;   // positive
        serial[0] |= 0x01;   // and no leading zero byte
        byte[] tbs = seq(tlv(0xA0, tlv(0x02, new byte[]{2})), tlv(0x02, serial), sha256WithRsa, name, validity, name,
                pair.getPublic().getEncoded());
        Signature s = Signature.getInstance("SHA256withRSA");
        s.initSign(pair.getPrivate());
        s.update(tbs);
        byte[] sig = s.sign();
        byte[] bits = new byte[sig.length + 1];   // a BIT STRING: no unused bits, then the signature
        System.arraycopy(sig, 0, bits, 1, sig.length);
        byte[] der = seq(tbs, sha256WithRsa, tlv(0x03, bits));
        return (X509Certificate) CertificateFactory.getInstance("X.509").generateCertificate(new ByteArrayInputStream(der));
    }

    private static byte[] oid(byte[] body) {
        return tlv(0x06, body);
    }

    private static byte[] seq(byte[]... parts) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        for (byte[] p : parts) b.write(p, 0, p.length);
        return tlv(0x30, b.toByteArray());
    }

    /** A DER tag, length and value. */
    private static byte[] tlv(int tag, byte[] value) {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        b.write(tag);
        int n = value.length;
        if (n < 0x80) {
            b.write(n);
        } else if (n < 0x100) {
            b.write(0x81);
            b.write(n);
        } else {
            b.write(0x82);
            b.write(n >> 8);
            b.write(n);
        }
        b.write(value, 0, n);
        return b.toByteArray();
    }
}
