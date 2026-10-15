package org.chiterok.grandDuels.command.admin.arena.group;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.List;

/** {@code /duels arena group <create|delete|setname|seticon|toggle> ...} */
public final class GroupCommand implements SubCommand {

    private final ClaudeDuels plugin;
    private final CommandRegistry registry;

    public GroupCommand(ClaudeDuels plugin) {
        this.plugin = plugin;
        this.registry = new CommandRegistry(plugin)
                .register(new Create(plugin))
                .register(new Delete(plugin))
                .register(new SetName(plugin))
                .register(new SetIcon(plugin))
                .register(new Toggle(plugin));
    }

    @Override
    public String name() {
        return "group";
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!registry.execute(sender, args)) plugin.messages().send(sender, "arena.group-usage");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return registry.complete(sender, args);
    }
}
