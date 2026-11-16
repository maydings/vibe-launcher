package com.maydings.vibelauncher;

import java.io.File;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.util.ArrayList;
import java.util.List;

/**
 * Which characters a TrueType or OpenType font file has, read from its own character map (cmap). Android 9's
 * Paint.hasGlyph also counts the system's fallback fonts, so it can't tell whether a chosen font covers a language;
 * the file itself can. Formats 4 (Unicode BMP) and 12 (all of Unicode), and the first font of a collection.
 */
final class FontFile {

    private FontFile() {
    }

    /** The characters the font maps, as sorted inclusive [start, end] pairs; null when it isn't a font. */
    static int[] ranges(File file) {
        try (RandomAccessFile f = new RandomAccessFile(file, "r")) {
            long base = 0;
            if (f.readInt() == 0x74746366) {   // 'ttcf': a collection, the first font in it
                f.seek(12);
                base = f.readInt() & 0xFFFFFFFFL;
                f.seek(base);
                f.readInt();
            }
            int tables = f.readUnsignedShort();
            long cmap = -1;
            for (int i = 0; i < tables; i++) {
                f.seek(base + 12 + 16L * i);
                int tag = f.readInt();
                f.readInt();   // checksum
                long offset = f.readInt() & 0xFFFFFFFFL;
                if (tag == 0x636D6170) cmap = offset;   // 'cmap'
            }
            if (cmap < 0) return null;
            f.seek(cmap + 2);
            int count = f.readUnsignedShort();
            long full = -1, bmp = -1;
            for (int i = 0; i < count; i++) {
                f.seek(cmap + 4 + 8L * i);
                int platform = f.readUnsignedShort(), encoding = f.readUnsignedShort();
                long at = cmap + (f.readInt() & 0xFFFFFFFFL);
                f.seek(at);
                int format = f.readUnsignedShort();
                boolean unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10));
                if (!unicode) continue;
                if (format == 12 && full < 0) full = at;
                if (format == 4 && bmp < 0) bmp = at;
            }
            if (full >= 0) return format12(f, full);
            if (bmp >= 0) return format4(f, bmp);
            return null;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static int[] format12(RandomAccessFile f, long at) throws IOException {
        f.seek(at + 12);
        long groups = f.readInt() & 0xFFFFFFFFL;
        if (groups > 1_000_000) return null;
        int[] out = new int[(int) groups * 2];
        for (int i = 0; i < groups; i++) {
            out[2 * i] = f.readInt();
            out[2 * i + 1] = f.readInt();
            f.readInt();   // first glyph
        }
        return out;
    }

    private static int[] format4(RandomAccessFile f, long at) throws IOException {
        f.seek(at + 6);
        int segments = f.readUnsignedShort() / 2;
        int[] ends = new int[segments];
        f.seek(at + 14);
        for (int i = 0; i < segments; i++) ends[i] = f.readUnsignedShort();
        f.readUnsignedShort();   // reserved
        List<Integer> out = new ArrayList<>();
        for (int i = 0; i < segments; i++) {
            int start = f.readUnsignedShort();
            if (start == 0xFFFF) continue;   // the closing segment
            out.add(start);
            out.add(ends[i]);
        }
        int[] r = new int[out.size()];
        for (int i = 0; i < r.length; i++) r[i] = out.get(i);
        return r;
    }

    /** Whether every letter, digit and mark in the text is in these ranges (spaces and punctuation aside). */
    static boolean covers(int[] ranges, String text) {
        if (ranges == null) return false;
        for (int i = 0; i < text.length(); ) {
            int c = text.codePointAt(i);
            i += Character.charCount(c);
            int type = Character.getType(c);
            boolean needed = Character.isLetterOrDigit(c) || type == Character.NON_SPACING_MARK
                    || type == Character.COMBINING_SPACING_MARK;
            if (needed && !has(ranges, c)) return false;
        }
        return true;
    }

    private static boolean has(int[] ranges, int c) {
        int lo = 0, hi = ranges.length / 2 - 1;
        while (lo <= hi) {
            int mid = (lo + hi) >>> 1;
            if (c < ranges[2 * mid]) hi = mid - 1;
            else if (c > ranges[2 * mid + 1]) lo = mid + 1;
            else return true;
        }
        return false;
    }
}
