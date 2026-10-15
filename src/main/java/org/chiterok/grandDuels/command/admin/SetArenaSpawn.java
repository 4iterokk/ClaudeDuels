package org.chiterok.grandDuels.command.admin;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.utils.StoredLocation;

import java.util.List;

/** {@code /duels setarenaspawn} - sets where players of arena mode ({@code /arena}) are teleported. */
public final class SetArenaSpawn implements SubCommand {

    private final ClaudeDuels plugin;

    public SetArenaSpawn(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "setarenaspawn";
    }

    @Override
    public List<String> aliases() {
        return List.of("setarenamode");
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
        StoredLocation spawn = StoredLocation.of(player.getLocation());
        plugin.configs().config().set("arena-mode.spawn", null);
        spawn.write(plugin.configs().config().createSection("arena-mode.spawn"));
        plugin.configs().configFile().save();
        plugin.reloadAll();
        plugin.messages().send(sender, "admin.arena-spawn-set", "location", spawn.pretty());
    }
}
