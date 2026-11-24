package com.swiftality.screens;

import io.papermc.paper.command.brigadier.BasicCommand;
import io.papermc.paper.command.brigadier.CommandSourceStack;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Collection;
import java.util.List;

/** /screens pointer [speed], /screens reload, /screens demo (a placed screen to try). */
final class ScreensCommand implements BasicCommand {
    private final SwiftScreens plugin;

    ScreensCommand(SwiftScreens plugin) {
        this.plugin = plugin;
    }

    @Override
    public void execute(CommandSourceStack stack, String[] args) {
        CommandSender s = stack.getSender();
        String sub = args.length == 0 ? "" : args[0].toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "pointer" -> {
                if (!(s instanceof Player p)) return;
                ScreenManager screens = SwiftScreens.screens();
                if (args.length < 2) {
                    s.sendMessage(Component.text("Pointer speed: " + screens.speed(p) + " (0.25 to 4, 1 is normal). /screens pointer <speed>", NamedTextColor.GRAY));
                    return;
                }
                try {
                    double v = Math.max(0.25, Math.min(4.0, Double.parseDouble(args[1])));
                    screens.speed(p, v);
                    s.sendMessage(Component.text("Pointer speed set to " + v + ". It applies the next time a menu opens.", NamedTextColor.GREEN));
                } catch (NumberFormatException e) {
                    s.sendMessage(Component.text("Give a number, like /screens pointer 1.5", NamedTextColor.RED));
                }
            }
            case "reload" -> {
                if (!s.hasPermission("swiftscreens.admin")) {
                    s.sendMessage(Component.text("You may not do that.", NamedTextColor.RED));
                    return;
                }
                plugin.reload();
                s.sendMessage(Component.text("SwiftScreens settings reloaded (the resource pack mode needs a restart).", NamedTextColor.GREEN));
            }
            case "demo" -> {
                if (!(s instanceof Player p)) return;
                if (!s.hasPermission("swiftscreens.admin")) {
                    s.sendMessage(Component.text("You may not do that.", NamedTextColor.RED));
                    return;
                }
                ScreenManager screens = SwiftScreens.screens();
                if (args.length > 1 && args[1].equalsIgnoreCase("clear")) {
                    screens.closePlaced(p);
                    return;
                }
                try {
                    screens.place(p, new DemoScreen());
                } catch (IllegalStateException e) {
                    s.sendMessage(Component.text(e.getMessage(), NamedTextColor.RED));
                }
            }
            default -> s.sendMessage(Component.text("/screens pointer [speed]" + (s.hasPermission("swiftscreens.admin") ? ", /screens reload, /screens demo [clear]" : ""), NamedTextColor.GRAY));
        }
    }

    @Override
    public Collection<String> suggest(CommandSourceStack stack, String[] args) {
        if (args.length <= 1) return stack.getSender().hasPermission("swiftscreens.admin") ? List.of("pointer", "reload", "demo") : List.of("pointer");
        if (args.length == 2 && args[0].equalsIgnoreCase("demo")) return List.of("clear");
        return List.of();
    }
}
