package org.chiterok.grandDuels.command.admin.kit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.kit.KitManager;

import java.util.List;
import java.util.Locale;

/**
 * {@code /duels kit save [name]}
 * <ul>
 *   <li>While editing: saves your loadout into the edited kit (or into {@code name}, which creates/overwrites that kit)
 *       and restores your own inventory.</li>
 *   <li>Not editing: saves your current inventory, armor, offhand and effects as kit {@code name}.</li>
 * </ul>
 */
public final class Save implements SubCommand {

    private final ClaudeDuels plugin;

    public Save(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "save";
    }

    @Override
    public String permission() {
        return "grandduels.admin";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        if (args.length > 1) {
            plugin.messages().send(sender, "kits.usage");
            return;
        }
        String name = args.length == 1 ? args[0].toLowerCase(Locale.ROOT) : null;
        if (name != null && !KitManager.isValidId(name)) {
            plugin.messages().send(sender, "general.invalid-name");
            return;
        }
        Player player = (Player) sender;

        if (plugin.kitEdits().isEditing(player.getUniqueId())) {
            if (name != null && plugin.kitEdits().isEditingKit(name)
                    && !name.equals(plugin.kitEdits().editedKit(player.getUniqueId()))) {
                plugin.messages().send(player, "kits.being-edited", "kit", name);
                return;
            }
            String saved = plugin.kitEdits().save(player, name);
            if (saved != null) plugin.messages().send(player, "kits.edit-saved", "kit", saved);
            return;
        }
        if (name == null) {
            plugin.messages().send(player, "kits.not-editing");
            return;
        }
        if (plugin.matches().isInMatch(player.getUniqueId())) {
            plugin.messages().send(player, "kits.in-duel");
            return;
        }
        plugin.kits().save(name, player);
        plugin.messages().send(player, "kits.saved", "kit", name);
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.kits().ids(), args[0]) : List.of();
    }
}
