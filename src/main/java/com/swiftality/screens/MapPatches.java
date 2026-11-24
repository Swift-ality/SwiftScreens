package com.swiftality.screens;

import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;

import java.util.Optional;

/** Sends a picture's changed parts to one player, map by map. */
final class MapPatches {
    private MapPatches() {}

    /**
     * Sends each map's changed rectangle of [frame] (all of it when [all]) as map ids [mapBase] + r * COLS + c, and
     * copies what was sent into [sent].
     */
    static void send(ServerPlayer sp, byte[] frame, byte[] sent, int mapBase, boolean all) {
        for (int r = 0; r < Screen.ROWS; r++) {
            for (int c = 0; c < Screen.COLS; c++) {
                int x0 = c * 128, y0 = r * 128;
                int minX = 128, minY = 128, maxX = -1, maxY = -1;
                for (int y = 0; y < 128; y++) {
                    int row = (y0 + y) * Screen.W + x0;
                    for (int x = 0; x < 128; x++) {
                        if (all || frame[row + x] != sent[row + x]) {
                            if (x < minX) minX = x;
                            if (x > maxX) maxX = x;
                            if (y < minY) minY = y;
                            if (y > maxY) maxY = y;
                        }
                    }
                }
                if (maxX < 0) continue;
                int w = maxX - minX + 1, h = maxY - minY + 1;
                byte[] colors = new byte[w * h];
                for (int y = 0; y < h; y++) {
                    int src = (y0 + minY + y) * Screen.W + x0 + minX;
                    System.arraycopy(frame, src, colors, y * w, w);
                    System.arraycopy(frame, src, sent, src, w);
                }
                // row-major, x fastest: MapPatch reads colors[x + y * width]
                MapItemSavedData.MapPatch patch = new MapItemSavedData.MapPatch(minX, minY, w, h, colors);
                sp.connection.send(new ClientboundMapItemDataPacket(new MapId(mapBase + r * Screen.COLS + c),
                        (byte) 0, true, Optional.empty(), Optional.of(patch)));
            }
        }
    }
}
