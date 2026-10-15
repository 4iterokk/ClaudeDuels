package org.chiterok.grandDuels.command.admin.arena;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.Arena;
import org.chiterok.grandDuels.arena.ArenaState;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.utils.StoredLocation;

import java.util.List;

/** {@code /duels arena pos1|setspawn1 <name>} - sets the first spawn point to the executor's location. */
public final class PosOne implements SubCommand {

    private final ClaudeDuels plugin;

    public PosOne(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "pos1";
    }

    @Override
    public List<String> aliases() {
        return List.of("setspawn1");
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
        if (args.length != 1) {
            plugin.messages().send(sender, "arena.usage");
            return;
        }
        Arena arena = plugin.arenas().get(args[0]);
        if (arena == null) {
            plugin.messages().send(sender, "arena.not-found", "arena", args[0]);
            return;
        }
        if (arena.state() != ArenaState.WAITING) {
            plugin.messages().send(sender, "arena.in-use", "arena", arena.name());
            return;
        }
        StoredLocation location = StoredLocation.of(((Player) sender).getLocation());
        StoredLocation other = arena.pos2();
        if (other != null && !other.world().equals(location.world())) {
            plugin.messages().send(sender, "arena.world-mismatch");
            return;
        }
        arena.setPos1(location);
        plugin.arenas().save();
        plugin.messages().send(sender, "arena.pos1-set", "arena", arena.name(), "location", location.pretty());
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.arenas().names(), args[0]) : List.of();
    }
}
