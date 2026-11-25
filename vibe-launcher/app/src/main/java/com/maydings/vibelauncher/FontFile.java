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
            long len = f.length();
            if (len < 12) return null;
            long base = 0;
            int magic = f.readInt();
            if (magic == 0x74746366) {   // 'ttcf': a collection, the first font in it
                if (len < 16) return null;
                f.seek(8);
                long numFonts = f.readInt() & 0xFFFFFFFFL;
                if (numFonts < 1) return null;
                base = f.readInt() & 0xFFFFFFFFL;
                if (base + 12 > len) return null;
                f.seek(base);
                magic = f.readInt();
            }
            if (magic != 0x00010000 && magic != 0x4F54544F && magic != 0x74727565) return null;
            int tables = f.readUnsignedShort();
            if (base + 12 + 16L * tables > len) return null;
            long cmap = -1;
            for (int i = 0; i < tables; i++) {
                f.seek(base + 12 + 16L * i);
                int tag = f.readInt();
                f.readInt();   // checksum
                long offset = f.readInt() & 0xFFFFFFFFL;
                if (tag == 0x636D6170) {   // 'cmap'
                    cmap = offset;
                    break;
                }
            }
            if (cmap < 0 || cmap + 4 > len) return null;
            f.seek(cmap + 2);
            int count = f.readUnsignedShort();
            if (cmap + 4 + 8L * count > len) return null;
            long full = -1, bmp = -1;
            for (int i = 0; i < count; i++) {
                f.seek(cmap + 4 + 8L * i);
                int platform = f.readUnsignedShort(), encoding = f.readUnsignedShort();
                long at = cmap + (f.readInt() & 0xFFFFFFFFL);
                boolean unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10));
                if (!unicode || at < 0 || at + 2 > len) continue;
                f.seek(at);
                int format = f.readUnsignedShort();
                if (format == 12 && full < 0) full = at;
                if (format == 4 && bmp < 0) bmp = at;
            }
            if (full >= 0) return format12(f, full, len);
            if (bmp >= 0) return format4(f, bmp, len);
            return null;
        } catch (IOException | RuntimeException | OutOfMemoryError e) {
            return null;
        }
    }

    private static int[] format12(RandomAccessFile f, long at, long len) throws IOException {
        if (at + 16 > len) return null;
        f.seek(at + 12);
        long groups = f.readInt() & 0xFFFFFFFFL;
        if (groups > 1_000_000 || at + 16 + groups * 12L > len) return null;
        int[] out = new int[(int) groups * 2];
        int prevEnd = -1;
        int n = 0;
        for (int i = 0; i < groups; i++) {
            long start = f.readInt() & 0xFFFFFFFFL;
            long end = f.readInt() & 0xFFFFFFFFL;
            f.readInt();   // first glyph
            if (start > end || end > Character.MAX_CODE_POINT) return null;
            if ((int) start < prevEnd) return null;   // must be sorted for binary search
            out[n++] = (int) start;
            out[n++] = (int) end;
            prevEnd = (int) end;
        }
        return out;
    }

    private static int[] format4(RandomAccessFile f, long at, long len) throws IOException {
        if (at + 14 > len) return null;
        f.seek(at + 6);
        int segCountX2 = f.readUnsignedShort();
        if ((segCountX2 & 1) != 0) return null;
        int segments = segCountX2 / 2;
        if (at + 16L + 4L * segments > len) return null;
        int[] ends = new int[segments];
        f.seek(at + 14);
        for (int i = 0; i < segments; i++) ends[i] = f.readUnsignedShort();
        f.readUnsignedShort();   // reserved
        List<Integer> out = new ArrayList<>();
        int prevEnd = -1;
        for (int i = 0; i < segments; i++) {
            int start = f.readUnsignedShort();
            if (start == 0xFFFF) continue;   // the closing segment
            if (start > ends[i] || start < prevEnd) return null;
            out.add(start);
            out.add(ends[i]);
            prevEnd = ends[i];
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
