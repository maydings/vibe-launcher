package com.maydings.vibelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;

import org.junit.Test;

import java.util.Map;

/** What the phone's page sends: how its size, its fields and a file's name are read. */
public class PhoneLinkTest {

    private static String head(String line) {
        return "POST /abc/wallpaper HTTP/1.1\r\nHost: tv\r\n" + line + "\r\n\r\n";
    }

    @Test
    public void theSizeSaid() {
        assertEquals(42, PhoneLink.contentLength(head("Content-Length: 42"), 100));
        assertEquals(42, PhoneLink.contentLength(head("content-length:42"), 100));   // however it's written
    }

    @Test
    public void noMoreThanIsTaken() {
        assertEquals(100, PhoneLink.contentLength(head("Content-Length: 1000"), 100));
    }

    @Test
    public void biggerThanTwoGigabytesIsStillASize() {
        // Read as nothing once, so a video too big was answered "nothing arrived" and not "too big".
        assertEquals(3_000_000_000L, PhoneLink.contentLength(head("Content-Length: 3000000000"), Long.MAX_VALUE));
    }

    @Test
    public void noSizeIsNone() {
        assertEquals(0, PhoneLink.contentLength(head("Accept: */*"), 100));
        assertEquals(0, PhoneLink.contentLength(head("Content-Length: lots"), 100));
        assertEquals(0, PhoneLink.contentLength(head("Content-Length: -5"), 100));
    }

    @Test
    public void aFormsFields() {
        Map<String, String> f = PhoneLink.parseForm("tmdbKey=abc123&name=hello+world&plexToken=%41%2Fb");
        assertEquals("abc123", f.get("tmdbKey"));
        assertEquals("hello world", f.get("name"));
        assertEquals("A/b", f.get("plexToken"));
        assertEquals(3, f.size());
    }

    @Test
    public void fieldsLeftEmptyArentSent() {
        // A key's field left empty keeps the key that's saved: it isn't among what was received.
        Map<String, String> f = PhoneLink.parseForm("tmdbKey=&youtubeKey=+++&plexToken=x");
        assertFalse(f.containsKey("tmdbKey"));
        assertFalse(f.containsKey("youtubeKey"));
        assertEquals(1, f.size());
    }

    @Test
    public void whatIsntAFieldIsLeftOut() {
        Map<String, String> f = PhoneLink.parseForm("=x&novalue&bad=%zz&ok=1");
        assertEquals(1, f.size());
        assertEquals("1", f.get("ok"));
    }

    @Test
    public void aFilesName() {
        assertEquals("My Clip", PhoneLink.nameIn("/abc/wallpaper?kind=video&name=My%20Clip&x=1"));
        assertEquals("beach.jpg", PhoneLink.nameIn("/abc/wallpaper?name=beach.jpg"));
        assertEquals("", PhoneLink.nameIn("/abc/wallpaper?kind=video"));
        assertEquals("", PhoneLink.nameIn("/abc/wallpaper?name="));
        assertEquals("", PhoneLink.nameIn("/abc/wallpaper?name=%zz"));   // not a name that can be read
    }
}
