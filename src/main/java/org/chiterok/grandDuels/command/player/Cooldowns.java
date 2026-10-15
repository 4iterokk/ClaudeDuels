package org.chiterok.grandDuels.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.gui.CooldownMenuGUI;

import java.util.List;

/** {@code /duel cooldowns} - opens the personal cooldowns and restrictions menu. */
public final class Cooldowns implements SubCommand {

    private final ClaudeDuels plugin;

    public Cooldowns(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "cooldowns";
    }

    @Override
    public List<String> aliases() {
        return List.of("cooldown", "rules");
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
        new CooldownMenuGUI(plugin, (Player) sender, null).open();
    }
}
