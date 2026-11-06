package com.maydings.vibelauncher;

import java.math.BigInteger;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Arrays;

/**
 * SPAKE2 over Edwards25519, exactly as BoringSSL's SPAKE2_generate_msg and SPAKE2_process_msg do it
 * (crypto/curve25519/spake25519.cc, Apache License 2.0, Copyright 2016 The BoringSSL Authors): what ADB's
 * Wireless debugging uses to check the pairing code (AdbPairing), with the names "adb pair client" (us, Alice)
 * and "adb pair server" (the TV, Bob).
 * <p>
 * Ported to plain BigInteger maths rather than BoringSSL's field code: a pairing does three scalar
 * multiplications, a fraction of a second even on a TV, and BigInteger keeps the port short enough to check line
 * by line against the original. Not constant-time; it doesn't need to be here (one exchange, on the TV itself,
 * with a code that is good for one try).
 * <p>
 * The points, the order and the steps are BoringSSL's, including its "password scalar hack" (multiples of the
 * order added so the scalar is a multiple of eight), which changes the mask point when M and N have a
 * small-order part, so it has to be the same on both ends.
 */
final class Spake2 {

    static final int ALICE = 0, BOB = 1;

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    /** The order of the prime-order subgroup, l = 2^252 + 27742317777372353535851937790883648493. */
    static final BigInteger L = BigInteger.ONE.shiftLeft(252)
            .add(new BigInteger("27742317777372353535851937790883648493"));
    private static final BigInteger D = BigInteger.valueOf(-121665)
            .multiply(BigInteger.valueOf(121666).modInverse(P)).mod(P);
    private static final BigInteger D2 = D.shiftLeft(1).mod(P);
    /** sqrt(-1) mod p. */
    private static final BigInteger SQRT_M1 = BigInteger.valueOf(2)
            .modPow(P.subtract(BigInteger.ONE).shiftRight(2), P);

    static final Point BASE = Point.affine(
            new BigInteger("15112221349535400772501151409588531511454012693041857206046113283949847762202"),
            new BigInteger("46316835694926478169428394003475163141307993866256225615783033603165251855960"));
    /** BoringSSL's N and M, found by hashing "edwards25519 point generation seed (N)" and "(M)". */
    static final Point N = decode(hex("10e3df0ae37d8e7a99b5fe74b44672103dbddcbd06af680d71329a11693bc778"));
    static final Point M = decode(hex("5ada7e4bf6ddd9adb6626d32131c6b5c51a1e347a3478f53cfcf441b88eed12e"));

    private final int role;
    private final byte[] myName, theirName;
    private BigInteger privateKey, passwordScalar;
    private byte[] passwordHash, myMsg;

    Spake2(int role, byte[] myName, byte[] theirName) {
        this.role = role;
        this.myName = myName.clone();
        this.theirName = theirName.clone();
    }

    /** Our 32-byte message, from the password; random as the private key is. */
    byte[] generateMessage(byte[] password) {
        byte[] random = new byte[64];
        new SecureRandom().nextBytes(random);
        return generateMessage(password, random);
    }

    /** The same with the private key's 64 random bytes given (tests). */
    byte[] generateMessage(byte[] password, byte[] random) {
        // x25519_sc_reduce, then left_shift_3: a multiple of eight, which clears the peer's small-order part later.
        privateKey = littleEndian(random).mod(L).shiftLeft(3);
        Point p = BASE.multiply(privateKey);
        passwordHash = sha512(password);
        BigInteger s = littleEndian(passwordHash).mod(L);
        // The hack: l, 2l and 4l added in turn while the bit they'd clear is set.
        if (s.testBit(0)) s = s.add(L);
        if (s.testBit(1)) s = s.add(L.shiftLeft(1));
        if (s.testBit(2)) s = s.add(L.shiftLeft(2));
        passwordScalar = s;
        Point mask = (role == ALICE ? M : N).multiply(passwordScalar);
        myMsg = p.add(mask).encode();
        return myMsg.clone();
    }

    /** The 64-byte shared key from the peer's message; null when it isn't a point. */
    byte[] processMessage(byte[] theirMsg) {
        if (myMsg == null || theirMsg.length != 32) return null;
        Point qStar = decode(theirMsg);
        if (qStar == null) return null;
        Point peersMask = (role == ALICE ? N : M).multiply(passwordScalar);
        Point q = qStar.add(peersMask.negate());
        byte[] shared = q.multiply(privateKey).encode();
        MessageDigest sha = sha512();
        if (role == ALICE) {
            update(sha, myName);
            update(sha, theirName);
            update(sha, myMsg);
            update(sha, theirMsg);
        } else {
            update(sha, theirName);
            update(sha, myName);
            update(sha, theirMsg);
            update(sha, myMsg);
        }
        update(sha, shared);
        update(sha, passwordHash);
        return sha.digest();
    }

    /** update_with_length_prefix: the length as eight bytes, little-endian, then the bytes. */
    private static void update(MessageDigest sha, byte[] data) {
        long n = data.length;
        for (int i = 0; i < 8; i++) {
            sha.update((byte) n);
            n >>>= 8;
        }
        sha.update(data);
    }

    // ---- Edwards25519 ------------------------------------------------------------------------------

    /** A point in extended coordinates (X:Y:Z:T), x = X/Z, y = Y/Z, xy = T/Z. */
    static final class Point {
        final BigInteger x, y, z, t;

        Point(BigInteger x, BigInteger y, BigInteger z, BigInteger t) {
            this.x = x;
            this.y = y;
            this.z = z;
            this.t = t;
        }

        static Point affine(BigInteger x, BigInteger y) {
            return new Point(x, y, BigInteger.ONE, x.multiply(y).mod(P));
        }

        static final Point ZERO = new Point(BigInteger.ZERO, BigInteger.ONE, BigInteger.ONE, BigInteger.ZERO);

        /** add-2008-hwcd-3: complete on this curve (a = -1, d not a square), doubling included. */
        Point add(Point q) {
            BigInteger a = y.subtract(x).multiply(q.y.subtract(q.x)).mod(P);
            BigInteger b = y.add(x).multiply(q.y.add(q.x)).mod(P);
            BigInteger c = t.multiply(D2).multiply(q.t).mod(P);
            BigInteger d = z.shiftLeft(1).multiply(q.z).mod(P);
            BigInteger e = b.subtract(a), f = d.subtract(c), g = d.add(c), h = b.add(a);
            return new Point(e.multiply(f).mod(P), g.multiply(h).mod(P), f.multiply(g).mod(P), e.multiply(h).mod(P));
        }

        Point negate() {
            return new Point(P.subtract(x).mod(P), y, z, P.subtract(t).mod(P));
        }

        /** k times this point, for any k >= 0 (the small-order part kept, as BoringSSL's tables keep it). */
        Point multiply(BigInteger k) {
            Point r = ZERO;
            for (int i = k.bitLength() - 1; i >= 0; i--) {
                r = r.add(r);
                if (k.testBit(i)) r = r.add(this);
            }
            return r;
        }

        BigInteger affineX() {
            return x.multiply(z.modInverse(P)).mod(P);
        }

        BigInteger affineY() {
            return y.multiply(z.modInverse(P)).mod(P);
        }

        /** y, little-endian, with x's lowest bit as the top bit. */
        byte[] encode() {
            BigInteger zi = z.modInverse(P);
            BigInteger ax = x.multiply(zi).mod(P), ay = y.multiply(zi).mod(P);
            byte[] out = toLittleEndian(ay, 32);
            if (ax.testBit(0)) out[31] |= (byte) 0x80;
            return out;
        }

        boolean sameAs(Point q) {
            return x.multiply(q.z).subtract(q.x.multiply(z)).mod(P).signum() == 0
                    && y.multiply(q.z).subtract(q.y.multiply(z)).mod(P).signum() == 0;
        }
    }

    /** x25519_ge_frombytes_vartime: null when there's no such point. */
    static Point decode(byte[] s) {
        if (s.length != 32) return null;
        byte[] b = s.clone();
        boolean sign = (b[31] & 0x80) != 0;
        b[31] &= 0x7F;
        BigInteger y = littleEndian(b).mod(P);
        BigInteger y2 = y.multiply(y).mod(P);
        BigInteger u = y2.subtract(BigInteger.ONE).mod(P);
        BigInteger v = D.multiply(y2).add(BigInteger.ONE).mod(P);
        // x = u v^3 (u v^7)^((p-5)/8)
        BigInteger v3 = v.pow(3).mod(P);
        BigInteger x = u.multiply(v3).multiply(u.multiply(v3).multiply(v3).multiply(v).mod(P)
                .modPow(P.subtract(BigInteger.valueOf(5)).shiftRight(3), P)).mod(P);
        BigInteger vx2 = v.multiply(x).multiply(x).mod(P);
        if (!vx2.equals(u)) {
            if (!vx2.equals(P.subtract(u).mod(P))) return null;
            x = x.multiply(SQRT_M1).mod(P);
        }
        if (x.testBit(0) != sign) x = P.subtract(x).mod(P);
        return Point.affine(x, y);
    }

    // ---- bytes -------------------------------------------------------------------------------------

    static BigInteger littleEndian(byte[] b) {
        byte[] be = new byte[b.length];
        for (int i = 0; i < b.length; i++) be[i] = b[b.length - 1 - i];
        return new BigInteger(1, be);
    }

    static byte[] toLittleEndian(BigInteger v, int length) {
        byte[] be = v.toByteArray();
        byte[] out = new byte[length];
        for (int i = 0; i < length && i < be.length; i++) out[i] = be[be.length - 1 - i];
        return out;
    }

    static byte[] hex(String s) {
        byte[] out = new byte[s.length() / 2];
        for (int i = 0; i < out.length; i++) out[i] = (byte) Integer.parseInt(s.substring(2 * i, 2 * i + 2), 16);
        return out;
    }

    private static MessageDigest sha512() {
        try {
            return MessageDigest.getInstance("SHA-512");
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha512(byte[] data) {
        return sha512().digest(data);
    }

    /** Forgets the secrets. */
    void destroy() {
        privateKey = passwordScalar = null;
        if (passwordHash != null) Arrays.fill(passwordHash, (byte) 0);
    }
}
