package com.swiftality.screens;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Learns how far a player's head turns for one step of their mouse, from the turns their client sends. Minecraft
 * turns the head by a whole number of mouse steps times a step that depends only on the sensitivity setting
 * ({@code 1.2 * (0.6 s + 0.2)^3} degrees), so every turn is a multiple of it: their greatest common divisor, taken
 * over enough turns, is the step. The server cannot read the setting itself.
 */
public final class MouseSteps {
    /** The step at the game's default sensitivity (100%). */
    public static final double DEFAULT_STEP = 0.15;
    /** The step at 0% sensitivity: anything smaller is rounding noise, not a step. */
    private static final double MIN_STEP = 0.0095;
    private static final double TOLERANCE = 0.0015;
    private static final int NEEDED = 24;

    private final Plugin plugin;
    private final NamespacedKey key;
    private final Map<UUID, Learning> learning = new ConcurrentHashMap<>();
    private final Map<UUID, Double> known = new ConcurrentHashMap<>();

    private static final class Learning {
        double gcd;
        int samples;
    }

    MouseSteps(Plugin plugin) {
        this.plugin = plugin;
        this.key = new NamespacedKey(plugin, "mouse_step");
    }

    /** The player's step in degrees: learnt, remembered on the player, or the default. */
    public double step(Player p) {
        Double k = known.get(p.getUniqueId());
        if (k != null) return k;
        Double saved = p.getPersistentDataContainer().get(key, PersistentDataType.DOUBLE);
        if (saved != null) {
            known.put(p.getUniqueId(), saved);
            return saved;
        }
        return DEFAULT_STEP;
    }

    /** One turn from the network thread. When a step is learnt (or changes), the view is told. */
    void sample(UUID id, float dYaw, float dPitch, ScreenView view) {
        Learning l = learning.computeIfAbsent(id, k -> new Learning());
        for (double d : new double[]{Math.abs(wrap(dYaw)), Math.abs(dPitch)}) {
            if (d < MIN_STEP * 0.5 || d > 90) continue;
            double g = l.gcd == 0 ? d : gcd(l.gcd, d);
            if (g < MIN_STEP) {
                // a turn that is no multiple of what we had (smooth camera, a server turn): start again
                l.gcd = d;
                l.samples = 1;
                continue;
            }
            l.gcd = g;
            l.samples++;
        }
        if (l.samples < NEEDED) return;
        double step = l.gcd;
        Double old = known.get(id);
        if (old != null && Math.abs(old - step) < TOLERANCE) return;
        known.put(id, step);
        Bukkit.getScheduler().runTask(plugin, () -> {
            Player p = Bukkit.getPlayer(id);
            if (p == null) return;
            p.getPersistentDataContainer().set(key, PersistentDataType.DOUBLE, step);
            view.speedChanged();
        });
    }

    void forget(UUID id) {
        learning.remove(id);
        known.remove(id);
    }

    private static double gcd(double a, double b) {
        if (a < b) {
            double t = a;
            a = b;
            b = t;
        }
        while (b > TOLERANCE) {
            double r = a % b;
            if (r > b - TOLERANCE) r = 0;
            a = b;
            b = r;
        }
        return a;
    }

    private static float wrap(float a) {
        a %= 360f;
        if (a > 180f) a -= 360f;
        if (a < -180f) a += 360f;
        return a;
    }
}
