package com.swiftality.screens;

import net.minecraft.network.protocol.game.ClientboundTeleportEntityPacket;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.PositionMoveRotation;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.BlockDisplay;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import io.papermc.paper.entity.TeleportFlag;
import org.joml.Quaternionf;
import org.joml.Vector3f;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * The pointer: a pixel-art hand made of flat display pieces riding one invisible root. Only the root moves, and the
 * viewer's client glides it (and so the whole hand) from one spot to the next. Seen by the viewer alone.
 */
final class Pointer {
    // '#' outline, 'o' fill; the hot spot is the fingertip (column 4.5, row 0).
    private static final String[] HAND = {
            "....##.......",
            "...#oo#......",
            "...#oo#......",
            "...#oo###....",
            "...#oo#oo##..",
            ".###oo#oo#o#.",
            "#oo#oooooooo#",
            "#ooooooooooo#",
            ".#oooooooooo#",
            "..#ooooooooo#",
            "..#oooooooo#.",
            "...#ooooooo#.",
            "...#ooooooo#.",
            "....#######..",
    };
    private static final double HOT_COL = 4.5;
    /** Size of one art cell in blocks: two map pixels. */
    private static final double CELL = 2.0 / 128.0;

    private final ItemDisplay root;
    private final List<Entity> parts = new ArrayList<>();

    private final Plugin plugin;
    private final Player viewer;
    private boolean shown = true;

    /** A pointer for a camera looking along +Z (the fullscreen wall). */
    Pointer(Plugin plugin, Player viewer, Location at) {
        this(plugin, viewer, at, 0f);
    }

    /** A pointer for a viewer looking the way [yaw] points (0: +Z, 90: -X), flat on a picture facing them. */
    Pointer(Plugin plugin, Player viewer, Location at, float yaw) {
        this.plugin = plugin;
        this.viewer = viewer;
        World w = at.getWorld();
        root = w.spawn(at, ItemDisplay.class, d -> {
            d.setPersistent(false);
            d.setVisibleByDefault(false);
            d.setTeleportDuration(1);
            d.setRotation(yaw, 0f);
        });
        viewer.showEntity(plugin, root);
        // the cube's top face (always lit fully) turned to face the camera, which looks along +Z
        Quaternionf faceCamera = new Quaternionf().rotateX((float) Math.toRadians(-90));
        for (int r = 0; r < HAND.length; r++) {
            String row = HAND[r];
            int c = 0;
            while (c < row.length()) {
                char ch = row.charAt(c);
                if (ch == '.') {
                    c++;
                    continue;
                }
                int start = c;
                while (c < row.length() && row.charAt(c) == ch) c++;
                int end = c - 1;
                boolean fill = ch == 'o';
                float tx = (float) (-(end + 1 - HOT_COL) * CELL);   // screen right is -X
                float ty = (float) (-(r + 1) * CELL);               // screen down is -Y
                float tz = fill ? -0.002f : 0f;                     // fill a hair nearer the camera
                Transformation t = new Transformation(new Vector3f(tx, ty, tz), new Quaternionf(),
                        new Vector3f((float) ((end - start + 1) * CELL), (float) CELL, 0.001f), faceCamera);
                BlockDisplay part = w.spawn(at, BlockDisplay.class, d -> {
                    d.setPersistent(false);
                    d.setVisibleByDefault(false);
                    d.setBlock((fill ? Material.WHITE_CONCRETE : Material.BLACK_CONCRETE).createBlockData());
                    d.setBrightness(new Display.Brightness(15, 15));
                    d.setTransformation(t);
                    d.setRotation(yaw, 0f);
                });
                viewer.showEntity(plugin, part);
                root.addPassenger(part);
                parts.add(part);
            }
        }
    }

    /** Moves the hand straight from the network thread: a teleport packet to the viewer only. */
    void sendMove(ServerPlayer viewer, double x, double y, double z) {
        viewer.connection.send(new ClientboundTeleportEntityPacket(root.getEntityId(),
                new PositionMoveRotation(new Vec3(x, y, z), Vec3.ZERO, 0f, 0f), Set.of(), false));
    }

    /** Keeps the server's copy where the client has it (server thread), so a periodic sync never snaps it back. */
    void syncServer(Location at) {
        if (root.isValid()) root.teleport(at, TeleportFlag.EntityState.RETAIN_PASSENGERS);
    }

    /**
     * Shows or hides the hand for the viewer (server thread). Showing puts it at [at] first; the parts go out before
     * the root, so the root's spawn carries them as its passengers.
     */
    void shown(boolean on, Location at) {
        if (on == shown) return;
        shown = on;
        if (on) {
            syncServer(at);
            for (Entity e : parts) viewer.showEntity(plugin, e);
            viewer.showEntity(plugin, root);
        } else {
            viewer.hideEntity(plugin, root);
            for (Entity e : parts) viewer.hideEntity(plugin, e);
        }
    }

    void remove() {
        for (Entity e : parts) e.remove();
        root.remove();
    }
}
