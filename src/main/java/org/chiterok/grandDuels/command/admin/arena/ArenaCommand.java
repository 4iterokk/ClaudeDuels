package org.chiterok.grandDuels.command.admin.arena;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.command.admin.arena.group.GroupCommand;

import java.util.List;

/** {@code /duels arena <group|add|remove|pos1|pos2|toggle|kits> ...} */
public final class ArenaCommand implements SubCommand {

    private final ClaudeDuels plugin;
    private final CommandRegistry registry;

    public ArenaCommand(ClaudeDuels plugin) {
        this.plugin = plugin;
        this.registry = new CommandRegistry(plugin)
                .register(new GroupCommand(plugin))
                .register(new Add(plugin))
                .register(new Remove(plugin))
                .register(new PosOne(plugin))
                .register(new PosTwo(plugin))
                .register(new ToggleArena(plugin))
                .register(new Kits(plugin));
    }

    @Override
    public String name() {
        return "arena";
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!registry.execute(sender, args)) plugin.messages().send(sender, "arena.usage");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return registry.complete(sender, args);
    }
}
