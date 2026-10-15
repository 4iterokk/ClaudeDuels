package org.chiterok.grandDuels.command;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Holds the sub-commands of one command level and handles permission / player-only checks and tab completion. */
public final class CommandRegistry {

    private final ClaudeDuels plugin;
    private final Map<String, SubCommand> lookup = new LinkedHashMap<>();
    private final List<SubCommand> commands = new ArrayList<>();

    public CommandRegistry(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    public CommandRegistry register(SubCommand command) {
        commands.add(command);
        lookup.put(command.name().toLowerCase(Locale.ROOT), command);
        for (String alias : command.aliases()) lookup.put(alias.toLowerCase(Locale.ROOT), command);
        return this;
    }

    public @Nullable SubCommand find(String label) {
        return lookup.get(label.toLowerCase(Locale.ROOT));
    }

    /** @return false if {@code args[0]} is not a registered sub-command (nothing was executed). */
    public boolean execute(CommandSender sender, String[] args) {
        if (args.length == 0) return false;
        SubCommand command = find(args[0]);
        if (command == null) return false;

        String permission = command.permission();
        if (permission != null && !sender.hasPermission(permission)) {
            plugin.messages().send(sender, "general.no-permission");
            return true;
        }
        if (command.playerOnly() && !(sender instanceof Player)) {
            plugin.messages().send(sender, "general.player-only");
            return true;
        }
        command.execute(sender, Arrays.copyOfRange(args, 1, args.length));
        return true;
    }

    /** Names of the sub-commands the sender may use. */
    public List<String> names(CommandSender sender) {
        List<String> names = new ArrayList<>();
        for (SubCommand command : commands) {
            String permission = command.permission();
            if (permission == null || sender.hasPermission(permission)) names.add(command.name());
        }
        return names;
    }

    /** Completes the sub-command label (args.length == 1) or delegates to the matching sub-command. */
    public List<String> complete(CommandSender sender, String[] args) {
        if (args.length <= 1) return filter(names(sender), args.length == 0 ? "" : args[0]);
        SubCommand command = find(args[0]);
        if (command == null) return List.of();
        String permission = command.permission();
        if (permission != null && !sender.hasPermission(permission)) return List.of();
        String[] rest = Arrays.copyOfRange(args, 1, args.length);
        return command.tabComplete(sender, rest);
    }

    public static List<String> filter(List<String> options, String prefix) {
        String lower = prefix.toLowerCase(Locale.ROOT);
        List<String> out = new ArrayList<>();
        for (String option : options) {
            if (option.toLowerCase(Locale.ROOT).startsWith(lower)) out.add(option);
        }
        return out;
    }
}
