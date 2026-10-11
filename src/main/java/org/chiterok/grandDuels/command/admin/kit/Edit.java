package org.chiterok.grandDuels.command.admin.kit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.kit.KitEditManager;

import java.util.List;

/** {@code /duels kit edit <name>} - gives you the kit (creative mode); finish with {@code /duels kit save}. */
public final class Edit implements SubCommand {

    private final GrandDuels plugin;

    public Edit(GrandDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "edit";
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
        if (args.length != 1) {
            plugin.messages().send(sender, "kits.usage");
            return;
        }
        Kit kit = plugin.kits().get(args[0]);
        if (kit == null) {
            plugin.messages().send(sender, "kits.not-found", "kit", args[0]);
            return;
        }
        Player player = (Player) sender;
        if (plugin.kitEdits().isEditingKit(kit.id()) && !kit.id().equals(plugin.kitEdits().editedKit(player.getUniqueId()))) {
            plugin.messages().send(player, "kits.being-edited", "kit", kit.id());
            return;
        }
        KitEditManager.StartResult result = plugin.kitEdits().start(player, kit.id(), kit);
        switch (result) {
            case STARTED -> plugin.messages().send(player, "kits.edit-started", "kit", kit.id());
            case IN_DUEL -> plugin.messages().send(player, "kits.in-duel");
            case ALREADY_EDITING -> plugin.messages().send(player, "kits.already-editing");
            case FAILED -> plugin.messages().send(player, "duels.start-failed");
        }
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        return args.length == 1 ? CommandRegistry.filter(plugin.kits().ids(), args[0]) : List.of();
    }
}
