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
    private final Map<UUID, Boolean> jumpHeld = new HashMap<>(), sneakHeld = new HashMap<>();
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
        renderer.shutdownNow();
    }

    public void pointerSpeed(double pixelsPerDegree) {
        this.pixelsPerDegree = pixelsPerDegree;
    }

    private ScreenPack pack;
    private double desktopWidth = 2400.0;
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

    /** Reads the player's head turns off their connection as they arrive, ahead of the server thread. */
    private void listen(Player player, ScreenView v) {
        io.netty.channel.Channel ch = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().connection.connection.channel;
        ch.eventLoop().execute(() -> {
            if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
            if (ch.pipeline().get("packet_handler") == null) return;
            ch.pipeline().addBefore("packet_handler", HANDLER, new io.netty.channel.ChannelInboundHandlerAdapter() {
                @Override
                public void channelRead(io.netty.channel.ChannelHandlerContext ctx, Object msg) throws Exception {
                    if (msg instanceof net.minecraft.network.protocol.game.ServerboundMovePlayerPacket mp && mp.hasRotation()) {
                        try {
                            v.look(mp.getYRot(0f), mp.getXRot(0f));
                        } catch (Throwable ignored) {
                            // never break the player's connection over the pointer
                        }
                    }
                    super.channelRead(ctx, msg);
                }
            });
        });
    }

    private void unlisten(Player player) {
        try {
            io.netty.channel.Channel ch = ((org.bukkit.craftbukkit.entity.CraftPlayer) player).getHandle().connection.connection.channel;
            ch.eventLoop().execute(() -> {
                if (ch.pipeline().get(HANDLER) != null) ch.pipeline().remove(HANDLER);
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
        if (v == null) return;
        unlisten(player);
        if (!quiet) hold(player, false);
        v.dispose(quiet);
    }

    /** Re-renders [player]'s screen on the next tick (its data changed, a face loaded). */
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
        if (on && p.isFlying()) p.setFlying(false);
    }

    private ScreenView view(Player p) {
        return views.get(p.getUniqueId());
    }

    // ---- input --------------------------------------------------------------------------------------------------

    @EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)
    public void onSwing(PlayerAnimationEvent e) {
        ScreenView v = view(e.getPlayer());
        if (v == null || e.getAnimationType() != PlayerAnimationType.ARM_SWING) return;
        e.setCancelled(true);
        v.click();
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteract(PlayerInteractEvent e) {
        if (viewing(e.getPlayer())) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onInteractEntity(PlayerInteractEntityEvent e) {
        if (viewing(e.getPlayer())) e.setCancelled(true);
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
    }

    private static boolean onSpot(double v) {
        double f = v - Math.floor(v);
        return Math.abs(f - ScreenPack.FRACTION) < 0.001 || Math.abs(f - (1 - ScreenPack.FRACTION)) < 0.001;
    }
}
