package org.chiterok.grandDuels.command.admin.arena.group;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.arena.ArenaGroup;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.List;

/** {@code /duels arena group toggle <group>} - switches a whole group on/off (maintenance). */
public final class Toggle implements SubCommand {

    private final ClaudeDuels plugin;

    public Toggle(ClaudeDuels plugin) {
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
        if (args.length != 1) {
            plugin.messages().send(sender, "arena.group-usage");
            return;
        }
        ArenaGroup group = plugin.arenas().group(args[0]);
        Boolean enabled = group == null ? null : plugin.arenas().toggleGroup(group.id());
        if (enabled == null) {
            plugin.messages().send(sender, "arena.group-not-found", "group", args[0]);
            return;
        }
        plugin.messages().send(sender, enabled ? "arena.group-enabled" : "arena.group-disabled", "group", group.id());
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.arenas().groupNames(), args[0]) : List.of();
    }
}
