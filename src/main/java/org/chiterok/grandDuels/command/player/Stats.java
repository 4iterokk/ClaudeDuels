package org.chiterok.grandDuels.command.player;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.config.Messages;
import org.chiterok.grandDuels.config.Settings;
import org.chiterok.grandDuels.data.PlayerStats;
import org.chiterok.grandDuels.data.StatsManager;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;

/** {@code /duel stats [player]} - the lookup runs off the main thread. */
public final class Stats implements SubCommand {

    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9_.-]{1,32}");

    private final ClaudeDuels plugin;

    public Stats(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "stats";
    }

    @Override
    public String permission() {
        return "grandduels.stats";
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        UUID uuid;
        String name;
        if (args.length == 0) {
            if (!(sender instanceof Player player)) {
                plugin.messages().send(sender, "general.player-only");
                return;
            }
            uuid = player.getUniqueId();
            name = player.getName();
        } else {
            name = args[0];
            if (!NAME.matcher(name).matches()) {
                plugin.messages().send(sender, "general.invalid-name");
                return;
            }
            uuid = StatsManager.resolve(name);
            if (uuid == null) {
                plugin.messages().send(sender, "general.player-not-found", "player", name);
                return;
            }
        }

        final String displayName = name;
        // kit rating mode: the ELO shown here is the average over the kits the player is rated in
        boolean kitMode = plugin.settings().matchmaking().ratingMode() == Settings.RatingMode.KIT;
        CompletableFuture<Integer> kitElo = kitMode
                ? plugin.kitRatings().lookup(uuid).thenApply(plugin.kitRatings()::average)
                : CompletableFuture.completedFuture(-1);
        plugin.stats().lookup(uuid).thenAcceptBoth(kitElo, (result, averageElo) -> Bukkit.getScheduler().runTask(plugin, () -> {
            PlayerStats stats = result.orElseGet(() -> PlayerStats.empty(uuid, displayName));
            int elo = kitMode ? averageElo : stats.elo();
            List<Component> lines = plugin.messages().lines("stats.lines", Messages.ph(
                    "player", displayName, "wins", stats.wins(), "losses", stats.losses(), "kills", stats.kills(),
                    "deaths", stats.deaths(), "kd", stats.kdFormatted(), "streak", stats.currentStreak(),
                    "best_streak", stats.bestStreak(), "elo", elo), Messages.viewerOf(sender));
            for (Component line : lines) sender.sendMessage(line);
        }));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length != 1) return List.of();
        List<String> names = new ArrayList<>();
        for (Player online : Bukkit.getOnlinePlayers()) names.add(online.getName());
        return CommandRegistry.filter(names, args[0]);
    }
}
