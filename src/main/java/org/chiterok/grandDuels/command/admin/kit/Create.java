package org.chiterok.grandDuels.command.admin.kit;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.GrandDuels;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.kit.KitEditManager;
import org.chiterok.grandDuels.kit.KitManager;

import java.util.Locale;

/** {@code /duels kit create <name>} - starts an empty loadout; finish with {@code /duels kit save}. */
public final class Create implements SubCommand {

    private final GrandDuels plugin;

    public Create(GrandDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "create";
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
        String id = args[0].toLowerCase(Locale.ROOT);
        if (!KitManager.isValidId(id)) {
            plugin.messages().send(sender, "general.invalid-name");
            return;
        }
        if (plugin.kits().get(id) != null) {
            plugin.messages().send(sender, "kits.exists", "kit", id);
            return;
        }
        Player player = (Player) sender;
        KitEditManager.StartResult result = plugin.kitEdits().start(player, id, null);
        switch (result) {
            case STARTED -> plugin.messages().send(player, "kits.create-started", "kit", id);
            case IN_DUEL -> plugin.messages().send(player, "kits.in-duel");
            case ALREADY_EDITING -> plugin.messages().send(player, "kits.already-editing");
            case FAILED -> plugin.messages().send(player, "duels.start-failed");
        }
    }
}
