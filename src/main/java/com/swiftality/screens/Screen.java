package com.swiftality.screens;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;

/**
 * One page drawn with Java2D. Clickable parts are registered with {@link #hit} while drawing, so what can be
 * clicked is always exactly what was drawn. No Bukkit here: screens render the same in a preview.
 */
public abstract class Screen {
    /** Picture size: 10 x 6 maps. */
    public static final int COLS = 10, ROWS = 6, W = COLS * 128, H = ROWS * 128;

    private record Hit(int id, int x, int y, int w, int h, Runnable action) {
        boolean contains(int px, int py) {
            return px >= x && py >= y && px < x + w && py < y + h;
        }
    }

    /** The clickable regions of the last finished render; replaced whole, so other threads read it safely. */
    private volatile List<Hit> hits = List.of();
    /** Regions of the render in progress (only the rendering thread touches it). */
    private List<Hit> building = new ArrayList<>();
    private volatile int hover = -1;
    private volatile boolean dirty = true;
    /** Set by the view that shows this screen; screens call it to switch page or close. */
    protected ScreenHost host;

    public final void attach(ScreenHost host) {
        this.host = host;
    }

    public final void render(BufferedImage img) {
        // cleared first: a change made while this render runs marks it dirty again and gets its own render
        dirty = false;
        building = new ArrayList<>();
        Graphics2D g = img.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_OFF);
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
        g.setRenderingHint(RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_OFF);
        try {
            draw(g);
        } finally {
            g.dispose();
        }
        hits = building;
        // what is under the pointer may have moved; a change shows on the next render
        int id = under(px, py);
        if (id != hover) {
            hover = id;
            dirty = true;
        }
    }

    private volatile int px = -1, py = -1;

    private int under(int x, int y) {
        List<Hit> h = hits;
        for (int i = h.size() - 1; i >= 0; i--) {
            if (h.get(i).contains(x, y)) return h.get(i).id;
        }
        return -1;
    }

    protected abstract void draw(Graphics2D g);

    /** Registers a clickable region (id must be stable between renders, unique on the page). */
    protected final void hit(int id, int x, int y, int w, int h, Runnable action) {
        building.add(new Hit(id, x, y, w, h, action));
    }

    protected final boolean hovered(int id) {
        return hover == id;
    }

    /** Whether the pointer is over something clickable (for the hand cursor). */
    public final boolean overClickable() {
        return hover >= 0;
    }

    public final void markDirty() {
        dirty = true;
    }

    public final boolean dirty() {
        return dirty;
    }

    /** Moves the pointer; marks the page dirty when what it is over changes. */
    public final void pointer(int x, int y) {
        px = x;
        py = y;
        int id = under(x, y);
        if (id != hover) {
            hover = id;
            dirty = true;
        }
    }

    /** A click at (x, y): runs the topmost region there. Returns whether anything was hit. */
    public final boolean click(int x, int y) {
        List<Hit> all = hits;
        for (int i = all.size() - 1; i >= 0; i--) {
            Hit h = all.get(i);
            if (h.contains(x, y)) {
                h.action.run();
                dirty = true;
                return true;
            }
        }
        return false;
    }

    /** A key: "jump" (space). Default does nothing. */
    public void key(String key) {}

    /** Called when the player tries to leave (sneak). Return false to keep the screen open. */
    public boolean mayClose() {
        return true;
    }

    /** Called when [mayClose] said no: tell the player why (host.actionBar, host.sound). */
    public void refusedClose() {}

    /** Called once the screen has been closed, however it closed. */
    public void closed() {}
}
