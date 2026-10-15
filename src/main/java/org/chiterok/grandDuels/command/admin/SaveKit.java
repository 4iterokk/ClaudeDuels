package org.chiterok.grandDuels.command.admin;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.kit.KitManager;

import java.util.List;
import java.util.Locale;

/** {@code /duels savekit <id>} - stores the admin's inventory, armor, offhand and effects as a kit. */
public final class SaveKit implements SubCommand {

    private final ClaudeDuels plugin;

    public SaveKit(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "savekit";
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
            plugin.messages().send(sender, "kits.usage-save");
            return;
        }
        String id = args[0].toLowerCase(Locale.ROOT);
        if (!KitManager.isValidId(id)) {
            plugin.messages().send(sender, "general.invalid-name");
            return;
        }
        plugin.kits().save(id, (Player) sender);
        plugin.messages().send(sender, "kits.saved", "kit", id);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.kits().ids(), args[0]) : List.of();
    }
}
