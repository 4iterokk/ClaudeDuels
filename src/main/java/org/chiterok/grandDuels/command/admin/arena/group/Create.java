package org.chiterok.grandDuels.command.admin.arena.group;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaManager;
import org.chiterok.grandDuels.command.SubCommand;

/** {@code /duels arena group create <name>} */
public final class Create implements SubCommand {

    private final ClaudeDuels plugin;

    public Create(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "create";
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length != 1) {
            plugin.messages().send(sender, "arena.group-usage");
            return;
        }
        if (!ArenaManager.isValidName(args[0])) {
            plugin.messages().send(sender, "general.invalid-name");
            return;
        }
        if (plugin.arenas().createGroup(args[0]) == null) {
            plugin.messages().send(sender, "arena.group-exists", "group", args[0]);
            return;
        }
        plugin.messages().send(sender, "arena.group-created", "group", args[0].toLowerCase(java.util.Locale.ROOT));
    }
}
