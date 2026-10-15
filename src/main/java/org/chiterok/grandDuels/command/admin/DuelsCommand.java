package org.chiterok.grandDuels.command.admin;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.admin.arena.ArenaCommand;
import org.chiterok.grandDuels.command.admin.kit.KitCommand;
import org.jetbrains.annotations.NotNull;

import java.util.List;

/** {@code /duels <arena|kit|reload|setlobby|setarenaspawn|forceend>} */
public final class DuelsCommand implements TabExecutor {

    private final ClaudeDuels plugin;
    private final CommandRegistry registry;

    public DuelsCommand(ClaudeDuels plugin) {
        this.plugin = plugin;
        this.registry = new CommandRegistry(plugin)
                .register(new ArenaCommand(plugin))
                .register(new Reload(plugin))
                .register(new KitCommand(plugin))
                .register(new SetLobby(plugin))
                .register(new SetArenaSpawn(plugin))
                .register(new ForceEnd(plugin));
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label,
                             @NotNull String[] args) {
        if (!registry.execute(sender, args)) {
            plugin.messages().send(sender, "admin.usage");
        }
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias,
                                      @NotNull String[] args) {
        return registry.complete(sender, args);
    }
}
