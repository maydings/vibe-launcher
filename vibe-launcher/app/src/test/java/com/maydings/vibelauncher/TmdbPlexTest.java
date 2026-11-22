package com.maydings.vibelauncher;

import static org.junit.Assert.assertEquals;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** What's picked out of TMDB's and Plex's answers. */
public class TmdbPlexTest {

    @Test
    public void whatTmdbHasntGotIsNothing() throws Exception {
        JSONObject o = new JSONObject("{\"overview\":null,\"release_date\":\"2017-10-04\",\"backdrop_path\":null}");
        assertEquals("", Tmdb.text(o, "overview"));       // not the word "null"
        assertEquals("", Tmdb.text(o, "backdrop_path"));
        assertEquals("2017-10-04", Tmdb.text(o, "release_date"));
        assertEquals("", Tmdb.text(o, "tagline"));
    }

    private static JSONArray logos(String... languageAndPath) throws Exception {
        JSONArray a = new JSONArray();
        for (int i = 0; i < languageAndPath.length; i += 2) {
            Object language = languageAndPath[i] == null ? JSONObject.NULL : languageAndPath[i];
            a.put(new JSONObject().put("iso_639_1", language).put("file_path", languageAndPath[i + 1]));
        }
        return a;
    }

    @Test
    public void aLogoInTheTvsLanguageFirst() throws Exception {
        JSONArray all = logos("en", "/en.png", "tr", "/tr.png", null, "/plain.png");
        assertEquals("/tr.png", Tmdb.logo(all, "tr"));
        assertEquals("/en.png", Tmdb.logo(all, "en"));
    }

    @Test
    public void thenEnglishThenOneOfNoLanguage() throws Exception {
        assertEquals("/en.png", Tmdb.logo(logos("tr", "/tr.png", "en", "/en.png"), "de"));
        assertEquals("/plain.png", Tmdb.logo(logos("tr", "/tr.png", null, "/plain.png"), "de"));
    }

    @Test
    public void theFirstListedOfALanguage() throws Exception {
        // TMDB lists them best-rated first.
        assertEquals("/best.png", Tmdb.logo(logos("en", "/best.png", "en", "/second.png"), "en"));
    }

    @Test
    public void onlyPngLogos() throws Exception {
        assertEquals("/tr.png", Tmdb.logo(logos("tr", "/tr.svg", "tr", "/tr.png"), "tr"));
        assertEquals("", Tmdb.logo(logos("tr", "/tr.svg"), "tr"));
    }

    @Test
    public void noLogos() throws Exception {
        assertEquals("", Tmdb.logo(null, "en"));
        assertEquals("", Tmdb.logo(new JSONArray(), "en"));
        assertEquals("", Tmdb.logo(logos("fr", "/fr.png"), "de"));   // another language's alone isn't used
    }

    private static JSONArray guids(String... ids) throws Exception {
        JSONArray a = new JSONArray();
        for (String id : ids) a.put(new JSONObject().put("id", id));
        return a;
    }

    @Test
    public void aTitlesTmdbIdFromPlex() throws Exception {
        assertEquals(1228710, Plex.tmdbId(guids("imdb://tt1234567", "tmdb://1228710", "tvdb://999")));
        assertEquals(603, Plex.tmdbId(guids("tmdb://603")));
    }

    @Test
    public void noIdWhereTheServerGivesNone() throws Exception {
        assertEquals(0, Plex.tmdbId(null));
        assertEquals(0, Plex.tmdbId(new JSONArray()));
        assertEquals(0, Plex.tmdbId(guids("imdb://tt1234567")));
        assertEquals(0, Plex.tmdbId(guids("tmdb://abc")));
        assertEquals(0, Plex.tmdbId(new JSONArray().put("tmdb://603")));   // not as Plex lists them
    }
}
