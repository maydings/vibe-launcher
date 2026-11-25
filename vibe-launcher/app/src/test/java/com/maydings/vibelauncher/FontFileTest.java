package com.maydings.vibelauncher;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;

import static org.junit.Assert.*;

/** Untrusted TTF/OTF cmap parsing safety & coverage checks in FontFile. */
public class FontFileTest {

    @Rule
    public TemporaryFolder tmp = new TemporaryFolder();

    private File writeBytes(String name, byte[] data) throws IOException {
        File f = tmp.newFile(name);
        try (FileOutputStream out = new FileOutputStream(f)) {
            out.write(data);
        }
        return f;
    }

    @Test
    public void rejectsCorruptMagicAndOversizedGroups() throws Exception {
        assertNull(FontFile.ranges(writeBytes("junk.ttf", "not a font at all".getBytes())));

        // Minimal sfnt with cmap format 12 claiming 900,000 groups inside a 64-byte file (must not allocate 7.2 MB)
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        d.writeInt(0x00010000);   // sfnt magic
        d.writeShort(1);          // numTables
        d.writeShort(0); d.writeShort(0); d.writeShort(0);
        // Table record: 'cmap' at offset 28
        d.writeInt(0x636D6170);
        d.writeInt(0);
        d.writeInt(28);
        d.writeInt(32);
        // cmap header at 28: version 0, count 1
        d.writeShort(0);
        d.writeShort(1);
        // subtable record: platform 3, encoding 10, offset 12 (absolute 40)
        d.writeShort(3);
        d.writeShort(10);
        d.writeInt(12);
        // format 12 at 40
        d.writeShort(12); d.writeShort(0);
        d.writeInt(16); d.writeInt(0);
        d.writeInt(900_000);      // bogus huge group count
        assertNull(FontFile.ranges(writeBytes("huge-groups.ttf", b.toByteArray())));
    }

    @Test
    public void skipsNonUnicodeSubtableWithOutOfBoundsOffset() throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        DataOutputStream d = new DataOutputStream(b);
        d.writeInt(0x00010000);
        d.writeShort(1);
        d.writeShort(0); d.writeShort(0); d.writeShort(0);
        // cmap at offset 28
        d.writeInt(0x636D6170); d.writeInt(0); d.writeInt(28); d.writeInt(64);
        // cmap header (28): 2 subtables (size 4 + 16 = 20 -> subtable starts at 48)
        d.writeShort(0);
        d.writeShort(2);
        // Subtable 0: Mac Roman (platform 1) with bogus out-of-bounds offset
        d.writeShort(1); d.writeShort(0); d.writeInt(999999);
        // Subtable 1: Unicode (platform 3, encoding 10), format 12 at cmap+20 (abs 48)
        d.writeShort(3); d.writeShort(10); d.writeInt(20);
        // Format 12 at 48: 1 group covering 'A'..'Z'
        d.writeShort(12); d.writeShort(0);
        d.writeInt(28); d.writeInt(0);
        d.writeInt(1);
        d.writeInt('A'); d.writeInt('Z'); d.writeInt(1);

        int[] ranges = FontFile.ranges(writeBytes("valid.ttf", b.toByteArray()));
        assertNotNull(ranges);
        assertTrue(FontFile.covers(ranges, "HELLO"));
        assertFalse(FontFile.covers(ranges, "hello"));
    }
}
