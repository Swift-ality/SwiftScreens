package com.swiftality.screens;

import io.netty.buffer.Unpooled;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.FriendlyByteBuf;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundMapItemDataPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetCameraPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.EntityTypes;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import net.minecraft.world.level.saveddata.maps.MapItemSavedData;
import net.minecraft.world.phys.Vec3;
import org.bukkit.Location;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * One player's fullscreen screen. A wall of invisible glow item frames holding maps, sent to that player alone, high
 * above them where no block is in the way, and an invisible armour stand facing it that their camera is locked to.
 * They sit on a seat only their client knows of (seated, a client keeps sending its head turns while its camera is
 * elsewhere). Head turns are read off the network as they arrive and move the pointer at once; drawing happens on a
 * worker thread, never the server thread.
 *
 * <p>Threads: the network thread calls {@link #look}; the server thread opens, closes, clicks and ticks; a worker
 * renders. Nothing waits on a lock: a render reads the screen as it is, and anything that changes meanwhile marks
 * it dirty for the next one.
 */
public final class ScreenView implements ScreenHost {
    /** Map ids the screens use; each client only sees its own, so every view can share them. */
    static final int MAP_ID_BASE = 1_960_000_000;
    /** Blocks between the camera and the wall: a 6-block-tall wall then fills most of a 70 degree view. */
    private static final double DISTANCE = 4.6;
    /** The map surface of a frame hanging on a block's face sits this far in from the block's edge. */
    private static final double FRAME_INSET = 1.0 / 16.0;

    private final ScreenManager manager;
    private final Player player;
    private final ServerPlayer sp;
    private volatile Screen screen;

    private final BufferedImage image = new BufferedImage(Screen.W, Screen.H, BufferedImage.TYPE_INT_ARGB);
    private final int[] rgb = new int[Screen.W * Screen.H];
    private final byte[] frame = new byte[Screen.W * Screen.H];
    private final byte[] sent = new byte[Screen.W * Screen.H];
    private boolean everSent;

    private final List<Integer> entityIds = new ArrayList<>();
    private int seatId;
    private Pointer pointer;
    private volatile double leftEdge, topEdge, surfaceZ;

    final Location returnTo;
    private volatile float lastYaw, lastPitch;
    private volatile double px = Screen.W / 2.0, py = Screen.H / 2.0;
    /** Set by the network thread when the pointer moved; the server thread then updates hover and the server copy. */
    private volatile boolean moved;
    private volatile boolean recentring;
    private volatile boolean closed;
    /** A render is queued or running. */
    private final java.util.concurrent.atomic.AtomicBoolean rendering = new java.util.concurrent.atomic.AtomicBoolean();
    /** A full resend is wanted (the first picture). */
    private volatile boolean wantFull;

    /** Shader mode: the player has the screens pack, so their own client draws the picture and pointer. */
    final boolean shader;
    /** The server is turning or moving the player itself (not a teleport that should close the screen). */
    volatile boolean selfMove;
    /** Shader mode: the player looked past the picture's edge; turn them back on the next tick. */
    private volatile boolean turnBack;

    /** 0-255: how far the head turns to cross the picture (see widthDegrees), from the player's mouse and speed. */
    private volatile int speedIndex;
    /** Shader mode: the eye's height within its block, in 256ths (the shader tells a map's top from its bottom by it). */
    private volatile int eyeFraction;
    private final double pixelsPerDegree;

    ScreenView(ScreenManager manager, Player player, Screen screen, boolean shader) {
        this.shader = shader;
        this.speedIndex = manager.speedIndex(player);
        this.pixelsPerDegree = manager.pixelsPerDegree(player);
        this.manager = manager;
        this.player = player;
        this.sp = ((CraftPlayer) player).getHandle();
        this.screen = screen;
        this.returnTo = player.getLocation();
        screen.attach(this);
    }

    public Player player() {
        return player;
    }

    public Screen screen() {
        return screen;
    }

    // ---- opening and closing (server thread) --------------------------------------------------------------------

    void open() {
        if (shader) {
            openShader();
            return;
        }
        ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();

        // The camera looks south (+Z) from high above the player's column, above the build limit: always air,
        // always in chunks their client has, so nothing is ever between it and the wall.
        Location loc = player.getLocation();
        int bx = loc.getBlockX(), bz = loc.getBlockZ();
        double eyeY = player.getWorld().getMaxHeight() + 24.0;

        // The wall: COLS x ROWS frames on the north faces of a row of blocks, centred on the camera's eye.
        // Looking south, the viewer's left is +X, so column 0 is the most easterly.
        int wallZ = bz + (int) Math.ceil(DISTANCE);
        surfaceZ = wallZ - FRAME_INSET;
        double camZ = surfaceZ - DISTANCE;
        int left = bx + Screen.COLS / 2;
        leftEdge = left + 1;                              // picture x = 0 is here; x grows towards -X
        double camX = leftEdge - Screen.COLS / 2.0;
        int bottom = (int) Math.floor(eyeY - Screen.ROWS / 2.0);
        topEdge = bottom + Screen.ROWS;                   // picture y = 0 is here; y grows downwards
        double centreY = bottom + Screen.ROWS / 2.0;

        List<Packet<?>> packets = new ArrayList<>();
        for (int r = 0; r < Screen.ROWS; r++) {
            for (int c = 0; c < Screen.COLS; c++) {
                BlockPos pos = new BlockPos(left - c, bottom + (Screen.ROWS - 1 - r), wallZ - 1);
                GlowItemFrame f = new GlowItemFrame(level, pos, Direction.NORTH);
                ItemStack map = new ItemStack(Items.FILLED_MAP);
                map.set(DataComponents.MAP_ID, new MapId(MAP_ID_BASE + r * Screen.COLS + c));
                f.setItem(map, false);
                f.setInvisible(true);
                packets.add(new ClientboundAddEntityPacket(f, Direction.NORTH.get3DDataValue(), pos));
                packets.add(new ClientboundSetEntityDataPacket(f.getId(), f.getEntityData().getNonDefaultValues()));
                entityIds.add(f.getId());
            }
        }

        ArmorStand cam = new ArmorStand(level, camX, 0, camZ);
        cam.setMarker(true);
        cam.setInvisible(true);
        cam.setNoGravity(true);
        // a hair off the middle row seam, which drew as a line when looked at dead level
        double eye = cam.getEyeHeight() - 0.07;
        cam.setPos(camX, centreY - eye, camZ);
        entityIds.add(cam.getId());
        packets.add(new ClientboundAddEntityPacket(cam.getId(), UUID.randomUUID(), camX, centreY - eye, camZ, 0f, 0f,
                EntityTypes.ARMOR_STAND, 0, Vec3.ZERO, 0.0));
        packets.add(new ClientboundSetEntityDataPacket(cam.getId(), cam.getEntityData().getNonDefaultValues()));

        ArmorStand seat = new ArmorStand(level, loc.getX(), loc.getY(), loc.getZ());
        seat.setMarker(true);
        seat.setInvisible(true);
        seatId = seat.getId();
        entityIds.add(seatId);
        packets.add(new ClientboundAddEntityPacket(seatId, UUID.randomUUID(), loc.getX(), loc.getY(), loc.getZ(),
                0f, loc.getYaw(), EntityTypes.ARMOR_STAND, 0, Vec3.ZERO, loc.getYaw()));
        packets.add(new ClientboundSetEntityDataPacket(seatId, seat.getEntityData().getNonDefaultValues()));
        packets.add(passengers(seatId, sp.getId()));

        // Their client alone thinks they are invisible with empty hands: no arm or item drawn in front of the screen.
        packets.add(new ClientboundSetEntityDataPacket(sp.getId(), List.of(flags(true))));
        for (int slot = 36; slot <= 45; slot++) {
            packets.add(new ClientboundContainerSetSlotPacket(0, sp.inventoryMenu.getStateId(), slot, ItemStack.EMPTY));
        }

        for (Packet<?> p : packets) sp.connection.send(p);
        sp.connection.send(new ClientboundSetCameraPacket(cam));
        pointer = new Pointer(manager.plugin(), player, pointerLocation());

        player.setRotation(loc.getYaw(), 0f);
        lastYaw = loc.getYaw();
        lastPitch = 0f;
        requestRender(true);
    }

    /**
     * Shader mode: the player is put on the spot the pack's shaders look for (their eye at an exact block fraction),
     * facing +Z; the maps hang right round their eye, so their client always draws them, and the shaders lay them
     * over the whole window. No camera lock, no seat: their own mouse moves the pointer, every frame.
     */
    private void openShader() {
        ServerLevel level = ((CraftWorld) player.getWorld()).getHandle();
        Location loc = player.getLocation();
        Location spot = new Location(loc.getWorld(), Math.floor(loc.getX()) + ScreenPack.FRACTION, loc.getY(),
                Math.floor(loc.getZ()) + ScreenPack.FRACTION, 0f, 0f);
        selfMove = true;
        player.teleport(spot);
        selfMove = false;
        double eyeY = spot.getY() + player.getEyeHeight();
        BlockPos eyeBlock = BlockPos.containing(spot.getX(), eyeY, spot.getZ());
        eyeFraction = (int) Math.max(0, Math.min(255, Math.floor((eyeY - Math.floor(eyeY)) * 256.0)));

        List<Packet<?>> packets = new ArrayList<>();
        for (int r = 0; r < Screen.ROWS; r++) {
            for (int c = 0; c < Screen.COLS; c++) {
                GlowItemFrame f = new GlowItemFrame(level, eyeBlock, Direction.NORTH);
                ItemStack map = new ItemStack(Items.FILLED_MAP);
                map.set(DataComponents.MAP_ID, new MapId(MAP_ID_BASE + r * Screen.COLS + c));
                f.setItem(map, false);
                f.setInvisible(true);
                packets.add(new ClientboundAddEntityPacket(f, Direction.NORTH.get3DDataValue(), eyeBlock));
                packets.add(new ClientboundSetEntityDataPacket(f.getId(), f.getEntityData().getNonDefaultValues()));
                entityIds.add(f.getId());
            }
        }
        packets.add(new ClientboundSetEntityDataPacket(sp.getId(), List.of(flags(true))));
        for (int slot = 36; slot <= 45; slot++) {
            packets.add(new ClientboundContainerSetSlotPacket(0, sp.inventoryMenu.getStateId(), slot, ItemStack.EMPTY));
        }
        for (Packet<?> p : packets) sp.connection.send(p);
        lastYaw = 0f;
        lastPitch = 0f;
        requestRender(true);
    }

    /** Shader mode: degrees of head turn across the picture, exactly as the shader decodes the signature. */
    private double widthDegrees() {
        return 10.0 + 1.4 * speedIndex;
    }

    /** The player's mouse step was learnt or changed: carry the new speed in the signature. */
    void speedChanged() {
        int n = manager.speedIndex(player);
        if (n == speedIndex) return;
        speedIndex = n;
        screen.markDirty();
    }

    /** Shader mode: the pointer for a head turned to (yaw, pitch), as the shader works it out. */
    private void shaderPointer(float yaw, float pitch) {
        double w = widthDegrees(), h = Math.min(w * Screen.ROWS / Screen.COLS, 170.0);
        double yw = wrap(yaw);
        double u = 0.5 + yw / w, v = 0.5 + pitch / h;
        // past an edge by a few degrees: turn them back before they reach the 180 degree wrap or the pitch stop
        if (Math.abs(yw) > w / 2 + 3 || Math.abs(pitch) > h / 2 + 3) turnBack = true;
        px = clamp(u, 0, 1) * (Screen.W - 1);
        py = clamp(v, 0, 1) * (Screen.H - 1);
    }

    /** Shader mode: the head direction that points at picture position (u, v). */
    private float[] anglesFor(double u, double v) {
        double w = widthDegrees(), h = Math.min(w * Screen.ROWS / Screen.COLS, 170.0);
        return new float[]{(float) ((u - 0.5) * w), (float) ((v - 0.5) * h)};
    }

    /** The player's shared entity flags as the server has them, with the invisible bit forced on or off. */
    private SynchedEntityData.DataValue<Byte> flags(boolean invisible) {
        byte f = 0;
        if (player.getFireTicks() > 0) f |= 0x01;
        if (player.isSneaking()) f |= 0x02;
        if (player.isSprinting()) f |= 0x08;
        if (player.isSwimming()) f |= 0x10;
        if (invisible || player.isInvisible()) f |= 0x20;
        if (player.isGlowing()) f |= 0x40;
        if (player.isGliding()) f |= (byte) 0x80;
        return new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, f);
    }

    private Location pointerLocation() {
        return new Location(player.getWorld(), leftEdge - px / 128.0, topEdge - py / 128.0, surfaceZ - 0.02);
    }

    @Override
    public void close() {
        manager.runOnServer(() -> manager.close(player, false));
    }

    /** Takes the screen down (server thread). [quiet]: the player is leaving the server, send nothing. */
    void dispose(boolean quiet) {
        if (closed) return;
        closed = true;
        screen.closed();
        if (pointer != null) pointer.remove();
        if (quiet) {
            // leaving: put them back where they stood before it is saved (else they log in on the screen spot)
            if (shader) {
                try {
                    selfMove = true;
                    player.teleport(returnTo);
                } catch (Throwable ignored) {
                    // the join check moves them off it instead
                } finally {
                    selfMove = false;
                }
            }
            return;
        }
        if (!player.isOnline()) return;
        if (shader) {
            sp.connection.send(new ClientboundRemoveEntitiesPacket(entityIds.stream().mapToInt(Integer::intValue).toArray()));
            sp.connection.send(new ClientboundSetEntityDataPacket(sp.getId(), List.of(flags(false))));
            player.updateInventory();
            selfMove = true;
            player.teleport(returnTo);
            selfMove = false;
            return;
        }
        sp.connection.send(passengers(seatId));
        sp.connection.send(new ClientboundSetCameraPacket(sp));
        sp.connection.send(new ClientboundRemoveEntitiesPacket(entityIds.stream().mapToInt(Integer::intValue).toArray()));
        sp.connection.send(new ClientboundSetEntityDataPacket(sp.getId(), List.of(flags(false))));
        player.updateInventory();
        Location back = player.getLocation();
        back.setYaw(returnTo.getYaw());
        back.setPitch(returnTo.getPitch());
        player.teleport(back);
    }

    @Override
    public void show(Screen next) {
        screen.closed();
        next.attach(this);
        next.pointer((int) px, (int) py);
        next.markDirty();
        screen = next;
        requestRender(false);
    }

    @Override
    public void sound(String sound, float volume, float pitch) {
        manager.runOnServer(() -> player.playSound(player, sound, volume, pitch));
    }

    @Override
    public void actionBar(String text) {
        manager.runOnServer(() -> player.sendActionBar(net.kyori.adventure.text.Component.text(text)));
    }

    // ---- input --------------------------------------------------------------------------------------------------

    /** A head turn, straight off the network (network thread): move the pointer now. */
    void look(float yaw, float pitch) {
        if (closed) return;
        if (shader) {
            if (!selfMove && !turnBack) manager.mouse().sample(player.getUniqueId(), yaw - lastYaw, pitch - lastPitch, this);
            lastYaw = yaw;
            lastPitch = pitch;
            double ox = px, oy = py;
            shaderPointer(yaw, pitch);
            if (ox != px || oy != py) moved = true;
            return;
        }
        if (recentring) {
            // the turn back to level is on its way; take the first rotation near it as the new start
            if (Math.abs(pitch) > 10f) return;
            recentring = false;
            lastYaw = yaw;
            lastPitch = pitch;
            return;
        }
        float dy = wrap(yaw - lastYaw), dp = pitch - lastPitch;
        lastYaw = yaw;
        lastPitch = pitch;
        if (dy == 0f && dp == 0f) return;
        double k = pixelsPerDegree;
        px = clamp(px + dy * k, 0, Screen.W - 1);
        py = clamp(py + dp * k, 0, Screen.H - 1);
        if (pointer != null) {
            Location l = pointerLocation();
            pointer.sendMove(sp, l.getX(), l.getY(), l.getZ());
        }
        moved = true;
    }

    /** Every tick (server thread): hover, the pitch stop, the server's copy of the pointer, and redraws. */
    void tick() {
        if (closed) return;
        if (--pointerItemsIn <= 0) {
            pointerItemsIn = 40;
            sendPointerItems();
        }
        if (shader) {
            if (turnBack) {
                turnBack = false;
                float[] a = anglesFor(px / (Screen.W - 1), py / (Screen.H - 1));
                selfMove = true;
                player.setRotation(a[0], a[1]);
                selfMove = false;
            }
            if (moved) {
                moved = false;
                screen.pointer((int) px, (int) py);
            }
            requestRender(false);
            return;
        }
        float pitch = lastPitch;
        if (!recentring && Math.abs(pitch) > 60f) {
            recentring = true;
            player.setRotation(lastYaw, 0f);
        }
        if (moved) {
            moved = false;
            screen.pointer((int) px, (int) py);
            if (pointer != null) pointer.syncServer(pointerLocation());
        }
        requestRender(false);
    }

    /** Ticks until the pointer items are sent again (the server may have resent a hotbar slot meanwhile). */
    private int pointerItemsIn;

    /**
     * Fills the hotbar, on the client only, with an invisible, nameless item: with something in hand, a right click
     * on nothing still reaches the server. The real inventory is sent back when the screen closes.
     */
    private void sendPointerItems() {
        org.bukkit.inventory.ItemStack item = new org.bukkit.inventory.ItemStack(org.bukkit.Material.PAPER);
        item.editMeta(m -> {
            m.setItemModel(org.bukkit.NamespacedKey.minecraft("air"));
            m.itemName(net.kyori.adventure.text.Component.empty());
        });
        net.minecraft.world.item.ItemStack nms = org.bukkit.craftbukkit.inventory.CraftItemStack.asNMSCopy(item);
        for (int i = 0; i < 9; i++) {
            sp.connection.send(new net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket(
                    sp.inventoryMenu.containerId, sp.inventoryMenu.incrementStateId(), 36 + i, nms.copy()));
        }
    }

    void click() {
        if (screen.click((int) px, (int) py)) requestRender(false);
    }

    void rightClick() {
        if (screen.rightClick((int) px, (int) py)) requestRender(false);
    }

    void text(String line) {
        screen.text(line);
        screen.markDirty();
        requestRender(false);
    }

    void key(String key) {
        screen.key(key);
        requestRender(false);
    }

    // ---- drawing (worker thread) --------------------------------------------------------------------------------

    private void requestRender(boolean full) {
        if (full) wantFull = true;
        if (closed || (!wantFull && !screen.dirty())) return;
        if (!rendering.compareAndSet(false, true)) return;
        manager.render(() -> {
            try {
                if (closed) return;
                boolean all = wantFull;
                wantFull = false;
                screen.render(image);
                image.getRGB(0, 0, Screen.W, Screen.H, rgb, 0, Screen.W);
                MapColors.convert(rgb, frame);
                if (shader) sign();
                send(all || !everSent);
                everSent = true;
            } catch (Throwable t) {
                manager.plugin().getLogger().warning("Drawing a screen for " + player.getName() + " failed: " + t);
            } finally {
                rendering.set(false);
            }
        });
    }

    private static final byte SIGN_ON = MapColors.of(0xFFFFFF), SIGN_OFF = MapColors.of(0x000000);
    private static final int[] SIGNATURE = {1, 0, 1, 1, 0, 0, 1, 0};

    /**
     * Shader mode: each map's top-left 32 pixels say "SwiftScreens screen, column c, row r, speed s, eye height e"
     * for the shaders (e: the eye's height within its block, in 256ths).
     */
    private void sign() {
        for (int r = 0; r < Screen.ROWS; r++) {
            for (int c = 0; c < Screen.COLS; c++) {
                int base = (r * 128) * Screen.W + c * 128;
                for (int i = 0; i < 8; i++) frame[base + i] = SIGNATURE[i] == 1 ? SIGN_ON : SIGN_OFF;
                for (int i = 0; i < 4; i++) {
                    frame[base + 8 + i] = ((c >> (3 - i)) & 1) == 1 ? SIGN_ON : SIGN_OFF;
                    frame[base + 12 + i] = ((r >> (3 - i)) & 1) == 1 ? SIGN_ON : SIGN_OFF;
                }
                for (int i = 0; i < 8; i++) frame[base + 16 + i] = ((speedIndex >> (7 - i)) & 1) == 1 ? SIGN_ON : SIGN_OFF;
                for (int i = 0; i < 8; i++) frame[base + 24 + i] = ((eyeFraction >> (7 - i)) & 1) == 1 ? SIGN_ON : SIGN_OFF;
            }
        }
    }

    /** "[vehicle] carries [riders]", built through the packet's own codec (its constructor wants a real entity). */
    private static ClientboundSetPassengersPacket passengers(int vehicle, int... riders) {
        FriendlyByteBuf buf = new FriendlyByteBuf(Unpooled.buffer());
        buf.writeVarInt(vehicle);
        buf.writeVarIntArray(riders);
        return ClientboundSetPassengersPacket.STREAM_CODEC.decode(buf);
    }

    /** Sends each map's changed rectangle (all of it when [all]). */
    private void send(boolean all) {
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
                sp.connection.send(new ClientboundMapItemDataPacket(new MapId(MAP_ID_BASE + r * Screen.COLS + c),
                        (byte) 0, true, Optional.empty(), Optional.of(patch)));
            }
        }
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a > 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }

    private static double clamp(double v, double lo, double hi) {
        return v < lo ? lo : v > hi ? hi : v;
    }
}
