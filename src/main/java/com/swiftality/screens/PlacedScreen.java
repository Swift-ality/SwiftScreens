package com.swiftality.screens;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.core.component.DataComponents;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundContainerSetSlotPacket;
import net.minecraft.network.protocol.game.ClientboundRemoveEntitiesPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.decoration.GlowItemFrame;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.saveddata.maps.MapId;
import org.bukkit.FluidCollisionMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.BlockFace;
import org.bukkit.craftbukkit.CraftWorld;
import org.bukkit.craftbukkit.entity.CraftPlayer;
import org.bukkit.entity.Player;
import org.bukkit.util.RayTraceResult;
import org.bukkit.util.Vector;

import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A screen hanging in the air, seen by one player, who keeps moving freely. It is the same 10 x 6 maps as a
 * fullscreen screen (one block each, so 10 x 6 blocks), on invisible glow item frames that only that player's client
 * knows of, facing one of the four sides. The maps carry no signature, so the pack's shaders leave them alone.
 *
 * <p>The pointer is where the player's line of sight meets the picture, worked out on the server every tick (a ray
 * from the eye, stopped by blocks). Swings click, right clicks right-click, and while the player aims at the picture
 * nothing behind it is hit, broken or used.
 *
 * <p>Threads: the server thread places, aims, clicks and closes; a worker renders.
 */
public final class PlacedScreen implements ScreenHost {
    /** Map ids for placed screens: after the fullscreen screen's, 60 per screen. */
    static final int MAP_ID_BASE = ScreenView.MAP_ID_BASE + 1000;
    /** How many placed screens one player can have at once (each needs its own map ids). */
    static final int MAX_PER_PLAYER = 16;
    private static final double FRAME_INSET = 1.0 / 16.0;
    /** How far in front of the picture the pointer hand floats. */
    private static final double POINTER_LIFT = 0.02;

    /** Where the line of sight meets a picture: [t] blocks from the eye, at picture pixel (x, y). */
    record Aim(double t, double x, double y) {}

    private final ScreenManager manager;
    private final Player player;
    private final ServerPlayer sp;
    private volatile Screen screen;
    final int slot;
    private final int mapBase;

    private final World world;
    /** The side the picture faces (towards the viewer), and the viewer's right as they face it. */
    final BlockFace facing;
    private final Vector normal, right;
    /** The picture's top-left corner on the map surface, and its middle. */
    private final Vector topLeft, centre;

    private final List<Integer> entityIds = new ArrayList<>();
    private Pointer pointer;

    private final BufferedImage image = new BufferedImage(Screen.W, Screen.H, BufferedImage.TYPE_INT_ARGB);
    private final int[] rgb = new int[Screen.W * Screen.H];
    private final byte[] frame = new byte[Screen.W * Screen.H];
    private final byte[] sent = new byte[Screen.W * Screen.H];
    private boolean everSent;
    private final AtomicBoolean rendering = new AtomicBoolean();
    private volatile boolean wantFull;
    private volatile boolean closed;

    /** The player is aiming at this picture (the nearest one they aim at); the pointer is at (px, py). */
    private boolean aiming;
    private double px = -1, py = -1;
    /** Hotbar slot (0-8) showing an invisible pointer item on the client, or -1. */
    private int fakeSlot = -1;

    /**
     * A picture whose middle is near [middle], facing [facing] (NORTH, SOUTH, EAST or WEST). Frames hang on whole
     * blocks, so the picture lands within half a block of it.
     */
    PlacedScreen(ScreenManager manager, Player player, Screen screen, int slot, Location middle, BlockFace facing) {
        if (!facing.isCartesian() || facing.getModY() != 0) throw new IllegalArgumentException("A placed screen faces NORTH, SOUTH, EAST or WEST, not " + facing);
        if (middle.getWorld() == null) throw new IllegalArgumentException("The location has no world");
        this.manager = manager;
        this.player = player;
        this.sp = ((CraftPlayer) player).getHandle();
        this.screen = screen;
        this.slot = slot;
        this.mapBase = MAP_ID_BASE + slot * Screen.COLS * Screen.ROWS;
        this.world = middle.getWorld();
        this.facing = facing;
        this.normal = facing.getDirection();
        // the viewer looks along -normal; their right is (-look.z, 0, look.x)
        this.right = new Vector(normal.getZ(), 0, -normal.getX());

        // The top-left frame's block: half a column in from the left edge, half a row down, and back from the
        // surface to the middle of the block the frame is in.
        Vector m = middle.toVector();
        Vector tlBlockPoint = m.clone()
                .subtract(right.clone().multiply(Screen.COLS / 2.0 - 0.5))
                .add(new Vector(0, Screen.ROWS / 2.0 - 0.5, 0))
                .add(normal.clone().multiply(0.5 - FRAME_INSET));
        BlockPos tl = BlockPos.containing(tlBlockPoint.getX(), tlBlockPoint.getY(), tlBlockPoint.getZ());
        this.topLeft = new Vector(tl.getX() + 0.5, tl.getY() + 0.5, tl.getZ() + 0.5)
                .subtract(right.clone().multiply(0.5))
                .add(new Vector(0, 0.5, 0))
                .subtract(normal.clone().multiply(0.5 - FRAME_INSET));
        this.centre = topLeft.clone().add(right.clone().multiply(Screen.COLS / 2.0)).subtract(new Vector(0, Screen.ROWS / 2.0, 0));
        this.tlBlock = tl;
        screen.attach(this);
    }

    private final BlockPos tlBlock;

    public Player player() {
        return player;
    }

    public Screen screen() {
        return screen;
    }

    /** Where the picture's middle is. */
    public Location centre() {
        return centre.toLocation(world);
    }

    public boolean isOpen() {
        return !closed;
    }

    // ---- placing and taking down (server thread) ----------------------------------------------------------------

    void open() {
        ServerLevel level = ((CraftWorld) world).getHandle();
        Direction dir = Direction.valueOf(facing.name());
        int rx = (int) Math.round(right.getX()), rz = (int) Math.round(right.getZ());
        List<Packet<?>> packets = new ArrayList<>();
        for (int r = 0; r < Screen.ROWS; r++) {
            for (int c = 0; c < Screen.COLS; c++) {
                BlockPos pos = tlBlock.offset(rx * c, -r, rz * c);
                GlowItemFrame f = new GlowItemFrame(level, pos, dir);
                ItemStack map = new ItemStack(Items.FILLED_MAP);
                map.set(DataComponents.MAP_ID, new MapId(mapBase + r * Screen.COLS + c));
                f.setItem(map, false);
                f.setInvisible(true);
                packets.add(new ClientboundAddEntityPacket(f, dir.get3DDataValue(), pos));
                packets.add(new ClientboundSetEntityDataPacket(f.getId(), f.getEntityData().getNonDefaultValues()));
                entityIds.add(f.getId());
            }
        }
        for (Packet<?> p : packets) sp.connection.send(p);
        Vector look = normal.clone().multiply(-1);
        float yaw = (float) Math.toDegrees(Math.atan2(-look.getX(), look.getZ()));
        pointer = new Pointer(manager.plugin(), player, pointerLocation(Screen.W / 2.0, Screen.H / 2.0), yaw);
        pointer.shown(false, null);
        requestRender(true);
    }

    @Override
    public void close() {
        manager.runOnServer(() -> manager.closePlaced(this, false));
    }

    /** Takes the screen down (server thread). [quiet]: the player is leaving the server, send nothing. */
    void dispose(boolean quiet) {
        if (closed) return;
        closed = true;
        screen.closed();
        if (pointer != null) pointer.remove();
        if (quiet || !player.isOnline()) return;
        restoreSlot();
        sp.connection.send(new ClientboundRemoveEntitiesPacket(entityIds.stream().mapToInt(Integer::intValue).toArray()));
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
        manager.runOnServer(() -> player.playSound(centre(), sound, volume, pitch));
    }

    @Override
    public void actionBar(String text) {
        manager.runOnServer(() -> player.sendActionBar(net.kyori.adventure.text.Component.text(text)));
    }

    // ---- aiming (server thread) ---------------------------------------------------------------------------------

    /** Whether the player is too far off (or in another world) to keep the picture. */
    boolean outOfRange(double maxDistance) {
        return player.getWorld() != world || player.getLocation().toVector().distanceSquared(centre) > maxDistance * maxDistance;
    }

    /**
     * Where the player's line of sight meets this picture, at most [reach] blocks off and not behind a block; null
     * when it does not.
     */
    Aim aim(double reach) {
        if (closed || player.getWorld() != world) return null;
        Location eyeLoc = player.getEyeLocation();
        Vector eye = eyeLoc.toVector(), dir = eyeLoc.getDirection();
        double denom = dir.dot(normal);
        if (denom > -1e-6) return null;                     // looking along or away from its face
        double t = topLeft.clone().subtract(eye).dot(normal) / denom;
        if (t < 0 || t > reach) return null;
        Vector rel = eye.clone().add(dir.clone().multiply(t)).subtract(topLeft);
        double x = rel.dot(right) * 128.0, y = -rel.getY() * 128.0;
        if (x < 0 || y < 0 || x >= Screen.W || y >= Screen.H) return null;
        RayTraceResult block = world.rayTraceBlocks(eyeLoc, dir, t, FluidCollisionMode.NEVER, true);
        if (block != null && block.getHitPosition().distanceSquared(eye) < t * t) return null;
        return new Aim(t, x, y);
    }

    boolean aiming() {
        return aiming;
    }

    /** This tick's aim, or null when the player is not aiming here (or aims at a nearer picture). */
    void tick(Aim a) {
        if (closed) return;
        if (a == null) {
            if (aiming) {
                aiming = false;
                px = py = -1;
                screen.pointer(-1, -1);
                pointer.shown(false, null);
            }
        } else {
            boolean was = aiming;
            aiming = true;
            if (!was || a.x() != px || a.y() != py) {
                px = a.x();
                py = a.y();
                screen.pointer((int) px, (int) py);
                Location l = pointerLocation(px, py);
                if (!was) pointer.shown(true, l);
                else {
                    pointer.sendMove(sp, l.getX(), l.getY(), l.getZ());
                    pointer.syncServer(l);
                }
            }
        }
        fakeSlotItem();
        requestRender(false);
    }

    /**
     * An empty hand sends no packet for a right click on nothing. While aiming with an empty hand, the selected
     * hotbar slot holds an invisible item on the client alone, so right clicks reach the server; it goes back
     * when the aim leaves, the slot changes or the hand fills.
     */
    private void fakeSlotItem() {
        int held = player.getInventory().getHeldItemSlot();
        boolean want = aiming && player.getInventory().getItemInMainHand().getType().isAir();
        if (fakeSlot >= 0 && (!want || fakeSlot != held)) restoreSlot();
        if (want && fakeSlot < 0) {
            fakeSlot = held;
            sp.connection.send(new ClientboundContainerSetSlotPacket(sp.inventoryMenu.containerId,
                    sp.inventoryMenu.incrementStateId(), 36 + held, ScreenView.pointerItem()));
        }
    }

    private void restoreSlot() {
        if (fakeSlot < 0) return;
        int s = fakeSlot;
        fakeSlot = -1;
        sp.connection.send(new ClientboundContainerSetSlotPacket(sp.inventoryMenu.containerId,
                sp.inventoryMenu.incrementStateId(), 36 + s, sp.getInventory().getItem(s).copy()));
    }

    void click() {
        if (aiming && screen.click((int) px, (int) py)) requestRender(false);
    }

    void rightClick() {
        if (aiming && screen.rightClick((int) px, (int) py)) requestRender(false);
    }

    private Location pointerLocation(double x, double y) {
        return topLeft.clone()
                .add(right.clone().multiply(x / 128.0))
                .subtract(new Vector(0, y / 128.0, 0))
                .add(normal.clone().multiply(POINTER_LIFT))
                .toLocation(world);
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
                MapPatches.send(sp, frame, sent, mapBase, all || !everSent);
                everSent = true;
            } catch (Throwable t) {
                manager.plugin().getLogger().warning("Drawing a placed screen for " + player.getName() + " failed: " + t);
            } finally {
                rendering.set(false);
            }
        });
    }
}
