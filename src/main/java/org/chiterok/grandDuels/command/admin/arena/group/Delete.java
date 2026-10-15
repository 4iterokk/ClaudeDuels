package org.chiterok.grandDuels.command.admin.arena.group;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaGroup;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.List;

/** {@code /duels arena group delete <group>} */
public final class Delete implements SubCommand {

    private final ClaudeDuels plugin;

    public Delete(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "delete";
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
        ArenaGroup group = plugin.arenas().group(args[0]);
        if (group == null) {
            plugin.messages().send(sender, "arena.group-not-found", "group", args[0]);
            return;
        }
        if (!plugin.arenas().deleteGroup(args[0])) {
            plugin.messages().send(sender, "arena.group-in-use", "group", group.id());
            return;
        }
        plugin.messages().send(sender, "arena.group-deleted", "group", group.id());
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.arenas().groupNames(), args[0]) : List.of();
    }
}
