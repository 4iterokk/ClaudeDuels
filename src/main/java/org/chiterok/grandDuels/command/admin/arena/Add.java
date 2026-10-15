package org.chiterok.grandDuels.command.admin.arena;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaGroup;
import org.chiterok.grandDuels.arena.ArenaManager;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.List;
import java.util.Locale;

/** {@code /duels arena add <group> <arena>} - adds a new sub-arena to a group. */
public final class Add implements SubCommand {

    private final ClaudeDuels plugin;

    public Add(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "add";
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length != 2) {
            plugin.messages().send(sender, "arena.usage");
            return;
        }
        ArenaGroup group = plugin.arenas().group(args[0]);
        if (group == null) {
            plugin.messages().send(sender, "arena.group-not-found", "group", args[0]);
            return;
        }
        if (!ArenaManager.isValidName(args[1])) {
            plugin.messages().send(sender, "general.invalid-name");
            return;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (plugin.arenas().addArena(group.id(), name) == null) {
            plugin.messages().send(sender, "arena.exists", "group", group.id(), "arena", name);
            return;
        }
        plugin.messages().send(sender, "arena.added", "group", group.id(), "arena", name);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.arenas().groupNames(), args[0]) : List.of();
    }
}
