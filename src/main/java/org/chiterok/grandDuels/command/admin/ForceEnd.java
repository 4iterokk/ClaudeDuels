package org.chiterok.grandDuels.command.admin;

import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.match.Match;

import java.util.ArrayList;
import java.util.List;

/** {@code /duels forceend <player>} - ends the player's duel as a draw. */
public final class ForceEnd implements SubCommand {

    private final ClaudeDuels plugin;

    public ForceEnd(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "forceend";
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length != 1) {
            plugin.messages().send(sender, "admin.force-usage");
            return;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        Match match = target == null ? null : plugin.matches().of(target);
        if (target == null || match == null) {
            plugin.messages().send(sender, "admin.force-none", "player", args[0]);
            return;
        }
        match.forceEnd();
        plugin.messages().send(sender, "admin.force-ended", "player", target.getName());
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        List<String> names = new ArrayList<>();
        for (Match match : plugin.matches().all()) {
            for (java.util.UUID id : match.participants()) {
                Player p = Bukkit.getPlayer(id);
                if (p != null) names.add(p.getName());
            }
        }
        return CommandRegistry.filter(names, args[0]);
    }
}
