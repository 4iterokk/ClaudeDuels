package org.chiterok.grandDuels.command.admin.arena;

import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaGroup;
import org.chiterok.grandDuels.command.CommandRegistry;

import java.util.List;

/** Tab completion shared by the {@code <group> <arena>} sub-commands. */
final class ArenaTabComplete {

    private ArenaTabComplete() {
    }

    static List<String> groupThenArena(ClaudeDuels plugin, String[] args) {
        if (args.length == 1) return CommandRegistry.filter(plugin.arenas().groupNames(), args[0]);
        if (args.length == 2) {
            ArenaGroup group = plugin.arenas().group(args[0]);
            return group == null ? List.of() : CommandRegistry.filter(group.arenaNames(), args[1]);
        }
        return List.of();
    }
}
