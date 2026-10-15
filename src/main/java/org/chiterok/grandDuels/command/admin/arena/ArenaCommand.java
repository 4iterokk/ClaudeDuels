package org.chiterok.grandDuels.command.admin.arena;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.List;

/** {@code /duels arena <create|delete|pos1|pos2|setname|seticon|kits> ...} */
public final class ArenaCommand implements SubCommand {

    private final ClaudeDuels plugin;
    private final CommandRegistry registry;

    public ArenaCommand(ClaudeDuels plugin) {
        this.plugin = plugin;
        this.registry = new CommandRegistry(plugin)
                .register(new Create(plugin))
                .register(new Delete(plugin))
                .register(new PosOne(plugin))
                .register(new PosTwo(plugin))
                .register(new SetName(plugin))
                .register(new SetIcon(plugin))
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
