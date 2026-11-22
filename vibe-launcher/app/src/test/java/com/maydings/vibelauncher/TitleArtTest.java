package com.maydings.vibelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;

import org.junit.Test;

/** How titles are compared: what an app calls a film against what TMDB or Stremio calls it. */
public class TitleArtTest {

    @Test
    public void caseAndPunctuationDontCount() {
        assertEquals("the office u s", TitleArt.normalize("The Office (U.S.)"));
        assertEquals(TitleArt.normalize("the office u s"), TitleArt.normalize("The Office (U.S.)"));
        assertEquals("wall e", TitleArt.normalize("WALL·E"));
        assertEquals("spider man", TitleArt.normalize("  Spider-Man!  "));
    }

    @Test
    public void ampersandReadsAsAnd() {
        assertEquals(TitleArt.normalize("Fast and Furious"), TitleArt.normalize("Fast & Furious"));
        assertEquals(TitleArt.normalize("The Mandalorian and Grogu"), TitleArt.normalize("The Mandalorian & Grogu"));
        assertEquals("fast and furious", TitleArt.normalize("Fast&Furious"));
    }

    @Test
    public void lettersOfOtherAlphabetsAreKept() {
        assertEquals("amélie", TitleArt.normalize("Amélie"));
        assertEquals("千と千尋の神隠し", TitleArt.normalize("千と千尋の神隠し"));
        assertNotEquals(TitleArt.normalize("Amelie"), TitleArt.normalize("Amélie"));
    }

    @Test
    public void nothingForNothing() {
        assertEquals("", TitleArt.normalize(null));
        assertEquals("", TitleArt.normalize("  !!  "));
    }

    @Test
    public void aPrefixIsAnotherTitle() {
        assertNotEquals(TitleArt.normalize("The Mandalorian and Grogu"),
                TitleArt.normalize("Star Wars: The Mandalorian and Grogu"));
    }
}
