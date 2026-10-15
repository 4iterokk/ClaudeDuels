package org.chiterok.grandDuels.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.SubCommand;

/** {@code /duel cancel} - cancels the request the player sent. */
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
        return "grandduels.duel";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        plugin.duels().cancel((Player) sender);
    }
}
