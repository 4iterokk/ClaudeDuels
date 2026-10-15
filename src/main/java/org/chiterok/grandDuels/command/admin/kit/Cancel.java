package org.chiterok.grandDuels.command.admin.kit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.SubCommand;

/** {@code /duels kit cancel} - leaves edit mode without saving and restores your inventory. */
public final class Cancel implements SubCommand {

    private final ClaudeDuels plugin;

    public Cancel(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "cancel";
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
        if (plugin.kitEdits().cancel((Player) sender)) plugin.messages().send(sender, "kits.edit-cancelled");
        else plugin.messages().send(sender, "kits.not-editing");
    }
}
