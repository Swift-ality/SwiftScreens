package com.swiftality.screens;

import com.destroystokyo.paper.profile.PlayerProfile;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.profile.PlayerTextures;

import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Players' faces (8x8, hat layer on top) for screens, online or not. {@link #face} answers from the cache on any
 * thread (draw() included) and starts loading a missing face; once it is in, every open screen redraws.
 */
public final class Faces {
    private final Plugin plugin;
    private final Runnable loaded;
    private final Map<UUID, BufferedImage> faces = new ConcurrentHashMap<>();
    /** Started or failed: never asked again this run (no request storms for offline-mode or skinless players). */
    private final Set<UUID> asked = ConcurrentHashMap.newKeySet();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8))
            .followRedirects(HttpClient.Redirect.NORMAL).build();

    Faces(Plugin plugin, Runnable loaded) {
        this.plugin = plugin;
        this.loaded = loaded;
    }

    /** The face, or null while it loads (or when there is none). Any thread. */
    public BufferedImage face(UUID player) {
        BufferedImage f = faces.get(player);
        if (f == null && asked.add(player)) Bukkit.getScheduler().runTask(plugin, () -> load(player));
        return f;
    }

    private void load(UUID id) {
        Player online = Bukkit.getPlayer(id);
        PlayerProfile profile = online != null ? online.getPlayerProfile() : Bukkit.createProfile(id);
        if (profile.getTextures().getSkin() != null) {
            Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> download(id, profile.getTextures()));
            return;
        }
        // not online: ask Mojang for the profile (Paper does it off the server thread)
        profile.update().thenAccept(p -> download(id, p.getTextures()));
    }

    private void download(UUID id, PlayerTextures textures) {
        URL skin = textures.getSkin();
        if (skin == null) return;
        try {
            HttpResponse<InputStream> r = http.send(HttpRequest.newBuilder(URI.create(skin.toString()))
                    .timeout(Duration.ofSeconds(8)).build(), HttpResponse.BodyHandlers.ofInputStream());
            BufferedImage img = ImageIO.read(r.body());
            if (img == null || img.getWidth() < 64) return;
            BufferedImage face = new BufferedImage(8, 8, BufferedImage.TYPE_INT_ARGB);
            for (int y = 0; y < 8; y++) {
                for (int x = 0; x < 8; x++) {
                    int base = img.getRGB(8 + x, 8 + y), hat = img.getRGB(40 + x, 8 + y);
                    face.setRGB(x, y, (hat >>> 24) > 128 ? hat : base | 0xFF000000);
                }
            }
            faces.put(id, face);
            Bukkit.getScheduler().runTask(plugin, loaded);
        } catch (Exception ignored) {
            // skin host down, timeouts: screens keep their plain stand-in
        }
    }
}
