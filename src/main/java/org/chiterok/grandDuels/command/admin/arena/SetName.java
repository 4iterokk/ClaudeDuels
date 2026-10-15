package org.chiterok.grandDuels.command.admin.arena;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.Arena;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.Arrays;
import java.util.List;

/**
 * {@code /duels arena setname <arena> [name...]} - gives the arena a custom name (color codes allowed) that players see
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
            plugin.messages().send(sender, "arena.usage");
            return;
        }
        Arena arena = plugin.arenas().get(args[0]);
        if (arena == null) {
            plugin.messages().send(sender, "arena.not-found", "arena", args[0]);
            return;
        }
        String display = args.length == 1 ? null : String.join(" ", Arrays.copyOfRange(args, 1, args.length)).trim();
        if (display != null && display.length() > MAX_LENGTH) {
            plugin.messages().send(sender, "arena.name-too-long", "max", MAX_LENGTH);
            return;
        }
        plugin.arenas().setDisplayName(arena.name(), display);
        if (display == null || display.isEmpty()) {
            plugin.messages().send(sender, "arena.name-reset", "arena", arena.name());
        } else {
            plugin.messages().send(sender, "arena.name-set", "arena", arena.name(), "name", display);
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.arenas().names(), args[0]) : List.of();
    }
}
