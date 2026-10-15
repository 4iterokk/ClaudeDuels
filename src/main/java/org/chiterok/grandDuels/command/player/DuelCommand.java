package org.chiterok.grandDuels.command.player;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;

/** {@code /duel <player|accept|deny|cancel|cooldowns|kit|leave|stats>} */
public final class DuelCommand implements TabExecutor {

    private final ClaudeDuels plugin;
    private final CommandRegistry registry;

    public DuelCommand(ClaudeDuels plugin) {
        this.plugin = plugin;
        this.registry = new CommandRegistry(plugin)
                .register(new Accept(plugin))
                .register(new Deny(plugin))
                .register(new Cancel(plugin))
                .register(new Cooldowns(plugin))
                .register(new PlayerKitCommand(plugin))
                .register(new Leave(plugin))
                .register(new Stats(plugin));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (args.length == 0) {
            plugin.messages().send(sender, "duels.usage");
            return true;
        }
        if (registry.execute(sender, args)) return true;

        // Not a sub-command: treat the first argument as the challenged player.
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return true;
        }
        if (!player.hasPermission("grandduels.duel")) {
            plugin.messages().send(player, "general.no-permission");
            return true;
        }
        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            plugin.messages().send(player, "general.player-not-found", "player", args[0]);
            return true;
        }
        plugin.duels().challenge(player, target);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        if (args.length == 1) {
            List<String> options = new ArrayList<>(registry.names(sender));
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (!online.equals(sender)) options.add(online.getName());
            }
            return CommandRegistry.filter(options, args[0]);
        }
        return registry.complete(sender, args);
    }
}
