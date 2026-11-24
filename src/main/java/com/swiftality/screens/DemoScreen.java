package com.swiftality.screens;

import java.awt.Graphics2D;

/** /screens demo: a small placed screen to try aiming and clicking with. */
final class DemoScreen extends Screen {
    private static final int COUNT = 1, CLOSE = 2;
    private volatile int count;

    @Override
    protected void draw(Graphics2D g) {
        Gfx.panel(g, 340, 184, 600, 400);
        g.setFont(Gfx.BIG);
        Gfx.centered(g, "SwiftScreens", 640, 220, Gfx.TEXT);
        g.setFont(Gfx.NORMAL);
        Gfx.centered(g, "Aim with your crosshair. Left click adds, right click takes away.", 640, 280, Gfx.MUTED);
        Gfx.centered(g, "Count: " + count, 640, 330, Gfx.GOLD);
        Gfx.button(g, 490, 380, 300, 48, "Click me", hovered(COUNT), Gfx.Style.GO);
        hit(COUNT, 490, 380, 300, 48, () -> {
            count++;
            host.sound("minecraft:ui.button.click", 0.6f, 1.4f);
        }, () -> {
            count--;
            host.sound("minecraft:ui.button.click", 0.6f, 0.8f);
        });
        Gfx.button(g, 540, 480, 200, 40, "Close", hovered(CLOSE), Gfx.Style.NORMAL);
        hit(CLOSE, 540, 480, 200, 40, () -> host.close());
    }
}
