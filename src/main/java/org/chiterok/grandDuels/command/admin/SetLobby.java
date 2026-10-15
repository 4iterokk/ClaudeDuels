package org.chiterok.grandDuels.command.admin;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.utils.StoredLocation;

/** {@code /duels setlobby} - sets the location used when {@code duel.return-mode} is LOBBY. */
public final class SetLobby implements SubCommand {

    private final ClaudeDuels plugin;

    public SetLobby(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "setlobby";
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
        Player player = (Player) sender;
        StoredLocation lobby = StoredLocation.of(player.getLocation());
        plugin.configs().config().set("lobby", null);
        lobby.write(plugin.configs().config().createSection("lobby"));
        plugin.configs().configFile().save();
        plugin.reloadAll();
        plugin.messages().send(sender, "admin.lobby-set", "location", lobby.pretty());
    }
}
