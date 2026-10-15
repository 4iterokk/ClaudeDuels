package org.chiterok.grandDuels.command.player;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.chiterok.grandDuels.ClaudeDuels;
import org.chiterok.grandDuels.command.CommandRegistry;
import org.chiterok.grandDuels.command.SubCommand;
import org.chiterok.grandDuels.kit.Kit;
import org.chiterok.grandDuels.kit.KitEditManager;
import org.chiterok.grandDuels.kit.KitManager;
import org.chiterok.grandDuels.kit.PlayerKitManager;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * {@code /duel kit <create|edit|save|cancel|delete|list> [name]} - player-made kits.
 * <ul>
 *   <li>{@code create <name>} / {@code edit <name>}: a restricted creative editor starts (see
 *   {@link org.chiterok.grandDuels.runtime.KitEditorListener}); {@code create} starts from what you carry now,
 *   {@code edit} from the saved kit. {@code save} stores the result and returns you to normal, {@code cancel} drops it.</li>
 *   <li>{@code save <name>} outside the editor copies the inventory, armor and offhand you carry (survival items only).</li>
 * </ul>
 * With {@code player-kits.editor.enabled: false}, {@code create <name>} copies your inventory instead.
 */
public final class PlayerKitCommand implements SubCommand {

    private static final List<String> ACTIONS = List.of("create", "edit", "save", "cancel", "delete", "list");

    private final ClaudeDuels plugin;

    public PlayerKitCommand(ClaudeDuels plugin) {
        this.plugin = plugin;
    }

    @Override
    public String name() {
        return "kit";
    }

    @Override
    public List<String> aliases() {
        return List.of("kits");
    }

    @Override
    public String permission() {
        return "grandduels.kits";
    }

    @Override
    public boolean playerOnly() {
        return true;
    }

    @Override
    public void execute(CommandSender sender, String[] args) {
        Player player = (Player) sender;
        if (args.length == 0) {
            plugin.messages().send(player, "playerkits.usage");
            return;
        }
        switch (args[0].toLowerCase(Locale.ROOT)) {
            case "create" -> create(player, args);
            case "edit" -> edit(player, args);
            case "save" -> save(player, args);
            case "cancel" -> cancel(player);
            case "delete", "remove" -> delete(player, args);
            case "list" -> list(player);
            default -> plugin.messages().send(player, "playerkits.usage");
        }
    }

    // ---------------------------------------------------------------- create / edit

    private void create(Player player, String[] args) {
        String name = validName(player, args);
        if (name == null) return;
        if (!plugin.settings().playerKits().editor().enabled()) {
            copyInventory(player, name, false);
            return;
        }
        if (!mayStartEditing(player)) return;
        PlayerKitManager.SaveResult problem = plugin.playerKits().precheck(player, name, false);
        if (problem != null) {
            report(player, name, problem);
            return;
        }
        startEditing(player, name, null, "playerkits.editor-started");
    }

    private void edit(Player player, String[] args) {
        String name = validName(player, args);
        if (name == null) return;
        if (!plugin.settings().playerKits().editor().enabled()) {
            plugin.messages().send(player, "playerkits.editor-disabled");
            return;
        }
        if (!mayStartEditing(player)) return;
        Kit existing = plugin.playerKits().get(player.getUniqueId(), name);
        if (existing == null) {
            plugin.messages().send(player, "playerkits.not-found", "kit", name);
            return;
        }
        startEditing(player, name, existing, "playerkits.editor-edit-started");
    }

    private boolean mayStartEditing(Player player) {
        if (plugin.kitEdits().isEditing(player.getUniqueId())) {
            plugin.messages().send(player, "kits.already-editing");
            return false;
        }
        if (plugin.isOccupied(player.getUniqueId())) {
            plugin.messages().send(player, "kits.in-duel");
            return false;
        }
        return true;
    }

    private void startEditing(Player player, String name, Kit existing, String startedMessage) {
        KitEditManager.StartResult result = plugin.kitEdits().startPlayerSession(player, name, existing);
        switch (result) {
            case STARTED -> {
                plugin.messages().send(player, startedMessage, "kit", name,
                        "minutes", Math.max(1, plugin.settings().playerKits().editor().timeoutSeconds() / 60),
                        "radius", (int) plugin.settings().playerKits().editor().radius());
            }
            case IN_DUEL -> plugin.messages().send(player, "kits.in-duel");
            case ALREADY_EDITING -> plugin.messages().send(player, "kits.already-editing");
            case FAILED -> plugin.messages().send(player, "duels.start-failed");
        }
    }

    // ---------------------------------------------------------------- save / cancel

    private void save(Player player, String[] args) {
        if (plugin.kitEdits().isRestricted(player.getUniqueId())) {
            String target = null;
            if (args.length == 2) {
                target = validName(player, args);
                if (target == null) return;
            } else if (args.length > 2) {
                plugin.messages().send(player, "playerkits.usage");
                return;
            }
            PlayerKitManager.SaveOutcome outcome = plugin.kitEdits().savePlayerKit(player, target);
            if (outcome != null) reportSave(player, target == null ? plugin.kitEdits().editedKit(player.getUniqueId()) : target, outcome);
            return;
        }
        if (plugin.kitEdits().isEditing(player.getUniqueId())) {
            plugin.messages().send(player, "kits.editing-busy");
            return;
        }
        String name = validName(player, args);
        if (name == null) return;
        copyInventory(player, name, true);
    }

    private void cancel(Player player) {
        if (plugin.kitEdits().cancelPlayerSession(player)) {
            plugin.messages().send(player, "playerkits.editor-cancelled");
        } else {
            plugin.messages().send(player, "playerkits.editor-none");
        }
    }

    // ---------------------------------------------------------------- copy the inventory (no editor)

    private void copyInventory(Player player, String name, boolean overwrite) {
        if (plugin.isOccupied(player.getUniqueId())) {
            plugin.messages().send(player, "kits.in-duel");
            return;
        }
        reportSave(player, name, plugin.playerKits().save(player, name, overwrite));
    }

    private void reportSave(Player player, String name, PlayerKitManager.SaveOutcome outcome) {
        report(player, name, outcome.result());
        if (outcome.skippedItems() > 0 && outcome.result() != PlayerKitManager.SaveResult.EXISTS) {
            plugin.messages().send(player, "playerkits.skipped", "count", outcome.skippedItems());
        }
    }

    private void report(Player player, String name, PlayerKitManager.SaveResult result) {
        switch (result) {
            case CREATED -> plugin.messages().send(player, "playerkits.created", "kit", name);
            case UPDATED -> plugin.messages().send(player, "playerkits.updated", "kit", name);
            case EXISTS -> plugin.messages().send(player, "playerkits.exists", "kit", name);
            case LIMIT_REACHED -> plugin.messages().send(player, "playerkits.limit", "limit", plugin.playerKits().limit(player));
            case EMPTY -> plugin.messages().send(player, "playerkits.empty");
            case DISABLED -> plugin.messages().send(player, "playerkits.disabled");
            case FAILED -> plugin.messages().send(player, "duels.start-failed");
        }
    }

    /** The normalized kit name from {@code args[1]}, or {@code null} after telling the player what is wrong. */
    private String validName(Player player, String[] args) {
        if (args.length != 2) {
            plugin.messages().send(player, "playerkits.usage");
            return null;
        }
        String name = args[1].toLowerCase(Locale.ROOT);
        if (!KitManager.isValidId(name)) {
            plugin.messages().send(player, "general.invalid-name");
            return null;
        }
        return name;
    }

    // ---------------------------------------------------------------- delete / list

    private void delete(Player player, String[] args) {
        if (args.length != 2) {
            plugin.messages().send(player, "playerkits.usage");
            return;
        }
        if (plugin.kitEdits().isRestricted(player.getUniqueId())
                && args[1].equalsIgnoreCase(plugin.kitEdits().editedKit(player.getUniqueId()))) {
            plugin.messages().send(player, "kits.editing-busy");
            return;
        }
        if (plugin.playerKits().delete(player.getUniqueId(), args[1])) {
            plugin.messages().send(player, "playerkits.deleted", "kit", args[1].toLowerCase(Locale.ROOT));
        } else {
            plugin.messages().send(player, "playerkits.not-found", "kit", args[1]);
        }
    }

    private void list(Player player) {
        List<Kit> kits = plugin.playerKits().list(player.getUniqueId());
        if (kits.isEmpty()) {
            plugin.messages().send(player, "playerkits.list-empty");
            return;
        }
        List<String> ids = new ArrayList<>();
        for (Kit kit : kits) ids.add(kit.id());
        plugin.messages().send(player, "playerkits.list", "count", kits.size(),
                "limit", plugin.playerKits().limit(player) == Integer.MAX_VALUE ? "∞" : plugin.playerKits().limit(player),
                "kits", String.join(", ", ids));
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String[] args) {
        if (args.length == 1) return CommandRegistry.filter(ACTIONS, args[0]);
        if (args.length == 2 && sender instanceof Player player
                && List.of("edit", "save", "delete", "remove").contains(args[0].toLowerCase(Locale.ROOT))) {
            List<String> ids = new ArrayList<>();
            for (Kit kit : plugin.playerKits().list(player.getUniqueId())) ids.add(kit.id());
            return CommandRegistry.filter(ids, args[1]);
        }
        return List.of();
    }
}
