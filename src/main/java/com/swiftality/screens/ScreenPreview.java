package com.swiftality.screens;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.IOException;

/** Renders a screen to PNG as players see it (map colours and pointer), with no server: for previews and tests. */
public final class ScreenPreview {
    private ScreenPreview() {}

    /** Draws [screen] with the pointer at (px, py) and writes it to [file]. */
    public static void save(Screen screen, File file, int px, int py) throws IOException {
        BufferedImage img = new BufferedImage(Screen.W, Screen.H, BufferedImage.TYPE_INT_RGB);
        screen.pointer(px, py);
        screen.render(img);
        if (screen.dirty()) screen.render(img);
        int[] rgb = img.getRGB(0, 0, Screen.W, Screen.H, null, 0, Screen.W);
        byte[] ids = new byte[rgb.length];
        MapColors.convert(rgb, ids);
        Cursor.draw(ids, Screen.W, Screen.H, px, py, screen.overClickable());
        for (int i = 0; i < ids.length; i++) rgb[i] = MapColors.rgb(ids[i]);
        img.setRGB(0, 0, Screen.W, Screen.H, rgb, 0, Screen.W);
        File dir = file.getAbsoluteFile().getParentFile();
        if (dir != null) dir.mkdirs();
        ImageIO.write(img, "png", file);
    }
}
