package org.chiterok.grandDuels.command.admin.arena.group;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaGroup;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.Arrays;
import java.util.List;

/**
 * {@code /duels arena group setname <group> [name...]} - gives the group a custom name (color codes allowed) that players see
 * in the scoreboard and messages. Without a name the custom name is removed.
 */
public final class SetName implements SubCommand {

    private static final int MAX_LENGTH = 48;

    private final ClaudeDuels plugin;

    public SetName(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "setname";
    }

    @Override
    public List<String> aliases() {
        return List.of("rename", "name");
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
        String display = args.length == 1 ? null : String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (display != null && display.length() > MAX_LENGTH) {
            plugin.messages().send(sender, "arena.name-too-long", "max", MAX_LENGTH);
            return;
        }
        plugin.arenas().setDisplayName(group.id(), display);
        if (display == null || display.isEmpty()) {
            plugin.messages().send(sender, "arena.group-name-reset", "group", group.id());
        } else {
            plugin.messages().send(sender, "arena.group-name-set", "group", group.id(), "name", display);
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.arenas().groupNames(), args[0]) : List.of();
    }
}
