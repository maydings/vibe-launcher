package com.maydings.vibelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

/** A backdrop order: a letter for each thing, capital when it's enabled ("COSfK", the System backdrop's "gf"). */
public class WallpapersOrderTest {

    @Test
    public void whereAThingIs() {
        assertEquals(0, Wallpapers.at("COSfK", 'c'));
        assertEquals(3, Wallpapers.at("COSfK", 'f'));
        assertEquals(3, Wallpapers.at("COSfK", 'F'));
        assertEquals(-1, Wallpapers.at("gf", 'c'));
    }

    @Test
    public void enabledIsACapital() {
        assertTrue(Wallpapers.on("COSfK", 'c'));
        assertTrue(Wallpapers.on("COSfK", 'k'));
        assertFalse(Wallpapers.on("COSfK", 'f'));
        assertFalse(Wallpapers.on("gf", 'g'));
        assertFalse(Wallpapers.on("gf", 'c'));   // not in it at all
    }

    @Test
    public void enablingAndDisabling() {
        assertEquals("COSFK", Wallpapers.enabled("COSfK", 'f', true));
        assertEquals("cOSfK", Wallpapers.enabled("COSfK", 'c', false));
        assertEquals("Gf", Wallpapers.enabled("gf", 'g', true));
        assertEquals("gf", Wallpapers.enabled("gf", 'x', true));   // not one of its things: as it was
    }

    @Test
    public void theColourStaysEnabled() {
        assertEquals("COSfK", Wallpapers.enabled("COSfK", 'k', false));
        assertEquals("COSfK", Wallpapers.enabled("COSfk", 'k', false));
    }

    @Test
    public void movingUpAndDown() {
        assertEquals("OCSfK", Wallpapers.moved("COSfK", 0, 1));
        assertEquals("COfSK", Wallpapers.moved("COSfK", 3, -1));
        assertEquals("KCOSf".length(), Wallpapers.moved("COSfK", 4, -1).length());
        assertEquals("COSKf", Wallpapers.moved("COSfK", 4, -1));
    }

    @Test
    public void notMovedPastEitherEnd() {
        assertEquals("COSfK", Wallpapers.moved("COSfK", 0, -1));
        assertEquals("COSfK", Wallpapers.moved("COSfK", 4, 1));
        assertEquals("COSfK", Wallpapers.moved("COSfK", 9, 1));
    }

    @Test
    public void aKeptOrderIsMadeWhole() {
        assertEquals("fCOSK", Wallpapers.whole("fC", "COSfK"));     // what's missing follows, as it is by default
        assertEquals("cOSfK", Wallpapers.whole("c", "COSfK"));      // its own choice of on or off is kept
        assertEquals("COSfK", Wallpapers.whole("xxCC", "COSfK"));   // what isn't one of them goes, and doubles
        assertEquals("gf", Wallpapers.whole(null, "gf"));
        assertEquals("fg", Wallpapers.whole("f", "gf"));
    }

    @Test
    public void theSystemBackdropFirst() {
        String order = Wallpapers.systemFirst();
        assertEquals(0, Wallpapers.at(order, 's'));
        assertTrue(Wallpapers.on(order, 's'));
        assertFalse(Wallpapers.on(order, 'f'));
        assertEquals(5, order.length());
    }
}
