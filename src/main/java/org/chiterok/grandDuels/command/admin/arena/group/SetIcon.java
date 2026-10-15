package org.chiterok.grandDuels.command.admin.arena.group;

import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaGroup;
import org.chiterok.grandDuels.arena.ArenaManager;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /duels arena group seticon <group> [material|reset]} - sets the item that represents the group in the arena
 * selector. Without a material the item in the admin's hand is used; {@code reset} restores the menu's default icons.
 */
public final class SetIcon implements SubCommand {

    private static final String RESET = "reset";

    private final ClaudeDuels plugin;

    public SetIcon(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "seticon";
    }

    @Override
    public List<String> aliases() {
        return List.of("icon");
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length < 1) {
            plugin.messages().send(sender, "arena.group-usage");
            return;
        }
        ArenaGroup group = plugin.arenas().group(args[0]);
        if (group == null) {
            plugin.messages().send(sender, "arena.group-not-found", "group", args[0]);
            return;
        }

        if (args.length >= 2 && args[1].equalsIgnoreCase(RESET)) {
            plugin.arenas().setDisplayMaterial(group.id(), null);
            plugin.messages().send(sender, "arena.group-icon-reset", "group", group.id());
            return;
        }

        Material material;
        if (args.length >= 2) {
            material = ArenaManager.parseIcon(args[1]);
            if (material == null) {
                plugin.messages().send(sender, "arena.icon-invalid", "material", args[1]);
                return;
            }
        } else if (sender instanceof Player player) {
            material = ArenaManager.parseIcon(player.getInventory().getItemInMainHand().getType().name());
            if (material == null) {
                plugin.messages().send(sender, "arena.icon-hold-item");
                return;
            }
        } else {
            plugin.messages().send(sender, "arena.group-usage");
            return;
        }
        plugin.arenas().setDisplayMaterial(group.id(), material);
        plugin.messages().send(sender, "arena.group-icon-set", "group", group.id(),
                "material", material.name().toLowerCase(Locale.ROOT));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return CommandRegistry.filter(plugin.arenas().groupNames(), args[0]);
        if (args.length != 2) return List.of();
        List<String> options = new ArrayList<>();
        options.add(RESET);
        String prefix = args[1].toLowerCase(Locale.ROOT);
        if (prefix.length() >= 2) {
            for (Material material : Material.values()) {
                if (material.isItem() && !material.isAir() && material.name().toLowerCase(Locale.ROOT).startsWith(prefix)) {
                    options.add(material.name().toLowerCase(Locale.ROOT));
                    if (options.size() >= 40) break;
                }
            }
        }
        return CommandRegistry.filter(options, args[1]);
    }
}
