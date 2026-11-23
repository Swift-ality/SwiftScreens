package com.swiftality.screens;

import java.awt.Color;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

/** Drawing helpers in the vanilla menu style: the Minecraft font, panels, slots, buttons. */
public final class Gfx {
    private static final Font BASE;

    static {
        Font f;
        try (InputStream in = Gfx.class.getResourceAsStream("/fonts/MacsMinecraft-Regular.ttf")) {
            f = Font.createFont(Font.TRUETYPE_FONT, in);
        } catch (Exception e) {
            f = new Font(Font.MONOSPACED, Font.PLAIN, 16);
        }
        BASE = f;
    }

    public static final Font NORMAL = BASE.deriveFont(16f), BIG = BASE.deriveFont(32f), SMALL = BASE.deriveFont(8f);

    public static final Color TEXT = new Color(0xE8E8E8), MUTED = new Color(0xA8A8B0), DESC = new Color(0xD0D0D8),
            GOOD = new Color(0x6EE36E), BAD = new Color(0xFF6B6B), MIXED = new Color(0xFFCF4A), GOLD = new Color(0xFFD54A),
            NONE = new Color(0x9A9A9A), RANDOM = new Color(0xC88CFF), SHADOW = new Color(0x2A2A2A);

    /** The page colour behind every screen. */
    public static final int BACKGROUND = 0x0B0B0E;
    /** screen.background in the config: false leaves the page see-through, so the world shows round the panels. */
    public static volatile boolean solidBackground = true;

    private Gfx() {}

    /** Paints the page background (or leaves it see-through, per the server's setting). Draw it first. */
    public static void background(Graphics2D g) {
        if (solidBackground) fill(g, BACKGROUND, 0, 0, Screen.W, Screen.H);
    }

    public static void fill(Graphics2D g, int rgb, int x, int y, int w, int h) {
        g.setColor(new Color(rgb));
        g.fillRect(x, y, w, h);
    }

    /** Dark panel: black outline, light top-left bevel, dark bottom-right bevel. */
    public static void panel(Graphics2D g, int x, int y, int w, int h) {
        fill(g, 0x000000, x, y, w, h);
        fill(g, 0x141418, x + 2, y + 2, w - 4, h - 4);
        fill(g, 0x8D8D99, x + 2, y + 2, w - 4, 2);
        fill(g, 0x8D8D99, x + 2, y + 2, 2, h - 4);
        fill(g, 0x2A2A31, x + 2, y + h - 4, w - 4, 2);
        fill(g, 0x2A2A31, x + w - 4, y + 2, 2, h - 4);
    }

    /** A recessed tile (cards, slots). */
    public static void tile(Graphics2D g, int x, int y, int w, int h, int bg, int border, int hi) {
        fill(g, border, x, y, w, h);
        fill(g, bg, x + 2, y + 2, w - 4, h - 4);
        fill(g, hi, x + 2, y + 2, w - 4, 2);
        fill(g, hi, x + 2, y + 2, 2, h - 4);
        fill(g, 0x16161A, x + 2, y + h - 4, w - 4, 2);
        fill(g, 0x16161A, x + w - 4, y + 2, 2, h - 4);
    }

    public enum Style { NORMAL, GO, DISABLED }

    public static void button(Graphics2D g, int x, int y, int w, int h, String label, boolean hover, Style style) {
        int bg, hi, lo;
        Color fg = Color.WHITE;
        switch (style) {
            case GO -> { bg = hover ? 0x4B9F45 : 0x3F8A3A; hi = 0x7FD277; lo = 0x2A5F26; }
            case DISABLED -> { bg = 0x2C2C2C; hi = 0x2C2C2C; lo = 0x2C2C2C; fg = new Color(0x8A8A8A); hover = false; }
            default -> { bg = hover ? 0x7A7A7A : 0x6F6F6F; hi = 0xAAAAAA; lo = 0x565656; }
        }
        if (hover) fg = new Color(0xFFFFA0);
        fill(g, hover ? 0xFFFFFF : 0x000000, x, y, w, h);
        fill(g, bg, x + 2, y + 2, w - 4, h - 4);
        if (style != Style.DISABLED) {
            fill(g, hi, x + 2, y + 2, w - 4, 2);
            fill(g, hi, x + 2, y + 2, 2, h - 4);
            fill(g, lo, x + 2, y + h - 6, w - 4, 4);
            fill(g, lo, x + w - 4, y + 2, 2, h - 4);
        }
        g.setFont(NORMAL);
        int tw = g.getFontMetrics().stringWidth(label);
        text(g, label, x + (w - tw) / 2, y + (h - 16) / 2 - 1, fg, style != Style.DISABLED);
    }

    /** Text with its top-left at (x, y) and the game's drop shadow. */
    public static void text(Graphics2D g, String s, int x, int y, Color c, boolean shadow) {
        FontMetrics fm = g.getFontMetrics();
        int base = y + fm.getAscent();
        int off = Math.max(1, g.getFont().getSize() / 8);
        if (shadow) {
            g.setColor(new Color(c.getRed() / 4, c.getGreen() / 4, c.getBlue() / 4));
            g.drawString(s, x + off, base + off);
        }
        g.setColor(c);
        g.drawString(s, x, base);
    }

    public static int width(Graphics2D g, String s) {
        return g.getFontMetrics().stringWidth(s);
    }

    public static void centered(Graphics2D g, String s, int cx, int y, Color c) {
        text(g, s, cx - width(g, s) / 2, y, c, true);
    }

    /** Word-wraps [s] to [w] pixels in the current font. [firstIndent] narrows the first line. */
    public static List<String> wrap(Graphics2D g, String s, int w, int firstIndent) {
        List<String> lines = new ArrayList<>();
        StringBuilder line = new StringBuilder();
        int limit = w - firstIndent;
        for (String word : s.split(" ")) {
            String next = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && width(g, next) > limit) {
                lines.add(line.toString());
                line = new StringBuilder(word);
                limit = w;
            } else {
                line = new StringBuilder(next);
            }
        }
        if (!line.isEmpty()) lines.add(line.toString());
        return lines;
    }

    /** Draws wrapped text and returns the height used. */
    public static int paragraph(Graphics2D g, String s, int x, int y, int w, Color c, int lineHeight) {
        int yy = y;
        for (String l : wrap(g, s, w, 0)) {
            text(g, l, x, yy, c, true);
            yy += lineHeight;
        }
        return yy - y;
    }

    /** Draws a pixel-art image scaled by whole steps, nearest neighbour. */
    public static void image(Graphics2D g, BufferedImage img, int x, int y, int size) {
        if (img == null) return;
        g.drawImage(img, x, y, size, size, null);
    }

    /** A small padlock, 8x9 pixels at [scale]. */
    public static void lock(Graphics2D g, int x, int y, int s) {
        fill(g, 0xCFCFCF, x + 2 * s, y, 4 * s, s);
        fill(g, 0xCFCFCF, x + s, y + s, s, 3 * s);
        fill(g, 0xCFCFCF, x + 6 * s, y + s, s, 3 * s);
        fill(g, 0xE2B33C, x, y + 4 * s, 8 * s, 5 * s);
        fill(g, 0x5A3E08, x + 3 * s, y + 5 * s, 2 * s, 2 * s);
    }
}
