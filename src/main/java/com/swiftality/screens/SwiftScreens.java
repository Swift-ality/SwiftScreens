package com.swiftality.screens;

import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

/**
 * Fullscreen clickable screens for vanilla clients. Other plugins depend on this one and call
 * {@code SwiftScreens.screens().open(player, new MyScreen())}. One server runs one copy, because the resource pack,
 * its web server and the network handler are shared by every screen.
 */
public final class SwiftScreens extends JavaPlugin {
    private static ScreenManager screens;
    private static Faces faces;
    private ScreenPack pack;

    /** Players' faces for screens (cached, loaded in the background). */
    public static Faces faces() {
        if (faces == null) throw new IllegalStateException("SwiftScreens is not enabled");
        return faces;
    }

    /** The screen manager. Call it from your plugin's onEnable or later (declare {@code depend: [SwiftScreens]}). */
    public static ScreenManager screens() {
        if (screens == null) throw new IllegalStateException("SwiftScreens is not enabled");
        return screens;
    }

    @Override
    public void onEnable() {
        saveDefaultConfig();
        pack = new ScreenPack(this);
        pack.start();
        Bukkit.getPluginManager().registerEvents(pack, this);
        ScreenManager m = new ScreenManager(this);
        m.pack(pack);
        applyConfig(m);
        m.start();
        screens = m;
        faces = new Faces(this, m::refreshAll);
        getLifecycleManager().registerEventHandler(LifecycleEvents.COMMANDS, e ->
                e.registrar().register("screens", "Menu pointer speed; reload the menu settings.", List.of(),
                        new ScreensCommand(this)));
    }

    @Override
    public void onDisable() {
        if (screens != null) screens.stop();
        screens = null;
        faces = null;
        if (pack != null) pack.stop();
    }

    void reload() {
        reloadConfig();
        applyConfig(screens);
    }

    private void applyConfig(ScreenManager m) {
        m.desktopWidth(getConfig().getDouble("screen.desktop-width", 2400.0));
        m.pointerSpeed(getConfig().getDouble("screen.pointer-speed", 22.0));
        Gfx.solidBackground = getConfig().getBoolean("screen.background", false);
        m.lockView(getConfig().getBoolean("screen.lock-view", true));
        m.placedRanges(getConfig().getDouble("placed.distance", 6.0), getConfig().getDouble("placed.reach", 16.0),
                getConfig().getDouble("placed.max-distance", 48.0));
    }
}
