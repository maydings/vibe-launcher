package com.maydings.vibelauncher;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Arrays;

/** Reading a line of Virtual Radar Server's lists: comma-separated, quoted where a field has a comma. */
public class VrsDatabaseTest {

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File list(String text) throws Exception {
        File f = folder.newFile();
        Files.write(f.toPath(), text.getBytes(StandardCharsets.UTF_8));
        return f;
    }

    @Test
    public void theLineForAKey() throws Exception {
        File f = list("Code,Name,ICAO,IATA\nUAL,United Airlines,UAL,UA\nAAL,American Airlines,AAL,AA\n");
        assertEquals(Arrays.asList("AAL", "American Airlines", "AAL", "AA"), VrsDatabase.line(f, "AAL"));
        assertNull(VrsDatabase.line(f, "ZZZ"));
    }

    @Test
    public void aKeyIsTheWholeFirstField() throws Exception {
        File f = list("UAL,United Airlines,UAL,UA\n");
        assertNull(VrsDatabase.line(f, "UA"));   // "UAL," doesn't start with "UA,"
    }

    @Test
    public void quotedFields() throws Exception {
        File f = list("A,\"Smith, John\",x\nB,\"He said \"\"hi\"\"\",y\nC,,z\n");
        assertEquals(Arrays.asList("A", "Smith, John", "x"), VrsDatabase.line(f, "A"));
        assertEquals(Arrays.asList("B", "He said \"hi\"", "y"), VrsDatabase.line(f, "B"));
        assertEquals(Arrays.asList("C", "", "z"), VrsDatabase.line(f, "C"));   // an empty field is a field
    }

    @Test
    public void theMarkAtTheStartOfAFileIsntPartOfIt() throws Exception {
        File f = list("﻿UAL,United Airlines\n");
        assertEquals(Arrays.asList("UAL", "United Airlines"), VrsDatabase.line(f, "UAL"));
    }

    @Test
    public void anEmptyListHasNothing() throws Exception {
        assertNull(VrsDatabase.line(list(""), "UAL"));
    }

    @Test
    public void numbers() {
        assertEquals(40.6413, VrsDatabase.number("40.6413"), 0);
        assertEquals(-73.7781, VrsDatabase.number("-73.7781"), 0);
        assertTrue(Double.isNaN(VrsDatabase.number("")));
        assertTrue(Double.isNaN(VrsDatabase.number("north")));
    }
}
