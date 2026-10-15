package org.chiterok.grandDuels.command.admin.arena;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.Arena;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.List;

/** {@code /duels arena delete <name>} */
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
    public List<String> aliases() {
        return List.of("remove");
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length != 1) {
            plugin.messages().send(sender, "arena.usage");
            return;
        }
        Arena arena = plugin.arenas().get(args[0]);
        if (arena == null) {
            plugin.messages().send(sender, "arena.not-found", "arena", args[0]);
            return;
        }
        if (!plugin.arenas().delete(args[0])) {
            plugin.messages().send(sender, "arena.in-use", "arena", arena.name());
            return;
        }
        plugin.messages().send(sender, "arena.deleted", "arena", arena.name());
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.arenas().names(), args[0]) : List.of();
    }
}
