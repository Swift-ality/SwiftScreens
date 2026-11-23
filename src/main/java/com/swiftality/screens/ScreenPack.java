package com.swiftality.screens;

import com.sun.net.httpserver.HttpServer;
import io.papermc.paper.event.connection.configuration.AsyncPlayerConnectionConfigureEvent;
import net.kyori.adventure.resource.ResourcePackCallback;
import net.kyori.adventure.resource.ResourcePackInfo;
import net.kyori.adventure.resource.ResourcePackRequest;
import net.kyori.adventure.resource.ResourcePackStatus;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerResourcePackStatusEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/**
 * The screens' resource pack: core shaders that draw a screen fullscreen and move its pointer on the client, every
 * frame, from the player's own mouse. Built from the jar at start-up, served by a small HTTP server in the plugin,
 * and sent while the player is still connecting, so it is loaded before they are in the world.
 */
public final class ScreenPack implements Listener {
    /** The block fraction the camera is put on while a screen is open (x and z): the shaders' signal. */
    public static final double FRACTION = 82.0 / 256.0;
    private static final String[] FILES = {
            "assets/minecraft/shaders/core/text.vsh",
            "assets/minecraft/shaders/core/text.fsh",
            "assets/minecraft/shaders/core/position_tex_color.fsh",
    };
    private static final UUID PACK_ID = UUID.nameUUIDFromBytes("swiftscreens:screens".getBytes(StandardCharsets.UTF_8));

    private final JavaPlugin plugin;
    private final Map<UUID, Boolean> loaded = new ConcurrentHashMap<>();
    private byte[] zip;
    private final Map<String, byte[]> files = new java.util.LinkedHashMap<>();
    private String sha1;
    private HttpServer http;
    private URI uri;
    /** How the pack reaches players. */
    public enum Mode {
        /** The plugin serves the pack itself and sends it as players connect. */
        SERVE,
        /** The plugin sends a pack you host at resource-pack.url. */
        URL,
        /** Another plugin or the server's own pack carries the shaders (merged in from the export); nothing is sent. */
        EXTERNAL,
        /** No pack: every screen is drawn on maps in front of a locked camera. */
        OFF
    }

    private Mode mode = Mode.SERVE;
    private boolean required, assumeLoaded;
    private UUID externalId;
    private java.util.function.Consumer<Player> onLoaded = p -> {};

    public ScreenPack(JavaPlugin plugin) {
        this.plugin = plugin;
    }

    public Mode mode() {
        return mode;
    }

    /** Called (server thread) when a player's pack with the shaders finishes loading after they joined. */
    public void onLoaded(java.util.function.Consumer<Player> c) {
        this.onLoaded = c;
    }

    public boolean has(Player p) {
        if (mode == Mode.OFF) return false;
        if (mode == Mode.EXTERNAL && assumeLoaded) return true;
        return Boolean.TRUE.equals(loaded.get(p.getUniqueId()));
    }

    public void start() {
        var cfg = plugin.getConfig();
        Object raw = cfg.get("resource-pack.mode", cfg.getBoolean("resource-pack.enabled", true) ? "serve" : "off");
        // YAML reads a bare off / on / no / yes as a boolean
        String m = raw instanceof Boolean b ? (b ? "serve" : "off") : String.valueOf(raw);
        try {
            mode = Mode.valueOf(m.trim().toUpperCase(java.util.Locale.ROOT));
        } catch (IllegalArgumentException e) {
            plugin.getLogger().warning("resource-pack.mode '" + m + "' is not serve, url, external or off: using off.");
            mode = Mode.OFF;
        }
        required = cfg.getBoolean("resource-pack.required", false);
        assumeLoaded = "always".equalsIgnoreCase(cfg.getString("resource-pack.external.detect", "any-pack"));
        String id = cfg.getString("resource-pack.external.pack-id", "");
        externalId = null;
        if (id != null && !id.isBlank()) {
            try {
                externalId = UUID.fromString(id.trim());
            } catch (IllegalArgumentException e) {
                plugin.getLogger().warning("resource-pack.external.pack-id is not a UUID: " + id);
            }
        }
        try {
            build();
            export(cfg.getString("resource-pack.export.zip", "screens-pack.zip"),
                    cfg.getString("resource-pack.export.folder", ""),
                    cfg.getBoolean("resource-pack.export.include-mcmeta", true));
        } catch (Exception e) {
            plugin.getLogger().severe("Could not build the screens resource pack: " + e + ". Screens use map mode.");
            mode = Mode.OFF;
            return;
        }
        switch (mode) {
            case OFF -> plugin.getLogger().info("Screens resource pack: off (map screens only).");
            case EXTERNAL -> plugin.getLogger().info("Screens resource pack: external. Merge the exported shaders into "
                    + "your server pack; players count as having it " + (assumeLoaded ? "always." : "once a pack loads."));
            case URL -> {
                String url = cfg.getString("resource-pack.url", "");
                if (url == null || url.isBlank()) url = cfg.getString("resource-pack.public-url", "");
                if (url == null || url.isBlank()) {
                    plugin.getLogger().severe("resource-pack.mode is url but resource-pack.url is empty. Screens use map mode.");
                    mode = Mode.OFF;
                    return;
                }
                uri = URI.create(url.trim());
                plugin.getLogger().info("Screens resource pack: " + uri + " (sha1 " + sha1 + "; the file there must be this exact export)");
            }
            case SERVE -> serve(cfg);
        }
    }

    private void serve(org.bukkit.configuration.file.FileConfiguration cfg) {
        int port = cfg.getInt("resource-pack.port", 25608);
        try {
            http = HttpServer.create(new InetSocketAddress(cfg.getString("resource-pack.bind", "0.0.0.0"), port), 16);
            http.createContext("/", ex -> {
                try (ex) {
                    if (!ex.getRequestURI().getPath().endsWith(".zip")) {
                        ex.sendResponseHeaders(404, -1);
                        return;
                    }
                    ex.getResponseHeaders().add("Content-Type", "application/zip");
                    ex.sendResponseHeaders(200, zip.length);
                    try (OutputStream out = ex.getResponseBody()) {
                        out.write(zip);
                    }
                }
            });
            http.setExecutor(java.util.concurrent.Executors.newFixedThreadPool(2, r -> {
                Thread t = new Thread(r, "SwiftScreens pack server");
                t.setDaemon(true);
                return t;
            }));
            http.start();
        } catch (Exception e) {
            plugin.getLogger().severe("Could not serve the screens pack on port " + port + ": " + e + ". Screens use map mode.");
            mode = Mode.OFF;
            return;
        }
        String host = cfg.getString("resource-pack.host", "");
        if (host == null || host.isBlank()) host = Bukkit.getIp().isBlank() ? "127.0.0.1" : Bukkit.getIp();
        uri = URI.create("http://" + host + ":" + port + "/swiftscreens-" + sha1.substring(0, 12) + ".zip");
        plugin.getLogger().info("Screens resource pack: " + uri + " (sha1 " + sha1 + ")");
    }

    public void stop() {
        if (http != null) http.stop(0);
        http = null;
    }

    /** A path from the config: absolute, or relative to the plugin's folder. Blank: none. */
    private java.nio.file.Path path(String p) {
        if (p == null || p.isBlank()) return null;
        java.nio.file.Path path = java.nio.file.Path.of(p.trim());
        return path.isAbsolute() ? path : plugin.getDataFolder().toPath().resolve(path);
    }

    /**
     * Writes the pack where the config says: a zip (to host yourself or drop into a pack plugin) and/or an unpacked
     * folder (for pack plugins that merge folders: ItemsAdder, CraftEngine, Nexo, Oraxen, BetterHud). Only the
     * files are rewritten; nothing else in the folder is touched.
     */
    private void export(String zipPath, String folderPath, boolean mcmeta) throws Exception {
        java.nio.file.Path z = path(zipPath);
        if (z != null) {
            if (z.getParent() != null) java.nio.file.Files.createDirectories(z.getParent());
            java.nio.file.Files.write(z, zip);
            plugin.getLogger().info("Exported the screens pack to " + z);
        }
        java.nio.file.Path dir = path(folderPath);
        if (dir != null) {
            for (Map.Entry<String, byte[]> e : files.entrySet()) {
                if (!mcmeta && e.getKey().equals("pack.mcmeta")) continue;
                java.nio.file.Path f = dir.resolve(e.getKey());
                java.nio.file.Files.createDirectories(f.getParent() == null ? dir : f.getParent());
                java.nio.file.Files.write(f, e.getValue());
            }
            plugin.getLogger().info("Exported the screens pack files to " + dir);
        }
    }

    private void build() throws Exception {
        files.clear();
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (ZipOutputStream z = new ZipOutputStream(bytes)) {
            put(z, "pack.mcmeta", """
                    {
                      "pack": {
                        "description": "SwiftScreens menus",
                        "min_format": [97, 0],
                        "max_format": [97, 65535]
                      }
                    }
                    """);
            for (String f : FILES) {
                try (InputStream in = plugin.getResource("pack/" + f)) {
                    if (in == null) throw new IllegalStateException("missing pack/" + f);
                    String src = new String(in.readAllBytes(), StandardCharsets.UTF_8)
                            .replace("@COLS@", Integer.toString(Screen.COLS))
                            .replace("@ROWS@", Integer.toString(Screen.ROWS))
                            .replace("@FRACTION@", Double.toString(FRACTION));
                    put(z, f, src);
                }
            }
        }
        zip = bytes.toByteArray();
        sha1 = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(zip));
    }

    private void put(ZipOutputStream z, String name, String content) throws Exception {
        files.put(name, content.getBytes(StandardCharsets.UTF_8));
        ZipEntry e = new ZipEntry(name);
        e.setTime(0);
        z.putNextEntry(e);
        z.write(content.getBytes(StandardCharsets.UTF_8));
        z.closeEntry();
    }

    /** Sends the pack while the player connects and waits for it to load (this event runs off the server thread). */
    @EventHandler
    public void onConfigure(AsyncPlayerConnectionConfigureEvent e) {
        if (mode != Mode.SERVE && mode != Mode.URL) return;
        UUID player = e.getConnection().getProfile().getId();
        if (player == null) return;
        CompletableFuture<Boolean> done = new CompletableFuture<>();
        ResourcePackCallback cb = (id, status, audience) -> {
            if (status.intermediate()) return;
            done.complete(status == ResourcePackStatus.SUCCESSFULLY_LOADED);
        };
        ResourcePackRequest req = ResourcePackRequest.resourcePackRequest()
                .packs(ResourcePackInfo.resourcePackInfo(PACK_ID, uri, sha1))
                .required(required)
                .replace(false)
                .prompt(Component.text("This server uses this pack for its menus."))
                .callback(cb)
                .build();
        e.getConnection().getAudience().sendResourcePacks(req);
        try {
            loaded.put(player, done.get(30, TimeUnit.SECONDS));
        } catch (Exception ex) {
            loaded.put(player, false);
        }
    }

    /** A pack sent later (another plugin's, or a re-send) can also report ours. */
    @EventHandler
    public void onStatus(PlayerResourcePackStatusEvent e) {
        boolean ours = switch (mode) {
            case SERVE, URL -> PACK_ID.equals(e.getID());
            // the server's pack (or a pack plugin's) carries our shaders: that one, or any pack if no id is set
            case EXTERNAL -> externalId == null || externalId.equals(e.getID());
            case OFF -> false;
        };
        if (!ours) return;
        UUID id = e.getPlayer().getUniqueId();
        switch (e.getStatus()) {
            case SUCCESSFULLY_LOADED -> {
                boolean was = Boolean.TRUE.equals(loaded.put(id, true));
                if (!was) onLoaded.accept(e.getPlayer());
            }
            case DECLINED, FAILED_DOWNLOAD, FAILED_RELOAD, INVALID_URL, DISCARDED -> {
                if (mode != Mode.EXTERNAL || externalId != null) loaded.put(id, false);
            }
            default -> { }
        }
    }

    /**
     * External mode: a pack sent while the player connected (server.properties, a pack plugin) fires no event, but
     * the server keeps its last answer. Read it as they join, before the picker opens.
     */
    @EventHandler(priority = org.bukkit.event.EventPriority.LOWEST)
    public void onJoin(org.bukkit.event.player.PlayerJoinEvent e) {
        if (mode != Mode.EXTERNAL) return;
        if (e.getPlayer().getResourcePackStatus() == PlayerResourcePackStatusEvent.Status.SUCCESSFULLY_LOADED) {
            loaded.put(e.getPlayer().getUniqueId(), true);
        }
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        loaded.remove(e.getPlayer().getUniqueId());
    }
}
