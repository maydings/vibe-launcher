package com.maydings.vibelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.math.BigInteger;
import java.util.Locale;

/** The small sums and wordings used here and there: versions, percentages, figures, hostnames, links. */
public class HelpersTest {

    private Locale before;

    @Before
    public void inEnglish() {
        before = Locale.getDefault();
        Locale.setDefault(Locale.US);   // figures are written the language's way: one way, for the tests
    }

    @After
    public void asItWas() {
        Locale.setDefault(before);
    }

    // ---- which version is newer (Updater) ------------------------------------------------------------

    @Test
    public void versionsAreComparedNumberByNumber() {
        assertTrue(Updater.newer("1.10.0", "1.9.2"));   // not letter by letter: 10 is more than 9
        assertTrue(Updater.newer("4.4.1", "4.4.0"));
        assertTrue(Updater.newer("5.0.0", "4.99.99"));
        assertFalse(Updater.newer("4.4.0", "4.4.0"));
        assertFalse(Updater.newer("4.3.9", "4.4.0"));
    }

    @Test
    public void aMissingNumberIsZero() {
        assertFalse(Updater.newer("4.4", "4.4.0"));
        assertFalse(Updater.newer("4.4.0", "4.4"));
        assertTrue(Updater.newer("4.4.1", "4.4"));
    }

    @Test
    public void whatFollowsANumberIsLeftOut() {
        assertTrue(Updater.newer("4.4.1-beta", "4.4.0"));
        assertFalse(Updater.newer("4.4.0-beta", "4.4.0"));
    }

    // ---- percentages (Percent) -----------------------------------------------------------------------

    @Test
    public void percentages() {
        assertEquals(0, Percent.clamp(-5));
        assertEquals(100, Percent.clamp(150));
        assertEquals(65, Percent.clamp(65));
        assertEquals(0, Percent.alpha(0));
        assertEquals(255, Percent.alpha(100));
        assertEquals(128, Percent.alpha(50));
        assertEquals(255, Percent.alpha(400));
        assertEquals("65%", Percent.format(65));
    }

    // ---- the flight card's wording (FlightBoard) -----------------------------------------------------

    @Test
    public void joiningWhatThereIs() {
        assertEquals("A320 · TC-JRE", FlightBoard.join(" · ", "A320", "TC-JRE"));
        assertEquals("A320", FlightBoard.join(" · ", "A320", null));
        assertEquals("TC-JRE", FlightBoard.join(" · ", null, "TC-JRE"));
        assertNull(FlightBoard.join(" · ", null, null));
    }

    @Test
    public void aFlightNumberAsItsAirlineWritesIt() {
        assertEquals("5X 2182", FlightBoard.spaced("5X2182"));
        assertEquals("TK 1", FlightBoard.spaced("TK1"));
        assertEquals("TK", FlightBoard.spaced("TK"));
    }

    @Test
    public void figuresToTheNearestStep() {
        assertEquals("9,600", FlightBoard.whole(9620, 50));
        assertEquals("11,000", FlightBoard.whole(10975, 50));
        assertEquals("36,000", FlightBoard.whole(36049, 100));
        assertEquals("540", FlightBoard.whole(540.4, 1));
        assertEquals("0", FlightBoard.whole(12, 50));
    }

    // ---- the meters' figures (TopBarMenus) -----------------------------------------------------------

    @Test
    public void gigabytes() {
        assertEquals("8.5", TopBarMenus.gb(8.46f));
        assertEquals("0.5", TopBarMenus.gb(0.5f));
        assertEquals("10", TopBarMenus.gb(9.96f));   // not "10.0": whole from ten up
        assertEquals("11", TopBarMenus.gb(11.4f));
        assertEquals("128", TopBarMenus.gb(128.2f));
    }

    // ---- a key as it's shown (SettingsScreen) --------------------------------------------------------

    @Test
    public void aKeyShowsItsLastFour() {
        assertEquals("••••••••1234", SettingsScreen.mask("abcdef1234"));
        assertEquals("••••••••ab", SettingsScreen.mask("ab"));
        assertEquals("", SettingsScreen.mask(""));
    }

    // ---- a hostname as the phone sent it (AdBlock) ---------------------------------------------------

    @Test
    public void aHostnameMadePlain() {
        assertEquals("abc123.dns.nextdns.io", AdBlock.clean("https://abc123.dns.nextdns.io/"));
        assertEquals("dns.example.com", AdBlock.clean("  TLS://Dns.Example.com:853  "));
        assertEquals("example.com", AdBlock.clean("example.com."));
        assertEquals("example.com", AdBlock.clean("example.com/dns-query?x=1#top"));
    }

    @Test
    public void whatIsntAHostnameIsNone() {
        assertEquals("", AdBlock.clean("localhost"));
        assertEquals("", AdBlock.clean("not a host"));
        assertEquals("", AdBlock.clean("-bad-.example.com"));
        assertEquals("", AdBlock.clean(""));
    }

    // ---- turning an app off and on (TvApps) ----------------------------------------------------------

    @Test
    public void theCommandForAnApp() {
        assertEquals("pm disable-user --user 0 com.foo.bar", TvApps.command("com.foo.bar", false));
        assertEquals("pm enable com.foo.bar", TvApps.command("com.foo.bar", true));
    }

    @Test
    public void nothingButAPackageNameGoesInACommand() {
        assertThrows(IllegalArgumentException.class, () -> TvApps.command("a; rm -rf /", true));
        assertThrows(IllegalArgumentException.class, () -> TvApps.command("com.foo bar", false));
        assertThrows(IllegalArgumentException.class, () -> TvApps.command("", true));
    }

    // ---- the clean-up lists' wording (CleanLists) ----------------------------------------------------

    @Test
    public void aReasonCutAtAComma() {
        assertEquals("short", CleanLists.trim("short", 10));
        assertEquals("alpha, beta…", CleanLists.trim("alpha, beta, gamma delta", 14));
        assertEquals("abcde…", CleanLists.trim("abcdefghij", 5));
    }

    @Test
    public void namesAsTheListsHaveThem() {
        assertEquals("sony-bravia", CleanLists.slug("Sony  Bravia"));
        assertEquals("tcl", CleanLists.slug(" TCL "));
        assertEquals("", CleanLists.slug(null));
        assertEquals(3, CleanLists.mentions("sony bravia xr", "Sony", 3));
        assertEquals(0, CleanLists.mentions("sony bravia xr", "TCL", 3));
        assertEquals(0, CleanLists.mentions("ab cd", "ab", 3));           // too short a name to go by
        assertEquals(0, CleanLists.mentions("unknown tv", "unknown", 3));
        assertEquals(0, CleanLists.mentions("sony", null, 3));
    }

    // ---- what's read from a card (Backdrops, HdHomeRun, AppAdapter) ----------------------------------

    @Test
    public void aTitlesImdbId() {
        assertEquals("tt0903747", Backdrops.imdbId("wn_tt0903747_s1e2", null));
        assertEquals("tt1234567", Backdrops.imdbId(null, "https://example.com/title/tt1234567/"));
        assertEquals("tt0903747", Backdrops.imdbId("wn_tt0903747", "https://example.com/tt1234567"));   // the card's own first
        assertNull(Backdrops.imdbId("att12345", null));   // inside a word: not an id
        assertNull(Backdrops.imdbId("tt123", null));      // too short
        assertNull(Backdrops.imdbId(null, null));
    }

    @Test
    public void aYouTubePicturesBiggestSize() {
        assertEquals("https://i.ytimg.com/vi/abc123/maxresdefault.jpg",
                Backdrops.youtubeMaxRes("https://i.ytimg.com/vi/abc123/hqdefault.jpg"));
        assertNull(Backdrops.youtubeMaxRes("https://example.com/vi/abc123/hqdefault.jpg"));
        assertNull(Backdrops.youtubeMaxRes("https://i.ytimg.com/vi/abc123"));
    }

    @Test
    public void cardsWithAStickerOnThem() {
        assertTrue(Backdrops.badged("intent:#Intent;S.x=badges=B:recently.added;end"));
        assertTrue(Backdrops.badged("https://example.com/?q=badges%3DB"));
        assertFalse(Backdrops.badged("https://example.com/title/1"));
        assertFalse(Backdrops.badged(null));
    }

    @Test
    public void aTitleKeptOnOneLine() {
        assertEquals("a b c d", Backdrops.oneLine("a\tb\nc\rd"));
        assertEquals("", Backdrops.oneLine(null));
    }

    @Test
    public void channelsAndBanners() {
        assertFalse(HdHomeRun.isChannel(null));
        assertFalse(HdHomeRun.isChannel("https://example.com"));
        assertTrue(AppAdapter.isBannerShaped(320, 180));
        assertFalse(AppAdapter.isBannerShaped(100, 100));
        assertTrue(AppAdapter.isBannerShaped(0, 0));   // no size of its own: it fills the tile
    }

    // ---- which letters a font has (FontFile) ---------------------------------------------------------

    @Test
    public void whetherAFontHasALanguagesLetters() {
        int[] latin = {'0', '9', 'A', 'Z', 'a', 'z'};
        assertTrue(FontFile.covers(latin, "Hello 123!"));   // spaces and punctuation aside
        assertTrue(FontFile.covers(latin, ""));
        assertFalse(FontFile.covers(latin, "Değiştir"));     // no ğ or ş
        assertFalse(FontFile.covers(latin, "é"));      // nor the accent that's put on a letter
        assertFalse(FontFile.covers(null, "a"));
        assertTrue(FontFile.covers(new int[]{'a', 'z', 0x11F, 0x11F, 0x15F, 0x15F}, "değiştir".replace("i", "")));
    }

    // ---- the weather's wording (Weather) -------------------------------------------------------------

    @Test
    public void temperatures() {
        assertEquals("22°", Weather.degrees(21.6));
        assertEquals("0°", Weather.degrees(-0.4));
        assertEquals("-4°", Weather.degrees(-3.6));
    }

    @Test
    public void theSkyByItsCode() {
        assertEquals(R.string.weather_clear, Weather.text(0));
        assertEquals(R.string.weather_fog, Weather.text(48));
        assertEquals(R.string.weather_rain, Weather.text(63));
        assertEquals(R.string.weather_snow, Weather.text(86));
        assertEquals(R.string.weather_thunder, Weather.text(99));
        assertEquals(R.string.weather_cloudy, Weather.text(4));   // a code that isn't one: clouds
        assertEquals(R.drawable.ic_wx_sun, Weather.icon(0, true));
        assertEquals(R.drawable.ic_wx_moon, Weather.icon(1, false));
        assertEquals(R.drawable.ic_wx_cloud, Weather.icon(2, false));   // partly cloudy has no night picture
        assertEquals(R.drawable.ic_wx_snow, Weather.icon(73, true));
        assertEquals(R.drawable.ic_wx_rain, Weather.icon(81, true));
    }

    // ---- numbers as the pairing's sums have them (Spake2) --------------------------------------------

    @Test
    public void littleEndianNumbers() {
        assertEquals(BigInteger.valueOf(258), Spake2.littleEndian(new byte[]{2, 1, 0, 0}));
        org.junit.Assert.assertArrayEquals(new byte[]{2, 1, 0, 0}, Spake2.toLittleEndian(BigInteger.valueOf(258), 4));
        org.junit.Assert.assertArrayEquals(new byte[]{10, -1, 0}, Spake2.hex("0aff00"));
        BigInteger big = new BigInteger("123456789abcdef0123456789abcdef", 16);
        assertEquals(big, Spake2.littleEndian(Spake2.toLittleEndian(big, 32)));   // there and back
    }
}
