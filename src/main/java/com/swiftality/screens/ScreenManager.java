package com.swiftality.screens;

import org.bukkit.event.player.PlayerInputEvent;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.attribute.Attribute;
import org.bukkit.attribute.AttributeInstance;
import org.bukkit.attribute.AttributeModifier;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerAnimationEvent;
import org.bukkit.event.player.PlayerAnimationType;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.inventory.EquipmentSlotGroup;
import org.bukkit.plugin.Plugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Opens and closes fullscreen screens, holds viewers still, and feeds them their mouse, clicks and keys. */
public final class ScreenManager implements Listener {
    private static final Attribute[] HOLD = {Attribute.MOVEMENT_SPEED, Attribute.JUMP_STRENGTH, Attribute.GRAVITY};

    private final Plugin plugin;
    private final NamespacedKey holdKey;
    private final Map<UUID, ScreenView> views = new java.util.concurrent.ConcurrentHashMap<>();
    /** Draws screens off the server thread. */
    private final java.util.concurrent.ExecutorService renderer = java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
        Thread t = new Thread(r, "SwiftScreens renderer");
        t.setDaemon(true);
        t.setPriority(Thread.NORM_PRIORITY - 1);
        return t;
    });
    private static final String HANDLER = "swiftscreens_screen";
    /** Reads right clicks for placed screens (the player moves freely, so nothing else is held back). */
    private static final String PLACED_HANDLER = "swiftscreens_placed";
    /** Each player's screens hanging in the air. */
    private final Map<UUID, List<PlacedScreen>> placed = new java.util.concurrent.ConcurrentHashMap<>();
    /** When each player's last right click for a placed screen arrived (nanos): one click can come as two packets. */
    private final Map<UUID, Long> lastPlacedRight = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long PLACED_RIGHT_NANOS = 60_000_000L;
    private volatile double placeDistance = 6.0, placedReach = 16.0, placedMaxDistance = 48.0;
    private final Map<UUID, Boolean> jumpHeld = new HashMap<>(), sneakHeld = new HashMap<>();
    /** Whether each viewer could fly before their screen opened (creative flight would lift the camera off its spot). */
    private final Map<UUID, Boolean> mayFly = new java.util.concurrent.ConcurrentHashMap<>();
    /** When each viewer's last right click arrived (nanos): the arm swing that can follow it is not a left click. */
    private final Map<UUID, Long> lastRight = new java.util.concurrent.ConcurrentHashMap<>();
    private static final long RIGHT_SWING_NANOS = 250_000_000L;
    private double pixelsPerDegree = 22.0;
    /** Told when a player tries to close a screen that will not close (sneak on a required pick). */
    private BukkitTask task;

    public ScreenManager(Plugin plugin) {
        this.plugin = plugin;
        this.holdKey = new NamespacedKey(plugin, "screen_hold");
        this.speedKey = new NamespacedKey(plugin, "pointer_speed");
        this.mouse = new MouseSteps(plugin);
    }

    public void start() {
        Bukkit.getPluginManager().registerEvents(this, plugin);
        task = Bukkit.getScheduler().runTaskTimer(plugin, this::tick, 1L, 1L);
    }

    public void stop() {
        if (task != null) task.cancel();
        for (UUID id : views.keySet().toArray(new UUID[0])) {
            Player p = Bukkit.getPlayer(id);
            if (p != null) close(p, false);
        }
        views.clear();
        for (List<PlacedScreen> list : placed.values()) {
            for (PlacedScreen ps : list) closePlaced(ps, false);
        }
        placed.clear();
        renderer.shutdownNow();
    }

    public void pointerSpeed(double pixelsPerDegree) {
        this.pixelsPerDegree = pixelsPerDegree;
    }

    /**
     * Placed screens: how far in front of the player {@link #place(Player, Screen)} hangs one, how far off it can be
     * aimed at, and how far the player can go before it is taken down.
     */
    public void placedRanges(double distance, double reach, double maxDistance) {
        this.placeDistance = distance;
        this.placedReach = reach;
        this.placedMaxDistance = maxDistance;
    }

    private ScreenPack pack;
    private double desktopWidth = 2400.0;
    private volatile boolean lockView = true;

    /** Shader mode keeps the view still while a screen is open (the mouse moves only the pointer). */
    public void lockView(boolean on) {
        lockView = on;
    }

    boolean lockView() {
        return lockView;
    }
    private final MouseSteps mouse;
    private final NamespacedKey speedKey;

    /** How many mouse steps cross the screen at pointer speed 1 (a desktop pointer on a screen this wide). */
    public void desktopWidth(double steps) {
        this.desktopWidth = steps;
    }

    MouseSteps mouse() {
        return mouse;
    }

    /** The player's own pointer speed (1 = the server's default), kept on the player. */
    public double speed(Player p) {
        Double s = p.getPersistentDataContainer().get(speedKey, org.bukkit.persistence.PersistentDataType.DOUBLE);
        return s == null ? 1.0 : s;
    }

    public void speed(Player p, double s) {
        p.getPersistentDataContainer().set(speedKey, org.bukkit.persistence.PersistentDataType.DOUBLE, s);
    }

    /**
     * The number the shader reads (10 + 1.4n degrees across the screen): the turn a desktop pointer's worth of
     * mouse steps makes, at the player's own sensitivity, divided by their pointer speed. Capped so the head never
     * has to turn past straight up or down.
     */
    int speedIndex(Player p) {
        double deg = desktopWidth * mouse.step(p) / Math.max(0.1, speed(p));
        deg = Math.max(12.0, Math.min(340.0, deg));
        return (int) Math.max(0, Math.min(255, Math.round((deg - 10.0) / 1.4)));
    }

    public void pack(ScreenPack pack) {
        this.pack = pack;
        pack.onLoaded(this::upgrade);
    }

    /** The player's pack with the shaders loaded while a map screen was open: show the same screen with the pack. */
    public void upgrade(Player p) {
        ScreenView v = views.get(p.getUniqueId());
        if (v == null || v.shader) return;
        Screen s = v.screen();
        close(p, false);
        open(p, s);
    }

    ScreenPack pack() {
        return pack;
    }

    double pixelsPerDegree() {
        return pixelsPerDegree;
    }

    /** Map mode: the player's pixels per degree. */
    double pixelsPerDegree(Player p) {
        return pixelsPerDegree * speed(p);
    }

    void render(Runnable job) {
        renderer.execute(job);
    }

    void runOnServer(Runnable r) {
        if (Bukkit.isPrimaryThread()) r.run();
        else Bukkit.getScheduler().runTask(plugin, r);
    }

    Plugin plugin() {
        return plugin;
    }

    public boolean viewing(Player p) {
        return views.containsKey(p.getUniqueId());
    }

    public Screen screen(Player p) {
        ScreenView v = views.get(p.getUniqueId());
        return v == null ? null : v.screen();
    }

    /** Opens [screen] fullscreen for [player], replacing any screen they have open. */
    public void open(Player player, Screen screen) {
        ScreenView old = views.get(player.getUniqueId());
        if (old != null) {
            old.show(screen);
            return;
        }
        ScreenView v = new ScreenView(this, player, screen, pack != null && pack.has(player));
        views.put(player.getUniqueId(), v);
        hold(player, true);
        v.open();
        listen(player, v);
    }

    // ---- placed screens -------------------------------------------------------------------------------------------

    /**
     * Hangs [screen] in the air in front of [player], facing them (snapped to the side they face most), and leaves
     * them free to move. They aim with their crosshair: swings click, right clicks right-click. Server thread only.
     *
     * @throws IllegalStateException when the player already has the most placed screens one player can have
     */
    public PlacedScreen place(Player player, Screen screen) {
        org.bukkit.block.BlockFace look = player.getFacing();
        Location middle = player.getEyeLocation().add(look.getDirection().multiply(placeDistance));
        return place(player, screen, middle, look.getOppositeFace());
    }

    /**
     * Hangs [screen] in the air for [player] with its middle near [middle] (within half a block: frames hang on whole
     * blocks), its picture facing [facing] (NORTH, SOUTH, EAST or WEST: the side a viewer stands on). Server thread
     * only.
     */
    public PlacedScreen place(Player player, Screen screen, Location middle, org.bukkit.block.BlockFace facing) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Place screens on the server thread");
        List<PlacedScreen> list = placed.computeIfAbsent(player.getUniqueId(), k -> new java.util.concurrent.CopyOnWriteArrayList<>());
        int slot = -1;
        for (int s = 0; s < PlacedScreen.MAX_PER_PLAYER && slot < 0; s++) {
            final int c = s;
            if (list.stream().noneMatch(p -> p.slot == c)) slot = s;
        }
        if (slot < 0) throw new IllegalStateException(player.getName() + " already has " + PlacedScreen.MAX_PER_PLAYER + " placed screens");
        PlacedScreen ps = new PlacedScreen(this, player, screen, slot, middle, facing);
        list.add(ps);
        ps.open();
        listenPlaced(player);
        return ps;
    }

    /** The player's placed screens. */
    public List<PlacedScreen> placed(Player player) {
        List<PlacedScreen> list = placed.get(player.getUniqueId());
        return list == null ? List.of() : List.copyOf(list);
    }

    /** Takes down all of [player]'s placed screens. */
    public void closePlaced(Player player) {
        for (PlacedScreen ps : placed(player)) closePlaced(ps, false);
    }

    void closePlaced(PlacedScreen ps, boolean quiet) {
        UUID id = ps.player().getUniqueId();
        List<PlacedScreen> list = placed.get(id);
        if (list == null || !list.remove(ps)) return;
        if (list.isEmpty()) {
            placed.remove(id, list);
            lastPlacedRight.remove(id);
            unlisten(ps.player(), PLACED_HANDLER);
        }
        ps.dispose(quiet);
    }

    /** The placed screen [player] aims at, or null. */
    public PlacedScreen aimed(Player player) {
        List<PlacedScreen> list = placed.get(player.getUniqueId());
        if (list == null) return null;
        for (PlacedScreen ps : list) if (ps.aiming()) return ps;
        return null;
    }

    /** Right clicks off the connection: the main-hand use and interact packets (see isRightClick). */
    private void listenPlaced(Player player) {
        io.netty.channel.Channel ch = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().connection.connection.channel;
        UUID id = player.getUniqueId();
        ch.eventLoop().execute(() -> {
            if (ch.pipeline().get(PLACED_HANDLER) != null || ch.pipeline().get("packet_handler") == null) return;
            ch.pipeline().addBefore("packet_handler", PLACED_HANDLER, new io.netty.channel.ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (isRightClick(msg)) {
                        long now = System.nanoTime();
                        Long before = lastPlacedRight.put(id, now);
                        lastRight.put(id, now);
                        if (before == null || now - before > PLACED_RIGHT_NANOS) {
                            runOnServer(() -> {
                                if (viewing(player)) return;
                                PlacedScreen ps = aimed(player);
                                if (ps != null) ps.rightClick();
                            });
                        }
                    }
                    super.channelRead(ctx, msg);
                }
            });
        });
    }

    /** Every tick: drop screens the player left behind, and aim at the nearest picture in their line of sight. */
    private void tickPlaced() {
        for (Map.Entry<UUID, List<PlacedScreen>> e : placed.entrySet()) {
            Player p = Bukkit.getPlayer(e.getKey());
            if (p == null) continue;
            boolean fullscreen = viewing(p);
            PlacedScreen best = null;
            PlacedScreen.Aim bestAim = null;
            for (PlacedScreen ps : e.getValue()) {
                if (ps.outOfRange(placedMaxDistance)) {
                    closePlaced(ps, false);
                    continue;
                }
                if (fullscreen) continue;
                PlacedScreen.Aim a = ps.aim(placedReach);
                if (a != null && (bestAim == null || a.t() < bestAim.t())) {
                    best = ps;
                    bestAim = a;
                }
            }
            for (PlacedScreen ps : e.getValue()) {
                try {
                    ps.tick(ps == best ? bestAim : null);
                } catch (Throwable t) {
                    plugin.getLogger().warning("Placed screen for " + p.getName() + " failed: " + t);
                    closePlaced(ps, false);
                }
            }
        }
    }

    /** Reads the player's head turns off their connection as they arrive, ahead of the server thread. */
    private void listen(Player player, ScreenView v) {
        io.netty.channel.Channel ch = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().connection.connection.channel;
        ch.eventLoop().execute(() -> {
            if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
            if (ch.pipeline().get("packet_handler") == null) return;
            ch.pipeline().addBefore("packet_handler", HANDLER, new io.netty.channel.ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (isRightClick(msg)) {
                        lastRight.put(player.getUniqueId(), System.nanoTime());
                        runOnServer(() -> {
                            if (views.get(player.getUniqueId()) == v) v.rightClick();
                        });
                    }
                    if (msg instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket mp) {
                        if (mp.hasRotation()) {
                            try {
                                v.look(mp.getYRot(0f), mp.getXRot(0f));
                            } catch (Throwable ignored) {
                                // never break the player's connection over the pointer
                            }
                        }
                        // the player is held still: their turns move the pointer, and nobody else sees the head move
                        if (views.get(player.getUniqueId()) == v) return;
                    }
                    super.channelRead(ctx, msg);
                }
            });
        });
    }

    /**
     * A right click, main hand: on a frame (interact), on a block, or on nothing (use item: the viewer holds the
     * invisible pointer item, so even an empty-handed click sends one).
     */
    private static boolean isRightClick(Object msg) {
        var main = net.minecraft.world.InteractionHand.MAIN_HAND;
        if (msg instanceof net.minecraft.network.protocol.game.ServerboundUseItemPacket p) return p.hand() == main;
        if (msg instanceof net.minecraft.network.protocol.game.ServerboundUseItemOnPacket p) return p.hand() == main;
        if (msg instanceof net.minecraft.network.protocol.game.ServerboundInteractPacket p) return p.hand() == main;
        return false;
    }

    private void unlisten(Player player) {
        unlisten(player, HANDLER);
    }

    private void unlisten(Player player, String handler) {
        try {
            io.netty.channel.Channel ch = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().connection.connection.channel;
            ch.eventLoop().execute(() -> {
                if (ch.pipeline().get(handler) != null) ch.pipeline().remove(handler);
            });
        } catch (Throwable ignored) {
            // connection already gone
        }
    }

    /** Closes [player]'s screen, whatever it is. [quiet]: they are leaving, send them nothing. */
    public void close(Player player, boolean quiet) {
        ScreenView v = views.remove(player.getUniqueId());
        jumpHeld.remove(player.getUniqueId());
        sneakHeld.remove(player.getUniqueId());
        lastRight.remove(player.getUniqueId());
        if (v == null) return;
        unlisten(player);
        if (!quiet) hold(player, false);
        restoreFlight(player);
        // the hotbar showed pointer items (packets only): send the real inventory back
        if (!quiet) player.updateInventory();
        v.dispose(quiet);
    }

    /** Re-renders [player]'s screen on the next tick (its data changed, a face loaded). */
    public void refreshAll() {
        for (ScreenView v : views.values()) v.screen().markDirty();
    }

    public void refresh(Player player) {
        ScreenView v = views.get(player.getUniqueId());
        if (v != null) {
            v.screen().markDirty();
        }
    }

    private void tick() {
        for (ScreenView v : views.values().toArray(new ScreenView[0])) {
            try {
                v.tick();
            } catch (Throwable t) {
                plugin.getLogger().warning("Screen for " + v.player().getName() + " failed: " + t);
                close(v.player(), false);
            }
        }
        tickPlaced();
    }

    /** Stops the player walking, jumping and falling while a screen is open (transient: never saved). */
    private void hold(Player p, boolean on) {
        for (Attribute a : HOLD) {
            AttributeInstance inst = p.getAttribute(a);
            if (inst == null) continue;
            inst.removeModifier(holdKey);
            if (on) {
                inst.addTransientModifier(new AttributeModifier(holdKey, -1.0,
                        AttributeModifier.Operation.MULTIPLY_SCALAR_1, EquipmentSlotGroup.ANY));
            }
        }
        if (on) {
            mayFly.putIfAbsent(p.getUniqueId(), p.getAllowFlight());
            p.setFlying(false);
            p.setAllowFlight(false);
        }
    }

    /** Gives back creative or plugin flight (saved with the player, so also when they leave mid-screen). */
    private void restoreFlight(Player p) {
        Boolean could = mayFly.remove(p.getUniqueId());
        if (could != null) p.setAllowFlight(could);
    }

    private ScreenView view(Player p) {
        return views.get(p.getUniqueId());
    }

    // ---- input --------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSwing(PlayerAnimationEvent e) {
        if (e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        ScreenView v = view(e.getPlayer());
        PlacedScreen ps = v == null ? aimed(e.getPlayer()) : null;
        if (v == null && ps == null) return;
        e.setCancelled(true);
        Long right = lastRight.get(e.getPlayer().getUniqueId());
        if (right != null && System.nanoTime() - right < RIGHT_SWING_NANOS) return;
        if (v != null) v.click();
        else ps.click();
    }

    /** A screen is open, or the player aims at a placed one: what is behind it is not broken, hit or used. */
    private boolean busy(Player p) {
        return viewing(p) || aimed(p) != null;
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onBreak(org.bukkit.event.block.BlockBreakEvent e) {
        if (aimed(e.getPlayer()) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onHit(org.bukkit.event.entity.EntityDamageByEntityEvent e) {
        if (e.getDamager() instanceof Player p && aimed(p) != null) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onFly(org.bukkit.event.player.PlayerToggleFlightEvent e) {
        if (viewing(e.getPlayer()) && e.isFlying()) e.setCancelled(true);
    }

    /** The hotbar holds pointer items that exist only on the client: no inventory changes while a screen is open. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onInventory(org.bukkit.event.inventory.InventoryClickEvent e) {
        if (e.getWhoClicked() instanceof Player p && viewing(p)) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onCreativeSlot(org.bukkit.event.inventory.InventoryCreativeEvent e) {
        if (e.getWhoClicked() instanceof Player p && viewing(p)) e.setCancelled(true);
    }

    /** A chat line while a screen is open goes to the screen (search boxes), not to chat. */
    @EventHandler(priority = EventPriority.LOWEST)
    public void onChat(io.papermc.paper.event.player.AsyncChatEvent e) {
        ScreenView v = view(e.getPlayer());
        if (v == null) return;
        e.setCancelled(true);
        String line = net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer.plainText().serialize(e.message());
        runOnServer(() -> {
            if (views.get(e.getPlayer().getUniqueId()) == v) v.text(line);
        });
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (busy(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler
    public void onInput(PlayerInputEvent e) {
        ScreenView v = view(e.getPlayer());
        if (v == null) return;
        boolean jump = e.getInput().isJump();
        Boolean was = jumpHeld.put(e.getPlayer().getUniqueId(), jump);
        if (jump && !Boolean.TRUE.equals(was)) v.key("jump");
        // seated, a sneak arrives as input (the client stays on the seat: only the server ever dismounts)
        boolean sneak = e.getInput().isSneak();
        Boolean wasSneak = sneakHeld.put(e.getPlayer().getUniqueId(), sneak);
        if (sneak && !Boolean.TRUE.equals(wasSneak)) {
            if (v.screen().mayClose()) close(e.getPlayer(), false);
            else v.screen().refusedClose();
        }
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSneak(PlayerToggleSneakEvent e) {
        // closing is handled from the input (onInput); here only keep the player from crouching
        if (viewing(e.getPlayer()) && e.isSneaking()) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onSwap(PlayerSwapHandItemsEvent e) {
        if (viewing(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrop(PlayerDropItemEvent e) {
        if (viewing(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onMove(PlayerMoveEvent e) {
        ScreenView v = view(e.getPlayer());
        if (v == null) return;
        Location from = e.getFrom(), to = e.getTo();
        if (from.getX() != to.getX() || from.getY() != to.getY() || from.getZ() != to.getZ()) {
            Location keep = from.clone();
            keep.setYaw(to.getYaw());
            keep.setPitch(to.getPitch());
            e.setTo(keep);
        }
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onTeleport(PlayerTeleportEvent e) {
        ScreenView tv = view(e.getPlayer());
        if (tv == null || tv.selfMove) return;
        Location f = e.getFrom(), t = e.getTo();
        if (f.getWorld() != t.getWorld() || f.distanceSquared(t) > 0.01) close(e.getPlayer(), false);
    }

    @EventHandler
    public void onWorld(PlayerChangedWorldEvent e) {
        close(e.getPlayer(), false);
        closePlaced(e.getPlayer());
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDamage(EntityDamageEvent e) {
        if (e.getEntity() instanceof Player p && viewing(p)) e.setCancelled(true);
    }

    @EventHandler
    public void onDeath(PlayerDeathEvent e) {
        close(e.getPlayer(), false);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        close(e.getPlayer(), true);
        for (PlacedScreen ps : placed(e.getPlayer())) closePlaced(ps, true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onJoin(PlayerJoinEvent e) {
        hold(e.getPlayer(), false);
        // Left while a screen was open: still on the spot the pack's shaders look for, which hides the crosshair.
        Location l = e.getPlayer().getLocation();
        if (onSpot(l.getX()) && onSpot(l.getZ())) {
            l.setX(l.getX() + 0.01);
            l.setZ(l.getZ() + 0.01);
            e.getPlayer().teleport(l);
        }
    }

    /** A plugin that made screens is going away: close the screens whose classes it loaded. */
    @EventHandler
    public void onPluginDisable(org.bukkit.event.server.PluginDisableEvent e) {
        ClassLoader owner = e.getPlugin().getClass().getClassLoader();
        for (ScreenView v : views.values().toArray(new ScreenView[0])) {
            if (v.screen().getClass().getClassLoader() == owner) close(v.player(), false);
        }
        for (List<PlacedScreen> list : placed.values()) {
            for (PlacedScreen ps : list) {
                if (ps.screen().getClass().getClassLoader() == owner) closePlaced(ps, false);
            }
        }
    }

    private static boolean onSpot(double v) {
        double f = v - Math.floor(v);
        return Math.abs(f - ScreenPack.FRACTION) < 0.001 || Math.abs(f - (1 - ScreenPack.FRACTION)) < 0.001;
    }
}
