package com.maydings.vibelauncher;

/**
 * Where tiles are in Home's grid: the dock (dock tiles, centred when that's fewer than a row), then rows of
 * columns. Positions are the adapter's: the dock's first, then row by row; count is how many tiles there are.
 * Only sums, apart from any screen, so they can be tried by themselves (DockGridTest).
 */
final class DockGrid {

    private DockGrid() {
    }

    /** The first position of position's row. */
    static int rowStart(int position, int dock, int columns) {
        return position < dock ? 0 : dock + (position - dock) / columns * columns;
    }

    /** The last position of position's row. */
    static int rowEnd(int position, int dock, int columns, int count) {
        return position < dock ? dock - 1 : Math.min(rowStart(position, dock, columns) + columns - 1, count - 1);
    }

    /** The empty space before the dock's first tile, and after its last, in tiles (halves when odd). */
    static float inset(int dock, int columns) {
        return (columns - dock) / 2f;
    }

    /**
     * The tile above: from the row under the dock, the dock tile nearest above it (the dock is centred, and
     * may be shorter); -1 from the dock.
     */
    static int above(int position, int dock, int columns) {
        if (position < dock) return -1;
        int col = position - dock;
        if (col >= columns) return position - columns;
        if (dock == 0) return -1;   // an open folder's first row: nothing above
        return dockOver(col, dock, columns);
    }

    /** The tile below, the nearest under a dock tile; the same position on the last row (nowhere to go). */
    static int below(int position, int dock, int columns, int count) {
        if (position < dock) {
            if (count <= dock) return position;
            return Math.min(count - 1, dock + columnUnder(position, dock, columns));
        }
        if (rowStart(position, dock, columns) + columns >= count) return position;   // the last row
        return Math.min(position + columns, count - 1);
    }

    /** The dock tile a position belongs under: itself in the dock, else the nearest above its column. */
    static int dockTileFor(int position, int dock, int columns) {
        if (dock == 0) return 0;
        if (position < dock) return position;
        return dockOver((position - dock) % columns, dock, columns);
    }

    /*
     * A shorter dock is centred, so with an odd difference (4 apps over 5 columns) each dock tile sits half
     * over two columns. Down takes the left one, and Up from a column takes the dock tile over its middle,
     * which is the one Down came from: rounding both ways (half up) moved one column right on every Down
     * and Up, all the way to the end of the row.
     */

    /** The column under dock tile i: the left one of the two it half covers. */
    static int columnUnder(int i, int dock, int columns) {
        return Math.max(0, Math.min(columns - 1, (int) Math.floor(i + inset(dock, columns))));
    }

    /** The dock tile over the middle of column col. */
    static int dockOver(int col, int dock, int columns) {
        return Math.max(0, Math.min(dock - 1, (int) Math.floor(col + 0.5f - inset(dock, columns))));
    }

    /**
     * Spans for the grid, in half tiles (its span count is twice the columns): a tile takes two, and the
     * dock's first and last tiles take the empty space beside them too, which DockDecoration insets, so a
     * shorter dock is centred and the next row starts on a line of its own.
     */
    static int spanSize(int position, int dock, int columns) {
        int extra = columns - dock;   // the empty half tiles on each side
        if (position >= dock) return 2;
        int span = 2;
        if (position == 0) span += extra;
        if (position == dock - 1) span += extra;
        return span;
    }
}
