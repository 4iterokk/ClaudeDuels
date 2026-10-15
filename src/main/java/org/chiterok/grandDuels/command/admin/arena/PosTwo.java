package org.chiterok.grandDuels.command.admin.arena;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.Arena;
import org.chiterok.grandDuels.arena.ArenaState;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.utils.StoredLocation;

import java.util.List;

/** {@code /duels arena pos2|setspawn2 <group> <arena>} - sets the second spawn point to the executor's location. */
public final class PosTwo implements SubCommand {

    private final ClaudeDuels plugin;

    public PosTwo(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "pos2";
    }

    @Override
    public List<String> aliases() {
        return List.of("setspawn2");
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public boolean playerOnly() {
        return true;
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
        if (arena.state() != ArenaState.WAITING) {
            plugin.messages().send(sender, "arena.in-use", "arena", arena.id());
            return;
        }
        StoredLocation location = StoredLocation.of(((Player) sender).getLocation());
        StoredLocation other = arena.pos1();
        if (other != null && !other.world().equals(location.world())) {
            plugin.messages().send(sender, "arena.world-mismatch");
            return;
        }
        arena.setPos2(location);
        plugin.arenas().save();
        plugin.messages().send(sender, "arena.pos2-set", "arena", arena.id(), "location", location.pretty());
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return ArenaTabComplete.groupThenArena(plugin, args);
    }
}
