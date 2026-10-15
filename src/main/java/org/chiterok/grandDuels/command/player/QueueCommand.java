package org.chiterok.grandDuels.command.player;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.gui.QueueMenuGUI;
import org.chiterok.grandDuels.match.DuelMode;
import org.jetbrains.annotations.NotNull;

/** {@code /ranked} and {@code /unranked}: open the queue menu of the given mode. */
public final class QueueCommand implements CommandExecutor {

    private final ClaudeDuels plugin;
    private final DuelMode mode;

    public QueueCommand(ClaudeDuels plugin, DuelMode mode) {
        this.plugin = plugin;
        this.mode = mode;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return true;
        }
        if (plugin.matches().isInMatch(player.getUniqueId())) {
            plugin.messages().send(player, "duels.self-busy");
            return true;
        }
        if (plugin.kitEdits().isEditing(player.getUniqueId())) {
            plugin.messages().send(player, "kits.editing-busy");
            return true;
        }
        new QueueMenuGUI(plugin, player, mode).open();
        return true;
    }
}
