package com.swiftality.screens;

/** The pointer, drawn into the picture in map colours: an arrow, or a hand over clickable things. */
public final class Cursor {
    // '#' outline, 'o' fill. Each cell is drawn 2x2 pixels.
    private static final String[] ARROW = {
            "#.........",
            "##........",
            "#o#.......",
            "#oo#......",
            "#ooo#.....",
            "#oooo#....",
            "#ooooo#...",
            "#oooooo#..",
            "#ooooooo#.",
            "#oooo####.",
            "#oo#o#....",
            "#o#.#o#...",
            "##..#o#...",
            "#....#o#..",
            ".....###..",
    };
    private static final String[] HAND = {
            "....##......",
            "...#oo#.....",
            "...#oo#.....",
            "...#oo###...",
            "...#oo#oo##.",
            ".###oo#oo#o#",
            "#oo#oooooooo#",
            "#ooooooooooo#",
            ".#oooooooooo#",
            "..#ooooooooo#",
            "..#oooooooo#.",
            "...#ooooooo#.",
            "...#ooooooo#.",
            "....#######..",
    };
    public static final int SCALE = 2;
    private static final byte OUTLINE = MapColors.of(0x000000), FILL = MapColors.of(0xFFFFFF);

    private Cursor() {}

    /** Draws the cursor with its hot spot at (x, y) into a picture of map colour ids. */
    public static void draw(byte[] px, int w, int h, int x, int y, boolean hand) {
        String[] art = hand ? HAND : ARROW;
        int ox = hand ? -5 * SCALE : 0, oy = 0;
        for (int r = 0; r < art.length; r++) {
            String row = art[r];
            for (int c = 0; c < row.length(); c++) {
                char ch = row.charAt(c);
                if (ch == '.') continue;
                byte v = ch == '#' ? OUTLINE : FILL;
                for (int dy = 0; dy < SCALE; dy++) {
                    for (int dx = 0; dx < SCALE; dx++) {
                        int xx = x + ox + c * SCALE + dx, yy = y + oy + r * SCALE + dy;
                        if (xx >= 0 && yy >= 0 && xx < w && yy < h) px[yy * w + xx] = v;
                    }
                }
            }
        }
    }
}
