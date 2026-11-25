package com.maydings.vibelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertThrows;

import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Reading one file out of the tar archive Apple's list of aerials comes in. */
public class AppleAerialsTest {

    /** A tar archive of these files (name, text, name, text...), ended as tar ends them. */
    private static byte[] tar(String... namesAndTexts) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < namesAndTexts.length; i += 2) {
            byte[] data = namesAndTexts[i + 1].getBytes(StandardCharsets.UTF_8);
            byte[] header = new byte[512];
            byte[] name = namesAndTexts[i].getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(name, 0, header, 0, name.length);
            byte[] size = String.format("%011o", data.length).getBytes(StandardCharsets.US_ASCII);
            System.arraycopy(size, 0, header, 124, size.length);
            out.write(header);
            out.write(data);
            out.write(new byte[(512 - data.length % 512) % 512]);
        }
        out.write(new byte[1024]);
        return out.toByteArray();
    }

    @Test
    public void aFileByItsName() throws IOException {
        byte[] tar = tar("readme.txt", "hello", "entries.json", "{\"assets\":[]}");
        assertEquals("{\"assets\":[]}", AppleAerials.fromTar(tar, "entries.json"));
        assertEquals("hello", AppleAerials.fromTar(tar, "readme.txt"));
    }

    @Test
    public void aFileInAFolder() throws IOException {
        assertEquals("{}", AppleAerials.fromTar(tar("resources/entries.json", "{}"), "entries.json"));
    }

    @Test
    public void aNameThatOnlyEndsTheSameIsAnotherFile() throws IOException {
        byte[] tar = tar("old-entries.json", "no", "entries.json", "yes");
        assertEquals("yes", AppleAerials.fromTar(tar, "entries.json"));
    }

    @Test
    public void lettersBeyondEnglishComeThroughWhole() throws IOException {
        assertEquals("İstanbul • 東京", AppleAerials.fromTar(tar("a.json", "İstanbul • 東京"), "a.json"));
    }

    @Test
    public void aFileLongerThanABlock() throws IOException {
        char[] many = new char[1300];
        Arrays.fill(many, 'x');
        String text = new String(many);
        assertEquals(text, AppleAerials.fromTar(tar("first", text, "second", "after"), "first"));
        assertEquals("after", AppleAerials.fromTar(tar("first", text, "second", "after"), "second"));
    }

    @Test
    public void notThere() throws IOException {
        byte[] tar = tar("readme.txt", "hello");
        assertThrows(IOException.class, () -> AppleAerials.fromTar(tar, "entries.json"));
        assertThrows(IOException.class, () -> AppleAerials.fromTar(new byte[0], "entries.json"));
    }

    @Test
    public void cutShort() throws IOException {
        byte[] whole = tar("entries.json", "0123456789");
        byte[] cut = Arrays.copyOf(whole, 512 + 4);   // the download stopped inside the file
        assertThrows(IOException.class, () -> AppleAerials.fromTar(cut, "entries.json"));
    }

    @Test
    public void notATarArchive() {
        byte[] page = new byte[1024];
        Arrays.fill(page, (byte) 'a');   // a web page where the archive should be
        assertThrows(IOException.class, () -> AppleAerials.fromTar(page, "entries.json"));
    }

    @Test
    public void negativeSizeDoesNotLoopOrCrash() {
        byte[] bad = new byte[1024];
        byte[] name = "other.txt".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(name, 0, bad, 0, name.length);
        byte[] neg = "-0000001000".getBytes(StandardCharsets.US_ASCII);
        System.arraycopy(neg, 0, bad, 124, neg.length);
        assertThrows(IOException.class, () -> AppleAerials.fromTar(bad, "entries.json"));
    }
}
