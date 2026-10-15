package org.chiterok.grandDuels.command.admin.kit;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.kit.Kit;

import java.util.List;

/** {@code /duels kit delete <name>} */
public final class Delete implements SubCommand {

    private final ClaudeDuels plugin;

    public Delete(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "delete";
    }

    @Override
    public List<String> aliases() {
        return List.of("remove");
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length != 1) {
            plugin.messages().send(sender, "kits.usage");
            return;
        }
        Kit kit = plugin.kits().get(args[0]);
        if (kit == null) {
            plugin.messages().send(sender, "kits.not-found", "kit", args[0]);
            return;
        }
        if (plugin.kitEdits().isEditingKit(kit.id())) {
            plugin.messages().send(sender, "kits.being-edited", "kit", kit.id());
            return;
        }
        if (plugin.kits().delete(kit.id())) plugin.messages().send(sender, "kits.deleted", "kit", kit.id());
        else plugin.messages().send(sender, "kits.not-found", "kit", args[0]);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.kits().ids(), args[0]) : List.of();
    }
}
