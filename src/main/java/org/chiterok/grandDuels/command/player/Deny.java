package org.chiterok.grandDuels.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.match.DuelRequest;

import java.util.List;

/** {@code /duel deny [player]} */
public final class Deny implements SubCommand {

    private final ClaudeDuels plugin;

    public Deny(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "deny";
    }

    @Override
    public List<String> aliases() {
        return List.of("decline");
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
        plugin.duels().deny((Player) sender, args.length > 0 ? args[0] : null);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1 || !(sender instanceof Player player)) return List.of();
        List<String> senders = plugin.requests().incoming(player.getUniqueId()).stream()
                .map(DuelRequest::senderName).toList();
        return CommandRegistry.filter(senders, args[0]);
    }
}
