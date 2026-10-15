package org.chiterok.grandDuels.command.player;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.gui.SpectateMenuGUI;
import org.chiterok.grandDuels.match.Match;
import org.chiterok.grandDuels.match.SpectatorManager;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * {@code /spec} opens the menu of running duels, {@code /spec <player>} jumps straight to the duel of that player,
 * {@code /spec leave} stops spectating.
 */
public final class SpectateCommand implements TabExecutor {

    private final ClaudeDuels plugin;

    public SpectateCommand(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return true;
        }
        if (args.length == 0) {
            openMenu(player);
        } else if (args.length == 1 && args[0].equalsIgnoreCase("leave")) {
            leave(player);
        } else if (args.length == 1) {
            spectatePlayer(player, args[0]);
        } else {
            plugin.messages().send(player, "spectate.usage");
        }
        return true;
    }

    private void openMenu(Player player) {
        if (!plugin.settings().spectate().enabled()) {
            plugin.messages().send(player, "spectate.disabled");
        } else if (isBusy(player)) {
            plugin.messages().send(player, "spectate.busy");
        } else {
            new SpectateMenuGUI(plugin, player).open();
        }
    }

    private void leave(Player player) {
        if (plugin.spectators().leave(player) == SpectatorManager.LeaveResult.LEFT) {
            plugin.messages().send(player, "spectate.left");
        } else {
            plugin.messages().send(player, "spectate.not-spectating");
        }
    }

    private void spectatePlayer(Player player, String name) {
        Player target = Bukkit.getPlayerExact(name);
        if (target == null) {
            plugin.messages().send(player, "general.player-not-found", "player", name);
            return;
        }
        Match match = plugin.matches().of(target);
        if (match == null || match.phase() == Match.Phase.FINISHED || target.getUniqueId().equals(player.getUniqueId())) {
            plugin.messages().send(player, "spectate.not-in-duel", "player", target.getName());
            return;
        }
        plugin.spectators().watch(player, match, target.getUniqueId());
    }

    /** In a duel, the arena or the kit editor: spectating is not possible (already spectating is fine). */
    private boolean isBusy(Player player) {
        UUID id = player.getUniqueId();
        return plugin.matches().isInMatch(id) || plugin.kitEdits().isEditing(id) || plugin.arenaMode().isIn(id);
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length != 1 || !(sender instanceof Player player)) return List.of();
        List<String> options = new ArrayList<>();
        if (plugin.spectators().isSpectating(player.getUniqueId())) options.add("leave");
        for (Match match : plugin.matches().all()) {
            if (match.phase() == Match.Phase.FINISHED) continue;
            for (UUID id : match.participants()) {
                Player duelist = Bukkit.getPlayer(id);
                if (duelist != null && !duelist.equals(player)) options.add(duelist.getName());
            }
        }
        return CommandRegistry.filter(options, args[0].toLowerCase(Locale.ROOT));
    }
}
