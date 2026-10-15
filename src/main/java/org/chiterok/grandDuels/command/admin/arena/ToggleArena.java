package org.chiterok.grandDuels.command.admin.arena;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.Arena;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.List;

/**
 * {@code /duels arena toggle <group> <arena>} - switches one sub-arena on/off (maintenance). A running duel there
 * finishes normally; the arena just is not picked for new ones while it is off.
 */
public final class ToggleArena implements SubCommand {

    private final ClaudeDuels plugin;

    public ToggleArena(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "toggle";
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
        Boolean enabled = arena == null ? null : plugin.arenas().toggleArena(arena.group().id(), arena.name());
        if (enabled == null) {
            plugin.messages().send(sender, "arena.not-found", "arena", args[0] + "/" + args[1]);
            return;
        }
        plugin.messages().send(sender, enabled ? "arena.arena-enabled" : "arena.arena-disabled",
                "group", arena.group().id(), "arena", arena.name());
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return ArenaTabComplete.groupThenArena(plugin, args);
    }
}
