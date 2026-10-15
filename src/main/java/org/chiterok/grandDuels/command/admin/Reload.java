package org.chiterok.grandDuels.command.admin;

import org.bukkit.command.CommandSender;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.SubCommand;

/** {@code /duels reload} - reloads config.yml, messages.yml and kits.yml. Running duels keep their kit. */
public final class Reload implements SubCommand {

    private final ClaudeDuels plugin;

    public Reload(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "reload";
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.reloadAll();
        plugin.messages().send(sender, "admin.reloaded", "kits", plugin.kits().all().size(),
                "arenas", plugin.arenas().all().size());
    }
}
