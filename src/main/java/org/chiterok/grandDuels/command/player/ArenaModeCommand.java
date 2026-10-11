package org.chiterok.grandDuels.command.player;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.gui.ArenaModeGUI;
import org.chiterok.grandDuels.match.ArenaModeManager;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;

/** {@code /arena} opens the kit menu of arena mode; {@code /arena leave} returns to the lobby. */
public final class ArenaModeCommand implements TabExecutor {

    private final GrandDuels plugin;

    public ArenaModeCommand(GrandDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            plugin.messages().send(sender, "general.player-only");
            return true;
        }
        if (args.length > 0) {
            if (args[0].equalsIgnoreCase("leave")) {
                leave(player);
            } else {
                plugin.messages().send(player, "arenamode.usage");
            }
            return true;
        }
        if (!plugin.settings().arenaMode().enabled()) {
            plugin.messages().send(player, "arenamode.disabled");
        } else if (plugin.arenaMode().isIn(player.getUniqueId())) {
            plugin.messages().send(player, "arenamode.already");
        } else if (plugin.isOccupied(player.getUniqueId())) {
            plugin.messages().send(player, "arenamode.busy");
        } else if (plugin.settings().arenaMode().spawn() == null) {
            plugin.messages().send(player, "arenamode.not-configured");
        } else if (plugin.kits().all().isEmpty()) {
            plugin.messages().send(player, "kits.none-available");
        } else {
            new ArenaModeGUI(plugin, player).open();
        }
        return true;
    }

    private void leave(Player player) {
        ArenaModeManager.LeaveResult result = plugin.arenaMode().leave(player);
        switch (result) {
            case LEFT -> plugin.messages().send(player, "arenamode.left");
            case NOT_IN_ARENA -> plugin.messages().send(player, "arenamode.not-in-arena");
            case IN_COMBAT -> plugin.messages().send(player, "arenamode.in-combat",
                    "seconds", plugin.arenaMode().combatSecondsLeft(player.getUniqueId()));
        }
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        return args.length == 1 ? CommandRegistry.filter(List.of("leave"), args[0].toLowerCase(Locale.ROOT)) : List.of();
    }
}
