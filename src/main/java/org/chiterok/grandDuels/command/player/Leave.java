package org.chiterok.grandDuels.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.SubCommand;

import java.util.List;

/** {@code /duel leave} - leaves the ranked/unranked queue. */
public final class Leave implements SubCommand {

    private final ClaudeDuels plugin;

    public Leave(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "leave";
    }

    @Override
    public List<String> aliases() {
        return List.of("leavequeue");
    }

    @Override
    public String permission() {
        return "grandduels.queue";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        plugin.messages().send(player, plugin.queues().leave(player.getUniqueId()) ? "queue.left" : "queue.not-queued");
    }
}
