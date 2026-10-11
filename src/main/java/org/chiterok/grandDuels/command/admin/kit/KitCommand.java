package org.chiterok.grandDuels.command.admin.kit;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.List;

/** {@code /duels kit <create [name]|edit [name]|save [name]|delete [name]>} (plus {@code cancel}). */
public final class KitCommand implements SubCommand {

    private final GrandDuels plugin;
    private final CommandRegistry registry;

    public KitCommand(GrandDuels plugin) {
        this.plugin = plugin;
        this.registry = new CommandRegistry(plugin)
                .register(new Create(plugin))
                .register(new Edit(plugin))
                .register(new Save(plugin))
                .register(new Delete(plugin))
                .register(new Cancel(plugin));
    }

    @Override
    public String name() {
        return "kit";
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (!registry.execute(sender, args)) plugin.messages().send(sender, "kits.usage");
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return registry.complete(sender, args);
    }
}
