package com.maydings.vibelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.json.JSONObject;
import org.junit.Test;

import java.util.List;

/** The flights' sums and reading: distances, the area asked of OpenSky, and what's taken from its answer. */
public class FlightsTest {

    private static final double JFK_LAT = 40.6413, JFK_LON = -73.7781, LHR_LAT = 51.4700, LHR_LON = -0.4543;

    @Test
    public void theAirlineACallsignStartsWith() {
        assertEquals("UAL", Flights.airlineOf("UAL510"));
        assertEquals("THY", Flights.airlineOf("THY1AB"));
        assertNull(Flights.airlineOf("N123AB"));   // a registration
        assertNull(Flights.airlineOf("UAL"));      // no number after it
        assertNull(Flights.airlineOf("UALX1"));    // four letters: not an airline's three and a number
        assertNull(Flights.airlineOf("ual510"));
        assertNull(Flights.airlineOf(""));
    }

    @Test
    public void nearbyDistances() {
        assertEquals(110.57, Flights.km(0, 0, 1, 0), 1e-9);    // a degree north
        assertEquals(111.32, Flights.km(0, 0, 0, 1), 1e-9);    // a degree east, at the equator
        assertEquals(111.32 * Math.cos(Math.toRadians(60)), Flights.km(60, 0, 60, 1), 1e-9);   // half that at 60 north
        assertEquals(0, Flights.km(41, 29, 41, 29), 0);
    }

    @Test
    public void bearings() {
        assertEquals(0, Flights.bearing(0, 0, 1, 0), 1e-9);
        assertEquals(90, Flights.bearing(0, 0, 0, 1), 1e-9);
        assertEquals(180, Flights.bearing(0, 0, -1, 0), 1e-9);
        assertEquals(270, Flights.bearing(0, 0, 0, -1), 1e-9);
    }

    @Test
    public void distancesAcrossOceans() {
        assertEquals(Math.PI * 6371, Flights.far(0, 0, 0, 180), 1e-6);   // halfway round
        assertEquals(5540, Flights.far(JFK_LAT, JFK_LON, LHR_LAT, LHR_LON), 25);   // New York to London
        assertEquals(Flights.far(JFK_LAT, JFK_LON, LHR_LAT, LHR_LON), Flights.far(LHR_LAT, LHR_LON, JFK_LAT, JFK_LON), 1e-9);
    }

    @Test
    public void whetherAnAircraftCanBeOnARoute() throws Exception {
        JSONObject route = new JSONObject().put("ola", JFK_LAT).put("olo", JFK_LON).put("dla", LHR_LAT).put("dlo", LHR_LON);
        assertTrue(Flights.between(route, 50, -35));          // mid-Atlantic
        assertTrue(Flights.between(route, JFK_LAT, JFK_LON));  // just taken off
        assertFalse(Flights.between(route, 0, 0));             // off West Africa: nowhere near it
        assertFalse(Flights.between(route, 35.68, 139.69));    // Tokyo
        assertTrue(Flights.between(new JSONObject().put("oc", "JFK"), 0, 0));   // airports not placed: taken as said
    }

    @Test
    public void theAreaAsked() {
        Weather.Place equator = new Weather.Place("x", 0, 0);
        String url = Flights.box(equator, 110.57);   // a degree each way
        assertTrue(url, url.contains("lamin=-1.0000&lomin=-0.9933&lamax=1.0000&lomax=0.9933"));
        assertTrue(url, url.endsWith("&extended=1"));
    }

    @Test
    public void theAreaIsNarrowedToOneCredit() {
        // 500 km each way would be 81 square degrees: narrowed east to west to OpenSky's 24.
        String url = Flights.box(new Weather.Place("x", 0, 0), 500);
        assertTrue(url, url.contains("lamin=-4.5220"));
        assertTrue(url, url.contains("lomax=1.3268"));
    }

    @Test
    public void theAreaStopsAtThePole() {
        String url = Flights.box(new Weather.Place("x", 89.9, 0), 100);
        assertTrue(url, url.contains("lamax=90.0000"));
    }

    /** One aircraft as OpenSky lists it, by what differs from one flying past: the rest as it would be. */
    private static String state(String hex, String callsign, Object timeAt, Object lon, Object lat, Object baro,
                                boolean onGround, Object speed, Object geo) {
        String name = callsign == null ? "null" : '"' + callsign + '"';
        return "[\"" + hex + "\"," + name + ",\"US\"," + timeAt + ",995," + lon + "," + lat + "," + baro + ","
                + onGround + "," + speed + ",90,0,null," + geo + ",\"1234\",false,0,3]";
    }

    @Test
    public void whatIsTakenFromAnAnswer() throws Exception {
        String body = "{\"time\":1000,\"states\":["
                + state("ABC123", "UAL510  ", 990, -73.5, 40.5, 10000, false, 200, 10100) + ","
                + state("ground", "X1", 990, -73.5, 40.5, 0, true, 5, 0) + ","           // says it's on the ground
                + state("old", "X2", 900, -73.5, 40.5, 10000, false, 200, 10100) + ","   // not heard for 100 s
                + state("nowhere", "X3", 990, -73.5, "null", 10000, false, 200, 10100) + ","   // doesn't say where
                + state("landed", "X4", 990, -73.5, 40.5, -10, false, 10, "null") + ","  // no height, walking pace
                + state("low", "X5", 990, -73.5, 40.5, -20, false, 100, "null") + ","    // no height, flying: low
                + state("silent", null, 990, -73.5, 40.5, 10000, false, 200, "null") + ","   // no callsign
                + "[\"short\",\"X6\"]]}";
        List<Flights.Heard> heard = Flights.parse(body);
        assertEquals(3, heard.size());

        Flights.Heard first = heard.get(0);
        assertEquals("abc123", first.hex);
        assertEquals("UAL510", first.callsign);
        assertEquals(40.5, first.lat, 0);
        assertEquals(-73.5, first.lon, 0);
        assertEquals(10100, first.altitude, 0);   // by satellite, when it says
        assertEquals(200, first.speed, 0);
        assertEquals(90, first.track, 0);
        assertEquals(3, first.category);

        assertEquals("low", heard.get(1).hex);
        assertEquals(0, heard.get(1).altitude, 0);   // never under the ground

        assertEquals("silent", heard.get(2).hex);
        assertEquals("", heard.get(2).callsign);
        assertEquals(10000, heard.get(2).altitude, 0);   // by air pressure, without the other
    }

    @Test
    public void anAnswerWithNoAircraft() throws Exception {
        assertTrue(Flights.parse("{\"time\":1000,\"states\":null}").isEmpty());
        assertTrue(Flights.parse("{\"time\":1000}").isEmpty());
    }

    @Test
    public void whatAServerDidntSayIsNothing() throws Exception {
        JSONObject o = new JSONObject("{\"a\":null,\"b\":\"5X\"}");
        assertEquals("", Flights.said(o, "a"));    // not the word "null"
        assertEquals("5X", Flights.said(o, "b"));
        assertEquals("", Flights.said(o, "c"));
    }
}
