package org.chiterok.grandDuels.command.admin.arena;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.gui.ArenaKitsGUI;

/** {@code /duels arena kits} - opens the menu where admins choose which arenas each kit can be played on. */
public final class Kits implements SubCommand {

    private final ClaudeDuels plugin;

    public Kits(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "kits";
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
        if (plugin.kits().all().isEmpty()) {
            plugin.messages().send(sender, "kits.none-available");
            return;
        }
        new ArenaKitsGUI(plugin, (Player) sender, 0).open();
    }
}
