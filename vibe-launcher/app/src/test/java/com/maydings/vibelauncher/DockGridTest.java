package com.maydings.vibelauncher;

import static org.junit.Assert.assertEquals;

import org.junit.Test;

/** Home's grid: a dock (centred when it's shorter than a row), then rows of columns. */
public class DockGridTest {

    // Six columns under a dock of four: an empty tile's width each side of the dock. Twenty tiles in all:
    // the dock is 0-3, the rows 4-9, 10-15 and 16-19.
    private static final int DOCK = 4, COLUMNS = 6, COUNT = 20;

    @Test
    public void upFromTheRowUnderAShorterDock() {
        assertEquals(0, DockGrid.above(4, DOCK, COLUMNS));   // the leftmost: no dock tile over it, so the first
        assertEquals(0, DockGrid.above(5, DOCK, COLUMNS));
        assertEquals(1, DockGrid.above(6, DOCK, COLUMNS));
        assertEquals(2, DockGrid.above(7, DOCK, COLUMNS));
        assertEquals(3, DockGrid.above(8, DOCK, COLUMNS));
        assertEquals(3, DockGrid.above(9, DOCK, COLUMNS));   // the rightmost: the last
    }

    @Test
    public void upFromLowerRowsIsARowUp() {
        assertEquals(4, DockGrid.above(10, DOCK, COLUMNS));
        assertEquals(15, DockGrid.above(21, DOCK, COLUMNS));
    }

    @Test
    public void nothingAboveTheDock() {
        for (int i = 0; i < DOCK; i++) assertEquals(-1, DockGrid.above(i, DOCK, COLUMNS));
        assertEquals(-1, DockGrid.above(2, 0, COLUMNS));   // an open folder's first row: it has no dock
    }

    @Test
    public void downFromTheDock() {
        assertEquals(5, DockGrid.below(0, DOCK, COLUMNS, COUNT));
        assertEquals(8, DockGrid.below(3, DOCK, COLUMNS, COUNT));
        assertEquals(1, DockGrid.below(1, 3, COLUMNS, 3));   // only a dock: nowhere to go
        assertEquals(4, DockGrid.below(3, DOCK, COLUMNS, 5));   // a row of one under it: that one
    }

    @Test
    public void downARowAndStoppingAtTheLast() {
        assertEquals(10, DockGrid.below(4, DOCK, COLUMNS, COUNT));
        assertEquals(19, DockGrid.below(15, DOCK, COLUMNS, COUNT));   // the row under is short: its last
        assertEquals(16, DockGrid.below(16, DOCK, COLUMNS, COUNT));   // the last row: stays
    }

    @Test
    public void downThenUpComesBack() {
        // Whatever the dock's length under whatever the row's: Down and Up again is where it started.
        for (int columns = 1; columns <= 8; columns++) {
            for (int dock = 1; dock <= columns; dock++) {
                for (int i = 0; i < dock; i++) {
                    int under = DockGrid.columnUnder(i, dock, columns);
                    assertEquals(dock + " over " + columns + ", tile " + i, i, DockGrid.dockOver(under, dock, columns));
                }
            }
        }
    }

    @Test
    public void anOddDifferenceTakesTheLeftColumn() {
        // Four over five: each dock tile is half over two columns.
        assertEquals(0, DockGrid.columnUnder(0, 4, 5));
        assertEquals(3, DockGrid.columnUnder(3, 4, 5));
        assertEquals(3, DockGrid.dockOver(4, 4, 5));
    }

    @Test
    public void theDockTileOverAnyTile() {
        assertEquals(2, DockGrid.dockTileFor(2, DOCK, COLUMNS));
        assertEquals(3, DockGrid.dockTileFor(9, DOCK, COLUMNS));
        assertEquals(0, DockGrid.dockTileFor(10, DOCK, COLUMNS));
        assertEquals(0, DockGrid.dockTileFor(7, 0, COLUMNS));
    }

    @Test
    public void rows() {
        assertEquals(0, DockGrid.rowStart(2, DOCK, COLUMNS));
        assertEquals(4, DockGrid.rowStart(7, DOCK, COLUMNS));
        assertEquals(10, DockGrid.rowStart(10, DOCK, COLUMNS));
        assertEquals(3, DockGrid.rowEnd(1, DOCK, COLUMNS, COUNT));
        assertEquals(9, DockGrid.rowEnd(7, DOCK, COLUMNS, COUNT));
        assertEquals(19, DockGrid.rowEnd(17, DOCK, COLUMNS, COUNT));   // a short last row
    }

    @Test
    public void theDockFillsItsRowWhateverItsLength() {
        for (int columns = 1; columns <= 8; columns++) {
            for (int dock = 1; dock <= columns; dock++) {
                int spans = 0;
                for (int i = 0; i < dock; i++) spans += DockGrid.spanSize(i, dock, columns);
                assertEquals(dock + " over " + columns, 2 * columns, spans);   // so the next row starts a new line
            }
        }
        assertEquals(2, DockGrid.spanSize(7, DOCK, COLUMNS));   // every other tile: its own width
    }

    @Test
    public void theSpaceBesideAShorterDock() {
        assertEquals(1f, DockGrid.inset(4, 6), 0f);
        assertEquals(0.5f, DockGrid.inset(4, 5), 0f);
        assertEquals(0f, DockGrid.inset(6, 6), 0f);
    }
}
