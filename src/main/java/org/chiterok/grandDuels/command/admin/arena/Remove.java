package org.chiterok.grandDuels.command.admin.arena;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.Arena;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.List;

/** {@code /duels arena remove <group> <arena>} - removes a sub-arena from a group. */
public final class Remove implements SubCommand {

    private final ClaudeDuels plugin;

    public Remove(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "remove";
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
        Arena arena = plugin.arenas().arena(args[0], args[1]);
        if (arena == null) {
            plugin.messages().send(sender, "arena.not-found", "arena", args[0] + "/" + args[1]);
            return;
        }
        if (!plugin.arenas().removeArena(arena.group().id(), arena.name())) {
            plugin.messages().send(sender, "arena.in-use", "arena", arena.id());
            return;
        }
        plugin.messages().send(sender, "arena.removed", "group", arena.group().id(), "arena", arena.name());
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return ArenaTabComplete.groupThenArena(plugin, args);
    }
}
